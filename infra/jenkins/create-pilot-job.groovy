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
    if (job == null) {
        job = controller.createProject(WorkflowJob, name)
        def remote = new UserRemoteConfig(
            'https://github.com/JUANES545/solventa-app-pf2.git',
            'origin',
            '+refs/heads/*:refs/remotes/origin/*',
            null,
        )
        def scm = new GitSCM([remote], [new BranchSpec(revision)], false, [], null, null, [])
        def definition = new CpsScmFlowDefinition(scm, 'Jenkinsfile')
        definition.setLightweight(true)
        job.setDefinition(definition)
        job.setDescription('One-run validation of the AWS Jenkins pilot; PR discovery is configured separately')
        job.save()
    }
    if (job instanceof WorkflowJob && job.getLastBuild() == null && !job.isInQueue()) {
        job.scheduleBuild2(0)
    }
}
