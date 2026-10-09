# Jenkins pilot on AWS

## Scope

This is a seven-day pilot in `us-east-2` for `solventa-app-pf2`. GitHub Actions remains the required CI. Jenkins initially adds unit tests, Android Lint and a debug build; it does not run an emulator, sign APKs or publish releases. The tests currently cover build configuration, not business functionality.

SOL-151 is the related CI story. Its backend/frontend scope and acceptance criteria are not completed by this Android pilot. No Jira changes are included.

## Launch approval

Do not deploy the template until the concrete launch configuration has been reviewed and approved. Before creating the stack, check that the authenticated account matches the approved account, the account plan is `FREE` and active, sufficient credits remain, and both instance types are eligible. Do not upgrade the plan, join Organizations, use Control Tower, buy commitments or use paid Marketplace images. Stop if AWS asks for a plan change.

`infra/jenkins/pilot.yaml` defines:

- A `t3.small` controller with 20 GiB encrypted gp3 storage and standard CPU credits.
- A `c7i-flex.large` Android agent with 30 GiB encrypted gp3 storage.
- Ubuntu Server 24.04 LTS x86_64, resolved from Canonical's public SSM parameter and verified before launch.
- Temporary public IPv4 addresses for outbound package downloads and SSM, but no public inbound access.
- No SSH key pair, NAT Gateway, load balancer, domain or additional data volumes.
- IMDSv2, basic EC2 monitoring and instance roles instead of AWS access keys.
- A one-time Scheduler action that stops both instances seven days after launch. It does not delete the disks.

The controller has no ingress rules. Jenkins listens on `127.0.0.1:8080` and is accessed through a private Session Manager tunnel. The agent accepts SSH only from the controller security group. Jenkins uses a locally generated controller key; the private key must not be copied into the repository, chat or tool output.

The controller role can describe instances and start/stop only agents tagged for this pilot. It cannot launch or delete instances, modify IAM or deploy application resources. The agent role provides SSM management only; build processes are blocked from instance metadata. The Scheduler role can stop only the two pilot instances.

The root disks are deleted if the stack is explicitly deleted. Export any reports or configuration that must be retained before authorizing deletion.

## Expected credit consumption

Ohio prices checked during preparation: `t3.small` USD 0.0208/hour, `c7i-flex.large` USD 0.08479/hour, gp3 USD 0.08/GiB-month, public IPv4 USD 0.005/hour.

Seven days of controller operation, 50 GiB of persistent disks and ten agent runs of 20 minutes are approximately USD 5.57 before initialization, data transfer and other small usage. The approved budget target is USD 8, not an automatic billing limit. Every additional agent hour is about USD 0.08979 including its temporary IPv4 address. A stopped instance retains storage charges.

The current On-Demand Standard quota is five vCPUs. This pilot uses four when both instances run. Another two-vCPU product server would not fit at the same time without a separately approved change. Do not request a quota or plan change automatically.

## Bootstrap integrity

Use an immutable `BootstrapCommit` and the SHA-256 checksums of both checked-in bootstrap scripts. Set `JenkinsVersion` to a verified available LTS package version. Never point instance initialization at a moving branch. The template does not contain credentials.

The controller script installs Jenkins with Java 21 and zero built-in executors. The agent script installs Java 21 for Jenkins remoting, JDK 17 for Gradle, Android platform 35 and build tools 35.0.0. No emulator is installed. A build user without sudo runs the pipeline. The Android tools archive comes from Google's HTTPS repository.

## Private setup and integration

After launch, verify EC2 status checks, encrypted disks, metadata settings, SSM registration and bootstrap exit status. Do not print full Jenkins or cloud-init logs; they can contain setup credentials.

1. Open Jenkins through a Session Manager port-forwarding session to controller port 8080. Local AWS CLI and Session Manager tooling, if used, only establish the tunnel; no builds run locally.
2. Obtain the initial administrator password directly in your own secure SSM session and use it in the setup wizard. Never paste it into chat.
3. Create your administrator account. Install Pipeline, Git, GitHub Branch Source, SSH Build Agents, Credentials, SSH Credentials, JUnit and Timestamper plugins, checking compatibility with the chosen Jenkins LTS.
   For this pilot, use `trilead-api` version `2.284.v1974ea_324382` or a reviewed compatible update. The minimum version selected by SSH Build Agents produced an SFTP transfer error during Java 21 validation; do not assume minimum dependency versions are sufficient.
