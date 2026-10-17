'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { verifyPull, automaticBackport, sourceNumber } = require('./verified-backport.cjs');
const HEAD = 'a'.repeat(40), RELEASE = 'b'.repeat(40), BASE = 'c'.repeat(40);

function fixture() {
  const full = 'JUANES545/solventa-app-pf2';
  const source = { number: 6, merged: true, merge_commit_sha: RELEASE, user: { id: 1 },
    head: { ref: 'release/0.1.0', sha: HEAD, repo: { full_name: full } },
    base: { ref: 'master', repo: { full_name: full } }, html_url: 'https://github.com/' + full + '/pull/6' };
  const pull = { number: 7, state: 'open', draft: false, mergeable: true,
    head: { ref: 'backport/release-pr-6', sha: RELEASE, repo: { full_name: full } },
    base: { ref: 'develop', sha: BASE, repo: { full_name: full } },
    user: { type: 'Bot', login: 'backport-app[bot]' }, performed_via_github_app: { id: 99 } };
  const checks = ['Android verification', 'Android UI tests', 'CI / Validación (Jenkins)'].map((name, index) =>
    ({ name, app: { id: index === 2 ? 5247357 : 15368 }, head_sha: HEAD, status: 'completed', conclusion: 'success' }));
  const reviews = [{ user: { id: 2, type: 'User' }, state: 'APPROVED', commit_id: HEAD }];
  let diverged = false, merged = false;
  const github = { rest: {
    pulls: { get: async ({ pull_number }) => ({ data: pull_number === 6 ? source : pull }),
      list: () => {}, listReviews: () => {}, merge: async () => { merged = true; return { data: { merged: true, sha: RELEASE } }; } },
    checks: { listForRef: () => {} },
    git: { getRef: async ({ ref }) => ({ data: { object: { sha: ref === 'heads/develop' ? BASE : RELEASE } } }),
      getCommit: async () => ({ data: { tree: { sha: HEAD } } }) },
    repos: { compareCommitsWithBasehead: async ({ basehead }) => ({ data:
      basehead === `${RELEASE}...${BASE}` ? { ahead_by: 0, behind_by: 1, status: 'behind' } :
      { ahead_by: 1, behind_by: diverged ? 1 : 0, status: diverged ? 'diverged' : 'ahead' } }) },
  }, paginate: async (method) => method === github.rest.checks.listForRef ? checks : method === github.rest.pulls.listReviews ? reviews : [pull] };
  return { github, pull, source, checks, reviews, owner: 'JUANES545', repo: 'solventa-app-pf2', appId: 99, appSlug: 'backport-app',
    diverge: () => { diverged = true; }, wasMerged: () => merged };
}

test('only canonical backport branch numbers are accepted', () => {
  assert.equal(sourceNumber('backport/release-pr-6'), 6);
  for (const value of ['feature/login', 'backport/release-pr-06', 'backport/release-pr-0', 'backport/release-pr-6/x']) assert.equal(sourceNumber(value), 0);
});
test('verified App PR reuses exact approved release evidence', async () => { await verifyPull(fixture()); });
for (const [name, mutate] of [
  ['human-created lookalike', f => { f.pull.user.type = 'User'; }],
  ['wrong App', f => { f.pull.performed_via_github_app.id = 100; }],
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
  ['divergent develop', f => { f.diverge(); }],
]) test(`${name} cannot bypass verification`, async () => {
  const f = fixture(); mutate(f); await assert.rejects(verifyPull(f)); assert.equal(f.wasMerged(), false);
});
test('automatic merge uses verified PR and never calls review APIs', async () => {
  const f = fixture();
  await automaticBackport({ ...f, sourceId: 6, context: { repo: { owner: f.owner, repo: f.repo } }, core: { info: () => {} } });
  assert.equal(f.wasMerged(), true);
});
test('unknown mergeability does not merge', async () => {
  const f = fixture(); f.pull.mergeable = null;
  await assert.rejects(automaticBackport({ ...f, sourceId: 6, context: { repo: { owner: f.owner, repo: f.repo } }, core: { info: () => {} } }));
  assert.equal(f.wasMerged(), false);
});
