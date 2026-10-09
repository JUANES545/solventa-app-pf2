import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import hudson.model.Item
import hudson.model.User
import hudson.security.GlobalMatrixAuthorizationStrategy
import jenkins.model.Jenkins
import org.jenkinsci.plugins.matrixauth.PermissionEntry

def controller = Jenkins.get()
def approval = new File(controller.rootDir, 'pilot-security-approved.json')
def marker = new File(controller.rootDir, 'pilot-security-applied.json')
if (approval.isFile() && !marker.isFile()) {
    def config = new JsonSlurper().parse(approval)
    def administrator = config.administrator as String
    def service = config.serviceUser as String
    if (!administrator || !service || administrator == service) {
        throw new IllegalArgumentException('Distinct approved administrator and service usernames are required')
    }
    def owner = User.getById(administrator, false)
    if (owner == null) {
        throw new IllegalStateException('The approved administrator does not exist; authorization was not changed')
    }
    if (controller.authorizationStrategy.class.name != 'hudson.security.FullControlOnceLoggedInAuthorizationStrategy') {
        throw new IllegalStateException('Authorization is already customized; inspect before changing it')
    }
    def matrix = new GlobalMatrixAuthorizationStrategy()
    matrix.add(Jenkins.ADMINISTER, PermissionEntry.user(administrator))
    matrix.add(Jenkins.READ, PermissionEntry.user(service))
    matrix.add(Item.READ, PermissionEntry.user(service))
    if (!matrix.rootACL.hasPermission2(owner.impersonate2(), Jenkins.ADMINISTER)) {
        throw new IllegalStateException('Administrator access could not be verified; authorization was not changed')
    }
    controller.setAuthorizationStrategy(matrix)
    controller.save()
    marker.text = JsonOutput.toJson([
        administrator: administrator,
        serviceUser: service,
        servicePermissions: ['Overall/Read', 'Job/Read'],
        securityRealmChanged: false,
        serviceAccountCreated: false,
    ])
}
