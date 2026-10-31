"""Real ATM10 Sky 2.3.1 enclosed-room integration test on a virtual X display.

Launches a disposable vanilla-sized NeoForge server only to construct a new
Minecraft 1.21.1 fixture, then runs the *complete pinned modpack* in an
integrated client against that saved fixture. No personal world is touched.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time

from atm10_sky import read_lock, verify_install
from visual_smoke import Rcon, FACES, fixture, free_port, image_check

ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / "neoforge-1.21.1"
OFFSET = 48
WORLD = "atm10-sky-mirror-fixture"
OWO_DEV_REMAP_NAME = "owo-lib-neoforge-0.12.15.6-beta.1+1.21.jar"
OWO_DEV_REMAP_SHA = "5fc306ad3d42e1dc26812f2df856e7c232376405caab6af4f5aaffe89cd2b256"


def gradle(*flags: str) -> list[str]:
    executable = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    return [str(executable), "-PvisualTest", "-PvisualMc=1.21.1",
            "-PvisualLoader=neoforge", "-Patm10SkyLoaderVersion=21.1.250",
            "--no-daemon", "--console=plain",
            "-Dorg.gradle.jvmargs=-Xmx1536m", "--max-workers=2", *flags]


def shift_command(command: str, offset: int) -> str:
    fields = command.split(" ")
    if fields[0] == "fill" and len(fields) >= 8:
        fields[1] = str(int(fields[1]) + offset)
        fields[4] = str(int(fields[4]) + offset)
    elif fields[0] == "setblock" and len(fields) >= 5:
        fields[1] = str(int(fields[1]) + offset)
    else:
        raise ValueError(f"Unrecognized native fixture command: {command}")
    return " ".join(fields)


def read_log_tail(path: Path, limit: int = 10000) -> str:
    if not path.exists():
        return "No log written"
    with path.open("rb") as stream:
        stream.seek(0, 2)
        size = stream.tell()
        stream.seek(max(0, size - limit))
        return stream.read().decode("utf-8", errors="replace")


def fatal_client_launch(log: Path, latest: Path) -> str | None:
    """Detect a real JVM fatality even when Gradle leaves its wrapper running.

    Non-fatal mod warning messages must not abort a visual test.
    """
    output = read_log_tail(log, 96_000)
    for marker in ('Exception in thread "main"', 'FAILURE: Build failed with an exception.',
                   'BUILD FAILED'):
        if marker in output:
            return "Fatal client launcher error (" + marker + "): " + output[-12_000:]
    game_output = read_log_tail(latest, 48_000)
    for marker in ('Uncaught exception in thread "Render thread"',
                   'Reported exception thrown!'):
        if marker in game_output:
            return "Fatal Minecraft client error (" + marker + "): " + game_output[-12_000:]
    return None


def stop_process(process: subprocess.Popen | None) -> None:
    if process is None or process.poll() is not None:
        return
    # Gradle may survive after the Minecraft server JVM has shut down. Kill
    # the process group to avoid leaking Gradle children into later variants.
    import signal
    if os.name != "nt":
        os.killpg(process.pid, signal.SIGTERM)
    else:
        process.terminate()
    try:
        process.wait(timeout=12)
    except subprocess.TimeoutExpired:
        if os.name != "nt":
            os.killpg(process.pid, signal.SIGKILL)
        else:
            process.kill()
        process.wait(timeout=10)


def stage_development_remapped_owo(client: Path, evidence: Path) -> None:
    """Load original owo through Loom's dev mappings, not an edited modpack."""
    staged = client / "mods" / OWO_DEV_REMAP_NAME
    if not staged.is_file():
        raise RuntimeError(f"Expected full-pack owo jar missing: {staged}")
    digest = hashlib.sha256(staged.read_bytes()).hexdigest()
    if digest != OWO_DEV_REMAP_SHA:
        raise ValueError(f"ATM10 owo checksum mismatch in staged client: {staged}")
    staged.unlink()
    (evidence / "dev-mapped-mods.json").write_text(json.dumps([{
        "filename": OWO_DEV_REMAP_NAME,
        "sha256": digest,
        "loader": "loom.modLocalRuntime",
        "source": "unchanged verified ATM10 installation",
        "reason": "production mixin targets require Loom development remapping",
    }], indent=2) + "\n", encoding="utf-8")
    print("ATM10_SKY_DEV_REMAPPED_OWO " + digest, flush=True)


