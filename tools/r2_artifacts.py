"""Private, immutable CI archives with a commit-last SHA-256 manifest.

Credentials are supplied only to artifact steps. No public URLs are created.
"""
from __future__ import annotations
import argparse
import fnmatch
import glob
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import tempfile
import zipfile


def component(value):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", value) or value in (".", ".."):
        raise ValueError("Invalid artifact namespace component")
    return value


def namespace(repository, workflow, run, attempt, sha):
    owner, repo = repository.split("/")
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("Expected full commit SHA")
    return "/".join(component(v) for v in (owner, repo, workflow, run, attempt, sha)) + "/"


def selected_files(patterns, root):
    included, excluded = set(), set()
    for pattern in patterns.splitlines():
        pattern = pattern.strip()
        if not pattern:
            continue
        target = excluded if pattern.startswith("!") else included
        for match in glob.glob(str(root / pattern.lstrip("!")), recursive=True):
            p = Path(match)
            for item in (p.rglob("*") if p.is_dir() else [p]):
                if item.is_file():
                    if item.is_symlink() or not item.resolve().is_relative_to(root.resolve()):
                        raise ValueError("Artifact path leaves workspace")
                    target.add(item)
    return sorted(included - excluded)


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def upload(client, bucket, prefix, name, patterns, missing="error", root=None):
    root = (root or Path.cwd()).resolve()
    name = component(name)
    files = selected_files(patterns, root)
    if not files:
        if missing == "error":
            raise ValueError("No artifact files found")
        print("No artifact files found; " + missing)
        return
    # Preserve relative paths below the common parent, matching upload-artifact.
    base = Path(os.path.commonpath([str(p.parent) for p in files]))
    key = prefix + name
    with tempfile.TemporaryDirectory() as temp:
        archive = Path(temp) / "artifact.zip"
        with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as z:
            for p in files:
                if any(part.startswith(".") for part in p.relative_to(root).parts):
                    raise ValueError("Hidden files must not enter CI archives")
                z.write(p, p.relative_to(base).as_posix())
        if archive.stat().st_size > 512 * 1024 * 1024:
            raise ValueError("Artifact exceeds 512 MiB; narrow evidence paths")
        sha = digest(archive)
        # Conditional puts prevent accidental parallel or retry overwrites.
        # A failed manifest write leaves an unreferenced archive for lifecycle cleanup.
        data_key = key + "/" + sha + ".zip"
        with archive.open("rb") as stream:
            try:
                client.put_object(Bucket=bucket, Key=data_key, Body=stream,
                                  IfNoneMatch="*", ContentType="application/zip")
            except Exception as error:
                if getattr(error, "response", {}).get("ResponseMetadata", {}).get("HTTPStatusCode") != 412:
                    raise
        manifest = json.dumps({"schema": 1, "sha256": sha, "size": archive.stat().st_size,
                               "key": data_key, "name": name}, sort_keys=True).encode()
        client.put_object(Bucket=bucket, Key=key + "/manifest.json", Body=manifest,
                          IfNoneMatch="*", ContentType="application/json")
        print(f"Uploaded {name}: {len(files)} files, {archive.stat().st_size} bytes, SHA-256 {sha}")


def extract(archive, output):
    with zipfile.ZipFile(archive) as z:
        seen = set()
        for item in z.infolist():
            path = PurePosixPath(item.filename)
            if (path.is_absolute() or ".." in path.parts or "\\" in item.filename
                    or ":" in item.filename or item.filename in seen
                    or (item.external_attr >> 16) & 0o170000 == 0o120000):
                raise ValueError("Unsafe archive member")
            seen.add(item.filename)
        z.extractall(output)


