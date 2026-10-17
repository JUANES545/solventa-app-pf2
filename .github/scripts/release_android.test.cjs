'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const childProcess = require('node:child_process');
const policy = require('./release_android.cjs');
const SHA = 'a'.repeat(40), BASE = 'b'.repeat(40);
const digest = bytes => 'sha256:' + crypto.createHash('sha256').update(bytes).digest('hex');
const missing = () => Object.assign(new Error('No existe'), { status: 404 });

function fixture(t) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'solventa-cd-test-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const properties = path.join(directory, 'version.properties');
  const apk = path.join(directory, 'app-preview.apk');
  fs.writeFileSync(properties, 'VERSION_CODE=2\nVERSION_NAME=0.2.0\n');
  fs.writeFileSync(apk, 'APK de pruebas');
  const context = { repo: { owner: 'test-owner', repo: 'test-repo' }, ref: 'refs/heads/master', eventName: 'push', sha: SHA, runId: 123 };
  const state = { tag: null, release: null, latest: '0.2.0', calls: [], assets: new Map(), ancestry: { status: 'identical', behind_by: 0 } };
  const github = { rest: {
    git: {
      getRef: async () => { if (!state.tag) throw missing(); return { data: { object: { type: 'commit', sha: state.tag } } }; },
      createRef: async args => { state.calls.push(['tag', args]); state.tag = args.sha; },
    },
    repos: {
      getCommit: async () => ({ data: { parents: [{ sha: BASE }] } }),
      getContent: async () => ({ data: { encoding: 'base64', content: Buffer.from('VERSION_NAME=0.1.0\nVERSION_CODE=1').toString('base64') } }),
      getReleaseByTag: async () => { if (!state.release) throw missing(); return { data: state.release }; },
      createRelease: async args => { state.calls.push(['release', args]); state.release = { id: 1, draft: true, prerelease: false, assets: [] }; return { data: state.release }; },
      uploadReleaseAsset: async args => {
        state.calls.push(['asset', args.name]); state.assets.set(args.name, Buffer.from(args.data));
        const asset = { name: args.name, digest: digest(args.data) }; state.release.assets.push(asset);
        return { data: asset };
      },
      deleteReleaseAsset: async args => { state.calls.push(['delete-empty', args.asset_id]); state.release.assets = state.release.assets.filter(asset => asset.id !== args.asset_id); },
      updateRelease: async args => { state.calls.push(['publish', args]); state.release.draft = args.draft; return { data: state.release }; },
      getLatestRelease: async () => ({ data: { tag_name: state.latest } }),
      compareCommitsWithBasehead: async () => ({ data: state.ancestry }),
      createDeployment: async args => { state.calls.push(['deployment', args]); return { data: { id: 10 } }; },
      createDeploymentStatus: async args => { state.calls.push(['deployment-status', args]); },
    },
  } };
  t.mock.method(childProcess, 'execFileSync', (command, args) => {
    if (command === 'python3') { state.calls.push(['appetize', args]); return; }
    assert.equal(command, 'gh');
    const target = args[args.indexOf('--dir') + 1];
    for (let i = 0; i < args.length; i++) if (args[i] === '--pattern') {
      const name = args[i + 1];
      assert.ok(state.assets.has(name));
      fs.writeFileSync(path.join(target, name), state.assets.get(name));
    }
  });
  return { github, context, apk, properties, state, directory };
}

test('versión estable y código se leen de una única fuente', () => {
  assert.deepEqual(policy.versionFromText('# Entrega\r\nVERSION_NAME=0.2.0\r\nVERSION_CODE=2'), { name: '0.2.0', code: 2 });
  policy.assertIncrease({ name: '0.10.0', code: 10 }, { name: '0.9.0', code: 9 });
  for (const text of ['VERSION_NAME=0.2.0', 'VERSION_NAME=0.02.0\nVERSION_CODE=2', 'VERSION_NAME=0.2.0\nVERSION_CODE=0',
    'VERSION_NAME=0.2.0\nVERSION_CODE=2\nVERSION_CODE=3', 'VERSION_NAME=0.2.0\nVERSION_CODE=9999999999999999999',
    'VERSION_NAME=9999999999999999999.0.0\nVERSION_CODE=2']) {
    assert.throws(() => policy.versionFromText(text));
  }
  for (const next of [{ name: '0.1.0', code: 2 }, { name: '0.0.9', code: 2 }, { name: '0.2.0', code: 1 }]) {
    assert.throws(() => policy.assertIncrease(next, { name: '0.1.0', code: 1 }));
  }
});

