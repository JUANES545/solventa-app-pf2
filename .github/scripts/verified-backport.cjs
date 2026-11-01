'use strict';

const { qualifies } = require('./backport.cjs');
const timers = require('node:timers/promises');
const shaPattern = /^[0-9a-f]{40}$/;
const requiredChecks = [
  ['Android verification', 15368],
  ['Android UI tests', 15368],
  ['CI / Validación (Jenkins)', 5247357],
];

function sourceNumber(branch) {
  const match = /^backport\/release-pr-([1-9][0-9]*)$/.exec(branch || '');
  const number = match ? Number(match[1]) : 0;
  return Number.isSafeInteger(number) ? number : 0;
}

async function ancestor(github, owner, repo, base, head) {
  const { data } = await github.rest.repos.compareCommitsWithBasehead({ owner, repo, basehead: `${base}...${head}` });
  if (!Number.isSafeInteger(data.ahead_by) || !Number.isSafeInteger(data.behind_by)) throw new Error('Incomplete ancestry response');
  return data.behind_by === 0 && ['ahead', 'identical'].includes(data.status);
}

async function verifySource({ github, owner, repo, number }) {
  const { data: source } = await github.rest.pulls.get({ owner, repo, pull_number: number });
  if (!qualifies(source, `${owner}/${repo}`) || source.number !== number || !shaPattern.test(source.merge_commit_sha || '') || !shaPattern.test(source.head?.sha || '')) {
    throw new Error('Source is not a verified merged origin release');
  }
  const master = (await github.rest.git.getRef({ owner, repo, ref: 'heads/master' })).data.object.sha;
  if (!await ancestor(github, owner, repo, source.merge_commit_sha, master)) throw new Error('Release is not contained in master');
  const checks = await github.paginate(github.rest.checks.listForRef, { owner, repo, ref: source.head.sha, filter: 'latest', per_page: 100 });
  for (const [name, appId] of requiredChecks) {
    const candidates = checks.filter((check) => check.name === name && check.app?.id === appId);
    if (candidates.length !== 1 || candidates[0].head_sha !== source.head.sha || candidates[0].status !== 'completed' || candidates[0].conclusion !== 'success') {
      throw new Error(`Missing successful release check: ${name}`);
    }
  }
  const reviews = await github.paginate(github.rest.pulls.listReviews, { owner, repo, pull_number: number, per_page: 100 });
  const latest = new Map();
  for (const review of reviews) {
    if (review.state !== 'COMMENTED') latest.set(review.user?.id, review);
  }
  const approved = [...latest.values()].some((review) => review.state === 'APPROVED' && review.commit_id === source.head.sha && review.user?.type === 'User' && review.user?.id !== source.user?.id);
  if (!approved) throw new Error('No current human release approval');
  return source;
}

async function verifyPull({ github, owner, repo, pull, appId, appSlug }) {
  if (!Number.isSafeInteger(appId) || appId <= 0 || !/^[a-z0-9-]+$/.test(appSlug || '')) throw new Error('Dedicated App identity is not configured');
  const number = sourceNumber(pull.head?.ref);
  if (!number || pull.state !== 'open' || pull.draft || pull.base?.ref !== 'develop' ||
      pull.head?.repo?.full_name !== `${owner}/${repo}` || pull.base?.repo?.full_name !== `${owner}/${repo}` ||
      pull.user?.type !== 'Bot' || pull.user?.login !== `${appSlug}[bot]` || !Number.isSafeInteger(pull.user?.id)) {
    throw new Error('PR is not owned by the dedicated backport App');
  }
  const { data: bot } = await github.rest.users.getByUsername({ username: `${appSlug}[bot]` });
  if (bot.id !== pull.user.id || bot.type !== 'Bot' || bot.login !== pull.user.login) {
    throw new Error('GitHub did not confirm the App that created the PR');
  }
  const source = await verifySource({ github, owner, repo, number });
  if (pull.head.sha !== source.merge_commit_sha) throw new Error('Backport contains different commits');
  const branch = (await github.rest.git.getRef({ owner, repo, ref: `heads/${pull.head.ref}` })).data.object.sha;
  const develop = (await github.rest.git.getRef({ owner, repo, ref: 'heads/develop' })).data.object.sha;
  if (branch !== source.merge_commit_sha || pull.base.sha !== develop) {
    throw new Error('Backport references changed; manual verification required');
  }
  if (!await ancestor(github, owner, repo, develop, branch)) {
    if (pull.mergeable !== true || !shaPattern.test(pull.merge_commit_sha || '')) {
      throw new Error('Divergent history needs a conflict-free prospective merge');
    }
    const candidate = (await github.rest.git.getCommit({ owner, repo, commit_sha: pull.merge_commit_sha })).data;
    const release = (await github.rest.git.getCommit({ owner, repo, commit_sha: source.merge_commit_sha })).data;
    if (!Array.isArray(candidate.parents) || candidate.parents.length !== 2 ||
        candidate.parents[0]?.sha !== develop || candidate.parents[1]?.sha !== branch ||
        !shaPattern.test(release.tree?.sha || '') || candidate.tree?.sha !== release.tree.sha) {
      throw new Error('Prospective merge is not the approved release tree; manual verification required');
    }
    const current = (await github.rest.git.getRef({ owner, repo, ref: 'heads/develop' })).data.object.sha;
    if (current !== develop) throw new Error('Develop changed during tree verification');
  }
  return { source, develop };
}

