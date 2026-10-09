'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const { executeBackport } = require('./backport.cjs');

const SHA = 'a'.repeat(40);
const OTHER_SHA = 'b'.repeat(40);

function fixture(options = {}) {
  const source = {
    number: 42,
    merged: true,
    merge_commit_sha: SHA,
    html_url: 'https://github.com/JUANES545/solventa-app-pf2/pull/42',
    head: { ref: 'release/0.1.0', repo: { full_name: 'JUANES545/solventa-app-pf2' } },
    base: { ref: 'master', repo: { full_name: 'JUANES545/solventa-app-pf2' } },
  };
  const context = {
    eventName: 'pull_request',
    repo: { owner: 'JUANES545', repo: 'solventa-app-pf2' },
    payload: { action: 'closed', pull_request: structuredClone(source) },
  };
  const createdRefs = [];
  const createdPulls = [];
  const reads = [];
  const outputs = {};
  let ref = options.ref || null;
  let listCount = 0;
  const failure = (status) => Object.assign(new Error('Simulated API failure'), { status });
  const github = {
    rest: {
      pulls: {
        get: async (params) => {
          reads.push(['pull', params]);
          if (options.sourceError) throw failure(options.sourceError);
          return { data: options.source || source };
        },
        list: async () => {},
        create: async (params) => {
          if (options.createPullError) throw failure(options.createPullError);
          createdPulls.push(params);
          return { data: { html_url: 'https://github.com/JUANES545/solventa-app-pf2/pull/43' } };
        },
      },
      git: {
        getRef: async (params) => {
          reads.push(['ref', params]);
          if (options.refError) throw failure(options.refError);
          if (!ref) throw failure(404);
          return { data: ref };
        },
        createRef: async (params) => {
          if (options.createRefError) {
            if (options.racedRef) ref = options.racedRef;
            throw failure(options.createRefError);
          }
          createdRefs.push(params);
          ref = { object: { sha: params.sha } };
          return { data: ref };
        },
      },
      repos: {
        compareCommitsWithBasehead: async (params) => {
          reads.push(['compare', params]);
          if (options.compareError) throw failure(options.compareError);
          return { data: options.comparison || { ahead_by: 1 } };
        },
      },
    },
    paginate: async (method, params) => {
      assert.equal(method, github.rest.pulls.list);
      assert.equal(params.base, 'develop');
      assert.equal(params.head, 'JUANES545:backport/release-pr-42');
      listCount += 1;
      return options.pullsAfterRace && listCount > 1 ? options.pullsAfterRace : options.pulls || [];
    },
  };
  const core = { info: () => {}, warning: () => {}, setOutput: (key, value) => { outputs[key] = value; } };
  return { source, context, github, core, createdRefs, createdPulls, reads, outputs };
}

async function run(f) {
  return executeBackport({ github: f.github, context: f.context, core: f.core });
}

test('merged release creates a branch at the exact merge SHA and a PR into develop', async () => {
  const f = fixture();
  assert.equal((await run(f)).status, 'created');
  assert.deepEqual(f.createdRefs, [{ owner: 'JUANES545', repo: 'solventa-app-pf2', ref: 'refs/heads/backport/release-pr-42', sha: SHA }]);
  assert.equal(f.createdPulls[0].base, 'develop');
  assert.equal(f.createdPulls[0].head, 'backport/release-pr-42');
  assert.match(f.createdPulls[0].body, new RegExp(SHA));
  assert.match(f.createdPulls[0].body, /pull\/42/);
  assert.equal(f.reads.find(([name]) => name === 'compare')[1].basehead, `develop...${SHA}`);
});

for (const [name, alter] of [
  ['closed without merging', (f) => { f.context.payload.pull_request.merged = false; }],
  ['feature branch', (f) => { f.context.payload.pull_request.head.ref = 'feature/login'; }],
  ['hotfix branch outside the approved scope', (f) => { f.context.payload.pull_request.head.ref = 'hotfix/login'; }],
  ['different target branch', (f) => { f.context.payload.pull_request.base.ref = 'develop'; }],
  ['fork release branch', (f) => { f.context.payload.pull_request.head.repo.full_name = 'someone/solventa-app-pf2'; }],
  ['deleted source repository', (f) => { f.context.payload.pull_request.head.repo = null; }],
  ['missing pull request', (f) => { delete f.context.payload.pull_request; }],
  ['empty release suffix', (f) => { f.context.payload.pull_request.head.ref = 'release/'; }],
  ['different event action', (f) => { f.context.payload.action = 'opened'; }],
  ['different event type', (f) => { f.context.eventName = 'push'; }],
]) {
  test(`${name} makes no API calls or changes`, async () => {
    const f = fixture();
    alter(f);
    assert.equal((await run(f)).status, 'skipped');
    assert.equal(f.reads.length, 0);
    assert.equal(f.createdRefs.length, 0);
    assert.equal(f.createdPulls.length, 0);
  });
}

test('repository name comparison is case-insensitive', async () => {
  const f = fixture();
  f.context.payload.pull_request.head.repo.full_name = 'juanes545/SOLVENTA-APP-PF2';
  assert.equal((await run(f)).status, 'created');
});

test('source PR must still be verified as merged', async () => {
  const f = fixture();
  f.source.merged = false;
  await assert.rejects(run(f), /could not be verified/);
  assert.equal(f.createdRefs.length, 0);
});

