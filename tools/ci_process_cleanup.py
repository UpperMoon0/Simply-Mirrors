"""Reap only Java processes owned by this Jenkins branch's workspace/scratch.

Gradle --no-daemon forks one-use JVMs that can outlive the wrapper after a
timeout. Minecraft client/server JVMs may likewise survive the Gradle launcher.
Jenkins workspace deletion alone is not process cleanup.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import signal
import time

AGENT_ROOT = Path("/home/jenkins/agent")
PROC_ROOT = Path("/proc")


def scoped_paths(environment):
    """Fail closed outside the dedicated Linux Jenkins container."""
    if os.name != "posix":
        return None
    scratch_value = environment.get("CI_SCRATCH", "")
    workspace_value = environment.get("WORKSPACE", "")
    if not scratch_value or not workspace_value:
        return None
    scratch = Path(scratch_value).resolve()
    workspace = Path(workspace_value).resolve()
    if not (scratch.parent == AGENT_ROOT and scratch.name.startswith("mirrors-ci-")
            and workspace.parent == AGENT_ROOT / "workspace"
            and workspace.name and workspace.name not in (".", "..")):
        raise ValueError("Unsafe CI cleanup scope")
    return workspace, scratch


def proc_identity(entry):
    try:
        fields = (entry / "stat").read_text().rsplit(")", 1)[1].split()
        if fields[0] == "Z":
            return None
        return fields[19]  # Linux /proc stat field 22: process start ticks.
    except (OSError, IndexError, ValueError):
        return None


def owned_java_processes(workspace, scratch, proc_root=PROC_ROOT, uid=None):
    """Match exact checkout/scratch scopes; never the Jenkins remoting agent."""
    if uid is None:
        uid = os.geteuid()
    markers = [os.fsencode(str(root)) for root in (workspace, scratch)]
    found = {}
    for entry in proc_root.iterdir():
        if not entry.name.isdecimal():
            continue
        try:
            if entry.stat().st_uid != uid:
                continue
            argv = (entry / "cmdline").read_bytes()
            first = argv.split(b"\0", 1)[0].rsplit(b"/", 1)[-1].lower()
            if first not in (b"java", b"javaw") or b"/usr/share/jenkins/agent.jar" in argv:
                continue
            try:
                cwd = (entry / "cwd").resolve(strict=True)
            except OSError:
                cwd = None
            scoped_cwd = cwd is not None and any(cwd == root or root in cwd.parents
                                                  for root in (workspace, scratch))
            scoped_argv = any(root + sep in argv
                              for root in markers for sep in (b"/", b"\0", b":", b";"))
            if not scoped_cwd and not scoped_argv:
                continue
            identity = proc_identity(entry)
            if identity is not None:
                found[int(entry.name)] = identity
        except (OSError, ValueError, ProcessLookupError):
            continue
    return found


def cleanup_owned_processes(environment=None, proc_root=PROC_ROOT, dry_run=False):
    scope = scoped_paths(os.environ if environment is None else environment)
    if scope is None:
        return 0
    workspace, scratch = scope
    owned = owned_java_processes(workspace, scratch, proc_root)
    if dry_run:
        print(f"CI_OWNED_JAVA_DRY_RUN count={len(owned)} pids={sorted(owned)}", flush=True)
        return len(owned)
    for attempt, sig in enumerate((signal.SIGTERM, signal.SIGKILL)):
        for pid, started in owned.items():
            entry = proc_root / str(pid)
            if proc_identity(entry) != started:
                continue  # PID reused or already reaped.
            try:
                os.kill(pid, sig)
            except ProcessLookupError:
                pass
        time.sleep(3 if attempt == 0 else 1)
        owned = owned_java_processes(workspace, scratch, proc_root)
        if not owned:
            break
    if owned:
        raise RuntimeError(f"Owned CI Java processes survived termination: {sorted(owned)}")
    print("CI_OWNED_JAVA_CLEANUP complete", flush=True)
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    cleanup_owned_processes(dry_run=args.dry_run)


if __name__ == "__main__":
    main()
