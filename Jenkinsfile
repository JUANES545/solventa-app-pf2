pipeline {
    agent { label 'android-aws' }

    options {
        disableConcurrentBuilds()
        timeout(time: 30, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '5'))
        skipDefaultCheckout(true)
        timestamps()
    }

    environment {
        JAVA_HOME = '/usr/lib/jvm/java-17-openjdk-amd64'
        ANDROID_HOME = '/opt/android-sdk'
        ANDROID_SDK_ROOT = '/opt/android-sdk'
    }

    stages {
        stage('Código') {
            steps {
                deleteDir()
                checkout scm
            }
        }
        stage('Pruebas unitarias y cobertura') {
            steps {
                publishChecks name: 'Cobertura', status: 'IN_PROGRESS', conclusion: 'NONE', title: 'Analizando cobertura JVM', summary: 'Objetivo recomendado: 80 % en líneas y ramas. Medición de pruebas JVM.', detailsURL: "https://github.com/JUANES545/solventa-app-pf2/pull/${env.CHANGE_ID}/checks"
                sh "python3 -m unittest discover -s scripts -p 'test_*summary.py'"
                sh './gradlew --no-daemon --max-workers=2 -Dorg.gradle.jvmargs="-Xmx1536m -Dfile.encoding=UTF-8" -Pkotlin.compiler.execution.strategy=in-process jacocoDebugUnitTestReport'
                sh 'test -s app/build/reports/coverage/unit/coverage.xml'
            }
        }
        stage('Android Lint') {
            steps {
                sh './gradlew --no-daemon --max-workers=2 -Dorg.gradle.jvmargs="-Xmx1536m -Dfile.encoding=UTF-8" -Pkotlin.compiler.execution.strategy=in-process lintDebug'
            }
        }
        stage('Compilación debug') {
            steps {
                sh './gradlew --no-daemon --max-workers=2 -Dorg.gradle.jvmargs="-Xmx1536m -Dfile.encoding=UTF-8" -Pkotlin.compiler.execution.strategy=in-process assembleDebug'
            }
        }
    }

    post {
        always {
            script {
                try {
                    if (fileExists('app/build/test-results/testDebugUnitTest')) {
                        junit testResults: 'app/build/test-results/testDebugUnitTest/*.xml', allowEmptyResults: false, skipPublishingChecks: true
                    }
                    def result = sh(returnStatus: true, script: '''python3 scripts/coverage_summary.py \
                        --coverage 'App=app/build/reports/coverage/unit/coverage.xml' \
                        --tests 'app/build/test-results/testDebugUnitTest/*.xml' \
                        --scope 'Pruebas JVM. No incluye instrumentación, UI en dispositivo ni dependencias externas.'
                    ''')
                    def summary = readFile(file: 'reports/coverage-summary.md', encoding: 'UTF-8')
                    def revision = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
                    summary += "\n**Revisión compilada:** [`${revision}`](https://github.com/JUANES545/solventa-app-pf2/commit/${revision}).\n"
                    summary += "\n[Informe HTML de cobertura](${env.BUILD_URL}artifact/app/build/reports/coverage/unit/html/index.html) · [Pruebas JUnit](${env.BUILD_URL}testReport/)\n"
                    publishChecks name: 'Cobertura', title: summary.readLines()[0], summary: summary,
                        detailsURL: "https://github.com/JUANES545/solventa-app-pf2/pull/${env.CHANGE_ID}/checks",
                        conclusion: result == 0 ? 'SUCCESS' : (currentBuild.currentResult == 'SUCCESS' ? 'FAILURE' : 'NEUTRAL')
                    if (result != 0 && currentBuild.currentResult == 'SUCCESS') {
                        error('Faltan reportes válidos de cobertura o pruebas')
                    }
                } finally {
                    archiveArtifacts artifacts: 'app/build/reports/**,app/build/test-results/**,reports/coverage-summary.md', allowEmptyArchive: true
                }
            }
        }
        cleanup {
            deleteDir()
        }
    }
}
