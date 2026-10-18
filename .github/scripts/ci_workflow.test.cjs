'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const workflows = path.join(__dirname, '../workflows');
const workflow = fs.readFileSync(path.join(workflows, 'android-ci.yml'), 'utf8');
const publication = fs.readFileSync(path.join(workflows, 'android-publish.yml'), 'utf8');
const verification = workflow.split('\n  verify:\n')[1].split('\n  instrumentation:\n')[0];
const steps = verification.split('\n      - name: ').slice(1);
const candidate = "github.event_name == 'pull_request' && vars.BACKPORT_AUTOMATION_ENABLED == 'true' && startsWith(github.head_ref, 'backport/release-pr-')";
const skip = "steps.policy.outputs.skip_tests != 'true'";

test('CI no crea workflows ni jobs separados de backport para PRs normales', () => {
  for (const file of ['backport-tests.yml', 'backport-preflight.yml']) {
    assert.equal(fs.existsSync(path.join(workflows, file)), false);
  }
  assert.equal(workflow.includes('backport_policy:'), false);
  assert.match(verification, /name: Android verification/);
  assert.match(verification, /run: node --test \.github\/scripts\/\*\.test\.cjs/);
});

test('solo candidatos a backport leen la política, siempre desde la base confiable', () => {
  assert.ok(steps[0].includes('if: ' + candidate));
  assert.ok(steps[1].includes('if: ' + candidate));
  assert.ok(steps[0].includes('ref: ${{ github.event.pull_request.base.sha }}'));
  assert.ok(steps[0].includes('persist-credentials: false'));
  assert.ok(steps[1].includes("require('./.github/scripts/verified-backport.cjs')"));
  assert.ok(steps[2].startsWith('Check out repository\n'));
});

test('reutilizar evidencia omite todos los pasos de compilación y las pruebas UI', () => {
  for (const step of steps.slice(2)) {
    assert.ok(step.includes('if: ' + skip) || step.includes('if: always() && ' + skip), step.split('\n')[0]);
  }
  assert.ok(verification.includes('skip_tests: ${{ steps.policy.outputs.skip_tests }}'));
  const instrumentation = workflow.split('\n  instrumentation:\n')[1];
  assert.ok(instrumentation.includes("if: needs.verify.outputs.skip_tests != 'true'"));
  assert.match(instrumentation, /needs: verify/);
});

async function runPolicy(failure) {
  const script = steps[1].split('          script: |\n')[1].split('\n').map(line => line.slice(12)).join('\n');
  const outputs = {}, messages = [], calls = [];
  const context = { repo: { owner: 'owner', repo: 'repo' }, issue: { number: 22 } };
  const pull = { number: 22 };
  const github = { rest: { pulls: { get: async () => {
    if (failure === 'api') throw new Error('API unavailable');
    return { data: pull };
  } } } };
  const core = { setOutput: (key, value) => { outputs[key] = value; }, info: message => messages.push(message), warning: message => messages.push(message) };
  const requirePolicy = file => {
    assert.equal(file, './.github/scripts/verified-backport.cjs');
    return { verifyPull: async input => {
      calls.push(input);
      if (failure === 'unverified') throw new Error('Wrong App or changed commits');
    } };
  };
  const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
  await new AsyncFunction('github', 'context', 'core', 'require', 'process', script)(github, context, core, requirePolicy,
    { env: { APP_ID: '5277097', APP_SLUG: 'dedicated-app' } });
  return { outputs, messages, calls, pull };
}

test('un backport verificado reutiliza evidencia sin afirmar que corrió las pruebas', async () => {
  const result = await runPolicy();
  assert.equal(result.outputs.skip_tests, 'true');
  assert.equal(result.calls[0].appId, 5277097);
  assert.equal(result.calls[0].pull, result.pull);
  assert.ok(result.messages[0].includes('no se repiten las pruebas'));
});

for (const failure of ['api', 'unverified']) {
  test(`fallo ${failure} conserva CI normal, sin conceder la excepción`, async () => {
    const result = await runPolicy(failure);
    assert.equal(result.outputs.skip_tests, 'false');
    assert.ok(result.messages[0].includes('se ejecuta CI normal'));
  });
}

test('CI no contiene jobs de publicación ni permisos de escritura', () => {
  assert.doesNotMatch(workflow, /publish_release:|deploy_app:|deploy_preview:|contents: write|deployments: write/);
  assert.match(workflow, /name: android-apk/);
  assert.match(workflow, /path: distribution\/Solventa\.apk/);
});

test('CD solo escucha CI de master y recupera el artefacto de la ejecución verificada', () => {
  assert.match(workflow, /^name: CI · Validación/m);
  assert.doesNotMatch(publication, /^  pull_request:/m);
  assert.match(publication, /workflow_run:/);
  assert.match(publication, /workflows: \['CI · Validación'\]/);
  assert.match(publication, /branches: \[master\]/);
  assert.ok(publication.includes("github.event.workflow_run.event == 'push'"));
  assert.ok(publication.includes('github.event.workflow_run.head_repository.full_name == github.repository'));
  assert.ok(publication.includes('run-id: ${{ steps.source.outputs.run_id }}'));
  assert.ok(publication.includes('await publicationSource({ github, context })'));
  assert.ok(publication.includes("github.event_name == 'workflow_dispatch' && github.ref == 'refs/heads/master'"));
  assert.doesNotMatch(publication, /gradlew|assemblePreview/);
});

test('la portada enlaza directamente a la app y a la última versión con imágenes accesibles', () => {
  const readme = fs.readFileSync(path.join(__dirname, '../../README.md'), 'utf8');
  assert.ok(readme.includes('href="https://appetize.io/app/b_gtzqvlruilrgvlrll4wschl6wy"'));
  assert.ok(readme.includes('href="https://github.com/JUANES545/solventa-app-pf2/releases/latest"'));
  assert.doesNotMatch(readme, /demo|preview|versión de pruebas|Proyecto Final|docs\/ci|\/deployments/i);
  for (const image of ['abrir-solventa', 'descargar-solventa']) {
    assert.ok(readme.includes(`src="docs/assets/${image}.svg" alt="`));
    assert.ok(fs.existsSync(path.join(__dirname, `../../docs/assets/${image}.svg`)));
  }
});
