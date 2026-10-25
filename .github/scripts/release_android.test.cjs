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
  const apk = path.join(directory, 'Solventa.apk');
  fs.writeFileSync(properties, 'VERSION_CODE=2\nVERSION_NAME=0.2.0\nRELEASE_ID=2026-10-19-1\nRELEASE_LEVEL=minor\n');
  fs.writeFileSync(apk, 'APK de pruebas');
  const context = { repo: { owner: 'test-owner', repo: 'test-repo' }, ref: 'refs/heads/master', eventName: 'push', sha: SHA, runId: 123 };
  const state = { tag: null, tags: [], release: null, latest: '0.2.0', calls: [], assets: new Map(), ancestry: { status: 'identical', behind_by: 0 },
    run: { id: 456, workflow_id: 10, name: 'CI · Validación', event: 'push', head_branch: 'master', head_sha: SHA,
      head_repository: { full_name: 'test-owner/test-repo' }, status: 'completed', conclusion: 'success' },
    jobs: ['Android verification', 'Android UI tests'].map(name => ({ name, status: 'completed', conclusion: 'success' })) };
  const github = { rest: {
    actions: { getWorkflowRun: async () => ({ data: state.run }), getWorkflow: async () => ({ data: { id: 10 } }), listJobsForWorkflowRun: () => {} },
    git: {
      getRef: async () => { if (!state.tag) throw missing(); return { data: { object: { type: 'commit', sha: state.tag } } }; },
      createRef: async args => { state.calls.push(['tag', args]); state.tag = args.sha; },
    },
    repos: {
      getCommit: async () => ({ data: { parents: [{ sha: BASE }] } }),
      getContent: async () => ({ data: { encoding: 'base64', content: Buffer.from('VERSION_NAME=0.1.0\nVERSION_CODE=1').toString('base64') } }),
      listTags: () => {},
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
  }, paginate: async method => method === github.rest.repos.listTags ? state.tags : state.jobs };
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

test('identificador de entrega valida fecha real y consecutivo sin cambiar la versión Android', () => {
  const properties = 'VERSION_NAME=0.3.1\nVERSION_CODE=6\n';
  assert.deepEqual(policy.versionFromText(properties + 'RELEASE_ID=2024-02-29-1'),
    { name: '0.3.1', code: 6, releaseId: '2024-02-29-1' });
  for (const id of ['', '2026-02-29-1', '2026-02-31-1', '2026-13-01-1', '2026-10-19-0',
    '2026-10-19-01', '2026-10-19', '19-10-2026-1', '2026-10-19-99999999999999999999']) {
    assert.throws(() => policy.versionFromText(properties + 'RELEASE_ID=' + id), /entrega inválido/);
  }
});

test('el equipo elige parche, cierre semanal o cierre de sprint y se reinician los niveles inferiores', () => {
  for (const [level, expected] of [['patch', '1.2.1'], ['minor', '1.3.0'], ['major', '2.0.0']]) {
    assert.equal(policy.incrementVersion('1.2.0', level), expected);
  }
  assert.equal(policy.incrementVersion('1.2.9', 'minor'), '1.3.0');
  assert.equal(policy.incrementVersion('1.9.9', 'major'), '2.0.0');
  assert.equal(policy.versionBaseline('0.3.1', ['0.3.0', '1.2.0', '0.10.0', 'v9.0.0', '1.02.0']), '1.2.0');
  assert.equal(policy.versionBaseline('1.3.0', ['1.2.0']), '1.3.0');
  assert.equal(policy.versionBaseline('0.9.0', ['0.10.0']), '0.10.0');
  for (const level of [undefined, '', 'weekly', 'PATCH']) {
    assert.throws(() => policy.incrementVersion('1.2.0', level), /RELEASE_LEVEL/);
    assert.throws(() => policy.versionFromText(`VERSION_NAME=1.2.0\nVERSION_CODE=6\nRELEASE_LEVEL=${level}`), /RELEASE_LEVEL/);
  }
  assert.throws(() => policy.incrementVersion('9007199254740991.0.0', 'major'), /límite/);
  assert.throws(() => policy.incrementVersion('1.02.0', 'patch'));
});

test('la entrega compara con el mayor tag existente aunque master conserve una versión anterior', async t => {
  const f = fixture(t);
  f.context.eventName = 'pull_request';
  f.context.payload = { pull_request: { base: { ref: 'master', sha: BASE }, head: { ref: 'release/2026-10-24-1' } } };
  f.state.tags = [{ name: '0.3.1' }, { name: '1.2.0' }];
  for (const [level, name] of [['patch', '1.2.1'], ['minor', '1.3.0'], ['major', '2.0.0']]) {
    fs.writeFileSync(f.properties, `VERSION_CODE=7\nVERSION_NAME=${name}\nRELEASE_ID=2026-10-24-1\nRELEASE_LEVEL=${level}\n`);
    assert.equal((await policy.validateVersion(f)).name, name);
  }
  fs.writeFileSync(f.properties, 'VERSION_CODE=7\nVERSION_NAME=1.4.0\nRELEASE_ID=2026-10-24-1\nRELEASE_LEVEL=minor\n');
  await assert.rejects(policy.validateVersion(f), /requiere VERSION_NAME=1.3.0 desde 1.2.0/);
  fs.writeFileSync(f.properties, 'VERSION_CODE=7\nVERSION_NAME=1.2.1\nRELEASE_ID=2026-10-24-1\n');
  await assert.rejects(policy.validateVersion(f), /RELEASE_LEVEL/);
  assert.equal(f.state.calls.length, 0);
});

test('un reintento no usa su propio tag como referencia ni oculta errores al consultar tags', async t => {
  const f = fixture(t);
  f.state.tag = SHA;
  f.state.tags = [{ name: '0.1.0' }, { name: '0.2.0' }, { name: '0.3.0' }];
  await policy.validateVersion(f);
  f.github.paginate = async () => { throw Object.assign(new Error('Prohibido consultar tags'), { status: 403 }); };
  await assert.rejects(policy.validateVersion(f), /Prohibido/);
});

test('un tag aparecido durante la validación no se excluye como si fuera un reintento', async t => {
  const f = fixture(t);
  f.context.eventName = 'pull_request';
  f.context.payload = { pull_request: { base: { ref: 'master', sha: BASE }, head: { ref: 'release/2026-10-19-1' } } };
  f.state.tags = [{ name: '0.2.0' }];
  await assert.rejects(policy.validateVersion(f), /requiere VERSION_NAME=0.3.0/);
});

test('hotfix también declara un incremento y no necesita el prefijo release', async t => {
  const f = fixture(t);
  f.context.eventName = 'pull_request';
  f.context.payload = { pull_request: { base: { ref: 'master', sha: BASE }, head: { ref: 'hotfix/correccion-sesion' } } };
  fs.writeFileSync(f.properties, 'VERSION_CODE=2\nVERSION_NAME=0.1.1\nRELEASE_ID=2026-10-19-1\nRELEASE_LEVEL=patch\n');
  assert.equal((await policy.validateVersion(f)).name, '0.1.1');
});

test('el consecutivo de entrega aumenta numéricamente y se reinicia al cambiar de fecha', () => {
  const previous = { name: '0.3.0', code: 5, releaseId: '2026-10-18-9' };
  policy.assertIncrease({ name: '0.3.1', code: 6, releaseId: '2026-10-18-10' }, previous);
  policy.assertIncrease({ name: '0.3.1', code: 6, releaseId: '2026-10-19-1' }, previous);
  for (const releaseId of ['2026-10-17-10', '2026-10-18-8', '2026-10-18-9']) {
    assert.throws(() => policy.assertIncrease({ name: '0.3.1', code: 6, releaseId }, previous), /RELEASE_ID/);
  }
});

test('PR de entrega exige identificador y una rama de fecha coincidente', async t => {
  const f = fixture(t);
  f.context.eventName = 'pull_request';
  f.context.payload = { pull_request: { base: { ref: 'master', sha: BASE }, head: { ref: 'release/0.2.0' } } };
  await assert.rejects(policy.validateVersion(f), /rama de entrega/);
  f.context.payload.pull_request.head.ref = 'release/2026-10-19-1';
  await policy.validateVersion(f);
  fs.writeFileSync(f.properties, 'VERSION_CODE=2\nVERSION_NAME=0.2.0\n');
  await assert.rejects(policy.validateVersion(f), /necesita RELEASE_ID/);
  f.context.eventName = 'push';
  await assert.rejects(policy.validateVersion(f), /necesita RELEASE_ID/);
  assert.equal(f.state.calls.length, 0);
});

test('PR a master valida incremento y rechaza un tag utilizado', async t => {
  const f = fixture(t);
  f.context.eventName = 'pull_request'; f.context.ref = 'refs/pull/1/merge';
  f.context.payload = { pull_request: { base: { ref: 'master', sha: BASE }, head: { ref: 'release/2026-10-19-1' } } };
  await policy.validateVersion(f);
  f.state.tag = BASE;
  await assert.rejects(policy.validateVersion(f), /utilizada/);
});

test('feature a develop no necesita incrementar todavía la versión', async t => {
  const f = fixture(t); f.context.ref = 'refs/heads/develop';
  assert.deepEqual(await policy.validateVersion(f), { name: '0.2.0', code: 2, releaseId: '2026-10-19-1', level: 'minor' });
  fs.writeFileSync(f.properties, 'VERSION_NAME=0.3.1\nVERSION_CODE=6\nRELEASE_ID=2026-10-19-1\n');
  assert.equal((await policy.validateVersion(f)).level, undefined);
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
  assert.deepEqual([...f.state.assets.keys()], ['Solventa.apk', 'release.json', 'SHA256SUMS']);
  assert.equal(f.state.assets.get('Solventa.apk').toString(), 'APK de pruebas');
  const creation = f.state.calls.find(call => call[0] === 'release')[1];
  assert.equal(creation.target_commitish, SHA);
  assert.equal(creation.draft, true);
  assert.equal(creation.name, 'Solventa · 2026-10-19-1 · v0.2.0');
  assert.equal(metadata.release_id, '2026-10-19-1');
  assert.equal(metadata.release_level, 'minor');
  assert.match(creation.body, /Descargar para Android/);
  assert.doesNotMatch(creation.body, /demo|pruebas|Play Store|Commit/i);
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

test('la fecha no sustituye el tag ni altera los enlaces de descarga y recuperación', async t => {
  const f = fixture(t);
  await policy.publishRelease(f);
  const creation = f.state.calls.find(call => call[0] === 'release')[1];
  assert.equal(creation.tag_name, '0.2.0');
  assert.match(creation.body, /releases\/download\/0\.2\.0\/Solventa\.apk/);
  f.state.release.name = 'Solventa · 2026-10-19-1 · v0.2.0';
  const downloaded = await policy.downloadRelease({ ...f, version: '0.2.0', directory: path.join(f.directory, 'dated') });
  assert.equal(downloaded.release_id, '2026-10-19-1');
  assert.equal(downloaded.version, '0.2.0');
  await assert.rejects(policy.prepareDeployment({ ...f, version: '2026-10-19-1', publicKey: 'test-public-key' }), /Versión/);
});

test('los metadatos anteriores sin fecha siguen permitiendo recuperar la APK original', async t => {
  const f = fixture(t);
  fs.writeFileSync(f.properties, 'VERSION_CODE=2\nVERSION_NAME=0.2.0\n');
  const metadata = await policy.publishRelease(f);
  assert.equal(metadata.release_id, undefined);
  assert.equal(f.state.calls.find(call => call[0] === 'release')[1].name, 'Solventa 0.2.0');
  assert.deepEqual(await policy.downloadRelease({ ...f, version: '0.2.0', directory: path.join(f.directory, 'old') }),
    { ...metadata, version_code: 2 });
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
  assert.equal(f.state.calls.filter(call => call[0] === 'asset' && call[1] === 'Solventa.apk').length, 1);
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
  f.state.release = { id: 1, draft: true, assets: [{ id: 99, name: 'Solventa.apk', state: 'starter', size: 0 }] };
  await policy.publishRelease(f);
  assert.ok(f.state.calls.some(call => call[0] === 'delete-empty' && call[1] === 99));
  assert.equal(f.state.release.draft, false);
});

test('un asset de una release pública no se borra aunque parezca incompleto', async t => {
  const f = fixture(t);
  f.state.release = { id: 1, draft: false, assets: [{ id: 99, name: 'Solventa.apk', state: 'starter', size: 0, digest: 'sha256:' + '0'.repeat(64) }] };
  await assert.rejects(policy.publishRelease(f), /diferente/);
  assert.equal(f.state.calls.filter(call => call[0] === 'delete-empty').length, 0);
});

test('recuperación descarga el APK original y valida tag, huella y pertenencia a master', async t => {
  const f = fixture(t); await policy.publishRelease(f);
  const directory = path.join(f.directory, 'recovery');
  const metadata = await policy.downloadRelease({ ...f, version: '0.2.0', directory });
  assert.equal(metadata.commit, SHA);
  assert.equal(fs.readFileSync(path.join(directory, 'Solventa.apk'), 'utf8'), 'APK de pruebas');
  f.state.assets.set('Solventa.apk', Buffer.from('corrupto'));
  await assert.rejects(policy.downloadRelease({ ...f, version: '0.2.0', directory: path.join(f.directory, 'bad') }), /huella/);
});

test('recuperación no admite borradores ni commits fuera de master', async t => {
  const f = fixture(t); await policy.publishRelease(f);
  f.state.release.draft = true;
  await assert.rejects(policy.downloadRelease({ ...f, version: '0.2.0', directory: path.join(f.directory, 'draft') }), /publicada/);
  f.state.release.draft = false; f.state.ancestry = { behind_by: 1, status: 'diverged' };
  await assert.rejects(policy.downloadRelease({ ...f, version: '0.2.0', directory: path.join(f.directory, 'diverged') }), /master/);
});

test('solo la última release se prepara para la app, sin despliegue falso de una antigua', async t => {
  const f = fixture(t); await policy.publishRelease(f);
  assert.deepEqual(await policy.prepareDeployment({ ...f, version: '0.2.0', publicKey: 'test-public-key' }),
    { publish: true, version: '0.2.0', url: 'https://appetize.io/app/test-public-key' });
  f.state.latest = '0.3.0';
  assert.deepEqual(await policy.prepareDeployment({ ...f, version: '0.2.0', publicKey: 'test-public-key' }), { publish: false });
});

test('una versión inexistente o borrador no se presenta como despliegue omitido exitoso', async t => {
  const f = fixture(t);
  await assert.rejects(policy.prepareDeployment({ ...f, version: '0.2.0', publicKey: 'test-public-key' }), /No existe/);
  await policy.publishRelease(f); f.state.release.draft = true;
  await assert.rejects(policy.prepareDeployment({ ...f, version: '0.2.0', publicKey: 'test-public-key' }), /publicada/);
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
  const result = await policy.deployApp({ ...f, directory, publicKey: 'test-public-key' });
  const deployment = f.state.calls.find(call => call[0] === 'deployment')[1];
  assert.equal(deployment.ref, SHA);
  assert.equal(deployment.auto_merge, false);
  assert.equal(deployment.environment, 'Solventa App');
  assert.deepEqual(f.state.calls.filter(call => call[0] === 'deployment-status').map(call => call[1].state), ['in_progress', 'success']);
  assert.equal(result.commit, SHA);
});

test('fallo de Appetize registra failure y conserva la release publicada', async t => {
  const f = fixture(t); await policy.publishRelease(f);
  const directory = path.join(f.directory, 'deploy');
  await policy.downloadRelease({ ...f, version: '0.2.0', directory });
  t.mock.method(childProcess, 'execFileSync', () => { throw new Error('Appetize rechazó la subida'); });
  await assert.rejects(policy.deployApp({ ...f, directory, publicKey: 'test-public-key' }), /rechazó/);
  assert.deepEqual(f.state.calls.filter(call => call[0] === 'deployment-status').map(call => call[1].state), ['in_progress', 'failure']);
  assert.equal(f.state.release.draft, false);
});

test('CD publica el SHA de CI, no el SHA de la rama predeterminada del workflow_run', async t => {
  const f = fixture(t);
  f.context.eventName = 'workflow_run'; f.context.ref = 'refs/heads/develop'; f.context.sha = BASE;
  f.context.payload = { workflow_run: { id: 456, head_sha: SHA } };
  assert.deepEqual(await policy.publicationSource(f), { sha: SHA, runId: 456 });
  const release = await policy.publishRelease(f);
  assert.equal(release.commit, SHA);
  assert.equal(f.state.tag, SHA);
});

for (const [name, mutate] of [
  ['CI de PR', f => { f.state.run.event = 'pull_request'; }],
  ['fork', f => { f.state.run.head_repository.full_name = 'other/repo'; }],
  ['develop', f => { f.state.run.head_branch = 'develop'; }],
  ['CI fallido', f => { f.state.run.conclusion = 'failure'; }],
  ['CI incompleto', f => { f.state.run.status = 'in_progress'; }],
  ['otro workflow', f => { f.state.run.workflow_id = 11; }],
  ['CI anterior a la separación', f => { f.state.run.name = 'CI · Actions'; }],
  ['SHA distinto', f => { f.state.run.head_sha = BASE; }],
  ['UI omitida', f => { f.state.jobs[1].conclusion = 'skipped'; }],
  ['UI ausente', f => { f.state.jobs.pop(); }],
  ['check ambiguo', f => { f.state.jobs.push(f.state.jobs[0]); }],
  ['commit fuera de master', f => { f.state.ancestry = { status: 'diverged', behind_by: 1 }; }],
]) test(`${name} no autoriza CD`, async t => {
  const f = fixture(t);
  f.context.eventName = 'workflow_run';
  f.context.payload = { workflow_run: { id: 456, head_sha: SHA } };
  mutate(f);
  await assert.rejects(policy.publishRelease(f));
  assert.equal(f.state.calls.length, 0);
});

test('las releases anteriores mantienen su APK y sus checksums, sin romper recuperación', async t => {
  const f = fixture(t); await policy.publishRelease(f);
  const legacy = 'solventa-preview.apk';
  f.state.assets.set(legacy, f.state.assets.get('Solventa.apk'));
  f.state.assets.delete('Solventa.apk');
  f.state.assets.set('SHA256SUMS', Buffer.from(f.state.assets.get('SHA256SUMS').toString().replace('Solventa.apk', legacy)));
  f.state.release.assets = [...f.state.assets].map(([name, data]) => ({ name, digest: digest(data) }));
  const calls = f.state.calls.length;
  await policy.publishRelease(f);
  assert.equal(f.state.calls.length, calls);
  const directory = path.join(f.directory, 'legacy');
  await policy.downloadRelease({ ...f, version: '0.2.0', directory });
  assert.equal(fs.readFileSync(path.join(directory, 'Solventa.apk'), 'utf8'), 'APK de pruebas');
  assert.equal(fs.existsSync(path.join(directory, legacy)), false);
});
