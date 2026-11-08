"""Preserve the existing three Complementary regression gates under Jenkins."""
import hashlib
from pathlib import Path
from ci_process_cleanup import cleanup_owned_processes
import subprocess
import sys
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def main():
    cases = [
        ['--run-name', 'enclosed-baseline', '--require-entity', '--force-stencil', '--check-motion'],
        ['--run-name', 'camera-motion', '--check-camera-motion'],
        ['--run-name', 'euphoria-compat', '--require-entity', '--force-stencil', '--check-motion'],
    ]
    extra = ROOT / 'build/shader-modifier-repro/EuphoriaPatcher-1.10.5-r5.9.3-neoforge.jar'
    extra.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(
        'https://cdn.modrinth.com/data/4H6sumDB/versions/QMsRXtSJ/EuphoriaPatcher-1.10.5-r5.9.3-neoforge.jar',
        headers={'User-Agent': 'SimplyMirrors-Jenkins/1.0'})
    with urllib.request.urlopen(request, timeout=120) as response:
        content = response.read()
    expected = '19e5528f6284f68d28e9e8b17a9531bb0f8e0ab862e3ab93a1b53fe3f18765cfa38cc512e66c2d4b308e6783307d132e8b5c337db4a39b016d75e6830376070a'
    if hashlib.sha512(content).hexdigest() != expected:
        raise ValueError('Euphoria Patcher checksum mismatch')
    extra.write_bytes(content)
    cases[-1] += ['--extra-client-mod', str(extra)]
    failures = []
    for case in cases:
        command = ['xvfb-run', '-a', '-s', '-screen 0 1280x720x24', sys.executable,
                   'tools/visual_smoke.py', '--target', 'neoforge-1.21.1', '--shaders', '--enclosed'] + case
        try:
            result = subprocess.run(command, cwd=ROOT, check=False)
        finally:
            # Each scene may fork detached Gradle/game JVMs; reclaim before the next.
            cleanup_owned_processes()
        if result.returncode:
            failures.append(case[1])
    # This independent API contract must still run after visual failures.
    result = subprocess.run([sys.executable, 'tools/verify_iris_framebuffer_api.py',
                             '--cache', 'build/shader-dependencies'], cwd=ROOT, check=False)
    if result.returncode:
        failures.append('iris-framebuffer-api')
    if failures:
        raise RuntimeError('Shader acceptance failed: ' + ', '.join(failures))


if __name__ == '__main__':
    main()
