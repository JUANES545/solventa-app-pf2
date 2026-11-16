import org.codehaus.groovy.ast.builder.AstBuilder
import org.codehaus.groovy.control.CompilePhase

def source = new File(args[0]).getText('UTF-8')
new AstBuilder().buildFromString(CompilePhase.CONVERSION, false, source)
def match = source =~ /(?s)def job = project\.items\.find (\{.*?\n        \})/
assert match.find(): 'The activation job selector was not found'
def expression = match.group(1)

// Execute the source selector with stand-ins, without loading or configuring Jenkins.
def loader = new GroovyClassLoader()
def prType = loader.parseClass('class PullRequestSCMHead {}')
def branch = [fullName: 'android/develop', lastBuild: new Object(), head: new Object()]
def pr = [fullName: 'android/PR-40', lastBuild: new Object(), head: prType.newInstance()]
def unbuilt = [fullName: 'android/PR-41', lastBuild: null, head: prType.newInstance()]
def scenarios = [
    [jobs: [branch, pr], expected: pr],
    [jobs: [unbuilt, branch, pr], expected: pr],
    [jobs: [branch], expected: null],
    [jobs: [unbuilt, branch], expected: null],
    [jobs: [], expected: null],
]
for (scenario in scenarios) {
    def project = new Expando(items: scenario.jobs,
        projectFactory: new Expando(getBranch: { job -> [head: job.head] }))
    def selector = new GroovyShell(loader, new Binding(project: project)).evaluate(expression)
    def selected = scenario.jobs.find(selector)
    assert selected.is(scenario.expected)
}
println 'Built-PR route selection passed (5 scenarios); no Jenkins controller or plugins loaded.'
