import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import hudson.model.Cause
import hudson.model.CauseAction
import hudson.security.ACL
import jenkins.model.Jenkins
import jenkins.scm.api.SCMRevisionAction
import jenkins.util.Timer
import org.jenkinsci.plugins.github_branch_source.Connector
import org.jenkinsci.plugins.github_branch_source.PullRequestSCMRevision
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject
import org.kohsuke.github.GHIssueState
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

def controller = Jenkins.get()
def approvalFile = new File(controller.rootDir, 'pilot-github-approved.json')
if (!approvalFile.isFile()) return
def approval = new JsonSlurper().parse(approvalFile)
if (approval.owner != 'JUANES545' || approval.repository != 'solventa-app-pf2') {
    throw new IllegalArgumentException('Only the approved repository is supported')
}
def policy = new GroovyClassLoader(getClass().classLoader).parseClass(new File('/opt/jenkins-pilot/comment-policy.groovy'))
def stateFile = new File(controller.rootDir, 'comment-trigger-state.json')
if (!stateFile.isFile()) {
    stateFile.text = JsonOutput.toJson([enabledAt: System.currentTimeMillis(), processed: []])
}
def logger = Logger.getLogger('solventa.comment-trigger')
// This trusted initializer runs once on restart, not inside a build workspace.
Timer.get().scheduleWithFixedDelay({
    def connection = null
    def systemContext = ACL.as2(ACL.SYSTEM2)
    try {
        def project = controller.getItem('solventa-android')
        if (!(project instanceof WorkflowMultiBranchProject)) return
        def state = new JsonSlurper().parse(stateFile)
        def processed = state.processed.collect { (it as Number).longValue() }
        def credential = Connector.lookupScanCredentials(project, 'https://api.github.com', approval.credentialId as String, approval.owner as String)
        if (credential == null) throw new IllegalStateException('The approved source credential is missing')
        connection = Connector.connect('https://api.github.com', credential)
        def repository = connection.getRepository('JUANES545/solventa-app-pf2')
        repository.getPullRequests(GHIssueState.OPEN).each { pull ->
            boolean origin = pull.head.repository?.fullName == 'JUANES545/solventa-app-pf2'
            if (!origin || !(pull.base.ref in ['develop', 'master'])) return
            def comments = new ArrayList(pull.listComments().toList()).sort { it.id }
            comments.each { comment ->
                long id = comment.id
                if (processed.contains(id) || !policy.isCommand(comment.body)) return
                def created = comment.createdAt.time
                def updated = comment.updatedAt.time
                if (created < (state.enabledAt as Number).longValue() || created != updated) return
                def user = comment.user
                def permission = repository.getPermission(user.login).toString()
                def info = [id: id, body: comment.body, createdAt: created, updatedAt: updated,
                            human: user.type == 'User', permission: permission,
                            open: true, origin: origin, target: pull.base.ref]
                if (!policy.eligible(info, (state.enabledAt as Number).longValue(), processed)) return
                def job = project.getItem('PR-' + pull.number)
                if (job == null) {
                    project.scheduleBuild2(0)
                    return
                }
                def branch = project.getProjectFactory().getBranch(job)
                def source = project.getSCMSource(branch.sourceId)
                def revision = source.fetch(branch.head, null)
                if (!(revision instanceof PullRequestSCMRevision) || revision.pullHash != pull.head.sha) return
                def request = job.scheduleBuild2(0,
                    new CauseAction(new Cause.RemoteCause('github-comment', "Requested by ${user.login} through PR comment ${id} for ${revision.pullHash}")),
                    new SCMRevisionAction(source, revision))
                if (request != null) {
                    processed.add(id)
                    state.processed = processed
                    stateFile.text = JsonOutput.toJson(state)
                    logger.info("Queued PR ${pull.number} from comment ${id} for ${revision.pullHash}")
                }
            }
        }
    } catch (Exception failure) {
        // Exception messages can contain sensitive request details; log the type only.
        logger.warning('Comment scan failed: ' + failure.class.name)
        logger.warning('Comment scan location: ' + failure.stackTrace.take(5).collect { frame -> frame.className + ':' + frame.lineNumber }.join(', '))
    } finally {
        if (connection != null) Connector.release(connection)
        systemContext.close()
    }
} as Runnable, 30, 60, TimeUnit.SECONDS)
