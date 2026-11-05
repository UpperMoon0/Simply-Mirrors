"""Recover a selected failed release attempt from its exact R2 namespace."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import shutil
import boto3
from botocore.config import Config
from r2_artifacts import namespace, download


def main():
    run_id, destination = sys.argv[1:]
    if not run_id.isdigit() or os.environ.get("GITHUB_REF") != "refs/heads/main":
        raise ValueError("Recovery requires a numeric source run and main branch")
    repo = os.environ["GITHUB_REPOSITORY"]
    run = json.loads(subprocess.check_output(
        ["gh", "api", f"repos/{repo}/actions/runs/{run_id}"], text=True))
    if (run["head_branch"] != "main" or run["path"] != ".github/workflows/release.yml"
            or run["event"] != "workflow_dispatch"):
        raise ValueError("Source must be an inspected main-branch release dispatch")
    prefix = namespace(repo, "release", run_id, str(run["run_attempt"]), run["head_sha"])
    client = boto3.client("s3", endpoint_url=os.environ["R2_ENDPOINT"], region_name="auto",
                          aws_access_key_id=os.environ["R2_ACCESS_KEY_ID"],
                          aws_secret_access_key=os.environ["R2_SECRET_ACCESS_KEY"],
                          config=Config(retries={"mode": "standard", "total_max_attempts": 5}))
    bucket = "nstut-ci-artifacts"
    with tempfile.TemporaryDirectory() as temp:
        staged = Path(temp)
        for name in ["simply-mirrors-release-bundle", "curseforge-upload-receipts"]:
            download(client, bucket, prefix, name, staged / name, merge=True, expected=[name])
        output = Path(destination)
        output.mkdir(parents=True, exist_ok=True)
        if any(output.iterdir()):
            raise ValueError("Recovery destination must be empty")
        for name in ["simply-mirrors-release-bundle", "curseforge-upload-receipts"]:
            shutil.copytree(staged / name, output, dirs_exist_ok=True)


if __name__ == "__main__":
    main()
