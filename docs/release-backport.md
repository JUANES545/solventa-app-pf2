# Release backport to develop

## Scope

After an origin `release/*` pull request is merged into `master`, GitHub Actions creates a branch named `backport/release-pr-<number>` at that pull request's exact integrated SHA and opens a PR into `develop`. It does not approve, merge, resolve conflicts, publish an APK or deploy infrastructure.

This implements the team's release synchronization request and supports SOL-146 and the Week 1 versioning evidence. It is separate from the CI and release-publication pipelines.

## Safety and repeat execution

- Closed but unmerged PRs, other target branches, feature/hotfix branches and forks are ignored.
- The integrated release is checked against the GitHub PR API before any mutation.
- A deterministic branch and source PR link preserve traceability.
- Repeated runs reuse an existing PR instead of creating duplicates. A manually closed PR is not reopened automatically.
- Existing references at a different SHA are never overwritten or force-pushed.
- If develop already contains the integrated commit, no new branch or PR is needed.
- The workflow creates references to existing commits; it does not generate new commit authorship.
- Branch/PR creation is serialized per source PR. API failures remain visible and do not remove existing work.

Use **Create a merge commit** when integrating the backport to preserve ancestry. Squashing the backport can cause the same master history to appear again in later synchronization PRs. Conflicts must be handled by the team, not by this automation.

## Authentication and repository setting

The MVP uses the short-lived repository `GITHUB_TOKEN`, not a personal token or the Jenkins GitHub App. Repository-wide workflow permissions remain read-only. Only the backport job requests `contents: write` and `pull-requests: write`.

Before activating it, the repository owner must open **Settings > Actions > General > Workflow permissions** and enable **Allow GitHub Actions to create and approve pull requests**. The UI combines creation and approval in one setting; this workflow only creates PRs and never calls a review/approval API. Keep the default workflow permission on read-only and retain the existing required review and checks.

No secret needs to be added. Never place a token in the workflow or chat. If the setting is disabled by a higher-level policy, ask the owner to review it; do not weaken protections or silently fall back to a different credential.

## CI on generated PRs

Under current GitHub behavior, `pull_request` workflows created by `GITHUB_TOKEN` can enter an approval-required state. A team member with write access selects **Approve workflows to run** on the generated PR. This approval starts CI; it is distinct from reviewing/approving the PR itself.

Jenkins uses periodic repository scanning and can discover the generated origin PR independently. GitHub Actions checks still need to pass before the backport can merge. Do not bypass them when they are awaiting workflow approval.

If fully unattended CI initiation is later required, consider a separate repository-scoped automation App. Do not broaden the existing Jenkins App's code-read permissions for this purpose.

## Installation and validation

The workflow, helper and tests must travel together through `feature/*` to `develop`, then through the normal reviewed release into `master`. The release merge carries the workflow into the target branch. Verify the first automatic run rather than assuming an untested event configuration is active. Do not directly push configuration into protected branches or create a fake release just to test it.

Local tests use Node.js 22 and mocked API methods:

```text
node --test .github/scripts/backport.test.cjs
```

`Backport validation` runs the same tests in GitHub Actions without secrets or API mutations. Existing required Android checks remain unchanged.

For the first approved real release, verify that the backport branch points to its exact merge SHA, the PR targets develop, source links are correct, CI runs after any required workflow approval and one team review is still required. Re-run the backport workflow and confirm it reuses the PR. This end-to-end release test requires the team's release approval; passing mocked tests alone is not evidence that a real backport was created.

Reference: https://docs.github.com/en/actions/how-tos/writing-workflows/choosing-when-your-workflow-runs/triggering-a-workflow
