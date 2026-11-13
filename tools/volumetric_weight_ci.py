"""Strict native A/B for exact volumetric fog-weight reuse; no visual settings change."""
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
        "--rounds", "1", "--profile-gpu", "--moving-camera",
        "--startup-timeout", "1800", "--rcon-timeout", "90",
        "--shader-cache", str((ROOT / "build/shader-dependencies").resolve()),
        "--run-name", name, "--shader-tail-crop", "--history-copy-image",
    ]
    if label == "trial":
        result.append("--shader-volumetric-weight-reuse")
    return result


def verify_settings(summary: dict, label: str) -> None:
    expected = label == "trial"
    required = {
        "shaderTailCropEnabled": True,
        "historyCopyImageEnabled": True,
        "shaderFinalFragmentGuardEnabled": False,
        "shaderVolumetricWeightReuseEnabled": expected,
        "shaderFinalOriginCropEnabled": False,
        "shaderEarlyCompositeCropEnabled": False,
        "shaderFinalDualCropEnabled": False,
        "frustumCropEnabled": False,
        "apertureCullEnabled": False,
        "resolution": "1920x1080",
        "renderDistance": 12,
        "movingCameraEnabled": True,
    }
    mismatch = {key: (summary.get(key), value)
                for key, value in required.items() if summary.get(key) != value}
    if mismatch:
        raise AssertionError(f"Incorrect volumetric weight A/B settings for {label}: {mismatch}")


def require_weight_reuse(logs: str) -> dict:
    if "SIMPLY_MIRRORS_VOLUMETRIC_WEIGHT_REUSE program=composite1" not in logs:
        raise AssertionError("Pinned native composite1 weight reuse did not apply")
    return {"program": "composite1", "applied": True}


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
    if one < 3.0 or nine < -2.0 or trial_finalize >= control_finalize:
        raise AssertionError(f"Insufficient real native volumetric shader improvement: {summary}")
    return summary


def run_variant(label: str, index: int, stamp: str,
                attempt: int = 0) -> tuple[dict, Path]:
    suffix = f"-retry{attempt}" if attempt else ""
    name = f"fps-volumetric-weight-{stamp}-{label}-{index}{suffix}"
    print("SIMPLY_MIRRORS_VOLUMETRIC_WEIGHT_START", label, index, name, flush=True)
    result = subprocess.run(benchmark_command(label, name), cwd=ROOT, check=False)
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
