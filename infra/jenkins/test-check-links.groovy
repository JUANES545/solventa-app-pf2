import io.jenkins.plugins.checks.api.ChecksConclusion
import io.jenkins.plugins.checks.api.ChecksDetails
import io.jenkins.plugins.checks.api.ChecksOutput
import io.jenkins.plugins.checks.api.ChecksPublisher
import io.jenkins.plugins.checks.api.ChecksStatus
import java.time.LocalDateTime

// Compile with the trusted controller's plugin classloader; do not publish any check.
def loader = new GroovyClassLoader(getClass().classLoader)
loader.parseClass(new File(args[0]))
def factory = loader.loadClass('SolventaGitHubEvidenceFactory')
def publisher = loader.loadClass('SolventaGitHubEvidencePublisher')
factory.REPOSITORIES.each { project, repository ->
    assert factory.evidenceUrl(project, repository, 40) ==
        "https://github.com/${repository}/pull/40/checks"
}
assert factory.evidenceUrl('unrelated', 'other/repository', 40) == null
for (invalid in [['solventa-android', 'other/repository', 40],
                 ['solventa-android', 'JUANES545/solventa-app-pf2', 0]]) {
    try {
        factory.evidenceUrl(*invalid)
        throw new AssertionError('Invalid evidence scope was accepted')
    } catch (IllegalStateException expected) {
        assert expected.message.contains('Unexpected evidence')
    }
}

def published = []
def recorder = [publish: { ChecksDetails details -> published.add(details) }] as ChecksPublisher
def url = factory.evidenceUrl('solventa-android', 'JUANES545/solventa-app-pf2', 40)
def wrapper = publisher.newInstance(recorder, url)
def start = LocalDateTime.of(2026, 10, 24, 10, 0)
def end = start.plusMinutes(3)
def output = new ChecksOutput.ChecksOutputBuilder()
    .withTitle('Existing result').withSummary('37 tests; 85.04% lines').build()
for (conclusion in ChecksConclusion.values()) {
    def original = new ChecksDetails.ChecksDetailsBuilder()
        .withName('CI / Validación (Jenkins)')
        .withStatus(ChecksStatus.COMPLETED).withConclusion(conclusion)
        .withDetailsURL('http://localhost:8080/job/private/1/')
        .withActions([new ChecksAction('Inspect', 'Existing action', 'inspect')])
        .withStartedAt(start).withCompletedAt(end).withOutput(output).build()
    wrapper.publish(original)
    def actual = published.last()
    assert actual.detailsURL.get() == url
    assert actual.name == original.name
    assert actual.status == original.status
    assert actual.conclusion == original.conclusion
    assert actual.startedAt == original.startedAt
    assert actual.completedAt == original.completedAt
    assert actual.output.get().title == original.output.get().title
    assert actual.output.get().summary == original.output.get().summary
    assert actual.actions == original.actions
    assert original.detailsURL.get().startsWith('http://localhost:')
}
for (status in [ChecksStatus.QUEUED, ChecksStatus.IN_PROGRESS]) {
    def original = new ChecksDetails.ChecksDetailsBuilder()
        .withName('CI / Validación (Jenkins)').withStatus(status).build()
    wrapper.publish(original)
    assert published.last().detailsURL.get() == url
    assert published.last().status == status
    assert !published.last().output.present
    assert !published.last().startedAt.present
    assert !published.last().completedAt.present
}
def coverage = new ChecksDetails.ChecksDetailsBuilder()
    .withName('Cobertura').withDetailsURL('https://github.com/example/repo/pull/1/checks').build()
wrapper.publish(coverage)
assert published.last().is(coverage)
println('GitHub evidence URL scope and result-preservation tests passed; no checks published.')
import io.jenkins.plugins.checks.api.ChecksAction
