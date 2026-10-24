'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const childProcess = require('node:child_process');
const { prepareRelease } = require('./prepare_release.cjs');

function fixture(t) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'solventa-version-test-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const properties = path.join(directory, 'version.properties');
  const original = '# Entrega anterior\nVERSION_CODE=6\nVERSION_NAME=0.3.1\nRELEASE_ID=2026-10-19-1\n';
  fs.writeFileSync(properties, original);
  t.mock.method(childProcess, 'execFileSync', (command, args, options) => {
    assert.equal(command, 'git');
    assert.deepEqual(args, ['tag', '--list']);
    assert.equal(options.cwd, directory);
    return '0.3.1\n1.2.0\n';
  });
  return { directory, properties, original };
}

for (const [level, name] of [['patch', '1.2.1'], ['minor', '1.3.0'], ['major', '2.0.0']]) {
  test(`preparar ${level} solo actualiza propiedades desde la referencia 1.2.0`, t => {
    const f = fixture(t);
    assert.deepEqual(prepareRelease({ directory: f.directory, level, releaseId: '2026-10-24-1' }),
      { name, code: 7, releaseId: '2026-10-24-1', level, baseline: '1.2.0' });
    assert.equal(fs.readFileSync(f.properties, 'utf8'),
      `# Entrega anterior\nVERSION_CODE=7\nVERSION_NAME=${name}\nRELEASE_ID=2026-10-24-1\nRELEASE_LEVEL=${level}\n`);
    assert.deepEqual(fs.readdirSync(f.directory), ['version.properties']);
  });
}

test('errores de nivel, fecha, consecutivo y código no modifican el archivo', t => {
  const f = fixture(t);
  for (const values of [
    { level: 'weekly', releaseId: '2026-10-24-1' },
    { level: 'patch' },
    { level: 'patch', releaseId: '2026-02-30-1' },
    { level: 'patch', releaseId: '2026-10-24-1\nEXTRA=inesperado' },
    { level: 'patch', releaseId: '2026-10-19-1' },
    { level: 'patch', releaseId: '2026-10-18-1' },
  ]) {
    assert.throws(() => prepareRelease({ directory: f.directory, ...values }));
    assert.equal(fs.readFileSync(f.properties, 'utf8'), f.original);
  }
  const maximum = f.original.replace('VERSION_CODE=6', 'VERSION_CODE=2100000000');
  fs.writeFileSync(f.properties, maximum);
  assert.throws(() => prepareRelease({ directory: f.directory, level: 'patch', releaseId: '2026-10-24-1' }), /Código/);
  assert.equal(fs.readFileSync(f.properties, 'utf8'), maximum);
});

test('un error de Git se informa sin cambiar propiedades', t => {
  const f = fixture(t);
  t.mock.method(childProcess, 'execFileSync', () => { throw new Error('No se pudieron consultar los tags'); });
  assert.throws(() => prepareRelease({ directory: f.directory, level: 'patch', releaseId: '2026-10-24-1' }), /consultar/);
  assert.equal(fs.readFileSync(f.properties, 'utf8'), f.original);
});

test('preparar otra entrega reemplaza RELEASE_LEVEL y conserva propiedades adicionales', t => {
  const f = fixture(t);
  fs.writeFileSync(f.properties, f.original + 'RELEASE_LEVEL=patch\nEXTRA=conservar\n');
  prepareRelease({ directory: f.directory, level: 'major', releaseId: '2026-10-24-1' });
  const text = fs.readFileSync(f.properties, 'utf8');
  assert.equal((text.match(/^RELEASE_LEVEL=/gm) || []).length, 1);
  assert.match(text, /^RELEASE_LEVEL=major$/m);
  assert.match(text, /^EXTRA=conservar$/m);
});

test('la entrada de comandos informa argumentos inválidos sin preparar una entrega', () => {
  const result = childProcess.spawnSync(process.execPath, [path.join(__dirname, 'prepare_release.cjs')], { encoding: 'utf8' });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /Uso:/);
  assert.equal(result.stdout, '');
});

test('el comando prepara un archivo real sin crear commits ni tags', t => {
  const f = fixture(t);
  fs.writeFileSync(f.properties, f.original.replace('VERSION_NAME=0.3.1', 'VERSION_NAME=1.2.0'));
  const init = childProcess.spawnSync('git', ['init', '--quiet', f.directory], { encoding: 'utf8' });
  assert.equal(init.status, 0, init.stderr);
  const result = childProcess.spawnSync(process.execPath,
    [path.join(__dirname, 'prepare_release.cjs'), 'patch', '2026-10-24-1'], { cwd: f.directory, encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /1\.2\.0 -> 1\.2\.1 \(patch\); VERSION_CODE=7/);
  assert.match(fs.readFileSync(f.properties, 'utf8'), /^VERSION_NAME=1.2.1$/m);
  const tags = childProcess.spawnSync('git', ['tag', '--list'], { cwd: f.directory, encoding: 'utf8' });
  assert.equal(tags.status, 0, tags.stderr);
  assert.equal(tags.stdout, '');
  const head = childProcess.spawnSync('git', ['rev-parse', '--verify', 'HEAD'], { cwd: f.directory, encoding: 'utf8' });
  assert.notEqual(head.status, 0);
});
