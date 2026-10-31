"""Install a checksum-pinned, disposable ATM10 To the Sky client for native visual tests.

Only accesses paths below --output and --cache. Nothing is installed into a
user's launcher, mods folder, saves, or account directory.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from hashlib import sha256
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import tempfile
import urllib.parse
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_LOCK = ROOT / "tools/atm10-sky/lock-2.3.1.json"
ALLOWED_HOSTS = {"edge.forgecdn.net", "mediafilez.forgecdn.net", "media.forgecdn.net"}


def file_hash(path: Path) -> str:
    digest = sha256()
    with path.open("rb") as source:
        for data in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(data)
    return digest.hexdigest()


def validate_url(url: str) -> None:
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != "https" or parsed.hostname not in ALLOWED_HOSTS:
        raise ValueError(f"Untrusted modpack download URL: {url}")


def download_checked(url: str, target: Path, digest: str) -> None:
    validate_url(url)
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists() and file_hash(target) == digest:
        return
    if target.exists():
        raise RuntimeError(f"Existing artifact checksum differs; refusing overwrite: {target}")
    for attempt in range(3):
        with tempfile.NamedTemporaryFile(prefix=".atm10-", suffix=".part",
                                         dir=target.parent, delete=False) as output:
            temporary = Path(output.name)
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "SimplyMirrors-ATM10-QA/1"})
            with urllib.request.urlopen(request, timeout=120) as response, temporary.open("wb") as stream:
                if urllib.parse.urlsplit(response.url).hostname not in ALLOWED_HOSTS:
                    raise ValueError(f"Unexpected redirect: {response.url}")
                shutil.copyfileobj(response, stream)
            if file_hash(temporary) != digest:
                raise ValueError(f"SHA-256 mismatch: {target.name}")
            if not zipfile.is_zipfile(temporary):
                raise ValueError(f"Invalid modpack ZIP/JAR: {target.name}")
            temporary.replace(target)
            return
        except Exception:
            if attempt == 2:
                raise
        finally:
            temporary.unlink(missing_ok=True)


def safe_override(name: str) -> Path | None:
    path = PurePosixPath(name)
    if not path.parts or path.parts[0] != "overrides" or len(path.parts) < 2:
        return None
    parts = path.parts[1:]
    if any(p in ("..", ".", "") or ":" in p or "\\" in p for p in parts):
        raise ValueError(f"Unsafe modpack override: {name}")
    if parts[0] in ("saves", "screenshots", "logs", "crash-reports"):
        raise ValueError(f"Unexpected user-data path inside modpack: {name}")
    return Path(*parts)


def read_lock(path: Path) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    if (data.get("schema"), data.get("pack"), data.get("pack_version"),
        data.get("minecraft"), data.get("neoforge")) != (1, "ATM10SKY", "2.3.1", "1.21.1", "21.1.250"):
        raise ValueError("Unexpected ATM10 Sky lock identity or schema")
    rows = data["files"]
    if len(rows) != 337 or len({row["file_id"] for row in rows}) != 337:
        raise ValueError("Incomplete or duplicate ATM10 Sky dependencies")
    seen = set()
    for row in rows:
        if row["folder"] not in ("mods", "resourcepacks"):
            raise ValueError(f"Invalid modpack destination: {row}")
        filename = row["filename"]
        if filename in ("", ".", "..") or "/" in filename or "\\" in filename:
            raise ValueError(f"Unsafe artifact name: {filename}")
        if (row["folder"], filename.lower()) in seen:
            raise ValueError(f"Duplicate destination {filename}")
        seen.add((row["folder"], filename.lower()))
        if len(row["sha256"]) != 64 or any(c not in "0123456789abcdef" for c in row["sha256"]):
            raise ValueError(f"Invalid SHA-256 for {filename}")
        validate_url(row["url"])
    validate_url(data["pack_archive_url"])
    return data


def extract_verified_archive(archive: Path, game: Path, lock: dict) -> int:
    extracted = 0
    with zipfile.ZipFile(archive) as source:
        manifest = json.loads(source.read("manifest.json"))
        manifest_ids = {(int(row["projectID"]), int(row["fileID"])) for row in manifest["files"]}
        lock_ids = {(row["project_id"], row["file_id"]) for row in lock["files"]}
        loader = manifest["minecraft"]["modLoaders"][0]["id"]
        if manifest_ids != lock_ids or loader != "neoforge-21.1.250":
            raise ValueError("Modpack manifest doesn't match pinned file identities")
        for info in source.infolist():
            if info.is_dir():
                continue
            rel = safe_override(info.filename)
            if rel is None:
                continue
            if (info.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError(f"Symlink override not allowed: {rel}")
            target = game / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            # Never replace a user-created file, even within the test workspace.
            if target.exists():
                if target.read_bytes() != source.read(info):
                    raise ValueError(f"Existing override differs: {target}")
                continue
            with source.open(info) as src, target.open("xb") as dst:
                shutil.copyfileobj(src, dst)
            extracted += 1
    return extracted


def verify_install(game: Path, lock: dict) -> dict:
    validated = []
    for row in lock["files"]:
        path = game / row["folder"] / row["filename"]
        if not path.is_file() or file_hash(path) != row["sha256"]:
            raise ValueError(f"Missing or changed: {path}")
        if not zipfile.is_zipfile(path):
            raise ValueError(f"Invalid ZIP/JAR: {path}")
        validated.append({"name": row["filename"], "sha256": row["sha256"],
                          "file_id": row["file_id"], "folder": row["folder"]})
    shaders = sorted(p.name for p in (game / "shaderpacks").glob("*.zip"))
    expected = {"ComplementaryReimagined_r5.9.3.zip",
                "ComplementaryUnbound_r5.9.3.zip",
                "Bliss_v2.1.2_(Chocapic13_Shaders_edit).zip"}
    if not expected.issubset(set(shaders)):
        raise ValueError(f"ATM10 Sky shader packs incomplete: {shaders}")
    return {"pack": lock["pack"], "version": lock["pack_version"],
            "minecraft": lock["minecraft"], "neoforge": lock["neoforge"],
            "file_count": len(validated), "shaderpacks": shaders,
            "mods": sum(row["folder"] == "mods" for row in lock["files"]),
            "provenance": validated}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lock", type=Path, default=DEFAULT_LOCK)
    parser.add_argument("--output", type=Path, required=True,
                        help="Path to a fresh disposable Minecraft game directory")
    parser.add_argument("--cache", type=Path, default=ROOT / "build/atm10-sky/downloads")
    parser.add_argument("--archive", type=Path,
                        help="Use a previously downloaded exact modpack ZIP")
    parser.add_argument("--verify-only", action="store_true")
    parser.add_argument("--workers", type=int, default=12)
    args = parser.parse_args()
    lock = read_lock(args.lock)
    game = args.output.resolve()
    if not args.verify_only:
        if game.exists() and any(game.iterdir()):
            raise ValueError(f"Fresh game directory required: {game}")
        game.mkdir(parents=True, exist_ok=True)
        archive = (args.archive or args.cache / "ATM10SKY-2.3.1.zip").resolve()
        if args.archive:
            if file_hash(archive) != lock["pack_archive_sha256"]:
                raise ValueError("Supplied modpack archive checksum mismatch")
        else:
            download_checked(lock["pack_archive_url"], archive, lock["pack_archive_sha256"])
        count = extract_verified_archive(archive, game, lock)
        print(f"Extracted {count} official modpack override files", flush=True)
        workers = max(1, min(args.workers, 16))
        with ThreadPoolExecutor(max_workers=workers) as pool:
            futures = {pool.submit(download_checked, row["url"],
                       game / row["folder"] / row["filename"], row["sha256"]): row
                       for row in lock["files"]}
            for index, future in enumerate(as_completed(futures), 1):
                future.result()
                if index % 25 == 0:
                    print(f"Verified downloaded artifacts: {index}/{len(futures)}", flush=True)
    report = verify_install(game, lock)
    evidence = game / "qa-evidence"
    evidence.mkdir(exist_ok=True)
    (evidence / "modpack-inventory.json").write_text(
        json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print("ATM10_SKY_PACK_VERIFIED " + json.dumps(
        {k: v for k, v in report.items() if k != "provenance"}), flush=True)


if __name__ == "__main__":
    main()
