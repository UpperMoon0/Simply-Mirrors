def targets() {
    return ['fabric-1.16.5', 'forge-1.16.5', 'fabric-1.20.1', 'forge-1.20.1',
            'fabric-1.21.1', 'neoforge-1.21.1', 'fabric-26.1.2', 'neoforge-26.1.2']
}

def targetCommand(String target, String command) {
    def home = target.endsWith('26.1.2') ? env.JAVA_HOME_25 : env.JAVA_HOME_21
    withEnv(["JAVA_HOME=${home}", "PATH+JDK=${home}/bin", "MIRRORS_TARGET=${target}"]) {
        try {
            sh command
        } finally {
            // Gradle --no-daemon can leave detached server/client JVMs behind.
            sh 'python tools/ci_process_cleanup.py'
        }
    }
}

pipeline {
    agent { label 'linux-minecraft-ci' }
    options {
        skipDefaultCheckout(true)
        disableConcurrentBuilds(abortPrevious: true)
        timestamps()
        timeout(time: 12, unit: 'HOURS')
        buildDiscarder(logRotator(numToKeepStr: '8', daysToKeepStr: '14'))
    }
    environment {
        LIBGL_ALWAYS_SOFTWARE = 'true'
        ALSOFT_DRIVERS = 'null'
        PIP_DISABLE_PIP_VERSION_CHECK = '1'
    }
    stages {
        stage('Trusted source and runtime') {
            steps {
                script {
                    // Host registry discovers only reviewed main or verified same-repository
                    // PR HEAD branches; fork PRs and merge refs are never admitted.
                    if (env.CHANGE_ID || !env.BRANCH_NAME) { error('Unverified Jenkins SCM head') }
                    checkout scm
                    env.CI_SCRATCH = sh(returnStdout: true, script: 'mktemp -d /home/jenkins/agent/mirrors-ci-XXXXXX').trim()
                    env.TMPDIR = env.CI_SCRATCH
                    // Do not share derived loader artifacts between different repositories/versions.
                    env.GRADLE_USER_HOME = "${env.CI_SCRATCH}/gradle"
                    env.PATH = "${env.CI_SCRATCH}/venv/bin:${env.JAVA_HOME_21}/bin:${env.PATH}"
                    env.JAVA_HOME = env.JAVA_HOME_21
                    env.GRADLE_OPTS = "-Dorg.gradle.workers.max=1 -Dorg.gradle.jvmargs=-Xmx1536m"
                }
                sh '''#!/usr/bin/env bash
set -euo pipefail
mkdir -p "$GRADLE_USER_HOME"
printf 'org.gradle.java.installations.paths=%s,%s,%s,%s\\norg.gradle.java.installations.auto-download=false\\n' "$JAVA_HOME_8" "$JAVA_HOME_17" "$JAVA_HOME_21" "$JAVA_HOME_25" > "$GRADLE_USER_HOME/gradle.properties"
python3 -m venv "$CI_SCRATCH/venv"
python -m pip install --no-cache-dir 'pillow==12.0.0'
command -v Xvfb
command -v xvfb-run
test -f /usr/lib/x86_64-linux-gnu/libGLX_mesa.so.0
"$JAVA_HOME_8/bin/java" -version
chmod +x gradlew forge-1.16.5/gradlew fabric-26.1.2/gradlew neoforge-26.1.2/gradlew
'''
            }
        }
        stage('Contracts and reflection math') {
            steps {
                sh "python -m unittest discover -s tools -p 'test_*.py'"
                sh './gradlew -Ptarget=common :common:test --no-daemon --max-workers=1 --console=plain'
            }
            post { always { sh 'python tools/ci_process_cleanup.py'; junit testResults: 'common/build/test-results/test/*.xml', allowEmptyResults: true } }
        }
        stage('Native OpenGL state and pixel parity') {
            steps {
                sh 'xvfb-run -a ./gradlew -Ptarget=common :common:shaderHistoryGlTest :common:mirrorCompositeBenchmark --no-daemon --max-workers=1 --console=plain'
            }
            post { always { sh 'python tools/ci_process_cleanup.py' } }
        }
        stage('Eight production targets') {
            steps {
                script {
                    for (target in targets()) {
                        stage("Build ${target}") {
                            catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                                targetCommand(target, 'python tools/build.py --target "$MIRRORS_TARGET" --skip-tests')
                            }
                        }
                    }
                }
            }
        }
        stage('Verify eight-target release bundle') {
            when { branch 'main' }
            steps {
                sh 'python tools/jenkins_collect.py'
                archiveArtifacts artifacts: 'release-output/*', fingerprint: true
            }
        }
        stage('Minecraft six-face visual acceptance') {
            steps {
                script {
                    for (target in targets()) {
                        stage("Visual ${target}") {
                            catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                                timeout(time: 40, unit: 'MINUTES') {
                                    targetCommand(target, "xvfb-run -a -s '-screen 0 1280x720x24' python tools/visual_smoke.py --target \"\$MIRRORS_TARGET\"")
                                }
                            }
                        }
                    }
                }
            }
        }
        stage('Complementary eight-target acceptance') {
            steps {
                script {
                    for (target in targets()) {
                        stage("Shaders ${target}") {
                            catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                                timeout(time: 45, unit: 'MINUTES') {
                                    targetCommand(target, "xvfb-run -a -s '-screen 0 1280x720x24' python tools/visual_smoke.py --target \"\$MIRRORS_TARGET\" --shaders")
                                }
                            }
                        }
                    }
                }
            }
        }
        stage('Enclosed, motion and shader modifier regressions') {
            steps {
                sh 'python tools/jenkins_shader_regressions.py'
            }
            post { always { sh 'python tools/ci_process_cleanup.py' } }
        }
    }
    post {
        always {
            archiveArtifacts artifacts: '**/run/*-evidence/**,**/run/*-client/visual-evidence/**,**/run/*-client/logs/**,**/run/*-client/crash-reports/**,common/build/reports/**,common/build/test-results/**,release-output/*', allowEmptyArchive: true
            script {
                if (env.CI_SCRATCH) {
                    sh 'python tools/ci_process_cleanup.py'
                    sh '''python3 - <<'PY'
import os, pathlib, shutil
path=pathlib.Path(os.environ['CI_SCRATCH']).resolve()
if path.parent != pathlib.Path('/home/jenkins/agent') or not path.name.startswith('mirrors-ci-'):
    raise ValueError('Unexpected scratch directory')
shutil.rmtree(path)
PY'''
                }
            }
            cleanWs(deleteDirs: true, disableDeferredWipeout: true)
        }
    }
}