def prepare_world(name: str, evidence: Path) -> Path:
    server_dir = MODULE / "run" / f"{name}-server"
    server_dir.mkdir(parents=True, exist_ok=False)
    port, rcon_port = free_port(), free_port()
    (server_dir / "eula.txt").write_text("eula=true\n")
    properties = {
        "server-ip": "127.0.0.1", "server-port": port,
        "enable-rcon": "true", "rcon.port": rcon_port,
        "rcon.password": "simply-mirrors-disposable-test",
        "online-mode": "false", "gamemode": "creative",
        "force-gamemode": "true", "allow-flight": "true",
        "spawn-protection": 0, "view-distance": 4,
        "simulation-distance": 4, "level-name": "visual-world",
        "level-type": "minecraft:flat", "max-players": 1,
        "max-tick-time": 0, "difficulty": "peaceful",
    }
    (server_dir / "server.properties").write_text(
        "".join(f"{key}={value}\n" for key, value in properties.items()))
    proc = None
    rcon = None
    log = evidence / "fixture-server.log"
    command = gradle(f"-PvisualPort={port}", f"-PvisualRunName={name}",
                     "-PmirrorsShaderStack=off", ":neoforge-1.21.1:runVisualServer")
    with log.open("w", encoding="utf-8") as output:
        try:
            proc = subprocess.Popen(command, cwd=ROOT, stdout=output,
                                    stderr=subprocess.STDOUT,
                                    start_new_session=(os.name != "nt"))
            deadline = time.monotonic() + 1200
            while time.monotonic() < deadline:
                if proc.poll() is not None:
                    raise RuntimeError("Fixture server exited: " + read_log_tail(log))
                try:
                    rcon = Rcon(rcon_port)
                    break
                except (OSError, ConnectionError, TimeoutError):
                    time.sleep(2)
            if rcon is None:
                raise TimeoutError("Fixture server RCON timeout: " + read_log_tail(log))
            commands = [
                "gamerule doDaylightCycle false",
                "gamerule doWeatherCycle false",
                "time set noon", "weather clear",
                "setworldspawn 0 65 -4",
            ]
            for scene in range(6):
                offset = OFFSET * scene
                commands.append(f"forceload add {offset-16} -16 {offset+16} 16")
                commands.extend(shift_command(line, offset) for line in fixture(scene, enclosed=True))
            for index, line in enumerate(commands):
                result = rcon.command(line)
                if any(word in result.lower() for word in
                       ("unknown", "incorrect", "too many blocks", "not loaded", "failed")):
                    raise RuntimeError(f"Fixture command rejected: {line}: {result}")
                if index % 30 == 0:
                    print(f"Fixture commands: {index}/{len(commands)}", flush=True)
            rcon.command("save-all flush")
            time.sleep(3)
            rcon.command("stop")
            world = server_dir / "visual-world"
            if not (world / "level.dat").is_file():
                raise RuntimeError(f"Fixture world not generated: {world}")
            try:
                proc.wait(timeout=30)
            except subprocess.TimeoutExpired:
                # The native server itself may finish shutdown while Gradle's
                # wrapper remains alive. Require explicit persisted-world and
                # completed-shutdown evidence before terminating the wrapper.
                tail = read_log_tail(log)
                if ("Stopping server" not in tail or
                        "All dimensions are saved" not in tail or
                        "Thread RCON Listener stopped" not in tail):
                    raise RuntimeError("Native server shutdown unverified: " + tail)
                print("ATM10_SKY_SERVER_STOP_CONFIRMED_GRADLE_WRAPPER_STILL_RUNNING", flush=True)
                stop_process(proc)
            print("ATM10_SKY_NATIVE_FIXTURE_READY", flush=True)
            return world
        finally:
            if rcon is not None:
                rcon.close()
            stop_process(proc)


def stage_client(game: Path, world: Path, name: str, mode: str, evidence: Path) -> Path:
    client = MODULE / "run" / f"{name}-client"
    if client.exists():
        raise ValueError(f"Refusing to reuse a client profile: {client}")
    shutil.copytree(game, client)
    stage_development_remapped_owo(client, evidence)
    saves = client / "saves"
    saves.mkdir(exist_ok=True)
    shutil.copytree(world, saves / WORLD)
    if mode == "complementary":
        euphoria = next((client / "mods").glob("EuphoriaPatcher-*.jar"), None)
        if euphoria is None:
            raise RuntimeError("Expected Euphoria Patcher missing from pack")
        euphoria.unlink()
        (evidence / "variant-note.txt").write_text(
            "Control variant: exact ATM10 Sky 2.3.1 with only Euphoria Patcher "
            "removed to isolate plain Complementary. Full-pack variants: off/euphoria.\n")
    pack = "ComplementaryReimagined_r5.9.3.zip"
    # Iris reads its persisted options from the game directory's config folder.
    # A root-level iris.properties is silently ignored by the real launcher.
    iris_config = client / "config" / "iris.properties"
    iris_config.parent.mkdir(parents=True, exist_ok=True)
    iris_config.write_text(
        f"enableShaders={'false' if mode == 'off' else 'true'}\n"
        f"shaderPack={pack}\n")
    (client / "visual-scene.txt").write_text("-1\n")
    return client


