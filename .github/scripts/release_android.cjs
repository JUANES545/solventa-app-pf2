'use strict';

const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const childProcess = require('node:child_process');

const APK = 'solventa-preview.apk';
const METADATA = 'release.json';
const SUMS = 'SHA256SUMS';
const shaPattern = /^[0-9a-f]{40}$/;
const versionPattern = /^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$/;
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');

function versionFromText(text) {
  const values = new Map();
  for (const line of text.split(/\r?\n/)) {
    if (!line.trim() || /^[#!]/.test(line.trim())) continue;
    const index = line.indexOf('=');
    if (index < 1) throw new Error('Propiedad de versión inválida');
    const key = line.slice(0, index).trim();
    if (values.has(key)) throw new Error('Propiedad de versión duplicada');
    values.set(key, line.slice(index + 1).trim());
  }
  const name = values.get('VERSION_NAME');
  const rawCode = values.get('VERSION_CODE');
  if (!versionPattern.test(name || '') || !name.split('.').every(part => Number.isSafeInteger(Number(part))) ||
      !/^[1-9][0-9]*$/.test(rawCode || '')) throw new Error('Versión inválida');
  const code = Number(rawCode);
  if (!Number.isSafeInteger(code) || code > 2100000000) throw new Error('Código de versión inválido');
  return { name, code };
}

function assertIncrease(next, previous) {
  const a = next.name.split('.').map(Number), b = previous.name.split('.').map(Number);
  const difference = a.findIndex((value, index) => value !== b[index]);
  if (difference < 0 || a[difference] < b[difference] || next.code <= previous.code) {
    throw new Error('La entrega debe aumentar VERSION_NAME y VERSION_CODE respecto a master');
  }
}

async function optional(read) {
  try { return (await read()).data; } catch (error) {
    if (error.status === 404) return null;
    throw error;
  }
}

function trustedMaster(context) {
  if (context.ref !== 'refs/heads/master' || !['push', 'workflow_dispatch'].includes(context.eventName) || !shaPattern.test(context.sha)) {
    throw new Error('Solo una ejecución confiable de master puede publicar');
  }
}

async function tagCommit(github, repo, version) {
  const ref = await optional(() => github.rest.git.getRef({ ...repo, ref: `tags/${version}` }));
  if (!ref) return null;
  let object = ref.object;
  if (object.type === 'tag') object = (await github.rest.git.getTag({ ...repo, tag_sha: object.sha })).data.object;
  if (object.type !== 'commit' || !shaPattern.test(object.sha)) throw new Error('El tag no identifica un commit válido');
  return object.sha;
}

async function validateVersion({ github, context, properties = 'version.properties' }) {
  const next = versionFromText(fs.readFileSync(properties, 'utf8'));
  let base;
  if (context.eventName === 'pull_request' && context.payload.pull_request.base.ref === 'master') {
    base = context.payload.pull_request.base.sha;
  } else if (context.ref === 'refs/heads/master') {
    trustedMaster(context);
    base = context.eventName === 'push' ? context.payload?.before : null;
    if (!base || /^0+$/.test(base)) base = (await github.rest.repos.getCommit({ ...context.repo, ref: context.sha })).data.parents[0]?.sha;
  } else return next;
  if (!shaPattern.test(base || '')) throw new Error('No se pudo identificar la versión anterior de master');
  const file = (await github.rest.repos.getContent({ ...context.repo, path: 'version.properties', ref: base })).data;
  if (file.encoding !== 'base64' || typeof file.content !== 'string') throw new Error('Respuesta incompleta de versión');
  assertIncrease(next, versionFromText(Buffer.from(file.content, 'base64').toString('utf8')));
  const existing = await tagCommit(github, context.repo, next.name);
  if (existing && (context.eventName === 'pull_request' || existing !== context.sha)) throw new Error('La versión ya está utilizada por otro commit');
  return next;
}

function downloadAssets(repo, version, directory, names) {
  fs.mkdirSync(directory, { recursive: true });
  childProcess.execFileSync('gh', ['release', 'download', version, '--repo', `${repo.owner}/${repo.repo}`,
    ...names.flatMap(name => ['--pattern', name]), '--dir', directory], { stdio: 'pipe' });
}

async function publishRelease({ github, context, apk, properties = 'version.properties' }) {
  trustedMaster(context);
  const version = versionFromText(fs.readFileSync(properties, 'utf8'));
  const bytes = fs.readFileSync(apk);
  const metadata = { version: version.name, version_code: version.code, commit: context.sha, sha256: hash(bytes) };
  const files = new Map([[APK, bytes], [METADATA, Buffer.from(JSON.stringify(metadata, null, 2) + '\n')],
    [SUMS, Buffer.from(`${metadata.sha256}  ${APK}\n`)]]);
  const existingTag = await tagCommit(github, context.repo, version.name);
  if (existingTag && existingTag !== context.sha) throw new Error('No se sobrescribe un tag de otro commit');
  if (!existingTag) await github.rest.git.createRef({ ...context.repo, ref: `refs/tags/${version.name}`, sha: context.sha });
  let release = await optional(() => github.rest.repos.getReleaseByTag({ ...context.repo, tag: version.name }));
  if (!release) {
    release = (await github.rest.repos.createRelease({ ...context.repo, tag_name: version.name,
      target_commitish: context.sha, name: `Solventa Android ${version.name}`, draft: true, prerelease: false,
      body: `APK de demostración firmada para pruebas; no es distribución de Play Store.\n\nCommit validado: \`${context.sha}\`.\n\nLa demo de Appetize muestra la última versión desplegada, no una copia histórica de esta entrega.` })).data;
  }
  for (const [name, data] of files) {
    let existing = release.assets.find(asset => asset.name === name);
    // GitHub puede dejar un asset vacío "starter" tras un error 502 de subida.
    if (existing && release.draft && existing.state === 'starter' && existing.size === 0) {
      await github.rest.repos.deleteReleaseAsset({ ...context.repo, asset_id: existing.id });
      existing = null;
    }
    if (existing) {
      let digest = existing.digest;
      if (!digest) {
        const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'solventa-release-'));
        try {
          downloadAssets(context.repo, version.name, temp, [name]);
          digest = 'sha256:' + hash(fs.readFileSync(path.join(temp, name)));
        } finally { fs.rmSync(temp, { recursive: true, force: true }); }
      }
      if (digest !== 'sha256:' + hash(data)) throw new Error(`No se sobrescribe un artefacto diferente: ${name}`);
    } else {
      if (!release.draft) throw new Error('La entrega publicada está incompleta; requiere revisión manual');
      await github.rest.repos.uploadReleaseAsset({ ...context.repo, release_id: release.id, name, data,
        headers: { 'content-type': 'application/octet-stream', 'content-length': data.length } });
    }
  }
  if (release.draft) await github.rest.repos.updateRelease({ ...context.repo, release_id: release.id, draft: false, make_latest: 'legacy' });
  return { version: version.name, commit: context.sha, sha256: metadata.sha256 };
}

