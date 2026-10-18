"""Bind a recovery bundle to its original tested commit and published GitHub assets."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
from release import TARGETS, expected_name, version


def gh(*args):
    return json.loads(subprocess.check_output(["gh", *args], text=True))


def verify_jobs(jobs):
    expected = {
        "build / Reflection math (Java 8)",
        "build / Shader history isolation (OpenGL)",
        "build / Verify eight-target release bundle",
        *{f"build / {target}" for target in TARGETS},
        *{f"visual / Visual {target}" for target in TARGETS},
        *{f"shaders / Complementary {target}" for target in TARGETS},
    }
    checked = [job for job in jobs if job["name"].startswith(("build /", "visual /", "shaders /"))]
    if (len(checked) != len(expected) or {job["name"] for job in checked} != expected
            or any(job["conclusion"] != "success" for job in checked)):
        raise ValueError("All eight named build, visual, and shader checks, math, native GL history, and bundle must pass")


def main():
    run_id, directory = sys.argv[1:]
    source = Path(directory)
    run = gh("run", "view", run_id, "--json", "headSha,headBranch,jobs")
    published = gh("release", "view", "v" + version(), "--json", "targetCommitish,assets,isPrerelease")
    if run["headBranch"] != "main" or published["targetCommitish"] != run["headSha"] or published["isPrerelease"]:
        raise ValueError("Recovery must match the stable release's original main commit")
    verify_jobs(run["jobs"])
    assets = {a["name"]: a["digest"] for a in published["assets"]}
    for name in [expected_name(t, version()) for t in TARGETS] + ["SHA256SUMS"]:
        actual = "sha256:" + hashlib.sha256((source / name).read_bytes()).hexdigest()
        if assets.get(name) != actual:
            raise ValueError(f"Published asset checksum mismatch: {name}")
    print("Recovery bundle matches all nine published assets and the fully tested release commit")


if __name__ == "__main__":
    main()
