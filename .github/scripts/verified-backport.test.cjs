'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const timers = require('node:timers/promises');
const { verifyPull, automaticBackport, sourceNumber } = require('./verified-backport.cjs');
const HEAD = 'a'.repeat(40), RELEASE = 'b'.repeat(40), BASE = 'c'.repeat(40);
const MERGE = 'd'.repeat(40);

function fixture() {
  const full = 'JUANES545/solventa-app-pf2';
  const source = { number: 6, merged: true, merge_commit_sha: RELEASE, user: { id: 1 },
    head: { ref: 'release/0.1.0', sha: HEAD, repo: { full_name: full } },
    base: { ref: 'master', repo: { full_name: full } }, html_url: 'https://github.com/' + full + '/pull/6' };
  const pull = { number: 7, state: 'open', draft: false, mergeable: true,
    merge_commit_sha: MERGE,
    head: { ref: 'backport/release-pr-6', sha: RELEASE, repo: { full_name: full } },
    base: { ref: 'develop', sha: BASE, repo: { full_name: full } },
    user: { id: 3, type: 'Bot', login: 'backport-app[bot]' } };
  const bot = { id: 3, type: 'Bot', login: 'backport-app[bot]' };
  const checks = ['Android verification', 'Android UI tests', 'CI / Validación (Jenkins)'].map((name, index) =>
    ({ name, app: { id: index === 2 ? 5247357 : 15368 }, head_sha: HEAD, status: 'completed', conclusion: 'success' }));
  const reviews = [{ user: { id: 2, type: 'User' }, state: 'APPROVED', commit_id: HEAD }];
  const candidate = { tree: { sha: HEAD }, parents: [{ sha: BASE }, { sha: RELEASE }] };
  let diverged = false, merged = false;
  const github = { rest: {
    users: { getByUsername: async () => ({ data: bot }) },
    pulls: { get: async ({ pull_number }) => ({ data: pull_number === 6 ? source : pull }),
      list: () => {}, listReviews: () => {}, merge: async () => { merged = true; return { data: { merged: true, sha: RELEASE } }; } },
    checks: { listForRef: () => {} },
    git: { getRef: async ({ ref }) => ({ data: { object: { sha: ref === 'heads/develop' ? BASE : RELEASE } } }),
      getCommit: async ({ commit_sha }) => ({ data: commit_sha === MERGE ? candidate : { tree: { sha: HEAD } } }) },
    repos: { compareCommitsWithBasehead: async ({ basehead }) => ({ data:
      basehead === `${RELEASE}...${BASE}` ? { ahead_by: 0, behind_by: 1, status: 'behind' } :
      basehead === `${BASE}...${RELEASE}` && diverged ? { ahead_by: 1, behind_by: 1, status: 'diverged' } :
      { ahead_by: 1, behind_by: 0, status: 'ahead' } }) },
  }, paginate: async (method) => method === github.rest.checks.listForRef ? checks : method === github.rest.pulls.listReviews ? reviews : [pull] };
  return { github, pull, bot, source, checks, reviews, candidate, owner: 'JUANES545', repo: 'solventa-app-pf2', appId: 99, appSlug: 'backport-app',
    diverge: () => { diverged = true; }, wasMerged: () => merged };
}

