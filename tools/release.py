"""Collect verified release artifacts for the centralized NsTut-CICD publisher.

API reference: https://support.curseforge.com/support/solutions/articles/9000197321
This repository never performs release uploads.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
from verify_artifact import ROOT, TARGETS, verify


def version(text=None):
    text = (ROOT / "gradle.properties").read_text() if text is None else text
    values = re.findall(r"^mod_version\s*=\s*([^\r\n]+)", text, re.M)
    if len(values) != 1 or not re.fullmatch(r"\d+\.\d+\.\d+(?:-[A-Za-z0-9][A-Za-z0-9.-]*)?", values[0].strip()):
        raise ValueError("Expected one valid mod_version")
    return values[0].strip()

def release_type(v):
    return "alpha" if "-dev" in v or "-alpha" in v else "beta" if "-" in v else "release"

def expected_name(target, v):
    return f"simply_mirrors-{target}-{v}.jar"

def collect(source, output):
    v = version()
    if not (ROOT / "changelog" / f"{v}.md").is_file():
        raise ValueError(f"Missing changelog/{v}.md")
    jars = list(source.rglob("*.jar"))
    expected = {expected_name(t, v) for t in TARGETS}
    if len(jars) != len(expected) or {p.name for p in jars} != expected:
        raise ValueError("Release requires exactly one production jar for each of the eight targets")
    # Verify everything before copying any publishable output.
    for target, info in TARGETS.items():
        path = next(p for p in jars if p.name == expected_name(target, v))
        verify(path, info)
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ValueError("Release output must be empty to exclude stale files")
    for jar in sorted(jars):
        shutil.copyfile(jar, output / jar.name)
    sums = [f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}" for p in sorted(output.glob("*.jar"))]
    (output / "SHA256SUMS").write_text("\n".join(sums) + "\n")
    print(f"Verified and collected all {len(jars)} jars for {v}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["collect", "plan", "preflight", "publish"])
    parser.add_argument("--input", type=Path, default=Path("release-input"))
    parser.add_argument("--output", type=Path, default=Path("release-output"))
    parser.add_argument("--resume", action="store_true", help="Skip uploads recorded in inspected receipts")
    args = parser.parse_args()
    if args.command == "collect": collect(args.input, args.output)
    else:
        parser.error("Publishing and recovery are owned by UpperMoon0/NsTut-CICD; use its reviewed release request")

if __name__ == "__main__": main()
