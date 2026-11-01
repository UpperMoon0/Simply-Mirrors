"""Temporary production NeoForge ATM10 Sky verification on a GitHub-hosted runner.

Unlike Architectury Loom's development runClient, this uses the official
NeoForge production launcher, original checksum-pinned modpack JARs, and a
freshly remapped Simply Mirrors QA JAR with disposable capture instrumentation.

Do not ship this file or its installer dependency. Delete before PR #6 merge.
"""
from __future__ import annotations

import argparse
from hashlib import sha256
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import zipfile

from atm10_sky import read_lock, verify_install
from atm10_sky_visual import (
    FACES, ROOT, MODULE, WORLD, gradle, prepare_world,
    read_log_tail, stop_process, fatal_client_launch, image_check,
)

MC_VERSION = "1.21.1"
NEOFORGE_VERSION = "21.1.250"
LAUNCHER_LIB_VERSION = "8.0"
SHADER = "ComplementaryReimagined_r5.9.3.zip"


def build_qa_jar(evidence: Path) -> Path:
    """Remap visual-test instrumentation to the *production* namespace."""
    log = evidence / "qa-jar-build.log"
    command = gradle("-PvisualPort=0", "-Patm10QaJar", "-PmirrorsShaderStack=off", ":neoforge-1.21.1:remapJar")
    with log.open("w", encoding="utf-8") as stream:
        result = subprocess.run(command, cwd=ROOT, stdout=stream,
                                stderr=subprocess.STDOUT, check=False)
    if result.returncode != 0:
        raise RuntimeError("Production QA remapJar build failed: " + read_log_tail(log))
    directory = MODULE / "build" / "visual" / "libs"
    jars = sorted(path for path in directory.glob("simply_mirrors-neoforge-1.21.1-*.jar")
                  if not any(part in path.stem.lower() for part in ("sources", "dev", "shadow")))
    if len(jars) != 1:
        raise RuntimeError(f"Expected one production remapped QA jar, found {jars}")
    jar = jars[0]
    with zipfile.ZipFile(jar) as archive:
        names = set(archive.namelist())
        required = {
            "com/nstut/simplymirrors/visualtest/VisualProbeMixin.class",
            "com/nstut/simplymirrors/visualsupport/Atm10SkyProbe.class",
            "simplymirrors.visual.mixins.json",
            "META-INF/neoforge.mods.toml",
        }
        if not required.issubset(names):
            raise RuntimeError(f"Production QA JAR missing capture instrumentation: {required - names}")
        if b"simplymirrors.visual.mixins.json" not in archive.read("META-INF/neoforge.mods.toml"):
            raise RuntimeError("NeoForge production QA jar does not register its visual mixin")
    digest = sha256(jar.read_bytes()).hexdigest()
    (evidence / "qa-jar.json").write_text(json.dumps({
        "name": jar.name, "sha256": digest, "namespace": "production",
        "instrumented": True,
        "source": os.environ.get("GITHUB_SHA", "local"),
    }, indent=2) + "\n", encoding="utf-8")
    print(f"ATM10_SKY_PRODUCTION_QA_JAR {jar.name} sha256={digest}", flush=True)
    return jar