def download(client, bucket, prefix, pattern, output, merge=False, expected=None):
    # Run attempts are isolated for immutable writes, but a rerun of *only*
    # release-package must be able to consume successful producers from attempt 1.
    # Restrict fallback to the exact repository/workflow/run/commit and never
    # accept artifacts from a newer attempt than the current consumer.
    scope = prefix.rstrip("/").split("/")
    if len(scope) != 6 or not re.fullmatch(r"[1-9][0-9]*", scope[4]) \
            or not re.fullmatch(r"[0-9a-f]{40}", scope[5]):
        raise ValueError("Invalid R2 run-attempt namespace")
    run_prefix = "/".join(scope[:4]) + "/"
    requested_attempt, sha = int(scope[4]), scope[5]
    selected = {}
    for page in client.get_paginator("list_objects_v2").paginate(Bucket=bucket, Prefix=run_prefix):
        for obj in page.get("Contents", []):
            key = obj["Key"]
            suffix = key[len(run_prefix):].split("/")
            if len(suffix) != 4 or suffix[3] != "manifest.json":
                continue
            producer_attempt, producer_sha, name, _ = suffix
            if producer_sha != sha or not re.fullmatch(r"[1-9][0-9]*", producer_attempt) \
                    or int(producer_attempt) > requested_attempt:
                continue
            if not fnmatch.fnmatchcase(name, pattern):
                continue
            component(name)
            producer_attempt = int(producer_attempt)
            if name not in selected or producer_attempt > selected[name][0]:
                producer_prefix = run_prefix + str(producer_attempt) + "/" + sha + "/"
                selected[name] = (producer_attempt, key, producer_prefix)

    names = set(selected)
    if not names or (expected is not None and names != set(expected)):
        raise ValueError("Missing or expired R2 artifacts for this run/commit")
    with tempfile.TemporaryDirectory() as temp:
        staged = Path(temp) / "staged"
        for index, (name, (_, key, producer_prefix)) in enumerate(sorted(selected.items())):
            response = client.get_object(Bucket=bucket, Key=key)
            m = json.loads(response["Body"].read(65537))
            if (m.get("schema") != 1 or m.get("name") != name
                    or not re.fullmatch(r"[0-9a-f]{64}", m.get("sha256", ""))
                    or m.get("key") != producer_prefix + name + "/" + m["sha256"] + ".zip"):
                raise ValueError("Invalid R2 artifact manifest")
            archive = Path(temp) / f"{index}.zip"
            client.download_file(bucket, m["key"], str(archive))
            if archive.stat().st_size != m["size"] or digest(archive) != m["sha256"]:
                raise ValueError("R2 artifact SHA-256 mismatch")
            extract(archive, staged if merge else staged / name)
        output.mkdir(parents=True, exist_ok=True)
        if any(output.iterdir()):
            raise ValueError("Download destination must be empty")
        shutil.copytree(staged, output, dirs_exist_ok=True)
    print(f"Downloaded and verified {len(selected)} R2 artifacts")


def main():
    import boto3
    from botocore.config import Config
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=["upload", "download"])
    parser.add_argument("--workflow", default=os.environ.get("R2_WORKFLOW", "build"))
    parser.add_argument("--run", default=os.environ.get("GITHUB_RUN_ID"))
    parser.add_argument("--attempt", default=os.environ.get("GITHUB_RUN_ATTEMPT"))
    parser.add_argument("--sha", default=os.environ.get("GITHUB_SHA"))
    args = parser.parse_args()
    prefix = namespace(os.environ["GITHUB_REPOSITORY"], args.workflow, args.run, args.attempt, args.sha)
    client = boto3.client("s3", region_name="auto", endpoint_url=os.environ["R2_ENDPOINT"],
                          aws_access_key_id=os.environ["R2_ACCESS_KEY_ID"],
                          aws_secret_access_key=os.environ["R2_SECRET_ACCESS_KEY"],
                          config=Config(retries={"mode": "standard", "total_max_attempts": 5},
                                        connect_timeout=15, read_timeout=120))
    bucket = os.environ.get("R2_BUCKET", "nstut-ci-artifacts")
    if args.operation == "upload":
        upload(client, bucket, prefix, os.environ["ARTIFACT_NAME"], os.environ["ARTIFACT_PATH"],
               os.environ.get("ARTIFACT_MISSING", "error"))
    else:
        expected = os.environ.get("ARTIFACT_EXPECTED", "").split()
        download(client, bucket, prefix, os.environ["ARTIFACT_PATTERN"],
                 Path(os.environ["ARTIFACT_PATH"]), os.environ.get("ARTIFACT_MERGE") == "true",
                 expected or None)


if __name__ == "__main__":
    main()
