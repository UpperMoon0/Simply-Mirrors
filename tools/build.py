"""Build the checked-in target matrix; no publishing or implicit dependency changes."""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
TARGETS = json.loads((ROOT / "tools/targets.json").read_text())

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", choices=["all"] + [t["target"] for t in TARGETS], default="all")
    parser.add_argument("--skip-tests", action="store_true", help="Skip shared tests (CI runs these in a separate job)")
    args = parser.parse_args()
    wrapper = "gradlew.bat" if os.name == "nt" else "./gradlew"
    if not args.skip_tests:
        subprocess.run([wrapper if os.name != "nt" else str(ROOT / wrapper), "-Ptarget=common", ":common:test", "--no-daemon", "--console=plain"], cwd=ROOT, check=True)
    selected = TARGETS if args.target == "all" else [t for t in TARGETS if t["target"] == args.target]
    for target in selected:
        name = target["target"]
        cwd = ROOT / target["build_root"]
        command = [str(cwd / "gradlew.bat")] if os.name == "nt" else ["./gradlew"]
        if target["build_root"] == ".":
            command.extend([f"-Ptarget={name}", f":{name}:build"])
        else:
            command.append("build")
        command.extend(["--no-daemon", "--console=plain"])
        print(f"Building {name}", flush=True)
        subprocess.run(command, cwd=cwd, check=True)
        subprocess.run([sys.executable, str(ROOT / "tools/verify_artifact.py"), name], cwd=ROOT, check=True)

if __name__ == "__main__":
    main()