def stage_full_pack(game: Path, world: Path, name: str, mode: str,
                    jar: Path, evidence: Path, renderer_only: bool = False) -> Path:
    client = MODULE / "run" / f"{name}-client"
    if client.exists():
        raise ValueError(f"Refusing to overwrite a previous profile: {client}")
    if renderer_only:
        # Exact rendering JARs from the pack. Deliberately exclude all
        # unrelated content/mods/configuration during causal isolation.
        client.mkdir(parents=True)
        mods = client / "mods"
        mods.mkdir()
        for mod_name in (
            "iris-neoforge-1.8.12+mc1.21.1.jar",
            "sodium-neoforge-0.6.13+mc1.21.1.jar",
            "EuphoriaPatcher-1.10.5-r5.9.3-neoforge.jar",
        ):
            source = game / "mods" / mod_name
            if not source.is_file():
                raise FileNotFoundError(f"Expected pinned renderer mod: {source}")
            shutil.copy2(source, mods / mod_name)
        shader = game / "shaderpacks" / SHADER
        if not shader.is_file():
            raise FileNotFoundError(f"Expected pinned shader: {shader}")
        (client / "shaderpacks").mkdir()
        shutil.copy2(shader, client / "shaderpacks" / shader.name)
    else:
        shutil.copytree(game, client)
    mods = client / "mods"
    preexisting = sorted(mods.glob("*simply*mirror*.jar"))
    if preexisting:
        raise RuntimeError(f"Unexpected Simply Mirrors JAR already in official pack: {preexisting}")
    shutil.copy2(jar, mods / jar.name)
    (client / "saves").mkdir(exist_ok=True)
    shutil.copytree(world, client / "saves" / WORLD)
    if mode == "complementary":
        euphoria = list(mods.glob("EuphoriaPatcher-*.jar"))
        if len(euphoria) != 1:
            raise RuntimeError(f"Expected exactly one pinned Euphoria Patcher: {euphoria}")
        euphoria[0].unlink()
    # Iris reads its persisted options from the game directory's config folder.
    # A root-level iris.properties is silently ignored by the real launcher.
    iris_config = client / "config" / "iris.properties"
    iris_config.parent.mkdir(parents=True, exist_ok=True)
    iris_config.write_text(
        f"enableShaders={'false' if mode == 'off' else 'true'}\n"
        f"shaderPack={SHADER}\n", encoding="utf-8")
    (client / "visual-scene.txt").write_text("-1\n", encoding="utf-8")
    (evidence / "launcher-mode.json").write_text(json.dumps({
        "runtime": "production-neoforge",
        "minecraft": MC_VERSION, "neoforge": NEOFORGE_VERSION,
        "offline-local-test-account": True,
        "full_pack_installed": not renderer_only,
        "renderer_only": renderer_only,
        "euphoria_patcher_present": mode != "complementary",
        "full_pack": not renderer_only,  # Legacy diagnostic field.
        "mode": mode, "shader": None if mode == "off" else SHADER,
        "qa_jar": jar.name, "qa_jar_sha256": sha256(jar.read_bytes()).hexdigest(),
    }, indent=2) + "\n", encoding="utf-8")
    return client


def install_and_command(client: Path, evidence: Path) -> list[str]:
    from importlib.metadata import version
    import minecraft_launcher_lib as launcher

    actual = version("minecraft-launcher-lib")
    if actual != LAUNCHER_LIB_VERSION:
        raise RuntimeError(f"Unpinned launcher library {actual}, expected {LAUNCHER_LIB_VERSION}")
    java = Path(os.environ.get("JAVA_HOME", "")) / "bin" / "java"
    if not java.is_file():
        raise RuntimeError("GitHub-hosted runner must provide a Java 21 JAVA_HOME")
    launcher_home = ROOT / "build" / "atm10-sky" / "production-launcher"
    launcher_home.mkdir(parents=True, exist_ok=True)
    loader = launcher.mod_loader.get_mod_loader("neoforge")
    installed = loader.install(
        MC_VERSION, launcher_home, loader_version=NEOFORGE_VERSION, java=java)
    expected = loader.get_installed_version(MC_VERSION, NEOFORGE_VERSION)
    if installed != expected:
        raise RuntimeError(f"Wrong installed NeoForge version: {installed}, expected {expected}")
    options = launcher.utils.generate_test_options()
    options["gameDirectory"] = str(client.resolve())
    options["executablePath"] = str(java)
    options["jvmArguments"] = [
        "-Xmx8192m", "-Xms512m", "-Dsimplymirrors.atm10Sky=true",
        "-Dsimplymirrors.visualPort=0",
    ]
    options["customResolution"] = True
    options["resolutionWidth"] = "960"
    options["resolutionHeight"] = "540"
    command = launcher.command.get_minecraft_command(installed, launcher_home, options)
    if not command or command[0] != str(java):
        raise RuntimeError("Production NeoForge command does not use the pinned Java runtime")
    (evidence / "production-launch.json").write_text(json.dumps({
        "version_id": installed, "java": str(java),
        "launcher_library": actual, "game_directory": str(client.resolve()),
        "command_parts": len(command),
    }, indent=2) + "\n", encoding="utf-8")
    print(f"ATM10_SKY_OFFICIAL_NEOFORGE_CLIENT_READY {installed}", flush=True)
    return command


