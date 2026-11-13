"""Strict native A/B for combined history redirection and monotonic cloud ray exit."""
from __future__ import annotations

import json
import os
import re
import statistics
import subprocess
import sys
import uuid
from pathlib import Path

from tail_crop_ci import (ROOT, TARGET, RUNS, SCENES, compare_capture,
                          assert_stable_pair, UnstableBenchmarkError, fps_values,
                          profile_medians)


def benchmark_command(label: str, name: str) -> list[str]:
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
    # Isolate the new ray-loop exit: both arms retain the winning history path.
    # Rejected lazy clearing and volumetric arithmetic stay disabled.
    return result


def verify_settings(summary: dict, label: str) -> None:
    expected = label == "trial"
    required = {
        "shaderTailCropEnabled": True,
        "historyCopyImageEnabled": True,
        "shaderFinalFragmentGuardEnabled": False,
        "shaderVolumetricWeightReuseEnabled": False,
        "shaderCloudOcclusionEnabled": expected,
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
    mismatch = {key: (summary.get(key), value)
                for key, value in required.items() if summary.get(key) != value}
    if mismatch:
        raise AssertionError(f"Incorrect volumetric weight A/B settings for {label}: {mismatch}")


def require_weight_reuse(logs: str) -> dict:
    match = re.search(r"SIMPLY_MIRRORS_HISTORY_REDIRECT_ACTIVE textures=(\d+) units=(\d+) attachments=(\d+)", logs)
    if match is None or any(int(x) <= 0 for x in match.groups()):
        raise AssertionError("Native history texture redirection did not map samplers and cached FBOs")
    copies = re.search(r"SIMPLY_MIRRORS_DISCARD_FINAL_HISTORY copies=(\d+)", logs)
    if copies is None or int(copies[1]) <= 0:
        raise AssertionError("Native final-history copies were not discarded")
    if ("SIMPLY_MIRRORS_CLOUD_OCCLUSION program=deferred1" not in logs or
            "SIMPLY_MIRRORS_CLOUD_RAY_EXIT_ACTIVE" not in logs):
        raise AssertionError("Native cloud-ray loop exit was not applied")
    return {"textures": int(match[1]), "units": int(match[2]), "attachments": int(match[3]),
            "discardedFinalCopies": int(copies[1]), "cloudRayExit": True, "applied": True}




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


def run_variant(label: str, index: int, stamp: str,
                attempt: int = 0) -> tuple[dict, Path]:
    suffix = f"-retry{attempt}" if attempt else ""
    name = f"fps-volumetric-weight-{stamp}-{label}-{index}{suffix}"
    print("SIMPLY_MIRRORS_VOLUMETRIC_WEIGHT_START", label, index, name, flush=True)
    env = os.environ.copy()
    env["SIMPLY_MIRRORS_CLOUD_OCCLUSION"] = "1" if label == "trial" else "0"
    env["SIMPLY_MIRRORS_HISTORY_REDIRECT"] = "1"
    env["SIMPLY_MIRRORS_DISCARD_FINAL_HISTORY"] = "1"
    env["SIMPLY_MIRRORS_LAZY_HISTORY_CLEAR"] = "0"
    result = subprocess.run(benchmark_command(label, name), cwd=ROOT, check=False, env=env)
    if result.returncode:
        raise RuntimeError(f"Native volumetric weight {label} {index} returned {result.returncode}")
    evidence = RUNS / f"{name}-evidence"
    summary = json.loads((evidence / "summary.json").read_text(encoding="utf-8"))
    verify_settings(summary, label)
    logs = (evidence / "client.log").read_text(encoding="utf-8", errors="replace")
    summary["gpuProfilerMedians"] = profile_medians(logs)
    if not {"gpuCaptureMs", "gpuWorldMs", "gpuFinalizeMs", "gpuRestoreMs"}.issubset(
            summary["gpuProfilerMedians"]):
        raise AssertionError("Native per-stage GPU timestamp evidence missing")
    if label == "trial":
        summary["volumetricWeightReuse"] = require_weight_reuse(logs)
    print("SIMPLY_MIRRORS_VOLUMETRIC_WEIGHT_RUN", label, index,
          json.dumps(fps_values(summary)), flush=True)
    return summary, RUNS / f"{name}-client" / "fps-evidence"


def accept() -> dict:
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
        for attempt in range(2):
            variants = {}
            # Prove the opt-in shader hook first. A missing/ineffective source
            # transform fails after one trial without wasting a control launch.
            # Reverse order on the middle pair to balance warmup effects.
            for label in (("trial", "control") if index != 1 else ("control", "trial")):
                variants[label] = run_variant(label, index, stamp, attempt)
            control, control_images = variants["control"]
            trial, trial_images = variants["trial"]
            try:
                neutral = assert_stable_pair(control, trial, accepted)
            except UnstableBenchmarkError as noise:
                print("SIMPLY_MIRRORS_VOLUMETRIC_WEIGHT_UNSTABLE",
                      json.dumps({"pair": index, "attempt": attempt,
                                  "reason": str(noise), "retry": attempt == 0}),
                      flush=True)
                if attempt:
                    raise
                continue
            images = {label: compare_capture(
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
                "volumetricWeightReuse": trial["volumetricWeightReuse"],
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
    path = RUNS / f"fps-volumetric-weight-{stamp}-acceptance-evidence/summary.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print("SIMPLY_MIRRORS_VOLUMETRIC_WEIGHT_ACCEPTANCE_PASS",
          json.dumps(summary), flush=True)
    # Centralized CI owns this legacy compatibility marker.
    print("SIMPLY_MIRRORS_TAIL_ACCEPTANCE_PASS",
          json.dumps(summary), flush=True)
    return summary


if __name__ == "__main__":
    accept()
