"""Strict native A/B for reflection blur rejection with the accepted history path."""
from __future__ import annotations

import json
import os
import re
import statistics
import shutil
import time
import subprocess
import sys
import uuid
from pathlib import Path

from tail_crop_ci import (ROOT, TARGET, RUNS, SCENES, compare_capture,
                          assert_stable_pair, UnstableBenchmarkError, fps_values,
                          profile_medians)


def benchmark_command(label: str, name: str, experiment="blur-rejection") -> list[str]:
    if label not in ("control", "trial"):
        raise ValueError("Expected control or trial")
    result = [
        sys.executable, "tools/fps_benchmark.py", "--target", TARGET,
        "--width", "1920", "--height", "1080", "--render-distance", "12",
        "--rounds", "1", "--profile-gpu", "--moving-camera", "--direct-game-launch",
        "--startup-timeout", "1800", "--rcon-timeout", "90",
        "--shader-cache", str((ROOT / "build/shader-dependencies").resolve()),
        "--run-name", name, "--shader-tail-crop", "--history-copy-image",
    ]
    # Isolate blur tap rejection: both arms retain the winning history path.
    # Rejected lazy clearing and volumetric arithmetic stay disabled.
    if experiment == "dependency-roi":
        result.remove("--shader-tail-crop")
    elif experiment != "blur-rejection":
        raise ValueError("Unknown GPU experiment")
    return result


def verify_settings(summary: dict, label: str, experiment="blur-rejection") -> None:
    expected = label == "trial"
    required = {
        "shaderTailCropEnabled": True,
        "historyCopyImageEnabled": True,
        "shaderFinalFragmentGuardEnabled": False,
        "shaderVolumetricWeightReuseEnabled": False,
        "shaderCloudOcclusionEnabled": False,
        "shaderReflectionBlurRejectionEnabled": expected,
        "historyTextureRedirectEnabled": True,
        "discardFinalHistoryEnabled": True,
        "lazyHistoryClearEnabled": False,
        "shaderVolumetricShadowRayEnabled": False,
        "shaderFinalOriginCropEnabled": False,
        "shaderEarlyCompositeCropEnabled": False,
        "shaderFinalDualCropEnabled": False,
        "frustumCropEnabled": False,
        "apertureCullEnabled": False,
        "resolution": "1920x1080",
        "renderDistance": 12,
        "movingCameraEnabled": True,
        "directGameLaunchEnabled": True,
    }
    if experiment == "dependency-roi":
        required.update(shaderTailCropEnabled=False, shaderReflectionBlurRejectionEnabled=False,
                        shaderDependencyRoiEnabled=expected, deterministicMotionParityEnabled=True,
                        postProcessScissorEnabled=False, reflectionOutputScissorEnabled=False)
    mismatch = {key: (summary.get(key), value)
                for key, value in required.items() if summary.get(key) != value}
    if mismatch:
        raise AssertionError(f"Incorrect volumetric weight A/B settings for {label}: {mismatch}")


def require_roi_activation(logs: str, label: str) -> dict:
    counts=[tuple(map(int,m)) for m in re.findall(
        r"SIMPLY_MIRRORS_DEPENDENCY_ROI draws=(\d+) pixelsSaved=(\d+)",logs)]
    profile="SIMPLY_MIRRORS_DEPENDENCY_ROI_PROFILE accepted=true" in logs
    if label=="control":
        if profile or any(draws or pixels for draws,pixels in counts):
            raise AssertionError("Control unexpectedly activated dependency ROI")
        return {"active":False}
    if not profile or not any(draws>0 and pixels>0 for draws,pixels in counts):
        raise AssertionError("Dependency ROI did not execute a verified native cropped draw")
    return {"active":True,"draws":max(x[0] for x in counts),"pixelsSaved":max(x[1] for x in counts)}


def roi_shader_timings(logs: str) -> dict:
    by_program={}
    for name,samples,median in re.findall(
            r"SIMPLY_MIRRORS_SHADER_QUAD_PROFILE program=(\S+) samples=(\d+) gpuMedianMs=([\d.]+)",logs):
        if int(samples)>0:
            by_program.setdefault(name,[]).append(float(median))
    if "final" not in by_program:
        raise AssertionError("Missing native final-pass GPU timing")
    return {name:statistics.median(values) for name,values in by_program.items()}


def compare_roi_captures(control, trial):
    return {label:{suffix or "static":compare_capture(
        control/f"scene-{scene}{suffix}.png",trial/f"scene-{scene}{suffix}.png",scene)
        for suffix in ("","-parity-motion-a","-parity-motion-b")}
        for scene,label in SCENES}


