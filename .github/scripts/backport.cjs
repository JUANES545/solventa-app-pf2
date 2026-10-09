'use strict';

function sameRepository(value, expected) {
  return typeof value === 'string' && value.toLowerCase() === expected.toLowerCase();
}

function qualifies(pull, repository) {
  return Boolean(
    pull &&
      Number.isSafeInteger(pull.number) &&
      pull.number > 0 &&
      pull.merged === true &&
      pull.base?.ref === 'master' &&
      typeof pull.head?.ref === 'string' &&
      pull.head.ref.startsWith('release/') &&
      pull.head.ref.length > 'release/'.length &&
      sameRepository(pull.head.repo?.full_name, repository) &&
      sameRepository(pull.base.repo?.full_name, repository),
  );
}

function report(core, status, url = '') {
  core.setOutput('status', status);
  core.setOutput('pull_request_url', url);
  return { status, url };
}

async function existingPull(github, owner, repo, branch) {
  const pulls = await github.paginate(github.rest.pulls.list, {
    owner,
    repo,
    state: 'all',
    head: `${owner}:${branch}`,
    base: 'develop',
    per_page: 100,
  });
  return pulls.find((pull) => pull.state === 'open') || pulls[0];
}

function reportExisting(core, pull) {
  const status = pull.state === 'open' ? 'existing' : pull.merged_at ? 'merged' : 'closed';
  core.info(`Backport PR already exists (${status}): ${pull.html_url}`);
  if (status === 'closed') {
    core.warning('The previous backport was closed without merging; review it manually. No duplicate was created.');
  }
  return report(core, status, pull.html_url);
}

async function readRef(github, owner, repo, branch) {
  try {
    return (await github.rest.git.getRef({ owner, repo, ref: `heads/${branch}` })).data;
  } catch (error) {
    if (error.status === 404) return null;
    throw error;
  }
}

function verifyRef(ref, sha) {
  if (ref && ref.object?.sha !== sha) {
    throw new Error('The deterministic backport branch points to another commit. Existing work was not overwritten.');
  }
}

async function executeBackport({ github, context, core }) {
  const { owner, repo } = context.repo;
  const repository = `${owner}/${repo}`;
  const eventPull = context.payload.pull_request;
  if (context.eventName !== 'pull_request' || context.payload.action !== 'closed' || !qualifies(eventPull, repository)) {
    core.info('This event is not a merged origin release PR into master. No changes made.');
    return report(core, 'skipped');
  }

  const source = (await github.rest.pulls.get({ owner, repo, pull_number: eventPull.number })).data;
  if (!qualifies(source, repository) || source.number !== eventPull.number) {
    throw new Error('The source PR could not be verified as an integrated release in this repository.');
  }
  const sha = source.merge_commit_sha;
  if (typeof sha !== 'string' || !/^[0-9a-f]{40}$/i.test(sha) || sha !== eventPull.merge_commit_sha) {
    throw new Error('The integrated commit differs from the triggering event or is invalid. No changes made.');
  }
  const branch = `backport/release-pr-${source.number}`;
  const previous = await existingPull(github, owner, repo, branch);
  if (previous) return reportExisting(core, previous);
  const currentRef = await readRef(github, owner, repo, branch);
  verifyRef(currentRef, sha);

  const comparison = (await github.rest.repos.compareCommitsWithBasehead({ owner, repo, basehead: `develop...${sha}` })).data;
  if (!Number.isSafeInteger(comparison.ahead_by) || comparison.ahead_by < 0) {
    throw new Error('The commit comparison was incomplete. No branch or PR was created.');
  }
  if (comparison.ahead_by === 0) {
    core.info('Develop already contains the integrated release commit. No backport needed.');
    return report(core, 'already_synchronized');
  }

  if (!currentRef) {
    try {
      await github.rest.git.createRef({ owner, repo, ref: `refs/heads/${branch}`, sha });
    } catch (error) {
      if (error.status !== 422) throw error;
      const racedRef = await readRef(github, owner, repo, branch);
      if (!racedRef) throw error;
      verifyRef(racedRef, sha);
    }
  }

  const title = `Backport ${source.head.ref} to develop`;
  const body = [
    `Synchronize the release integrated by ${source.html_url} into develop.`,
    '',
    `Source release: \`${source.head.ref}\``,
    `Integrated commit: \`${sha}\``,
    '',
    'Review the changes and required checks before merging.',
    'Use a merge commit to preserve the relationship between master and develop.',
    'This workflow does not approve or merge pull requests.',
  ].join('\n');
  try {
    const pull = (await github.rest.pulls.create({ owner, repo, head: branch, base: 'develop', title, body, draft: false })).data;
    core.info(`Backport PR created: ${pull.html_url}`);
    return report(core, 'created', pull.html_url);
  } catch (error) {
    if (error.status === 422) {
      const racedPull = await existingPull(github, owner, repo, branch);
      if (racedPull) return reportExisting(core, racedPull);
    }
    throw error;
  }
}

module.exports = { executeBackport, qualifies };
