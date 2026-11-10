#!/usr/bin/env python3
"""Real-GPU Minecraft FPS comparison: pinned Complementary + Oculus/Embeddium.

Run only from a disposable checkout on the reviewed native interactive
graphics worker. Inherit its restricted private desktop; never broker a
normal user's token or launch in service-only Session 0.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
import statistics
from pathlib import Path
from gpu_lane import exclusive_graphics_lane
import shutil
import subprocess
import sys
import time

from shader_stack import prepare, install_pack
from image_pixels import flattened_pixels
from visual_smoke import Rcon, fixture, free_port, scene_file, disconnected_during_login

ROOT = Path(__file__).resolve().parents[1]
CASES = [(7, "no-mirror"), (8, "one-mirror"), (9, "nine-coplanar-mirrors")]


def graphics_java_options(gpu_profile: bool = False, coverage_scissor: bool = True,
                          frustum_crop: bool = False, camera_motion: bool = False,
                          output_scissor: bool = False,
                          history_copy_image: bool = False,
                          postprocess_scissor: bool = False,
                          shader_tail_crop: bool = False) -> str:
    """Build explicit shader A/B toggles; never enable experimental culling by default."""
    return ("-Dsimplymirrors.fpsBenchmark=true -Dsimplymirrors.fpsOffscreen=true" +
            (" -Dsimplymirrors.gpuProfile=true" if gpu_profile else "") +
            (" -Dsimplymirrors.coverageScissor=false" if not coverage_scissor else "") +
            (" -Dsimplymirrors.frustumCrop=true" if frustum_crop else "") +
            (" -Dsimplymirrors.fpsCameraMotion=true" if camera_motion else "") +
            (" -Dsimplymirrors.reflectionOutputScissor=true" if output_scissor else "") +
            (" -Dsimplymirrors.historyCopyImage=true" if history_copy_image else "") +
            (" -Dsimplymirrors.shaderPostProcessCrop=true" if postprocess_scissor else "") +
            (" -Dsimplymirrors.shaderTailCrop=true" if shader_tail_crop else ""))


def start_private_graphics_client(command: list[str], work: Path, log_path: Path,
                                  java_home: str, gpu_profile: bool = False,
                                  coverage_scissor: bool = True,
                                  java_tool_options: str | None = None,
                                  frustum_crop: bool = False,
                                   camera_motion: bool = False,
                                   output_scissor: bool = False,
                                   history_copy_image: bool = False,
                                   postprocess_scissor: bool = False,
                                   shader_tail_crop: bool = False) -> subprocess.Popen:
    """Launch directly under the verified restricted Windows graphics worker.

    Never query an interactive user's WTS token or use CreateProcessAsUser.
    The Jenkins graphics worker already owns its private desktop.
    """
    if os.name != "nt" or os.environ.get("CI_GRAPHICS_AGENT") != "1":
        raise RuntimeError("Real GPU benchmark requires the reviewed native Windows graphics agent")
    if not Path(java_home, "bin", "java.exe").is_file():
        raise RuntimeError("Verified Java home is missing")
    env = os.environ.copy()
    env["JAVA_HOME"] = java_home
    env["PATH"] = str(Path(java_home) / "bin") + os.pathsep + env.get("PATH", "")
    env["JAVA_TOOL_OPTIONS"] = (java_tool_options if java_tool_options is not None else
                                graphics_java_options(gpu_profile, coverage_scissor,
                                                      frustum_crop, camera_motion,
                                                       output_scissor, history_copy_image,
                                                       postprocess_scissor, shader_tail_crop))
    with log_path.open("wb") as stream:
        return subprocess.Popen(command, cwd=work, env=env, stdout=stream,
                                stderr=subprocess.STDOUT,
                                creationflags=subprocess.CREATE_NO_WINDOW)


def stop_tree(pid: int):
    if pid:
        subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                       check=False)


def owned_gradle_server_wrapper_pids(work: Path, run_name: str) -> list[int]:
    """Find only THIS disposable run's orphaned Windows Gradle server launchers.

    On Windows a .bat Popen may terminate while its JVM children survive;
    waiting for the cmd.exe PID alone is not sufficient process cleanup.
    This query NEVER matches the user's normal Java/Minecraft processes.
    """
    root = str(work).replace("'", "''")
    tag = ("-PvisualRunName=" + run_name).replace("'", "''")
    script = (
        "$root='" + root + "';"
        "$tag='" + tag + "';"
        "Get-CimInstance Win32_Process | Where-Object {"
        "$_.Name -eq 'java.exe' -and $_.CommandLine -and "
        "$_.CommandLine.Contains('GradleWrapperMain') -and "
        "$_.CommandLine.Contains($tag) -and "
        "$_.CommandLine.IndexOf($root,[StringComparison]::OrdinalIgnoreCase) -ge 0"
        "} | Select-Object -ExpandProperty ProcessId"
    )
    found = subprocess.run(
        ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script],
        capture_output=True, text=True, timeout=35, check=False)
    if found.returncode != 0:
        raise RuntimeError("Failed to enumerate only benchmark-owned server wrappers: "
                           + found.stderr[-1500:])
    return [int(line) for line in found.stdout.splitlines() if line.strip().isdigit()]


def cleanup_owned_gradle_servers(work: Path, run_name: str):
    # The gracefully issued RCON 'stop' may leave cmd.exe/GradleWrapperMain
    # children alive. Kill ONLY the process trees tagged with this run's unique
    # Gradle -PvisualRunName property, then assert that none remain.
    stale = owned_gradle_server_wrapper_pids(work, run_name)
    for pid in stale:
        print("FPS_CLEANUP owned test wrapper PID=" + str(pid), flush=True)
        stop_tree(pid)
    remaining = owned_gradle_server_wrapper_pids(work, run_name)
    if remaining:
        raise RuntimeError("Benchmark-owned server wrappers leaked: " + str(remaining))


def wait_result(path: Path, client_process: subprocess.Popen, client_log: Path, deadline_s: int):
    until = time.monotonic() + deadline_s
    while time.monotonic() < until:
        if path.is_file():
            return json.loads(path.read_text(encoding="utf-8"))
        exit_code = client_process.poll()
        if exit_code is not None:
            log = client_log.read_text(errors="replace")[-7000:] if client_log.exists() else ""
            raise RuntimeError(f"Client exited {exit_code} before {path.name}:\n{log}")
        time.sleep(.6)
    log = client_log.read_text(errors="replace")[-7000:] if client_log.exists() else ""
    raise TimeoutError(f"Timed out waiting for {path}:\n{log}")


def validate_frame_pass_cadence(result):
    """Reject fast-but-missing reflection frames, including sparse dropouts."""
    scene = result["scenario"]
    if scene % 10 not in (7, 8, 9):
        raise AssertionError(f"Unexpected FPS scene {scene}")
    samples = result["samples"]
    intervals = result["frameIntervalsNs"]
    passes = result["passesPerFrame"]
    if samples < 30 or len(intervals) != samples or len(passes) != samples:
        raise AssertionError(f"Malformed per-frame FPS evidence: {scene}")
    expected = 0 if scene % 10 == 7 else 1
    for i, count in enumerate(passes):
        if type(count) is not int or count != expected:
            raise AssertionError(
                f"Scene {scene} frame {i} rendered {count!r} reflection passes; expected {expected}")
    if sum(passes) != result["reflectionPasses"]:
        raise AssertionError(f"Reflection total disagrees with frame evidence: {scene}")


def require_command(rcon: Rcon, command: str):
    reply = rcon.command(command)
    if any(e in reply for e in ["Unknown", "Incorrect", "too many blocks", "Too many blocks"]):
        raise RuntimeError(f"Fixture command failed: {command}: {reply}")
    return reply


def system_gpu(evidence: Path, stage: str):
    nvsmi = shutil.which("nvidia-smi")
    if not nvsmi:
        return
    p = subprocess.run([nvsmi,
                        "--query-gpu=name,driver_version,memory.used,utilization.gpu,temperature.gpu",
                        "--format=csv,noheader,nounits"],
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                       timeout=20, check=False)
    (evidence / f"gpu-{stage}.txt").write_text(p.stdout or p.stderr,
                                                encoding="utf-8")



def validate_camera_motion(first: Path, second: Path) -> dict:
    """Reject frozen reflections; both actual GPU captures precede FPS sampling."""
    from PIL import Image, ImageChops, ImageStat
    with Image.open(first) as early, Image.open(second) as late:
        a, b = early.convert("RGB"), late.convert("RGB")
    if a.size != b.size:
        raise AssertionError("Dynamic reflection capture resolution changed")
    w, h = a.size
    region = (int(w * .30), int(h * .22), int(w * .70), int(h * .72))
    delta = ImageChops.difference(a.crop(region), b.crop(region))
    average = sum(ImageStat.Stat(delta).mean) / 3
    if average <= 1.5:
        raise AssertionError(
            f"Moving-camera reflection remained static: mean pixel difference {average:.3f}")
    return {"meanAbsoluteDifference": round(average, 3), "region": list(region)}


def main():
    with exclusive_graphics_lane():
        return run_benchmark()


def run_benchmark():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", choices=["forge-1.20.1"], default="forge-1.20.1")
    parser.add_argument("--width", type=int, default=1280)
    parser.add_argument("--height", type=int, default=720)
    parser.add_argument("--shader-cache", type=Path, required=True)
    parser.add_argument("--java-home", default=r"C:\Program Files\Java\jdk-21")
    parser.add_argument("--run-name", default="fps-" + str(int(time.time())))
    parser.add_argument("--rounds", type=int, default=1, help="1-5 alternating trial rounds")
    parser.add_argument("--profile-gpu", action="store_true", help="Enable asynchronous GPU timestamp stage profiling")
    parser.add_argument("--reflection-output-scissor", action="store_true",
                        help="Experimental: constrain only native FBO fullscreen output; never production default")
    parser.add_argument("--shader-tail-crop", action="store_true",
                        help="Experimental named local postprocessing tail only; never production default")
    parser.add_argument("--postprocess-scissor", action="store_true",
                        help="Experimental native-resolution shader intermediate pass scissor; default off")
    parser.add_argument("--history-copy-image", action="store_true",
                        help="Experimental: direct GL 4.3 shader-history image copies; never production default")
    parser.add_argument("--rcon-timeout", type=int, default=5,
                        help="RCON response timeout in seconds for resource-contended test hosts")
    parser.add_argument("--startup-timeout", type=int, default=600,
                        help="Bounded server/client startup timeout in seconds (default 600)")
    parser.add_argument("--disable-coverage-scissor", action="store_true", help="Control run with coverage optimization disabled")
    parser.add_argument("--enable-frustum-crop", action="store_true",
                        help="Opt in to experimental reflection-section culling; default is off")
    parser.add_argument("--moving-camera", action="store_true",
                        help="Deterministic camera pan/translation, with two warmup image checks per mirror scene")
    parser.add_argument("--render-distance", type=int, choices=[4, 8, 12], default=4,
                        help="Native benchmark viewport distance; use 12 for realistic 1080p")
    args = parser.parse_args()
    if not 5 <= args.rcon_timeout <= 120:
        parser.error("--rcon-timeout must be in [5, 120]")
    if not 120 <= args.startup_timeout <= 1800:
        parser.error("--startup-timeout must be in [120, 1800]")
    if not 1 <= args.rounds <= 5:
        parser.error("--rounds must be in [1, 5]")
    if not (640 <= args.width <= 2560 and 360 <= args.height <= 1440):
        parser.error("Unsupported benchmark viewport")
    if not __import__("re").fullmatch(r"fps-[a-z0-9-]+", args.run_name):
        parser.error("Benchmark run name must start with fps- and be alphanumeric/hyphen")
    root = ROOT
    module = root / args.target
    client = module / "run" / f"{args.run_name}-client"
    server_dir = module / "run" / f"{args.run_name}-server"
    evidence = module / "run" / f"{args.run_name}-evidence"
    for path in (client, server_dir, evidence):
        path.mkdir(parents=True, exist_ok=False)
    if not (root / "gradlew.bat").is_file():
        raise RuntimeError("Run from an isolated Simply Mirrors checkout")
    server = None
    client_process = None
    rcon = None
    success = False
    try:
        _, pack = prepare(args.target, args.shader_cache.resolve())
        install_pack(client, pack, loader="forge")
        (client / "options.txt").write_text(
            f"renderDistance:{args.render_distance}\nmaxFps:260\nenableVsync:false\n"
            "pauseOnLostFocus:false\nfullscreen:false\n"
            "graphicsMode:0\n", encoding="utf-8")
        port = free_port()
        rcon_port = free_port()
        (server_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
        properties = {
            "server-ip": "127.0.0.1",
            "server-port": port,
            "enable-rcon": "true",
            "rcon.port": rcon_port,
            "rcon.password": "simply-mirrors-disposable-test",
            "online-mode": "false",
            "gamemode": "creative",
            "force-gamemode": "true",
            "allow-flight": "true",
            "spawn-protection": 0,
            "view-distance": args.render_distance,
            "simulation-distance": args.render_distance,
            "level-name": "fps-visual-world",
            "level-type": "minecraft:flat",
            "max-players": 2,
            "max-tick-time": -1,
        }
        (server_dir / "server.properties").write_text(
            "".join(f"{key}={value}\n" for key, value in properties.items()),
            encoding="utf-8")
        scene_file(client, -1)
        options = [
            f"-Ptarget={args.target}",
            "-PvisualTest",
            "-PvisualMc=1.20.1",
            "-PvisualLoader=forge",
            f"-PvisualPort={port}",
            f"-PvisualRunName={args.run_name}",
            f"-PvisualWidth={args.width}",
            f"-PvisualHeight={args.height}",
            "--no-daemon", "--console=plain", "--max-workers=2",
            "-Dorg.gradle.jvmargs=-Xmx1024m",
        ]
        gradle = str(root / "gradlew.bat")
        base = [gradle] + options
        server_log = evidence / "server.log"
        client_log = evidence / "client.log"
        env = os.environ.copy()
        env["JAVA_HOME"] = args.java_home
        env["GRADLE_USER_HOME"] = r"D:\DevCaches\.gradle"
        env["PATH"] = str(Path(args.java_home) / "bin") + os.pathsep + env.get("PATH", "")
        with server_log.open("w", encoding="utf-8") as stream:
            server = subprocess.Popen(
                base + ["-PmirrorsShaderStack=off", ":forge-1.20.1:runVisualServer"],
                cwd=root, stdout=stream, stderr=subprocess.STDOUT,
                creationflags=subprocess.CREATE_NO_WINDOW, env=env)
        print("FPS_STAGE server starting", flush=True)
        until = time.monotonic() + args.startup_timeout
        while time.monotonic() < until:
            if server.poll() is not None:
                raise RuntimeError("Server exited:\n" + server_log.read_text(errors="replace")[-9000:])
            try:
                rcon = Rcon(rcon_port)
                rcon.sock.settimeout(args.rcon_timeout)
                break
            except (OSError, ConnectionError):
                time.sleep(.7)
        if rcon is None:
            raise TimeoutError("Dedicated server did not start:\n" +
                               server_log.read_text(errors="replace")[-6000:])
        for command in [
                "gamerule doDaylightCycle false", "gamerule doWeatherCycle false",
                "time set noon", "weather clear", "setworldspawn 0 65 -4",
                "forceload add -16 -16 16 16",
                "fill -12 53 -12 12 53 12 stone",
        ]:
            require_command(rcon, command)
        for command in fixture(0, enclosed=True):
            if "simply_mirrors:mirror" in command:
                command = command.replace("simply_mirrors:mirror[facing=north]",
                                          "white_concrete")
            require_command(rcon, command)
        system_gpu(evidence, "before")
        client_command = base + ["-PmirrorsShaderStack=" + str(args.shader_cache.resolve()),
                                 ":forge-1.20.1:runVisualClient"]
        # Only a fresh initial login disconnection may be retried, once.
        # Never retry shader validation, missing reflections or a bad capture.
        joined = False
        for attempt in range(2):
            client_process = start_private_graphics_client(
                client_command, root, client_log, args.java_home, args.profile_gpu,
                not args.disable_coverage_scissor, frustum_crop=args.enable_frustum_crop,
                camera_motion=args.moving_camera,
                output_scissor=args.reflection_output_scissor,
                history_copy_image=args.history_copy_image,
                postprocess_scissor=args.postprocess_scissor,
                shader_tail_crop=args.shader_tail_crop)
            print("FPS_STAGE client launched private-GPU attempt=" + str(attempt + 1) +
                  " PID=" + str(client_process.pid), flush=True)
            deadline = time.monotonic() + args.startup_timeout
            disconnected = False
            while time.monotonic() < deadline:
                if client_process.poll() is not None:
                    raise RuntimeError("Client exited before joining server:\n" +
                                       client_log.read_text(errors="replace")[-8000:])
                response = rcon.command("tp @a 0.5 65 -3.5 0 0")
                if "Teleported" in response:
                    joined = True
                    break
                if disconnected_during_login(client_log):
                    print("FPS_STAGE login disconnected attempt=" + str(attempt + 1), flush=True)
                    (evidence / ("login-disconnect-attempt-" + str(attempt + 1) + ".log")).write_bytes(
                        client_log.read_bytes()[-32768:])
                    stop_tree(client_process.pid)
                    try:
                        client_process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        raise RuntimeError("Benchmark-owned disconnected client did not stop")
                    client_process = None
                    disconnected = True
                    break
                time.sleep(1)
            if joined:
                break
            if not disconnected:
                raise TimeoutError("Minecraft graphical client did not join the local server")
            if attempt == 1:
                raise RuntimeError("Both isolated Minecraft clients disconnected during initial login")
            scene_file(client, -1)
        if not joined:
            raise RuntimeError("No verified initial benchmark login")
        results = []
        for round_index in range(args.rounds):
            order = CASES if round_index % 2 == 0 else list(reversed(CASES))
            for kind, label in order:
                number = round_index * 10 + kind
                # Restore an equivalent white surface, then install this trial's mirror
                # topology. The camera is constant and the shader never reloads.
                for x in range(-1, 2):
                    for y in range(65, 68):
                        require_command(rcon, f"setblock {x} {y} 0 white_concrete")
                if kind == 8:
                    require_command(rcon, "setblock 0 66 0 simply_mirrors:mirror[facing=north]")
                elif kind == 9:
                    for x in range(-1, 2):
                        for y in range(65, 68):
                            require_command(rcon, f"setblock {x} {y} 0 simply_mirrors:mirror[facing=north]")
                scene_file(client, number)
                print(f"FPS_STAGE round={round_index} measuring {label}", flush=True)
                result = wait_result(client / "fps-evidence" / f"scene-{number}.json",
                                     client_process, client_log, 240)
                validate_frame_pass_cadence(result)
                if not result["shaderActive"] or result["windowIconified"] or result["glError"] != 0:
                    raise RuntimeError("Invalid game/shader/GL measurement " + str(result))
                if "NVIDIA" not in result["glVendor"]:
                    raise RuntimeError("Hardware GPU not confirmed: " + result["glVendor"])
                result["label"] = label
                result["round"] = round_index
                results.append(result)
                system_gpu(evidence, f"{label}-round-{round_index}")
                print(f"FPS_RESULT round={round_index} {label} avgFPS={result['averageFps']:.2f} "
                      f"p95ms={result['p95FrameMs']:.2f} passes={result['reflectionPasses']}",
                      flush=True)
        scene_file(client, 6)
        until = time.monotonic() + 90
        while time.monotonic() < until and client_process.poll() is None:
            time.sleep(.5)
        if client_process.poll() != 0:
            raise RuntimeError("Client did not exit cleanly after measurement")
        # A/B comparisons use same-round baselines, not a single cold first trial.
        # Validate the actual framebuffer contents, not just completed render passes.
        from PIL import Image, ImageChops
        from visual_smoke import image_check
        checks = {}
        for round_index in range(args.rounds):
            base_image = client / "fps-evidence" / f"scene-{round_index*10+7}.png"
            one_image = client / "fps-evidence" / f"scene-{round_index*10+8}.png"
            nine_image = client / "fps-evidence" / f"scene-{round_index*10+9}.png"
            nine_colors = image_check(nine_image, shaders=True)
            with Image.open(base_image) as original, Image.open(one_image) as reflection:
                a = original.convert("RGB")
                b = reflection.convert("RGB")
                if a.size != b.size:
                    raise AssertionError("Single-mirror image resolution changed")
                region = (int(a.width*.35), int(a.height*.25),
                          int(a.width*.65), int(a.height*.75))
                difference = ImageChops.difference(a.crop(region), b.crop(region))
                changed = sum(max(pixel) > 20 for pixel in flattened_pixels(difference))
                if changed < 1500:
                    raise AssertionError("One mirror did not change enough actual framebuffer pixels: " + str(changed))
            checks[str(round_index)] = {"singleMirrorChangedPixels": changed,
                                        "nineMirrorColorMarkers": nine_colors}
        if args.moving_camera:
            for round_index in range(args.rounds):
                checks[str(round_index)]["cameraMotion"] = {
                    label: validate_camera_motion(
                        client / "fps-evidence" / f"scene-{round_index*10+kind}-motion-a.png",
                        client / "fps-evidence" / f"scene-{round_index*10+kind}-motion-b.png")
                    for kind, label in CASES if kind != 7
                }
        rounds = []
        by_label = {label: [] for _,label in CASES}
        for round_index in range(args.rounds):
            members = {r["label"]: r for r in results if r["round"] == round_index}
            baseline = members["no-mirror"]["averageFps"]
            deltas = {label: round(100 * (members[label]["averageFps"] / baseline - 1), 2)
                      for _, label in CASES}
            rounds.append({"round": round_index, "fpsChangePct": deltas})
            for _, label in CASES:
                by_label[label].append(members[label])
        comparison = {
            label: {
                "medianAvgFps": round(statistics.median(r["averageFps"] for r in trials), 3),
                "medianP95FrameMs": round(statistics.median(r["p95FrameMs"] for r in trials), 3),
                "medianP99FrameMs": round(statistics.median(
                    sorted(r["frameIntervalsNs"])[max(0, (len(r["frameIntervalsNs"]) * 99 // 100) - 1)]
                    / 1_000_000 for r in trials), 3),
                "fpsChangePctVsPairedBaseline": round(statistics.median(
                    rr["fpsChangePct"][label] for rr in rounds), 2),
                "trialAvgFps": [r["averageFps"] for r in trials],
                "samples": [r["samples"] for r in trials],
                "reflectionPasses": [r["reflectionPasses"] for r in trials],
            } for label, trials in by_label.items()
        }
        summary = {
            "minecraft": "1.20.1",
            "loader": "Forge",
            "shader": pack.name,
            "shaderSha256": hashlib.sha256(pack.read_bytes()).hexdigest(),
            "modBranch": subprocess.check_output(["git", "rev-parse", "HEAD"],
                                                   cwd=root, text=True).strip(),
            "resolution": f"{args.width}x{args.height}",
            "renderDistance": args.render_distance,
            "coverageScissorEnabled": not args.disable_coverage_scissor,
            "frustumCropEnabled": args.enable_frustum_crop,
            "movingCameraEnabled": args.moving_camera,
            "reflectionOutputScissorEnabled": args.reflection_output_scissor,
            "historyCopyImageEnabled": args.history_copy_image,
            "postProcessScissorEnabled": args.postprocess_scissor,
            "shaderTailCropEnabled": args.shader_tail_crop,
            "stages": comparison,
            "rounds": rounds,
            "imageChecks": checks,
            "rawData": [str((client / "fps-evidence" / f"scene-{r['scenario']}.json").relative_to(root))
                        for r in results],
            "capturePaths": [str((client / "fps-evidence" / f"scene-{r['scenario']}.png").relative_to(root))
                             for r in results],
        }
        (evidence / "summary.json").write_text(json.dumps(summary, indent=2) + "\n",
                                                encoding="utf-8")
        success = True
    except BaseException as failure:
        (evidence / "failure.txt").write_text(repr(failure) + "\n", encoding="utf-8")
        print("SIMPLY_MIRRORS_REAL_GPU_FPS_FAIL " + repr(failure), flush=True)
        raise
    finally:
        if rcon is not None:
            try:
                rcon.command("stop")
            except (OSError, RuntimeError):
                pass
            rcon.close()
        if server is not None:
            try:
                server.wait(timeout=35)
            except subprocess.TimeoutExpired:
                server.kill()
        if client_process is not None and client_process.poll() is None:
            stop_tree(client_process.pid)
            try:
                client_process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                raise RuntimeError("Benchmark-owned client process did not stop")
        if not success:
            system_gpu(evidence, "failed")
        cleanup_owned_gradle_servers(root, args.run_name)
        if success:
            print("SIMPLY_MIRRORS_REAL_GPU_FPS_PASS " + json.dumps(comparison), flush=True)

if __name__ == "__main__":
    main()
