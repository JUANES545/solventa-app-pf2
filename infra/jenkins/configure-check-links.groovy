import hudson.ExtensionList
import hudson.model.Job
import hudson.model.Run
import hudson.model.TaskListener
import io.jenkins.plugins.checks.api.ChecksDetails
import io.jenkins.plugins.checks.api.ChecksPublisher
import io.jenkins.plugins.checks.api.ChecksPublisherFactory
import io.jenkins.plugins.checks.github.GitHubChecksPublisherFactory
import jenkins.model.Jenkins
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource
import org.jenkinsci.plugins.github_branch_source.PullRequestSCMHead
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject

class SolventaGitHubEvidencePublisher extends ChecksPublisher {
    final ChecksPublisher delegate
    final String url

    SolventaGitHubEvidencePublisher(ChecksPublisher delegate, String url) {
        this.delegate = delegate
        this.url = url
    }

    @Override
    void publish(ChecksDetails details) {
        if (details.name.orElse('') != 'CI / Validación (Jenkins)') {
            delegate.publish(details)
            return
        }
        def builder = new ChecksDetails.ChecksDetailsBuilder()
            .withName(details.name.get())
            .withStatus(details.status)
            .withConclusion(details.conclusion)
            .withDetailsURL(url)
            .withActions(details.actions)
        details.startedAt.ifPresent { builder.withStartedAt(it) }
        details.completedAt.ifPresent { builder.withCompletedAt(it) }
        details.output.ifPresent { builder.withOutput(it) }
        delegate.publish(builder.build())
    }
}

// The standard factory still owns credentials, SHA resolution and existing check IDs.
class SolventaGitHubEvidenceFactory extends GitHubChecksPublisherFactory {
    static final Map<String, String> REPOSITORIES = [
        'solventa-android': 'JUANES545/solventa-app-pf2',
        'solventa-web/pr-checks': 'JazminCorAndes/solventa-web-pf2',
        'solventa-backend/pr-checks': 'Migue765/backend-solventa',
    ].asImmutable()

    static String evidenceUrl(String project, String repository, int number) {
        if (!REPOSITORIES.containsKey(project)) return null
        if (REPOSITORIES[project] != repository || number < 1) {
            throw new IllegalStateException('Unexpected evidence repository or pull request')
        }
        return "https://github.com/${repository}/pull/${number}/checks"
    }

    String urlForJob(Job job) {
        def project = job.parent
        if (!(project instanceof WorkflowMultiBranchProject) ||
            !REPOSITORIES.containsKey(project.fullName)) return null
        if (project.sourcesList.size() != 1) {
            throw new IllegalStateException('Expected a single approved GitHub source')
        }
        def source = project.sourcesList[0].source
        def head = project.projectFactory.getBranch(job).head
        if (!(source instanceof GitHubSCMSource)) {
            throw new IllegalStateException('Expected the approved GitHub source')
        }
        if (!(head instanceof PullRequestSCMHead)) return null
        return evidenceUrl(project.fullName, "${source.repoOwner}/${source.repository}", head.number)
    }

    @Override
    protected Optional<ChecksPublisher> createPublisher(Run run, TaskListener listener) {
        def url = urlForJob(run.parent)
        if (url == null) return Optional.empty()
        return super.createPublisher(run, listener).map {
            new SolventaGitHubEvidencePublisher(it, url)
        }
    }

    @Override
    protected Optional<ChecksPublisher> createPublisher(Job job, TaskListener listener) {
        def url = urlForJob(job)
        if (url == null) return Optional.empty()
        return super.createPublisher(job, listener).map {
            new SolventaGitHubEvidencePublisher(it, url)
        }
    }
}

def factories = ExtensionList.lookup(ChecksPublisherFactory)
def name = SolventaGitHubEvidenceFactory.name
if (factories.any { it.class.name == name }) {
    println('GitHub evidence links already configured; no duplicate factory added.')
    return
}
if (!factories.any { it.class == GitHubChecksPublisherFactory }) {
    throw new IllegalStateException('Inspect the installed GitHub Checks publisher before applying')
}
def factory = new SolventaGitHubEvidenceFactory()
factories.add(factory)
// Refuse activation if another factory would bypass the scoped wrapper.
if (!factories.first().is(factory)) {
    factories.remove(factory)
    throw new IllegalStateException('Unsupported factory priority; original publishers preserved')
}
def verified = []
try {
    SolventaGitHubEvidenceFactory.REPOSITORIES.each { projectName, repository ->
        def project = Jenkins.get().getItemByFullName(projectName)
        if (project == null) return
        if (!(project instanceof WorkflowMultiBranchProject) || project.sourcesList.size() != 1) {
            throw new IllegalStateException('Unexpected approved project configuration')
        }
        def source = project.sourcesList[0].source
        if (!(source instanceof GitHubSCMSource) ||
            "${source.repoOwner}/${source.repository}" != repository) {
            throw new IllegalStateException('Unexpected approved repository configuration')
        }
        def job = project.items.find { it.lastBuild != null }
        if (job != null) {
            def url = factory.urlForJob(job)
            if (url == null || !url.startsWith("https://github.com/${repository}/pull/")) {
                throw new IllegalStateException('Scoped publisher verification failed; originals preserved')
            }
        }
        verified.add(projectName)
    }
    new File(Jenkins.get().rootDir, 'ci-evidence-links.json').text = JsonOutput.toJson([
        factory: name, verifiedProjects: verified, activatedAt: java.time.Instant.now().toString(),
        verification: 'factory-registration-sources-and-existing-job-routes',
    ])
} catch (RuntimeException | IOException failure) {
    factories.remove(factory)
    throw failure
}
println('GitHub evidence links configured for approved PR jobs; checks and results preserved.')
import groovy.json.JsonOutput
