import groovy.json.JsonSlurper
import hudson.plugins.git.BranchSpec
import hudson.plugins.git.GitSCM
import hudson.plugins.git.UserRemoteConfig
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob

def controller = Jenkins.get()
def configFile = new File(controller.rootDir, 'pilot-agent.json')
if (configFile.isFile()) {
    def config = new JsonSlurper().parse(configFile)
    def revision = config.validationCommit as String
    if (revision == null || !(revision ==~ /[0-9a-f]{40}/)) {
        throw new IllegalArgumentException('An immutable validation commit is required')
    }
    def name = 'solventa-android-pilot'
    def job = controller.getItem(name)
    def description = 'One-run validation of the AWS Jenkins pilot; PR discovery is configured separately'
    if (job == null) {
        job = controller.createProject(WorkflowJob, name)
    } else if (!(job instanceof WorkflowJob) || job.getDescription() != description) {
        throw new IllegalStateException('An unrelated job uses the pilot name; inspect before changing it')
    }
    def remote = new UserRemoteConfig(
        'https://github.com/JUANES545/solventa-app-pf2.git',
        'origin',
        '+refs/heads/*:refs/remotes/origin/*',
        null,
    )
    def scm = new GitSCM([remote], [new BranchSpec(revision)], false, [], null, null, [])
    def definition = new CpsScmFlowDefinition(scm, 'Jenkinsfile')
    definition.setLightweight(false)
    job.setDefinition(definition)
    job.setDescription(description)
    job.save()
    def attempt = config.validationAttempt as String
    def marker = new File(controller.rootDir, 'pilot-validation-attempt.txt')
    if (attempt == null || !(attempt ==~ /[0-9]+/)) {
        throw new IllegalArgumentException('An explicit numeric validation attempt is required')
    }
    if ((!marker.isFile() || marker.text.trim() != attempt) && !job.isInQueue()) {
        marker.text = attempt
        job.scheduleBuild2(0)
    }
}
