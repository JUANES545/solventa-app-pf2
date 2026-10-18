# Verified automatic backports

The team approved a dedicated GitHub App for automatic release synchronization. This replaces manual review and repeated application tests only for an exact, already-approved release whose history contains current develop. Other PRs retain all checks and review requirements. This does not implement login, publish releases or change AWS.

## App setup (owner, private credentials)

Create a separate GitHub App, for example `solventa-release-backport`, with no webhook, user authorization callback or public endpoint. Install on **only solventa-app-pf2** initially. Repository permissions: Contents write, Pull requests write, Checks read, Metadata read. Do not give Administration, Secrets, Actions write or Jenkins credentials. Verify whether Workflows write is required when synchronizing commits changing workflow files; grant it only if the actual API requires it, with explicit review.

Configure repository variables `BACKPORT_APP_ID`, `BACKPORT_APP_SLUG` and `BACKPORT_AUTOMATION_ENABLED` (initially `false`). Store the PEM key privately as Actions secret `BACKPORT_APP_PRIVATE_KEY`. Never paste it into chat or commit it. The workflow mints a short-lived installation token scoped to this repository and revokes it on completion. Keep global Actions permissions read-only.

## Protections — no broad bypass

Activation requires a reviewed migration of develop's classic protection to equivalent active rulesets. Rulesets do not override classic protection; leaving classic approval/check requirements in place still blocks the App.

1. Save the complete existing protection privately for comparison/recovery.
2. Create active develop rulesets preserving PR-only changes, blocked force pushes/deletion and resolved review conversations. No admin/write-role bypasses.
3. Place one approval (stale reviews dismissed) and the existing strict required checks in an additional develop ruleset: Android verification (15368), Android UI tests (15368), CI / Validación (Jenkins) (5247357).
4. Only this additional ruleset grants the dedicated App `Integration` bypass in `pull_request` mode. The non-bypass PR/force-push/deletion rules still apply to the App.
5. Compare effective protections before removing the redundant classic develop rule. Never disable all protection as an intermediate step. Master must remain unchanged.
6. Verify effective normal-PR protections and denied direct pushes. Do not perform destructive test pushes.

GitHub grants bypass to an actor, not to a source branch. The App technically can bypass checks/reviews on other develop PRs; the trusted workflow is responsible for narrowing use to verified backports. Protect its secret and review all workflow/policy edits. Never run privileged code checked out from a PR branch; the merge job checks out master.

## Verification and omission of repeated tests

- Only merged origin release/* PRs into master qualify; hotfixes are not included.
- Verify merge SHA belongs to current master, source-head required checks are successful from the expected Apps, and a human approval belongs to that source head.
- Dedicated-App PR creator, App ID, repository, deterministic branch, target and head SHA must match API evidence. Existing PRs created by github-actions[bot] (including PR #7) are not silently adopted or auto-merged.
- App identity uses the authenticated `app-slug` output from token creation, matched against configuration. Users API confirms that the PR author has the real Bot ID and login for that App. Do not rely on `performed_via_github_app`: it is absent from PR responses and can be null in Issues API.
- Current develop must be an ancestor of the release merge. Divergent branches, human-added commits and conflicts require the existing manual flow. This constraint makes omission of repeated tests justified; combining different changes is not an already-validated tree.
- Inside `Android verification`, Actions runs a small API policy step only for candidate backport branches, loading trusted policy from the base commit before checking out any PR code. Verified App backports reuse release evidence instead of repeating application tests. Other branches run normal CI, including automation unit tests in that same job; no separate backport checks are created. Merge commit includes [skip ci] to avoid the redundant push workflow.
- Jenkins already suppresses automatic builds. Do not request /test-jenkins on exact automatic backports; they merge via App bypass without Jenkins checks. Existing comments are not cancelled or deleted.
- A new PR may have unknown mergeability briefly. The workflow rereads it up to five times, waiting one second between unknown results. Conflicts stop immediately; if the state remains unknown, it fails visibly and can be rerun from master with the source PR number. Identity, SHA and ancestry checks still run after the wait.

## Concurrency limitation

The GitHub merge API pins the PR head SHA but has no base-SHA precondition. The job serializes backports and rereads develop immediately before merging; a normal PR can still merge during that tiny interval. A post-merge tree comparison detects this race and fails visibly if the tree differs from the release. It cannot undo an already-completed merge safely. Coordinate normal merges during synchronization. If that residual risk is unacceptable, retain manual verification/CI rather than enabling this no-retest mode.

## Activation order and evidence

1. Review this implementation through an ordinary PR with Actions, Jenkins and approval; ship the workflow/policy to master through a reviewed release.
2. Install/configure the App and private secret, verify identity without exposing token/key values, then perform the reviewed ruleset migration.
3. Ensure the trusted backport policy also exists in develop before setting BACKPORT_AUTOMATION_ENABLED=true. The legacy workflow is disabled only while this variable is true.
4. Test a real approved subsequent release, confirming correct source/head/tree, App identity, skipped application tests, merge commit and unchanged normal-PR/master rules.
5. Record actual results. Prepared code and mocked tests do not prove a working installation, bypass or automatic merge. Repeated runs must not reopen closed PRs or overwrite refs.

Rollback: set BACKPORT_AUTOMATION_ENABLED=false to retain the legacy PR-creation workflow for future releases. Existing closed/open PRs are not altered automatically. Remove the App bypass if deactivating its credentials; retain normal protection.

## Activation status — 2026-10-18

`BACKPORT_AUTOMATION_ENABLED=true`. Develop uses rulesets 25324691 (PR-only, resolved
conversations, no force pushes/deletion, no bypass) and 25324708 (one approval and strict
Android/Jenkins checks, bypass only for App 5277097 in pull-request mode). Master is unchanged.
App authentication, rejection of a legacy-bot PR and idempotent recovery were verified with
real Actions runs. The first new App-created automatic merge still needs observation on a
real release; no release was manufactured for this test.
