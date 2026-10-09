# Private GitHub integration

## Approved scope

Use a GitHub App installed only on `JUANES545/solventa-app-pf2`:

| Repository permission | Access |
| --- | --- |
| Contents | Read |
| Metadata | Read |
| Pull requests | Read |
| Checks | Read and write |
| Commit statuses | Read and write |

No repository administration, code-write permission, organization access or public webhook is needed. Jenkins polls for origin PRs every ten minutes. Fork discovery is disabled. GitHub Actions and the required review approval remain unchanged.

## Introduce the GitHub credential privately

The repository owner creates the app through GitHub Settings > Developer settings > GitHub Apps. Set the homepage to the repository URL, leave user OAuth callbacks unused and disable the webhook. Allow installation only on the owner's account. During installation, select only `solventa-app-pf2`.

Generate the app's private key in GitHub and keep the downloaded file outside the repository and shared folders. Convert it to PKCS#8 with OpenSSL in a private terminal if required by the GitHub Branch Source credential form. Neither the original nor converted key belongs in chat, commits or build artifacts.

In the private Jenkins UI, add a GitHub App credential with ID `solventa-github-app`. Enter the App ID and key yourself. Set repository access to infer only the accessible repository, and default permissions in untrusted contexts to read-only repository contents. Installation permissions still apply in trusted source indexing and result publishing.

Only the App ID, installation ID and Jenkins credential ID may be shared for configuration. Do not share the private key, client secret or installation token. After adding the credential, leave the credential editor and return to the dashboard before any assisted UI inspection.

## Multibranch source

`infra/jenkins/configure-multibranch.groovy` reads a non-secret approval file in Jenkins home and checks only the credential's ID/type. It configures project `solventa-android`, origin PR discovery, merge-based builds and check name `Solventa Android Jenkins`. The GitHub Checks plugin publishes against the PR head, even when the workspace contains the merge revision. Logs are suppressed in the published check.

The initializer does not integrate a PR, modify GitHub branch protection or read the credential's private-key value. The first scan is started only after the credential and lifecycle prerequisites have been validated. Existing unrelated projects are not overwritten.

## Service identity

The approved matrix retains `JUANES545` as administrator and grants `jenkins-lifecycle` only `Overall/Read` and `Job/Read`. The security realm and administrator password are not changed. No anonymous permissions are added.

After that policy is active, the administrator creates the local service account through the private UI and chooses its password privately. Generate a named API token for that service identity, not for the administrator. Introduce it directly on the controller into `/etc/jenkins-pilot/read-credentials.json`, owned by the `jenkins-lifecycle` OS user, mode 0600, inside its mode-0700 directory.

The file has `user` and `token` fields. It is never checked in, printed or returned by management commands. The lifecycle process uses it locally for queue/node reads only. No build, configure, connect-agent, credential-view or administrative permission is granted to this identity.

`introduce-service-credential.py` is an interactive helper for the user's private Session Manager terminal. Run it as the lifecycle OS user. It hides input, writes atomically with mode 0600 and refuses non-interactive execution. Do not run it through management tools or paste the credential into shell arguments.

## Validation before timer activation

1. Verify the administrator can still log in and administer Jenkins.
2. Verify the GitHub credential is installed only on the intended repository.
3. Run the lifecycle script with `--dry-run` as the service OS user. It must successfully read queue/node status without changing EC2 state or the idle marker.
4. Confirm an empty queue plans no start while the agent is stopped.
5. Confirm a queued origin PR is recognized and plans a start. Enable the timer only after these checks pass.
6. Run a real PR, verify a GitHub check on its exact head SHA, and retain the build result and reports.
7. After completion, verify the agent stops after five idle minutes. Verify a later PR can start it again.

Redirects are not followed on authenticated lifecycle requests. Incomplete queue/node responses fail closed instead of being treated as inactivity. The lifecycle uses bounded AWS retries and timeouts and refuses to start the agent after the pilot deadline.

The seven-day stop schedule and active FREE account plan must remain unchanged. The fixed one-run pilot job is retained as evidence; it is not a replacement for PR discovery and status reporting.
