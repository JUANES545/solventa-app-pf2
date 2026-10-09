import com.cloudbees.plugins.credentials.SystemCredentialsProvider
import com.cloudbees.plugins.credentials.domains.Domain
import com.cloudbees.hudson.plugins.folder.computed.PeriodicFolderTrigger
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import io.jenkins.plugins.checks.github.status.GitHubSCMSourceStatusChecksTrait
import jenkins.branch.BranchSource
import jenkins.model.Jenkins
import jenkins.scm.impl.trait.WildcardSCMHeadFilterTrait
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource
import org.jenkinsci.plugins.github_branch_source.OriginPullRequestDiscoveryTrait
import org.jenkinsci.plugins.workflow.multibranch.WorkflowBranchProjectFactory
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject

def controller = Jenkins.get()
def configFile = new File(controller.rootDir, 'pilot-github-approved.json')
if (configFile.isFile()) {
    def config = new JsonSlurper().parse(configFile)
    if (config.owner != 'JUANES545' || config.repository != 'solventa-app-pf2') {
        throw new IllegalArgumentException('This pilot is limited to the approved repository')
    }
    def credentialId = config.credentialId as String
    def credentials = SystemCredentialsProvider.getInstance().getStore().getCredentials(Domain.global())
    def credential = credentials.find { it.id == credentialId }
    if (credential == null || credential.class.name != 'org.jenkinsci.plugins.github_branch_source.GitHubAppCredentials') {
        throw new IllegalStateException('Introduce the approved GitHub App credential privately before configuring the source')
    }
    def name = 'solventa-android'
    def description = 'Repository-scoped Android PR checks for the private AWS Jenkins pilot'
    def project = controller.getItem(name)
    if (project == null) {
        project = controller.createProject(WorkflowMultiBranchProject, name)
    } else if (!(project instanceof WorkflowMultiBranchProject) || project.description != description) {
        throw new IllegalStateException('An unrelated project uses the pilot name; inspect before changing it')
    }
    def source = new GitHubSCMSource(config.owner as String, config.repository as String, null, false)
    source.setId('solventa-android-github')
    source.setCredentialsId(credentialId)
    def checks = new GitHubSCMSourceStatusChecksTrait()
    checks.setName('Solventa Android Jenkins')
    checks.setSuppressLogs(true)
    source.setTraits([
        new OriginPullRequestDiscoveryTrait(1),
        new WildcardSCMHeadFilterTrait('PR-*', ''),
        checks,
    ])
    def factory = new WorkflowBranchProjectFactory()
    factory.setScriptPath('Jenkinsfile')
    project.setProjectFactory(factory)
    project.sourcesList.clear()
    project.sourcesList.add(new BranchSource(source))
    project.setDescription(description)
    project.addTrigger(new PeriodicFolderTrigger('10m'))
    project.save()
    new File(controller.rootDir, 'pilot-multibranch-applied.json').text = JsonOutput.toJson([
        project: name,
        repository: config.owner + '/' + config.repository,
        credentialId: credentialId,
        checksName: 'Solventa Android Jenkins',
        forkDiscovery: false,
        scanScheduled: false,
    ])
}