def run_production_capture(client: Path, command: list[str], mode: str,
                           evidence: Path) -> dict:
    log = evidence / "client.log"
    process = None
    results, failures = {}, {}
    try:
        with log.open("w", encoding="utf-8") as output:
            process = subprocess.Popen(command, cwd=ROOT, stdout=output,
                                       stderr=subprocess.STDOUT, start_new_session=True)
            for scene, face in enumerate(FACES):
                (client / "visual-scene.txt").write_text(str(scene) + "\n", encoding="utf-8")
                result_file = client / "visual-evidence" / f"result-{scene}.json"
                deadline = time.monotonic() + (1200 if scene == 0 else 600)
                while time.monotonic() < deadline:
                    if result_file.is_file():
                        break
                    if process.poll() is not None:
                        raise RuntimeError(f"Production Minecraft exited during {face}: {read_log_tail(log)}")
                    fatal = fatal_client_launch(log, client / "logs" / "latest.log")
                    if fatal is not None:
                        raise RuntimeError(f"Production Minecraft crashed during {face}: {fatal}")
                    time.sleep(2)
                if not result_file.is_file():
                    raise TimeoutError(f"No production framebuffer captured for {face}: {read_log_tail(log)}")
                info = json.loads(result_file.read_text(encoding="utf-8"))
                if (info.get("passes", 0) < 120 or info.get("angleFrames", 0) < 120
                        or info.get("glError") != 0 or not info.get("creativeTab")
                        or info.get("integratedWorld") is not True):
                    failures[face] = f"Native counters failed: {info}"
                if info.get("shaderPackActive") is not (mode != "off"):
                    failures[face] = f"Shader activation inconsistent: {info}"
                screenshot = client / "visual-evidence" / f"mirror-{scene}.png"
                try:
                    info.update(image_check(screenshot, shaders=(mode != "off")))
                except Exception as exc:
                    failures[face] = str(exc)
                    info["imageError"] = str(exc)
                results[face] = info
                (evidence / "results.json").write_text(json.dumps(results, indent=2) + "\n")
                print(f"ATM10_SKY_PRODUCTION_FACE {mode} {face} {json.dumps(info)}", flush=True)
            (client / "visual-scene.txt").write_text("6\n", encoding="utf-8")
            process.wait(timeout=180)
            if process.returncode != 0:
                raise RuntimeError(f"Production Minecraft returned {process.returncode}")
        if failures:
            raise AssertionError("Production full-pack reflection failures: " + json.dumps(failures))
        (evidence / "verified.json").write_text(json.dumps(results, indent=2) + "\n")
        print(f"ATM10_SKY_PRODUCTION_VISUAL_PASS {mode}", flush=True)
        return results
    finally:
        stop_process(process)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--game", type=Path, required=True)
    parser.add_argument("--mode", choices=("off", "complementary", "euphoria"), required=True)
    parser.add_argument("--renderer-only", action="store_true",
                        help="Isolate Iris/Sodium/Euphoria/Complementary from full pack")
    args = parser.parse_args()
    lock = read_lock(ROOT / "tools/atm10-sky/lock-2.3.1.json")
    report = verify_install(args.game, lock)
    name = f"atm10-{'renderer-only-' if args.renderer_only else ''}{args.mode}"
    evidence = MODULE / "run" / f"{name}-evidence"
    evidence.mkdir(parents=True, exist_ok=False)
    (evidence / "modpack.json").write_text(
        json.dumps({k: v for k, v in report.items() if k != "provenance"}, indent=2) + "\n")
    try:
        jar = build_qa_jar(evidence)
        world = prepare_world(name, evidence)
        client = stage_full_pack(args.game, world, name, args.mode, jar, evidence, args.renderer_only)
        command = install_and_command(client, evidence)
        run_production_capture(client, command, args.mode, evidence)
    except BaseException as exc:
        (evidence / "failure.txt").write_text(
            repr(exc) + "\n" + read_log_tail(evidence / "client.log") + "\n", encoding="utf-8")
        print(f"ATM10_SKY_PRODUCTION_FAIL {exc!r}", flush=True)
        raise


if __name__ == "__main__":
    main()
