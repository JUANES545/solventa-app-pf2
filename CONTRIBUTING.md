# Working together

## Branches

| Branch | Purpose | Pull request target |
| --- | --- | --- |
| `master` | Stable releases | — |
| `develop` | Integrated work for the next release | `master` through a release pull request |
| `feature/<name>` | New functionality | `develop` |
| `fix/<name>` | Corrections during development | `develop` |
| `migration/<name>` | Data or configuration migrations | `develop` |
| `release/<version>` | Release preparation | `master`, then synchronize with `develop` |
| `hotfix/<name>` | Urgent correction to a stable release | `master`, then synchronize with `develop` |
| `backport/release-pr-<number>` | Synchronize an integrated release back into development | `develop` |

Start feature, fix, migration and release branches from `develop`. Start hotfix branches from `master`. Use short, descriptive lowercase names separated by hyphens.

Merged origin `release/*` PRs into `master` create a backport PR through GitHub Actions after the workflow and repository setting are enabled. Backports still require review and passing checks; merge them with a merge commit to preserve ancestry. See [docs/release-backport.md](docs/release-backport.md). Hotfix synchronization remains separate work and is not included in this release-only automation.

## Before making changes

Check the working tree, active branch and remote. Fetch remote changes before updating your branch. Use `git pull --ff-only` when an update is safe. Do not discard another person's work or rewrite shared history.

Review the Jira story, its acceptance criteria and the current design before implementing it. If the sources disagree, clarify the decision with the team first.

## Commits

Keep each commit focused on one change. Use a short message in natural English, such as `Add login validation` or `Update setup instructions`.

Add explicit file paths and review the staged diff before committing. Keep credentials, local configuration, logs, generated builds and temporary files out of Git.

## Pull requests

1. Publish your working branch and open a pull request against the appropriate target.
2. Link the Jira story and explain what changed.
3. Describe the checks you ran and any limitations.
4. Request reviews from the team. At least one approval from a team member with write access is required before merging into `master` or `develop`.
5. Address review comments. New commits dismiss earlier approvals, so reviewers must review the updated changes.
6. Resolve all review conversations before merging.

Direct pushes, force pushes and branch deletion are blocked for `master` and `develop`, including for repository administrators.

GitHub Actions runs Android verification and device tests for pull requests. See [docs/android-setup.md](docs/android-setup.md) for commands, report locations and the scope of the initial tests. Passing checks do not replace review of the acceptance criteria or the design.
