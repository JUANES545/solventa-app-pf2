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
                checkout scm
            }
        }
        stage('Pruebas unitarias y cobertura') {
            steps {
                sh './gradlew --no-daemon --max-workers=2 -Dorg.gradle.jvmargs="-Xmx1536m -Dfile.encoding=UTF-8" -Pkotlin.compiler.execution.strategy=in-process jacocoDebugUnitTestReport'
                sh 'test -s app/build/reports/coverage/unit/coverage.xml'
                sh '''
                    python3 - <<'PY'
import xml.etree.ElementTree as E
counter = E.parse('app/build/reports/coverage/unit/coverage.xml').getroot().find("counter[@type='LINE']")
assert counter is not None, 'Missing coverage counter'
covered, missed = int(counter.get('covered')), int(counter.get('missed'))
assert covered + missed > 0, 'No application lines measured'
print(f'Cobertura JVM de líneas: {100 * covered / (covered + missed):.2f}% ({covered}/{covered + missed})')
PY
                '''
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
                if (fileExists('app/build/test-results/testDebugUnitTest')) {
                    junit testResults: 'app/build/test-results/testDebugUnitTest/*.xml', allowEmptyResults: false
                }
            }
            archiveArtifacts artifacts: 'app/build/reports/**,app/build/test-results/**', allowEmptyArchive: true
            deleteDir()
        }
    }
}