test('PR a master valida incremento y rechaza un tag utilizado', async t => {
  const f = fixture(t);
  f.context.eventName = 'pull_request'; f.context.ref = 'refs/pull/1/merge';
  f.context.payload = { pull_request: { base: { ref: 'master', sha: BASE } } };
  await policy.validateVersion(f);
  f.state.tag = BASE;
  await assert.rejects(policy.validateVersion(f), /utilizada/);
});

test('feature a develop no necesita incrementar todavía la versión', async t => {
  const f = fixture(t); f.context.ref = 'refs/heads/develop';
  assert.deepEqual(await policy.validateVersion(f), { name: '0.2.0', code: 2 });
  assert.equal(f.state.calls.length, 0);
});

test('master admite reintentar el mismo SHA, pero no reutilizar otro tag', async t => {
  const f = fixture(t); f.state.tag = SHA;
  await policy.validateVersion(f);
  f.state.tag = BASE;
  await assert.rejects(policy.validateVersion(f), /otro commit/);
});

test('push compara contra master anterior, también al integrar varios commits con rebase', async t => {
  const f = fixture(t); f.context.payload = { before: BASE };
  f.github.rest.repos.getCommit = async () => { throw new Error('No debe usar el primer padre'); };
  await policy.validateVersion(f);
});

test('release publicada contiene APK y metadatos del SHA exacto sin reconstruir', async t => {
  const f = fixture(t);
  const metadata = await policy.publishRelease(f);
  assert.equal(metadata.commit, SHA);
  assert.equal(f.state.tag, SHA);
  assert.deepEqual([...f.state.assets.keys()], ['solventa-preview.apk', 'release.json', 'SHA256SUMS']);
  assert.equal(f.state.assets.get('solventa-preview.apk').toString(), 'APK de pruebas');
  const creation = f.state.calls.find(call => call[0] === 'release')[1];
  assert.equal(creation.target_commitish, SHA);
  assert.equal(creation.draft, true);
  const publication = f.state.calls.find(call => call[0] === 'publish')[1];
  assert.equal(publication.make_latest, 'legacy');
  assert.equal(f.state.release.draft, false);
});

test('reintento es idempotente y no vuelve a subir ni crear la release', async t => {
  const f = fixture(t);
  await policy.publishRelease(f);
  const calls = f.state.calls.length;
  await policy.publishRelease(f);
  assert.equal(f.state.calls.length, calls);
});

test('tag ajeno o APK diferente nunca se sobrescriben', async t => {
  const f = fixture(t); f.state.tag = BASE;
  await assert.rejects(policy.publishRelease(f), /otro commit/);
  assert.equal(f.state.calls.length, 0);
  f.state.tag = null;
  await policy.publishRelease(f);
  fs.writeFileSync(f.apk, 'otro APK');
  await assert.rejects(policy.publishRelease(f), /artefacto diferente/);
});

test('401/403 de GitHub no se interpretan como recurso inexistente', async t => {
  const f = fixture(t);
  f.github.rest.git.getRef = async () => { throw Object.assign(new Error('Prohibido'), { status: 403 }); };
  await assert.rejects(policy.publishRelease(f), /Prohibido/);
  assert.equal(f.state.calls.length, 0);
});

test('release parcial conserva sus assets y reanuda solo los que faltan', async t => {
  const f = fixture(t);
  const upload = f.github.rest.repos.uploadReleaseAsset;
  f.github.rest.repos.uploadReleaseAsset = async args => {
    if (args.name === 'release.json') throw new Error('Fallo temporal');
    return upload(args);
  };
  await assert.rejects(policy.publishRelease(f), /temporal/);
  assert.equal(f.state.release.draft, true);
  f.github.rest.repos.uploadReleaseAsset = upload;
  await policy.publishRelease(f);
  assert.equal(f.state.calls.filter(call => call[0] === 'asset' && call[1] === 'solventa-preview.apk').length, 1);
  assert.equal(f.state.release.draft, false);
});

test('asset sin digest se descarga para comprobarlo, no se reemplaza', async t => {
  const f = fixture(t); await policy.publishRelease(f);
  f.state.release.assets[0].digest = null;
  const calls = f.state.calls.length;
  await policy.publishRelease(f);
  assert.equal(f.state.calls.length, calls);
});

test('solo un starter vacío de un borrador se limpia para reanudar la subida', async t => {
  const f = fixture(t);
  f.state.release = { id: 1, draft: true, assets: [{ id: 99, name: 'solventa-preview.apk', state: 'starter', size: 0 }] };
  await policy.publishRelease(f);
  assert.ok(f.state.calls.some(call => call[0] === 'delete-empty' && call[1] === 99));
  assert.equal(f.state.release.draft, false);
});