test('a different integrated SHA fails without modifying references', async () => {
  const f = fixture();
  f.source.merge_commit_sha = OTHER_SHA;
  await assert.rejects(run(f), /differs/);
  assert.equal(f.createdRefs.length, 0);
});

test('an invalid SHA fails before mutations', async () => {
  const f = fixture();
  f.source.merge_commit_sha = f.context.payload.pull_request.merge_commit_sha = 'invalid';
  await assert.rejects(run(f), /invalid/);
  assert.equal(f.createdRefs.length, 0);
});

test('a matching existing branch is reused without a push or new commit', async () => {
  const f = fixture({ ref: { object: { sha: SHA } } });
  assert.equal((await run(f)).status, 'created');
  assert.equal(f.createdRefs.length, 0);
  assert.equal(f.createdPulls.length, 1);
});

test('an existing branch with different work is never overwritten', async () => {
  const f = fixture({ ref: { object: { sha: OTHER_SHA } } });
  await assert.rejects(run(f), /not overwritten/);
  assert.equal(f.createdRefs.length, 0);
  assert.equal(f.createdPulls.length, 0);
});

for (const [status, pull] of [
  ['existing', { state: 'open', html_url: 'https://example.invalid/pull/43' }],
  ['merged', { state: 'closed', merged_at: '2026-10-09', html_url: 'https://example.invalid/pull/43' }],
  ['closed', { state: 'closed', merged_at: null, html_url: 'https://example.invalid/pull/43' }],
]) {
  test(`${status} backport PR is not duplicated or reopened`, async () => {
    const f = fixture({ pulls: [pull] });
    assert.equal((await run(f)).status, status);
    assert.equal(f.createdRefs.length, 0);
    assert.equal(f.createdPulls.length, 0);
  });
}

test('develop already containing the release skips branch and PR creation', async () => {
  const f = fixture({ comparison: { ahead_by: 0 } });
  assert.equal((await run(f)).status, 'already_synchronized');
  assert.equal(f.createdRefs.length, 0);
  assert.equal(f.createdPulls.length, 0);
});

test('an open backport with human conflict-resolution commits is preserved and reused', async () => {
  const f = fixture({ ref: { object: { sha: OTHER_SHA } }, pulls: [{ state: 'open', html_url: 'https://example.invalid/pull/43' }] });
  assert.equal((await run(f)).status, 'existing');
  assert.equal(f.createdRefs.length, 0);
  assert.equal(f.createdPulls.length, 0);
});

test('an API PR number mismatch fails before mutations', async () => {
  const f = fixture();
  f.source.number = 99;
  await assert.rejects(run(f), /could not be verified/);
  assert.equal(f.createdRefs.length, 0);
});

test('a PR creation conflict without an existing PR is reported', async () => {
  const f = fixture({ createPullError: 422 });
  await assert.rejects(run(f), /Simulated API failure/);
  assert.equal(f.createdPulls.length, 0);
});

test('an incomplete comparison fails closed', async () => {
  const f = fixture({ comparison: {} });
  await assert.rejects(run(f), /incomplete/);
  assert.equal(f.createdRefs.length, 0);
});

for (const [name, options] of [
  ['source read failure', { sourceError: 403 }],
  ['reference read failure', { refError: 500 }],
  ['comparison failure', { compareError: 404 }],
]) {
  test(`${name} does not create a branch or PR`, async () => {
    const f = fixture(options);
    await assert.rejects(run(f), /Simulated API failure/);
    assert.equal(f.createdRefs.length, 0);
    assert.equal(f.createdPulls.length, 0);
  });
}

test('a matching reference created during a race is reused', async () => {
  const f = fixture({ createRefError: 422, racedRef: { object: { sha: SHA } } });
  assert.equal((await run(f)).status, 'created');
  assert.equal(f.createdPulls.length, 1);
});

test('a conflicting reference created during a race is preserved', async () => {
  const f = fixture({ createRefError: 422, racedRef: { object: { sha: OTHER_SHA } } });
  await assert.rejects(run(f), /not overwritten/);
  assert.equal(f.createdPulls.length, 0);
});

test('a failed reference creation without a matching branch is reported', async () => {
  const f = fixture({ createRefError: 422 });
  await assert.rejects(run(f), /Simulated API failure/);
  assert.equal(f.createdPulls.length, 0);
});

test('a PR created during a race is reused instead of duplicated', async () => {
  const f = fixture({ createPullError: 422, pullsAfterRace: [{ state: 'open', html_url: 'https://example.invalid/pull/43' }] });
  assert.equal((await run(f)).status, 'existing');
  assert.equal(f.createdPulls.length, 0);
});

test('a PR permission failure is reported without removing the prepared branch', async () => {
  const f = fixture({ createPullError: 403 });
  await assert.rejects(run(f), /Simulated API failure/);
  assert.equal(f.createdRefs.length, 1);
});

test('no review, merge, reference update or deletion API is used', async () => {
  const f = fixture();
  await run(f);
  assert.deepEqual(Object.keys(f.github.rest.pulls).sort(), ['create', 'get', 'list']);
  assert.deepEqual(Object.keys(f.github.rest.git).sort(), ['createRef', 'getRef']);
});
