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
        stage('Checkout') {
            steps {
                checkout scm
            }
        }
        stage('Unit tests') {
            steps {
                sh './gradlew --no-daemon --max-workers=2 -Dorg.gradle.jvmargs="-Xmx1536m -Dfile.encoding=UTF-8" -Pkotlin.compiler.execution.strategy=in-process testDebugUnitTest'
            }
        }
        stage('Android Lint') {
            steps {
                sh './gradlew --no-daemon --max-workers=2 -Dorg.gradle.jvmargs="-Xmx1536m -Dfile.encoding=UTF-8" -Pkotlin.compiler.execution.strategy=in-process lintDebug'
            }
        }
        stage('Build debug app') {
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