test('un asset de una release pública no se borra aunque parezca incompleto', async t => {
  const f = fixture(t);
  f.state.release = { id: 1, draft: false, assets: [{ id: 99, name: 'solventa-preview.apk', state: 'starter', size: 0, digest: 'sha256:' + '0'.repeat(64) }] };
  await assert.rejects(policy.publishRelease(f), /diferente/);
  assert.equal(f.state.calls.filter(call => call[0] === 'delete-empty').length, 0);
});

test('recuperación descarga el APK original y valida tag, huella y pertenencia a master', async t => {
  const f = fixture(t); await policy.publishRelease(f);
  const directory = path.join(f.directory, 'recovery');
  const metadata = await policy.downloadRelease({ ...f, version: '0.2.0', directory });
  assert.equal(metadata.commit, SHA);
  assert.equal(fs.readFileSync(path.join(directory, 'solventa-preview.apk'), 'utf8'), 'APK de pruebas');
  f.state.assets.set('solventa-preview.apk', Buffer.from('corrupto'));
  await assert.rejects(policy.downloadRelease({ ...f, version: '0.2.0', directory: path.join(f.directory, 'bad') }), /huella/);
});

test('recuperación no admite borradores ni commits fuera de master', async t => {
  const f = fixture(t); await policy.publishRelease(f);
  f.state.release.draft = true;
  await assert.rejects(policy.downloadRelease({ ...f, version: '0.2.0', directory: path.join(f.directory, 'draft') }), /publicada/);
  f.state.release.draft = false; f.state.ancestry = { behind_by: 1, status: 'diverged' };
  await assert.rejects(policy.downloadRelease({ ...f, version: '0.2.0', directory: path.join(f.directory, 'diverged') }), /master/);
});

test('solo la última release se prepara para la demo, sin despliegue falso de una antigua', async t => {
  const f = fixture(t); await policy.publishRelease(f);
  assert.deepEqual(await policy.preparePreview({ ...f, version: '0.2.0', publicKey: 'test-public-key' }),
    { publish: true, version: '0.2.0', url: 'https://appetize.io/app/test-public-key' });
  f.state.latest = '0.3.0';
  assert.deepEqual(await policy.preparePreview({ ...f, version: '0.2.0', publicKey: 'test-public-key' }), { publish: false });
});

test('una versión inexistente o borrador no se presenta como despliegue omitido exitoso', async t => {
  const f = fixture(t);
  await assert.rejects(policy.preparePreview({ ...f, version: '0.2.0', publicKey: 'test-public-key' }), /No existe/);
  await policy.publishRelease(f); f.state.release.draft = true;
  await assert.rejects(policy.preparePreview({ ...f, version: '0.2.0', publicKey: 'test-public-key' }), /publicada/);
});

test('ningún PR, develop o tag puede publicar una release', async t => {
  const f = fixture(t);
  for (const change of [{ ref: 'refs/heads/develop' }, { eventName: 'pull_request' }, { eventName: 'pull_request_target' }, { ref: 'refs/tags/0.2.0' }]) {
    await assert.rejects(policy.publishRelease({ ...f, context: { ...f.context, ...change } }), /master/);
  }
  assert.equal(f.state.calls.length, 0);
});

test('despliegue registra el SHA de la APK original, no el código del workflow de recuperación', async t => {
  const f = fixture(t); await policy.publishRelease(f);
  const directory = path.join(f.directory, 'deploy');
  await policy.downloadRelease({ ...f, version: '0.2.0', directory });
  f.context.sha = BASE;
  const result = await policy.deployPreview({ ...f, directory, publicKey: 'test-public-key' });
  const deployment = f.state.calls.find(call => call[0] === 'deployment')[1];
  assert.equal(deployment.ref, SHA);
  assert.equal(deployment.auto_merge, false);
  assert.deepEqual(f.state.calls.filter(call => call[0] === 'deployment-status').map(call => call[1].state), ['in_progress', 'success']);
  assert.equal(result.commit, SHA);
});

test('fallo de Appetize registra failure y conserva la release publicada', async t => {
  const f = fixture(t); await policy.publishRelease(f);
  const directory = path.join(f.directory, 'deploy');
  await policy.downloadRelease({ ...f, version: '0.2.0', directory });
  t.mock.method(childProcess, 'execFileSync', () => { throw new Error('Appetize rechazó la subida'); });
  await assert.rejects(policy.deployPreview({ ...f, directory, publicKey: 'test-public-key' }), /rechazó/);
  assert.deepEqual(f.state.calls.filter(call => call[0] === 'deployment-status').map(call => call[1].state), ['in_progress', 'failure']);
  assert.equal(f.state.release.draft, false);
});