def run_client(client: Path, mode: str, name: str, evidence: Path) -> dict:
    output = evidence / "client.log"
    command = gradle("-Patm10Sky", "-PvisualPort=0",
                     f"-PvisualRunName={name}", "-PmirrorsShaderStack=off",
                     ":neoforge-1.21.1:runVisualClient")
    process = None
    results = {}
    errors = {}
    try:
        with output.open("w", encoding="utf-8") as log:
            process = subprocess.Popen(command, cwd=ROOT, stdout=log,
                                       stderr=subprocess.STDOUT,
                                       start_new_session=(os.name != "nt"))
            for scene, face in enumerate(FACES):
                (client / "visual-scene.txt").write_text(str(scene) + "\n")
                result = client / "visual-evidence" / f"result-{scene}.json"
                timeout = 2100 if scene == 0 else 960
                deadline = time.monotonic() + timeout
                while time.monotonic() < deadline:
                    if result.is_file():
                        break
                    if process.poll() is not None:
                        raise RuntimeError(f"Game exited during {face}: {read_log_tail(output)}")
                    fatal = fatal_client_launch(output, client / "logs" / "latest.log")
                    if fatal is not None:
                        raise RuntimeError(f"Client crashed before {face} reflection capture: {fatal}")
                    time.sleep(2)
                if not result.is_file():
                    raise TimeoutError(f"Game didn't capture {face}: {read_log_tail(output)}")
                info = json.loads(result.read_text())
                if (info.get("passes", 0) < 120 or info.get("angleFrames", 0) < 120
                        or info.get("glError") != 0 or not info.get("creativeTab")
                        or info.get("integratedWorld") is not True):
                    errors[face] = f"Native test counters failed: {info}"
                if info.get("shaderPackActive") is not (mode != "off"):
                    errors[face] = f"Iris shader state incorrect: {info}"
                image = client / "visual-evidence" / f"mirror-{scene}.png"
                try:
                    info.update(image_check(image, shaders=(mode != "off")))
                except Exception as error:
                    errors[face] = str(error)
                    info["imageError"] = str(error)
                results[face] = info
                (evidence / "results.json").write_text(json.dumps(results, indent=2))
                print(f"ATM10_SKY_FACE {mode} {face} {json.dumps(info)}", flush=True)
            (client / "visual-scene.txt").write_text("6\n")
            process.wait(timeout=180)
            if process.returncode != 0:
                raise RuntimeError(f"Game process exit {process.returncode}")
        if errors:
            raise AssertionError("Missing or invalid actual mirror reflections: " +
                                 json.dumps(errors))
        (evidence / "verified.json").write_text(json.dumps(results, indent=2))
        print("ATM10_SKY_FULL_PACK_VISUAL_PASS " + mode, flush=True)
        return results
    finally:
        stop_process(process)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", required=True, choices=("off", "complementary", "euphoria"))
    parser.add_argument("--game", type=Path, required=True)
    parser.add_argument("--name")
    args = parser.parse_args()
    lock = read_lock(ROOT / "tools/atm10-sky/lock-2.3.1.json")
    report = verify_install(args.game, lock)
    name = args.name or f"atm10-{args.mode}"
    if not name.replace("-", "").isalnum() or not name.islower():
        raise ValueError("Use a unique lowercase alphanumeric/hyphen run name")
    evidence = MODULE / "run" / f"{name}-evidence"
    evidence.mkdir(parents=True, exist_ok=False)
    (evidence / "modpack.json").write_text(
        json.dumps({k: v for k, v in report.items() if k != "provenance"}, indent=2))
    (evidence / "configuration.json").write_text(json.dumps({
        "mode": args.mode, "minecraft": "1.21.1", "neoforge": "21.1.250",
        "pack": "ATM10SKY 2.3.1", "full_pack": args.mode != "complementary",
        "mirror_branch": os.environ.get("GITHUB_SHA", "local"),
        "shaderpack": None if args.mode == "off" else "ComplementaryReimagined_r5.9.3.zip",
        "headless_display": os.environ.get("DISPLAY", "unset"),
    }, indent=2))
    try:
        world = prepare_world(name, evidence)
        client = stage_client(args.game, world, name, args.mode, evidence)
        run_client(client, args.mode, name, evidence)
    except BaseException as error:
        (evidence / "failure.txt").write_text(repr(error) + "\n" + read_log_tail(
            evidence / "client.log") + "\n")
        print("ATM10_SKY_FULL_PACK_VISUAL_FAIL " + repr(error), flush=True)
        raise


if __name__ == "__main__":
    main()