4. Keep the built-in node at zero executors. The optional `configure-agent.groovy` initializer can create node `android-aws` from a private `pilot-agent.json` file in Jenkins home. It sets label `android-aws`, one executor, remote directory `/var/lib/jenkins-agent`, Java 21 and known-host verification. If configured manually, use the same settings and the agent's private IP.
5. Add the controller public SSH key to the agent's `authorized_keys`, owned by `jenkins-agent`, mode 0600. Disable forwarding and PTY for this key. Verify the agent host key through SSM and configure known-host verification; do not disable host-key checking.
6. The initializer stores the controller key as a system-scoped SSH credential inside Jenkins without printing it or sending it through the client. If configured manually, use username `jenkins-agent` and system scope. Never use file-based credential sources or bind the node key into builds.
7. Create Multibranch Pipeline `solventa-android` for `JUANES545/solventa-app-pf2`, with script path `Jenkinsfile`. Discover origin PRs, not forks. Start with manual scans, then periodic scans every ten minutes. Keep the job limited to the intended repository.
8. Configure a GitHub App scoped to this repository for authenticated PR discovery and status reporting. Its credential remains in Jenkins; no secret is required in the Jenkinsfile. Jenkins results remain non-required during the pilot.
9. Create a Jenkins service account with only the read permissions needed to inspect the queue and agent. Store its API credential directly on the controller in `/etc/jenkins-pilot/read-credentials.json`, readable only by the lifecycle service user. Do not use an administrator token for the lifecycle service.
10. Install `agent-lifecycle.py` and its service/timer under a separate `jenkins-lifecycle` OS user. Configure `AWS_REGION`, `AGENT_INSTANCE_ID`, `JENKINS_READ_CREDENTIALS_FILE`, `LIFECYCLE_STATE_FILE`, `PILOT_STOP_AT_UTC` and `JENKINS_JOB_PREFIX=solventa-android` in `/etc/jenkins-pilot/lifecycle.env`. The UTC deadline must match the stack schedule. Enable the timer only after its credentials, queue detection and node status are verified.

The lifecycle service starts the stopped agent for queued Solventa work and stops it after five minutes idle. Jenkins read failures must not be treated as inactivity. Both instances initially start for bootstrap; the agent schedules its first shutdown one minute after successful installation. Start it only for configuration and verification while integration is pending. On later starts, idle shutdown requires the configured lifecycle timer. The one-time pilot stop schedule does not prevent an administrator from manually restarting a host later; do not restart after the pilot deadline without approval.

`create-pilot-job.groovy` can create a validation job using the immutable `validationCommit` and numeric `validationAttempt` in the private agent configuration. Full checkout is required for a raw commit SHA. Each explicit attempt is scheduled once and recorded locally. It reads the public repository without a GitHub credential and does not report PR status or replace Multibranch Pipeline integration. Run it only while the agent is intentionally started. Remove the initializer after validation.

## Acceptance checklist

- Account still reports an active FREE plan after deployment.
- Both hosts are SSM-managed and have no public inbound Jenkins or SSH access.
- Agent can connect without disabling host-key verification.
- A real PR runs unit tests, lint and compilation on the AWS agent.
- A failing test produces a failing Jenkins result.
- Available reports are retained in Jenkins.
- Agent stops after inactivity and restarts for new work.
- Pilot stop schedule is enabled with the intended UTC deadline.
- GitHub Actions requirements and one review approval remain unchanged.

## Validation before deployment

Run `python -m unittest discover -s infra/jenkins -p 'test_*.py'` to validate lifecycle decisions. Validate the CloudFormation template with cfn-lint and AWS `ValidateTemplate` before creating any resource. Validate shell syntax separately. The Jenkinsfile still needs validation against a configured Jenkins controller; static preparation alone is not proof that Jenkins has run.
