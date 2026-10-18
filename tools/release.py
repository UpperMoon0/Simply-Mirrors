"""Verify release artifacts and publish using the CurseForge Upload API.

API reference: https://support.curseforge.com/support/solutions/articles/9000197321
No uploads occur during collect, plan, or preflight.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import urllib.error
import urllib.request
import uuid
from verify_artifact import ROOT, TARGETS, verify

API = "https://minecraft.curseforge.com/api"

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

def plan(env):
    v = version()
    publish = False
    if env.get("GITHUB_REF") == "refs/heads/main":
        if env.get("EVENT_NAME") == "workflow_dispatch":
            publish = env.get("REQUEST_PUBLISH") == "true"
        elif env.get("EVENT_NAME") == "push" and "-dev" not in v:
            before = env.get("BEFORE_SHA", "")
            if not re.fullmatch(r"[0-9a-f]{40}", before) or set(before) == {"0"}:
                raise ValueError("Cannot establish the previous main revision; use a manual release")
            previous = subprocess.check_output(["git", "show", f"{before}:gradle.properties"], cwd=ROOT, text=True)
            publish = v != version(previous)
    return {"version": v, "release_type": release_type(v), "publish": str(publish).lower()}

def settings():
    project = os.environ.get("CURSEFORGE_PROJECT_ID", "")
    token = os.environ.get("CURSEFORGE_API_TOKEN", "")
    if not project.isdigit() or int(project) <= 0:
        raise ValueError("Set repository variable CURSEFORGE_PROJECT_ID to Simply Mirrors' project ID")
    if not token:
        raise ValueError("Set repository secret CURSEFORGE_API_TOKEN")
    return project, token

def request(path, token, data=None, content_type=None):
    headers = {"X-Api-Token": token, "User-Agent": "Simply-Mirrors-release"}
    if content_type:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(API + path, data=data, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        detail = error.read(4096).decode("utf-8", errors="replace").replace(token, "[redacted]")
        raise RuntimeError(f"CurseForge request {path} failed: HTTP {error.code}: {detail}") from None

def metadata(info, v):
    loader = {"fabric": "Fabric", "forge": "Forge", "neoforge": "NeoForge"}[info["loader"]]
    relations = [{"slug": "fabric-api", "type": "requiredDependency"}] if info["loader"] == "fabric" else []
    result = {
        "displayName": f"Simply Mirrors {v} - {loader} {info['minecraft']}",
        "changelog": (ROOT / "changelog" / f"{v}.md").read_text(),
        "changelogType": "markdown", "releaseType": release_type(v),
        "gameVersionNames": [info["minecraft"], loader, f"Java {info['java']}", "Client", "Server"],
    }
    if relations:
        result["relations"] = {"projects": relations}
    return result

def preflight(source):
    project, token = settings()
    v = version()
    available = {entry["name"] for entry in request("/game/versions", token)}
    for target, info in TARGETS.items():
        verify(source / expected_name(target, v), info)
        missing = set(metadata(info, v)["gameVersionNames"]) - available
        if missing:
            raise ValueError(f"Unrecognized CurseForge tags for {target}: {sorted(missing)}")
    print(f"CurseForge setup and all eight artifacts/tags verified for project {project}")
    return project, token

def multipart(meta, path):
    boundary = "SimplyMirrors" + uuid.uuid4().hex
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"metadata\"\r\n\r\n" + json.dumps(meta) +
            f"\r\n--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{path.name}\"\r\n" +
            "Content-Type: application/java-archive\r\n\r\n").encode()
    return body + path.read_bytes() + f"\r\n--{boundary}--\r\n".encode(), f"multipart/form-data; boundary={boundary}"

def publish(source, resume=False):
    project, token = preflight(source)
    receipts = source / "curseforge-receipts.json"
    if receipts.exists() and not resume:
        raise ValueError("Upload receipts already exist; inspect them before resuming to avoid duplicate uploads")
    if resume:
        result = json.loads(receipts.read_text())
        if (result.get("project") != project or result.get("version") != version()
                or not isinstance(result.get("files"), dict)
                or any(t not in TARGETS or type(i) is not int or i <= 0 for t, i in result["files"].items())):
            raise ValueError("Receipt project, version, targets, or file IDs do not match this release")
    else:
        result = {"project": project, "version": version(), "files": {}}
    receipts.write_text(json.dumps(result, indent=2) + "\n")
    # No automatic retries: a network timeout may happen after a successful upload.
    for target, info in TARGETS.items():
        if target in result["files"]:
            print(f"Keeping {target}: CurseForge file {result['files'][target]}")
            continue
        path = source / expected_name(target, version())
        body, content_type = multipart(metadata(info, version()), path)
        response = request(f"/projects/{project}/upload-file", token, body, content_type)
        file_id = response["id"]
        if not isinstance(file_id, int) or file_id <= 0:
            raise ValueError(f"Unexpected upload response for {target}; inspect CurseForge before retrying")
        result["files"][target] = file_id
        receipts.write_text(json.dumps(result, indent=2) + "\n")
        print(f"Uploaded {path.name}: CurseForge file {file_id}")

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["collect", "plan", "preflight", "publish"])
    parser.add_argument("--input", type=Path, default=Path("release-input"))
    parser.add_argument("--output", type=Path, default=Path("release-output"))
    parser.add_argument("--resume", action="store_true", help="Skip uploads recorded in inspected receipts")
    args = parser.parse_args()
    if args.command == "collect": collect(args.input, args.output)
    elif args.command == "plan":
        data = plan(os.environ)
        print(json.dumps(data))
        if os.environ.get("GITHUB_OUTPUT"):
            with open(os.environ["GITHUB_OUTPUT"], "a") as output:
                output.write("".join(f"{k}={v}\n" for k, v in data.items()))
    elif args.command == "preflight": preflight(args.input)
    else: publish(args.input, resume=args.resume)

if __name__ == "__main__": main()
