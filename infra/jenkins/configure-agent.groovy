import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.SystemCredentialsProvider
import com.cloudbees.plugins.credentials.domains.Domain
import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey
import groovy.json.JsonSlurper
import hudson.model.Node
import hudson.plugins.sshslaves.SSHLauncher
import hudson.plugins.sshslaves.verifiers.KnownHostsFileKeyVerificationStrategy
import hudson.slaves.DumbSlave
import jenkins.model.Jenkins

def controller = Jenkins.get()
def configFile = new File(controller.rootDir, 'pilot-agent.json')
if (configFile.isFile()) {
    def config = new JsonSlurper().parse(configFile)
    def credentialId = 'solventa-android-agent-ssh'
    def store = SystemCredentialsProvider.getInstance().getStore()
    def domain = Domain.global()
    if (!store.getCredentials(domain).any { it.id == credentialId }) {
        def privateKey = new File(controller.rootDir, '.ssh/id_ed25519').text
        def source = new BasicSSHUserPrivateKey.DirectEntryPrivateKeySource(privateKey)
        def credential = new BasicSSHUserPrivateKey(
            CredentialsScope.SYSTEM,
            credentialId,
            'jenkins-agent',
            source,
            '',
            'Private connection to the fixed Android pilot agent',
        )
        store.addCredentials(domain, credential)
    }
    if (controller.getNode('android-aws') == null) {
        def launcher = new SSHLauncher(config.privateIp as String, 22, credentialId)
        launcher.setJavaPath('/usr/lib/jvm/java-21-openjdk-amd64/bin/java')
        launcher.setJvmOptions('-Xms64m -Xmx256m')
        launcher.setSshHostKeyVerificationStrategy(new KnownHostsFileKeyVerificationStrategy())
        def node = new DumbSlave('android-aws', '/var/lib/jenkins-agent', launcher)
        node.setNodeDescription('Fixed AWS Android agent for the seven-day pilot')
        node.setNumExecutors(1)
        node.setMode(Node.Mode.EXCLUSIVE)
        node.setLabelString('android-aws')
        controller.addNode(node)
    }
    controller.setNumExecutors(0)
    controller.save()
}
