// Any trusted Linux Minecraft worker may execute a target independently.
// An agent with the 'linux-minecraft-ci' label must provide the pinned JDKs,
// Xvfb/software GL, Python, and a 6 GiB / single-heavy-job resource envelope.
def targets() {
    return ['fabric-1.16.5', 'forge-1.16.5', 'fabric-1.20.1', 'forge-1.20.1',
            'fabric-1.21.1', 'neoforge-1.21.1', 'fabric-26.1.2', 'neoforge-26.1.2']
}

def withMirrorWorker(String target, Closure work) {
    // Every parallel branch gets its own Jenkins workspace and disposable JVM
    // scratch. Gradle's dependency cache, unlike the build outputs, is local
    // to the worker and persisted across PRs and container restarts.
    checkout scm
    def scratch = sh(script: 'mktemp -d /home/jenkins/agent/mirrors-ci-XXXXXX', returnStdout: true).trim()
    def jdk = target.endsWith('26.1.2') ? env.JAVA_HOME_25 : env.JAVA_HOME_21
    withEnv(['CI_SCRATCH=' + scratch, 'TMPDIR=' + scratch,
             'GRADLE_USER_HOME=/home/jenkins/.gradle',
             'PIP_CACHE_DIR=/home/jenkins/agent/caches/pip',
             'JAVA_HOME=' + jdk, 'PATH+JDK=' + jdk + '/bin',
             'PATH+VENV=' + scratch + '/venv/bin',
             'MIRRORS_TARGET=' + target,
             'GRADLE_OPTS=-Dorg.gradle.workers.max=1 -Dorg.gradle.jvmargs=-Xmx1536m']) {
        try {
            sh '''#!/usr/bin/env bash
set -euo pipefail
mkdir -p "$GRADLE_USER_HOME" "$PIP_CACHE_DIR"
# Gradle's shared user-home cache is lock-safe on ONE filesystem. Do not
# mount this directory over NFS or share it between physically different nodes.
(
  flock -x 9
  props="$GRADLE_USER_HOME/gradle.properties"
  if ! grep -q '^org.gradle.java.installations.paths=' "$props" 2>/dev/null; then
    printf 'org.gradle.java.installations.paths=%s,%s,%s,%s\\norg.gradle.java.installations.auto-download=false\\n' \
      "$JAVA_HOME_8" "$JAVA_HOME_17" "$JAVA_HOME_21" "$JAVA_HOME_25" >> "$props"
  fi
) 9>"$GRADLE_USER_HOME/.mirrors-toolchain.lock"
python3 -m venv "$CI_SCRATCH/venv"
python -m pip install 'pillow==12.0.0'
command -v Xvfb
command -v xvfb-run
test -f /usr/lib/x86_64-linux-gnu/libGLX_mesa.so.0
chmod +x gradlew forge-1.16.5/gradlew fabric-26.1.2/gradlew neoforge-26.1.2/gradlew
'''
            work()
        } finally {
            // No release or caches are deleted when a worker is interrupted.
            // Dispose only JVMs rooted in this exact Jenkins workspace/scratch.
            try {
                sh 'python3 tools/ci_process_cleanup.py'
            } finally {
                archiveArtifacts artifacts: '**/run/*-evidence/**,**/run/*-client/visual-evidence/**,**/run/*-client/logs/**,**/run/*-client/crash-reports/**,common/build/reports/**,common/build/test-results/**,**/build/libs/simply_mirrors-*.jar', allowEmptyArchive: true
                sh '''#!/usr/bin/env python3
import os, pathlib, shutil
path = pathlib.Path(os.environ['CI_SCRATCH']).resolve()
if path.parent != pathlib.Path('/home/jenkins/agent') or not path.name.startswith('mirrors-ci-'):
    raise ValueError('Unexpected scratch directory')
shutil.rmtree(path)
'''
                cleanWs(deleteDirs: true, disableDeferredWipeout: true)
            }
        }
    }
}

pipeline {
    agent none
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
        stage('Fast contracts and OpenGL parity') {
            steps {
                script {
                    if (env.CHANGE_ID || !env.BRANCH_NAME) {
                        error('Unverified Jenkins SCM head')
                    }
                    node('linux-minecraft-ci') {
                        stage('Shared contracts and GL') {
                            withMirrorWorker('common') {
                                sh "python -m unittest discover -s tools -p 'test_*.py'"
                                sh './gradlew -Ptarget=common :common:test --no-daemon --max-workers=1 --console=plain'
                                junit testResults: 'common/build/test-results/test/*.xml', allowEmptyResults: false
                                sh 'xvfb-run -a ./gradlew -Ptarget=common :common:shaderHistoryGlTest :common:mirrorCompositeBenchmark --no-daemon --max-workers=1 --console=plain'
                            }
                        }
                    }
                }
            }
        }
        stage('Parallel Minecraft target acceptance') {
            steps {
                script {
                    def jobs = [:]
                    for (String name : targets()) {
                        def target = name  // CPS-safe capture for parallel closures
                        jobs[target] = {
                            node('linux-minecraft-ci') {
                                // A stage failure marks this build failed without
                                // hiding the outcome of other independent targets.
                                catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                                    withMirrorWorker(target) {
                                        stage('Build ' + target) {
                                            sh 'python tools/build.py --target "$MIRRORS_TARGET" --skip-tests'
                                            if (env.BRANCH_NAME == 'main') {
                                                stash name: 'release-' + target,
                                                    includes: target + '/build/libs/simply_mirrors-*.jar',
                                                    allowEmpty: false
                                            }
                                        }
                                        stage('Visual ' + target) {
                                            timeout(time: 40, unit: 'MINUTES') {
                                                sh '''xvfb-run -a -s '-screen 0 1280x720x24' python tools/visual_smoke.py --target "$MIRRORS_TARGET"'''
                                            }
                                        }
                                        stage('Shaders ' + target) {
                                            timeout(time: 45, unit: 'MINUTES') {
                                                sh '''xvfb-run -a -s '-screen 0 1280x720x24' python tools/visual_smoke.py --target "$MIRRORS_TARGET" --shaders'''
                                            }
                                        }
                                        if (target == 'neoforge-1.21.1') {
                                            stage('Enclosed, motion and Euphoria regressions') {
                                                timeout(time: 120, unit: 'MINUTES') {
                                                    sh 'python tools/jenkins_shader_regressions.py'
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // One work queue per target, not one executor for the entire
                    // pipeline. Jenkins distributes to all eligible Linux nodes.
                    // abortPrevious cancels pending branches for obsolete commits.
                    parallel jobs
                }
            }
        }
        stage('Verify eight-target release bundle') {
            when { branch 'main' }
            steps {
                script {
                    node('linux-minecraft-ci') {
                        try {
                            checkout scm
                            for (String target : targets()) {
                                unstash 'release-' + target
                            }
                            sh 'python tools/jenkins_collect.py'
                            archiveArtifacts artifacts: 'release-output/*', fingerprint: true
                        } finally {
                            cleanWs(deleteDirs: true, disableDeferredWipeout: true)
                        }
                    }
                }
            }
        }
    }
}