async function automaticBackport({ github, context, core, appId, appSlug, sourceId }) {
  if (!Number.isSafeInteger(appId) || appId <= 0 || !/^[a-z0-9-]+$/.test(appSlug || '')) throw new Error('Dedicated App identity is not configured');
  const { owner, repo } = context.repo;
  const source = await verifySource({ github, owner, repo, number: sourceId });
  const branch = `backport/release-pr-${source.number}`;
  let pulls = await github.paginate(github.rest.pulls.list, { owner, repo, head: `${owner}:${branch}`, base: 'develop', state: 'all', per_page: 100 });
  if (pulls.some((pull) => pull.state === 'closed')) {
    core.info('A previous backport is closed; no duplicate or reopen');
    return;
  }
  if (pulls.length > 1) throw new Error('Ambiguous backport PR');
  const develop = (await github.rest.git.getRef({ owner, repo, ref: 'heads/develop' })).data.object.sha;
  if (await ancestor(github, owner, repo, source.merge_commit_sha, develop)) {
    core.info('Develop already contains the release');
    return;
  }
  if (!pulls.length) {
    try {
      await github.rest.git.createRef({ owner, repo, ref: `refs/heads/${branch}`, sha: source.merge_commit_sha });
    } catch (error) {
      if (error.status !== 422) throw error;
      const current = (await github.rest.git.getRef({ owner, repo, ref: `heads/${branch}` })).data.object.sha;
      if (current !== source.merge_commit_sha) throw new Error('Existing branch was not overwritten');
    }
    const { data: created } = await github.rest.pulls.create({ owner, repo, head: branch, base: 'develop',
      title: `Sincroniza ${source.head.ref} con develop`,
      body: `Sincroniza con develop la entrega aprobada ${source.html_url}, conservando ambos historiales y el contenido validado.`, draft: false });
    pulls = [created];
  }
  // Fresh API reads immediately before merging; do not trust event payloads or PR text.
  let pull;
  for (let attempt = 0; attempt < 5; attempt++) {
    pull = (await github.rest.pulls.get({ owner, repo, pull_number: pulls[0].number })).data;
    if (pull.mergeable !== null) break;
    if (attempt < 4) await timers.setTimeout(1000);
  }
  if (pull.mergeable === false) throw new Error('Backport has conflicts; manual verification required');
  if (pull.mergeable !== true) throw new Error('Mergeability is still unknown after automatic retries');
  await verifyPull({ github, owner, repo, pull, appId, appSlug });
  const { data: merged } = await github.rest.pulls.merge({ owner, repo, pull_number: pull.number, sha: source.merge_commit_sha,
    merge_method: 'merge', commit_title: `Sincroniza la entrega del PR #${source.number} con develop [skip ci]`,
    commit_message: `Sincronización exacta desde ${source.html_url}; se reutiliza la evidencia validada de la entrega.` });
  if (!merged.merged) throw new Error('GitHub did not merge the backport');
  // Detect a base race: GitHub's merge API pins head, not base. Do not claim validation on unexpected content.
  const releaseCommit = (await github.rest.git.getCommit({ owner, repo, commit_sha: source.merge_commit_sha })).data;
  const mergeCommit = (await github.rest.git.getCommit({ owner, repo, commit_sha: merged.sha })).data;
  if (mergeCommit.tree.sha !== releaseCommit.tree.sha) throw new Error('Base changed during merge; resulting tree needs manual validation');
  core.info(`Integrated verified backport ${pull.html_url} at ${merged.sha}`);
}

module.exports = { automaticBackport, verifyPull, verifySource, ancestor, sourceNumber };