test('only canonical backport branch numbers are accepted', () => {
  assert.equal(sourceNumber('backport/release-pr-6'), 6);
  for (const value of ['feature/login', 'backport/release-pr-06', 'backport/release-pr-0', 'backport/release-pr-6/x']) assert.equal(sourceNumber(value), 0);
});
test('verified App PR uses real bot identity, not optional attribution fields', async () => {
  const f = fixture();
  assert.equal('performed_via_github_app' in f.pull, false);
  await verifyPull(f);
});
for (const [name, mutate] of [
  ['human-created lookalike', f => { f.pull.user.type = 'User'; }],
  ['wrong App', f => { f.bot.id = 100; }],
  ['non-bot identity', f => { f.bot.type = 'User'; }],
  ['different bot slug', f => { f.bot.login = 'other-app[bot]'; }],
  ['fork', f => { f.pull.head.repo.full_name = 'someone/repo'; }],
  ['wrong target', f => { f.pull.base.ref = 'master'; }],
  ['additional commits', f => { f.pull.head.sha = HEAD; }],
  ['base race', f => { f.pull.base.sha = HEAD; }],
  ['draft', f => { f.pull.draft = true; }],
  ['failed release check', f => { f.checks[0].conclusion = 'failure'; }],
  ['wrong check publisher', f => { f.checks[2].app.id = 99; }],
  ['obsolete Jenkins name', f => { f.checks[2].name = 'Solventa Android Jenkins'; }],
  ['stale check', f => { f.checks[0].head_sha = BASE; }],
  ['ambiguous check', f => { f.checks.push(f.checks[0]); }],
  ['no review', f => { f.reviews.length = 0; }],
  ['stale review', f => { f.reviews[0].commit_id = BASE; }],
  ['dismissed review', f => { f.reviews[0].state = 'DISMISSED'; }],
  ['self review', f => { f.reviews[0].user.id = 1; }],
  ['unmerged source', f => { f.source.merged = false; }],
  ['divergent develop with unvalidated content', f => { f.diverge(); f.candidate.tree.sha = BASE; }],
]) test(`${name} cannot bypass verification`, async () => {
  const f = fixture(); mutate(f); await assert.rejects(verifyPull(f)); assert.equal(f.wasMerged(), false);
});
test('automatic merge uses verified PR and never calls review APIs', async () => {
  const f = fixture();
  await automaticBackport({ ...f, sourceId: 6, context: { repo: { owner: f.owner, repo: f.repo } }, core: { info: () => {} } });
  assert.equal(f.wasMerged(), true);
});
test('divergent history is accepted only when its prospective merge is the approved release tree', async () => {
  const f = fixture(); f.diverge();
  await verifyPull(f);
  await automaticBackport({ ...f, sourceId: 6, context: { repo: { owner: f.owner, repo: f.repo } }, core: { info: () => {} } });
  assert.equal(f.wasMerged(), true);
});
test('a new divergent backport preserves the exact release head before verifying its merge tree', async t => {
  const f = fixture(); f.diverge();
  const paginate = f.github.paginate;
  t.mock.method(f.github, 'paginate', async (method, args) =>
    method === f.github.rest.pulls.list ? [] : paginate(method, args));
  let created = false;
  f.github.rest.git.createRef = async args => {
    assert.equal(args.ref, 'refs/heads/backport/release-pr-6');
    assert.equal(args.sha, RELEASE);
  };
  f.github.rest.pulls.create = async args => {
    assert.equal(args.head, 'backport/release-pr-6');
    assert.equal(args.base, 'develop');
    created = true;
    return { data: f.pull };
  };
  await automaticBackport({ ...f, sourceId: 6, context: { repo: { owner: f.owner, repo: f.repo } }, core: { info: () => {} } });
  assert.equal(created, true);
  assert.equal(f.wasMerged(), true);
});
for (const [name, mutate] of [
  ['unknown mergeability', f => { f.pull.mergeable = null; }],
  ['conflict', f => { f.pull.mergeable = false; }],
  ['missing merge commit', f => { delete f.pull.merge_commit_sha; }],
  ['different merge base', f => { f.candidate.parents[0].sha = HEAD; }],
  ['different merge head', f => { f.candidate.parents[1].sha = HEAD; }],
  ['missing merge parents', f => { delete f.candidate.parents; }],
  ['additional merge parent', f => { f.candidate.parents.push({ sha: HEAD }); }],
  ['missing merge tree', f => { delete f.candidate.tree; }],
]) test(`divergent history with ${name} requires manual verification`, async () => {
  const f = fixture(); f.diverge(); mutate(f);
  await assert.rejects(verifyPull(f));
  assert.equal(f.wasMerged(), false);
});
test('develop moving while the prospective tree is checked prevents automatic integration', async t => {
  const f = fixture(); f.diverge();
  const read = f.github.rest.git.getRef;
  let reads = 0;
  t.mock.method(f.github.rest.git, 'getRef', async args => {
    if (args.ref === 'heads/develop' && ++reads > 1) return { data: { object: { sha: HEAD } } };
    return read(args);
  });
  await assert.rejects(verifyPull(f), /changed/);
  assert.equal(f.wasMerged(), false);
});
test('unknown mergeability stops after bounded retries without merging', async t => {
  const wait = t.mock.method(timers, 'setTimeout', async () => {});
  const f = fixture(); f.pull.mergeable = null;
  const get = t.mock.method(f.github.rest.pulls, 'get');
  await assert.rejects(automaticBackport({ ...f, sourceId: 6, context: { repo: { owner: f.owner, repo: f.repo } }, core: { info: () => {} } }));
  assert.equal(f.wasMerged(), false);
  assert.equal(get.mock.calls.filter(call => call.arguments[0].pull_number === 7).length, 5);
  assert.equal(wait.mock.callCount(), 4);
});
test('new PR waits for GitHub to compute mergeability and then integrates normally', async t => {
  const wait = t.mock.method(timers, 'setTimeout', async () => {});
  const f = fixture();
  const get = f.github.rest.pulls.get;
  let reads = 0;
  t.mock.method(f.github.rest.pulls, 'get', async args => {
    if (args.pull_number !== 7) return get(args);
    return { data: { ...f.pull, mergeable: ++reads < 3 ? null : true } };
  });
  await automaticBackport({ ...f, sourceId: 6, context: { repo: { owner: f.owner, repo: f.repo } }, core: { info: () => {} } });
  assert.equal(f.wasMerged(), true);
  assert.equal(reads, 3);
  assert.equal(wait.mock.callCount(), 2);
});
test('real conflicts are not retried or bypassed', async t => {
  const wait = t.mock.method(timers, 'setTimeout', async () => {});
  const f = fixture(); f.pull.mergeable = false;
  await assert.rejects(automaticBackport({ ...f, sourceId: 6, context: { repo: { owner: f.owner, repo: f.repo } }, core: { info: () => {} } }), /conflicts/);
  assert.equal(f.wasMerged(), false);
  assert.equal(wait.mock.callCount(), 0);
});
test('changed commits during the wait still fail identity and SHA verification', async t => {
  t.mock.method(timers, 'setTimeout', async () => {});
  const f = fixture();
  const get = f.github.rest.pulls.get;
  let reads = 0;
  t.mock.method(f.github.rest.pulls, 'get', async args => {
    if (args.pull_number !== 7) return get(args);
    return { data: { ...f.pull, mergeable: ++reads < 2 ? null : true, head: { ...f.pull.head, sha: HEAD } } };
  });
  await assert.rejects(automaticBackport({ ...f, sourceId: 6, context: { repo: { owner: f.owner, repo: f.repo } }, core: { info: () => {} } }), /different commits/);
  assert.equal(f.wasMerged(), false);
});
test('merge workflow binds configured identity to the App authenticated by its private key', () => {
  const workflow = fs.readFileSync(path.join(__dirname, '../workflows/automatic-backport.yml'), 'utf8');
  assert.ok(workflow.includes('BACKPORT_APP_SLUG: ${{ steps.app.outputs.app-slug }}'));
  assert.ok(workflow.includes('process.env.BACKPORT_APP_SLUG !== process.env.EXPECTED_APP_SLUG'));
});