def require_weight_reuse(logs: str) -> dict:
    match = re.search(r"SIMPLY_MIRRORS_HISTORY_REDIRECT_ACTIVE textures=(\d+) units=(\d+) attachments=(\d+)", logs)
    if match is None or any(int(x) <= 0 for x in match.groups()):
        raise AssertionError("Native history texture redirection did not map samplers and cached FBOs")
    copies = re.search(r"SIMPLY_MIRRORS_DISCARD_FINAL_HISTORY copies=(\d+)", logs)
    if copies is None or int(copies[1]) <= 0:
        raise AssertionError("Native final-history copies were not discarded")
    if ("SIMPLY_MIRRORS_REFLECTION_BLUR_REJECTION program=composite1" not in logs or
            "SIMPLY_MIRRORS_REFLECTION_BLUR_REJECTION_ACTIVE" not in logs):
        raise AssertionError("Native reflection blur rejection was not applied")
    return {"textures": int(match[1]), "units": int(match[2]), "attachments": int(match[3]),
            "discardedFinalCopies": int(copies[1]), "reflectionBlurRejection": True, "applied": True}




def stage_savings_ms(control: dict, trial: dict) -> dict[str, float]:
    """Attribute GPU savings by stage; reject invalid profiler data."""
    import math
    keys = ("gpuCaptureMs", "gpuWorldMs", "gpuFinalizeMs", "gpuRestoreMs")
    result = {}
    for key in keys:
        before, after = control.get(key), trial.get(key)
        if not all(isinstance(v, (int, float)) and
                   not isinstance(v, bool) and math.isfinite(v) and v >= 0
                   for v in (before, after)):
            raise AssertionError(f"Invalid GPU profiler stage {key}: {before}, {after}")
        result[key] = round(before - after, 4)
    return result


def evaluate_gpu_acceptance(pairs: list[dict]) -> dict:
    """Require three stable pairs and independently enforce native GPU gates."""
    if len(pairs) != 3:
        raise AssertionError("Three stable control/trial pairs required")
    one = statistics.median(p["fpsChangePct"]["one-mirror"] for p in pairs)
    nine = statistics.median(p["fpsChangePct"]["nine-coplanar-mirrors"] for p in pairs)
    control_finalize = statistics.median(
        p["stagesMs"]["control"]["gpuFinalizeMs"] for p in pairs)
    trial_finalize = statistics.median(
        p["stagesMs"]["trial"]["gpuFinalizeMs"] for p in pairs)
    values = (one, nine, control_finalize, trial_finalize)
    if not all(isinstance(v, (int, float)) and
               not isinstance(v, bool) and __import__("math").isfinite(v)
               for v in values):
        raise AssertionError("Non-finite native GPU acceptance measurement")
    summary = {
        "pairs": pairs,
        "medianOneMirrorImprovementPct": round(one, 3),
        "medianNineMirrorImprovementPct": round(nine, 3),
        "medianControlGpuFinalizeMs": round(control_finalize, 3),
        "medianTrialGpuFinalizeMs": round(trial_finalize, 3),
    }
    # History redirection eliminates transfers outside gpuWorldMs. Enforce the
    # sum of the disjoint capture/world/restore timers; finalize is nested in world.
    for pair in pairs:
        stage_savings_ms(pair["stagesMs"]["control"], pair["stagesMs"]["trial"])
    summary["medianGpuWorldMs"] = {label: statistics.median(
        p["stagesMs"][label]["gpuWorldMs"] for p in pairs)
        for label in ("control", "trial")}
    totals = {label: statistics.median(
        sum(p["stagesMs"][label][key] for key in
            ("gpuCaptureMs", "gpuWorldMs", "gpuRestoreMs"))
        for p in pairs) for label in ("control", "trial")}
    summary["medianGpuCaptureWorldRestoreMs"] = totals
    if one < 3.0 or nine < -2.0 or totals["trial"] >= totals["control"]:
        raise AssertionError(f"Insufficient real native reflection improvement: {summary}")
    return summary


MAX_PAIR_ATTEMPTS = 3


