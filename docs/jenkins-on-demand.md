# On-demand Jenkins checks

## Approved change

Jenkins is required, on-demand verification for PRs into develop and master. GitHub Actions remains automatic and required. A collaborator with current write, maintain or administrator access requests Jenkins by posting a new PR conversation comment whose complete trimmed body is `/test-jenkins`.

Both branches require `Android verification` and `Android UI tests` from the GitHub Actions App (15368), and `Solventa Android Jenkins` from the Jenkins App (5247357), plus one approval. Missing or unsuccessful checks block merging, not submission of a review. If Jenkins or AWS is unavailable, merging remains blocked; do not bypass protections. A new head commit requires a new Jenkins request and current checks.

Read-only visitors, bots, fork PRs and PRs targeting branches other than develop/master are not eligible. Review comments inside code diffs are not scanned. The same comment is processed once; edited comments and comments created before activation are ignored. Post a new comment after publishing new commits if another verification is desired. Existing success on an earlier SHA is not proof of validation of a later SHA.

The controller polls comments every minute with its existing GitHub App. It does not expose a public webhook, execute comment text as code, copy the key into a build, add AWS roles or change the read-only lifecycle user's permissions. Collaborator permission lookup must succeed; an API denial prevents scheduling and must be reviewed rather than silently accepting callers.

Multibranch keeps discovering origin PRs but suppresses automatic builds from indexing and SCM events. An authorized comment schedules the job with the verified PR revision. Jenkins publishes its result on the PR head. No requested execution means no claim that Jenkins tests ran; GitHub Actions still verifies the change automatically.

## Verified runtime deployment

On 2026-10-11, AWS identity was verified as account 396383602233 with an active FREE plan. The CloudFormation update changed only PilotStopSchedule.State to DISABLED without replacing resources. The local deadline was removed and the lifecycle timer remained active. PR-4 build 3 succeeded on b72e2c415ad4da770d6185c9da4d387d929be7ac after an authorized comment; GitHub published successful Jenkins checks. Automatic agent start and subsequent idle shutdown were observed. Integration fixes used supported Jenkins APIs and a standard build cause without weakening security class filters.

The following procedure records the deployment steps; it is not a pending authorization request. Recheck live state and approval before future infrastructure changes.

1. Reauthenticate AWS MCP and verify account identity and active FREE plan.
2. Compare the deployed stack and current template. Disable the existing PilotStopSchedule through a reviewed CloudFormation update that changes only its enabled state. Preserve instances, IAM roles, stored data and security groups. Keep its timestamp parameter for update compatibility; the disabled schedule cannot stop the hosts at that date.
3. Deploy the date-free lifecycle script and remove only `PILOT_STOP_AT_UTC` from its non-secret environment file. Preserve the service credential, five-minute idle timeout and build timeouts.
4. Verify updated lifecycle dry-run and timer configuration.
5. Deploy the no-automatic-build multibranch configuration before scanning new PRs. Install the comment policy and trusted comment initializer outside workspaces. Restart once, preserve authentication and verify plugins.
6. Verify a PR/new commit alone does not start the agent. Use a new authorized `/test-jenkins` comment for a real end-to-end check, confirm the correct head SHA and automatic idle shutdown. Test denied callers using mocked policy data, not unauthorized accounts.

The previous USD 8 target was an estimate, not a hard billing control. Removing the seven-day deadline authorizes continued credit consumption, not conversion to a paid plan. EC2 controller/storage continue consuming credits even without requests. AWS FREE expiration and credit exhaustion still apply. Do not upgrade, join Organizations, purchase commitments or increase capacity without separate authorization.

## Tests and limits

Run lifecycle tests with `python -m unittest discover -s infra/jenkins -p 'test_*.py'`. Run the pure comment policy assertions with Groovy using `test-comment-policy.groovy` and `comment-policy.groovy`. Validate the trusted integration script's class/API compatibility on the real controller before enabling it. Static tests do not prove live GitHub permission access or scheduling.

The processed-comment ledger is local persistent Jenkins data. Keep it with Jenkins backups. If the controller crashes between scheduling and persisting a comment, a duplicate execution is possible; this MVP does not promise transactional exactly-once delivery across Jenkins and disk. The single agent executor prevents simultaneous builds.
