def jenkinsfile = new File(args[0])
def runPost = { Map scenario ->
    def result = [checks: [], archived: false, build: [currentResult: scenario.buildResult ?: 'SUCCESS']]
    def binding = new Binding([
        pipeline: { body -> body() },
        agent: { body -> },
        options: { body -> },
        environment: { body -> },
        stages: { body -> },
        post: { body -> body() },
        always: { body -> body() },
        script: { body -> body() },
        cleanup: { body -> },
        env: [CHANGE_ID: '55', BUILD_URL: 'https://jenkins.example/job/55/1/'],
        currentBuild: result.build,
        fileExists: { path -> scenario.junitDirectory != false },
        junit: { options ->
            assert options.allowEmptyResults == false
            assert options.skipPublishingChecks == true
            if (scenario.junitError) throw scenario.junitError
        },
        sh: { options ->
            if (options.returnStdout) return 'approved-revision\n'
            if (scenario.summaryError) throw scenario.summaryError
            return scenario.summaryExit ?: 0
        },
        readFile: { options ->
            if (scenario.readError) throw scenario.readError
            return scenario.summaryExit ? 'Cobertura incompleta\nReporte parcial\n' : 'Cobertura JVM\nInforme real\n'
        },
        publishChecks: { options ->
            result.checks << options
            if (scenario.publishError) throw scenario.publishError
        },
        error: { message -> throw new IllegalStateException(message) },
        archiveArtifacts: { options -> result.archived = true }
    ])
    try {
        new GroovyShell(binding).evaluate(jenkinsfile)
    } catch (Exception failure) {
        result.failure = failure
    }
    assert result.archived
    assert result.checks.size() == 1
    assert result.checks[0].name == 'Cobertura'
    assert result.checks[0].status == 'COMPLETED'
    result
}

for (buildResult in ['SUCCESS', 'FAILURE']) {
    def failure = new IllegalStateException('No test report files were found')
    def result = runPost([buildResult: buildResult, junitError: failure])
    assert result.failure.is(failure)
    assert result.build.currentResult == buildResult
    assert result.checks[0].conclusion == 'FAILURE'
    assert result.checks[0].title == 'Cobertura incompleta'
    assert !result.checks[0].summary.contains('%')
    assert result.checks[0].summary.contains('console')
}

for (step in ['summaryError', 'readError']) {
    def failure = new IllegalStateException("Cannot process ${step}")
    def result = runPost([(step): failure])
    assert result.failure.is(failure)
    assert result.checks[0].conclusion == 'FAILURE'
}

def success = runPost([:])
assert !success.failure
assert success.checks[0].conclusion == 'SUCCESS'
assert success.checks[0].summary.contains('Informe real')
assert success.checks[0].summary.contains('approved-revision')

def missing = runPost([junitDirectory: false, summaryExit: 1])
assert missing.failure.message == 'Faltan reportes válidos de cobertura o pruebas'
assert missing.checks[0].conclusion == 'FAILURE'

def failedBuild = runPost([buildResult: 'FAILURE', junitDirectory: false, summaryExit: 1])
assert !failedBuild.failure
assert failedBuild.build.currentResult == 'FAILURE'
assert failedBuild.checks[0].conclusion == 'NEUTRAL'
assert failedBuild.checks[0].summary.contains('Reporte parcial')

def publishingError = new IllegalStateException('Cannot publish check')
assert runPost([publishError: publishingError]).failure.is(publishingError)

def commands = []
new GroovyShell(new Binding([
    pipeline: { body -> body() },
    agent: { body -> },
    options: { body -> },
    environment: { body -> },
    stages: { body -> body() },
    stage: { name, body -> if (name == 'Pruebas unitarias y cobertura') body() },
    steps: { body -> body() },
    post: { body -> },
    env: [CHANGE_ID: '55'],
    publishChecks: { options -> },
    sh: { command -> commands << command }
])).evaluate(jenkinsfile)
def gradleCommands = commands.findAll { it.startsWith('./gradlew ') }
assert gradleCommands.size() == 2
assert gradleCommands[0].endsWith(' compileDebugUnitTestSources')
assert gradleCommands[1].endsWith(' jacocoDebugUnitTestReport')
for (command in gradleCommands) {
    assert command.contains(' --no-daemon ')
    assert command.contains(' -Pkotlin.compiler.execution.strategy=in-process ')
}
println 'Coverage post tests passed (8 scenarios); isolated compilation command verified.'