def sample_host_load() -> dict:
    """Record actual host load before starting a fresh benchmark arm."""
    import psutil
    smi = shutil.which("nvidia-smi")
    if not smi:
        raise RuntimeError("Native benchmark requires NVIDIA telemetry")
    result = subprocess.run(
        [smi, "--query-gpu=utilization.gpu,memory.free,temperature.gpu,clocks.gr",
         "--format=csv,noheader,nounits"], capture_output=True, text=True,
        check=True, timeout=15)
    rows = result.stdout.strip().splitlines()
    if len(rows) != 1:
        raise RuntimeError("Expected one reviewed native GPU")
    gpu, free, temp, clock = map(int, rows[0].split(","))
    return {"gpuPct": gpu, "freeVramMiB": free, "temperatureC": temp,
            "graphicsClockMHz": clock, "cpuPct": psutil.cpu_percent(interval=1),
            "availableRamMiB": psutil.virtual_memory().available // (1024 ** 2)}


def quiet_sample(sample: dict) -> bool:
    # Admission only, not a substitute for the unchanged A/B acceptance gates.
    return (sample["gpuPct"] <= 25 and sample["cpuPct"] <= 50
            and sample["freeVramMiB"] >= 768 and sample["availableRamMiB"] >= 3072)


def wait_for_quiet_host(timeout: int = 180) -> list[dict]:
    """Require three consecutive quiet readings; never stop unrelated apps."""
    deadline = time.monotonic() + timeout
    consecutive = []
    while time.monotonic() < deadline:
        sample = sample_host_load()
        print("SIMPLY_MIRRORS_HOST_LOAD", json.dumps(sample), flush=True)
        if quiet_sample(sample):
            consecutive.append(sample)
            if len(consecutive) == 3:
                return consecutive
        else:
            consecutive.clear()
        time.sleep(5)
    raise UnstableBenchmarkError("Host did not provide a quiet GPU/CPU window")


def pair_order(index: int, attempt: int) -> tuple[str, str]:
    return ("trial", "control") if (index + attempt) % 2 == 0 else ("control", "trial")


def run_variant(label: str, index: int, stamp: str,
                attempt: int = 0, experiment="blur-rejection") -> tuple[dict, Path]:
    suffix = f"-retry{attempt}" if attempt else ""
    prefix = "dependency-roi" if experiment=="dependency-roi" else "volumetric-weight"
    name = f"fps-{prefix}-{stamp}-{label}-{index}{suffix}"
    print("SIMPLY_MIRRORS_VOLUMETRIC_WEIGHT_START", label, index, name, flush=True)
    preflight_samples = wait_for_quiet_host()
    env = os.environ.copy()
    env["SIMPLY_MIRRORS_CLOUD_OCCLUSION"] = "0"
    env["SIMPLY_MIRRORS_REFLECTION_BLUR_REJECTION"] = "1" if label == "trial" and experiment=="blur-rejection" else "0"
    env["SIMPLY_MIRRORS_DEPENDENCY_ROI"] = "1" if label=="trial" and experiment=="dependency-roi" else "0"
    env["SIMPLY_MIRRORS_DETERMINISTIC_MOTION_PARITY"] = "1" if experiment=="dependency-roi" else "0"
    env.pop("JAVA_TOOL_OPTIONS",None)
    env["SIMPLY_MIRRORS_HISTORY_REDIRECT"] = "1"
    env["SIMPLY_MIRRORS_DISCARD_FINAL_HISTORY"] = "1"
    env["SIMPLY_MIRRORS_LAZY_HISTORY_CLEAR"] = "0"
    result = subprocess.run(benchmark_command(label, name, experiment), cwd=ROOT, check=False, env=env)
    if result.returncode:
        raise RuntimeError(f"Native volumetric weight {label} {index} returned {result.returncode}")
    evidence = RUNS / f"{name}-evidence"
    summary = json.loads((evidence / "summary.json").read_text(encoding="utf-8"))
    (evidence / "host-preflight.json").write_text(json.dumps(preflight_samples, indent=2) + "\n", encoding="utf-8")
    verify_settings(summary, label, experiment)
    logs = (evidence / "client.log").read_text(encoding="utf-8", errors="replace")
    summary["gpuProfilerMedians"] = profile_medians(logs)
    if not {"gpuCaptureMs", "gpuWorldMs", "gpuFinalizeMs", "gpuRestoreMs"}.issubset(
            summary["gpuProfilerMedians"]):
        raise AssertionError("Native per-stage GPU timestamp evidence missing")
    if experiment=="dependency-roi":
        summary["dependencyRoiActivation"]=require_roi_activation(logs,label)
        summary["shaderGpuMedians"]=roi_shader_timings(logs)
        for marker in ("SIMPLY_MIRRORS_HISTORY_REDIRECT_ACTIVE","SIMPLY_MIRRORS_DISCARD_FINAL_HISTORY"):
            if marker not in logs:
                raise AssertionError(f"Missing native history isolation evidence: {marker}")
    elif label == "trial":
        summary["volumetricWeightReuse"] = require_weight_reuse(logs)
    print("SIMPLY_MIRRORS_VOLUMETRIC_WEIGHT_RUN", label, index,
          json.dumps(fps_values(summary)), flush=True)
    return summary, RUNS / f"{name}-client" / "fps-evidence"


def accept(experiment="blur-rejection") -> dict:
    if experiment not in ("blur-rejection","dependency-roi"):
        raise ValueError("Unknown GPU experiment")
    if os.name != "nt" or os.environ.get("CI_GRAPHICS_AGENT") != "1":
        raise RuntimeError("Only the trusted private native GPU CI agent can run acceptance")
    # Fail before Minecraft FPS if actual-driver original/mip/filter isolation fails.
    preflight = [str(ROOT / "gradlew.bat"), "-Ptarget=common", ":common:shaderHistoryRedirectGlTest",
                 "--no-daemon", "--max-workers=1", "-Dorg.gradle.jvmargs=-Xmx512m",
                 "-Dsimplymirrors.lazyHistoryClear=false"]
    preflight_env = os.environ.copy()
    preflight_env.pop("JAVA_TOOL_OPTIONS", None)
    subprocess.run(preflight, cwd=ROOT, env=preflight_env, check=True)
    stamp = uuid.uuid4().hex[:8]
    accepted: list[dict] = []
    for index in range(3):
        for attempt in range(MAX_PAIR_ATTEMPTS):
            variants = {}
            try:
                # Alternate both pairs and retries to avoid consistently
                # assigning the same thermal/startup order to one variant.
                for label in pair_order(index, attempt):
                    variants[label] = run_variant(label, index, stamp, attempt, experiment)
                control, control_images = variants["control"]
                trial, trial_images = variants["trial"]
                neutral = assert_stable_pair(control, trial, accepted)
            except UnstableBenchmarkError as noise:
                print("SIMPLY_MIRRORS_VOLUMETRIC_WEIGHT_UNSTABLE",
                      json.dumps({"pair": index, "attempt": attempt,
                                  "reason": str(noise), "retry": attempt + 1 < MAX_PAIR_ATTEMPTS}),
                      flush=True)
                if attempt + 1 >= MAX_PAIR_ATTEMPTS:
                    raise
                continue
            images = compare_roi_captures(control_images,trial_images) if experiment=="dependency-roi" else {label: compare_capture(
                control_images / f"scene-{scene}.png",
                trial_images / f"scene-{scene}.png", scene)
                for scene, label in SCENES}
            fps_changes = {label: round(100 * (
                trial["stages"][label]["medianAvgFps"]
                / control["stages"][label]["medianAvgFps"] - 1), 3)
                for _, label in SCENES}
            accepted.append({
                "pair": index, "attempt": attempt,
                "control": fps_values(control), "trial": fps_values(trial),
                "neutralFpsDeltaPct": neutral,
                "fpsChangePct": fps_changes, "pixelComparisons": images,
                **({"dependencyRoiActivation":trial["dependencyRoiActivation"],
                    "shaderGpuMedians":{label:variants[label][0]["shaderGpuMedians"] for label in ("control","trial")}}
                   if experiment=="dependency-roi" else {"volumetricWeightReuse":trial["volumetricWeightReuse"]}),
                "gpuStageSavingsMs": stage_savings_ms(
                    control["gpuProfilerMedians"], trial["gpuProfilerMedians"]),
                "stagesMs": {
                    "control": control["gpuProfilerMedians"],
                    "trial": trial["gpuProfilerMedians"]
                },
            })
            print("SIMPLY_MIRRORS_VOLUMETRIC_WEIGHT_PAIR",
                  json.dumps(accepted[-1]), flush=True)
            break
    summary = evaluate_gpu_acceptance(accepted)
    summary["experiment"]=experiment
    prefix="dependency-roi" if experiment=="dependency-roi" else "volumetric-weight"
    path = RUNS / f"fps-{prefix}-{stamp}-acceptance-evidence/summary.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print("SIMPLY_MIRRORS_DEPENDENCY_ROI_ACCEPTANCE_PASS" if experiment=="dependency-roi" else "SIMPLY_MIRRORS_VOLUMETRIC_WEIGHT_ACCEPTANCE_PASS",
          json.dumps(summary), flush=True)
    # Centralized CI owns this legacy compatibility marker.
    print("SIMPLY_MIRRORS_TAIL_ACCEPTANCE_PASS",
          json.dumps(summary), flush=True)
    return summary


if __name__ == "__main__":
    accept()