async function preparePreview({ github, context, version, publicKey }) {
  trustedMaster(context);
  if (!versionPattern.test(version || '') || !/^[A-Za-z0-9_-]{6,128}$/.test(publicKey || '')) throw new Error('Versión o destino de Appetize inválido');
  const release = (await github.rest.repos.getReleaseByTag({ ...context.repo, tag: version })).data;
  if (release.draft || release.prerelease) throw new Error('La entrega debe estar publicada');
  const latest = (await github.rest.repos.getLatestRelease(context.repo)).data;
  if (latest.tag_name !== version) return { publish: false };
  return { publish: true, version, url: `https://appetize.io/app/${publicKey}` };
}

async function downloadRelease({ github, context, version, directory }) {
  trustedMaster(context);
  if (!versionPattern.test(version || '')) throw new Error('Versión inválida');
  const release = (await github.rest.repos.getReleaseByTag({ ...context.repo, tag: version })).data;
  if (release.draft || release.prerelease) throw new Error('La entrega debe estar publicada');
  downloadAssets(context.repo, version, directory, [APK, METADATA, SUMS]);
  const metadata = JSON.parse(fs.readFileSync(path.join(directory, METADATA), 'utf8'));
  if (metadata.version !== version || !shaPattern.test(metadata.commit || '') ||
      !/^[0-9a-f]{64}$/.test(metadata.sha256 || '') || !Number.isSafeInteger(metadata.version_code) ||
      metadata.version_code < 1 || metadata.version_code > 2100000000) {
    throw new Error('Metadatos de entrega inválidos');
  }
  if (await tagCommit(github, context.repo, version) !== metadata.commit) throw new Error('El tag no coincide con la entrega');
  const ancestry = (await github.rest.repos.compareCommitsWithBasehead({ ...context.repo, basehead: `${metadata.commit}...master` })).data;
  if (ancestry.behind_by !== 0 || !['ahead', 'identical'].includes(ancestry.status)) throw new Error('La entrega no pertenece a master');
  const bytes = fs.readFileSync(path.join(directory, APK));
  if (hash(bytes) !== metadata.sha256 || fs.readFileSync(path.join(directory, SUMS), 'utf8') !== `${metadata.sha256}  ${APK}\n`) {
    throw new Error('La huella del APK no coincide con la entrega');
  }
  return metadata;
}

async function deployPreview({ github, context, directory, publicKey }) {
  trustedMaster(context);
  if (!/^[A-Za-z0-9_-]{6,128}$/.test(publicKey || '')) throw new Error('Destino de Appetize inválido');
  const metadata = JSON.parse(fs.readFileSync(path.join(directory, METADATA), 'utf8'));
  if (!shaPattern.test(metadata.commit || '') || !versionPattern.test(metadata.version || '')) throw new Error('Metadatos de despliegue inválidos');
  const url = `https://appetize.io/app/${publicKey}`;
  const logUrl = `${context.serverUrl || 'https://github.com'}/${context.repo.owner}/${context.repo.repo}/actions/runs/${context.runId}`;
  const deployment = (await github.rest.repos.createDeployment({ ...context.repo, ref: metadata.commit,
    environment: 'appetize', description: `Android ${metadata.version}`, auto_merge: false, required_contexts: [],
    production_environment: false })).data;
  if (!Number.isSafeInteger(deployment.id)) throw new Error('GitHub no confirmó el despliegue');
  const status = state => github.rest.repos.createDeploymentStatus({ ...context.repo, deployment_id: deployment.id,
    state, environment_url: url, log_url: logUrl, auto_inactive: state === 'success' });
  await status('in_progress');
  // shortcut: si el runner termina abruptamente, comprobar y cerrar el registro al recuperar.
  try {
    childProcess.execFileSync('python3', ['.github/scripts/deploy_appetize.py', path.join(directory, APK),
      path.join(directory, METADATA)], { stdio: 'inherit' });
    await status('success');
  } catch (error) {
    try { await status('failure'); } catch { /* El workflow sigue fallando aunque GitHub no permita actualizar el estado. */ }
    throw error;
  }
  return { url, commit: metadata.commit, version: metadata.version };
}

module.exports = { versionFromText, assertIncrease, validateVersion, publishRelease, preparePreview, downloadRelease, deployPreview };
