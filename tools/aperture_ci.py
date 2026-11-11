"""Native A/B acceptance for conservative mirror-aperture section culling.

Both arms use the accepted shader-tail and GL4.3 history-copy options.
Only the aperture-culling JVM property differs. All testing runs through the
reviewed private graphics worker, never the user's normal Minecraft client.
"""
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
        raise ValueError("Expected a control or trial GPU benchmark")
    result = [
        sys.executable, "tools/fps_benchmark.py", "--target", TARGET,
        "--width", "1920", "--height", "1080", "--render-distance", "12",
        "--rounds", "1", "--profile-gpu", "--moving-camera",
        "--startup-timeout", "1800", "--rcon-timeout", "90",
        "--shader-cache", str((ROOT / "build/shader-dependencies").resolve()),
        "--run-name", name, "--shader-tail-crop", "--history-copy-image",
    ]
    if label == "trial":
        result.append("--aperture-cull")
    return result

def verify_settings(summary: dict, label: str) -> None:
    expected = label == "trial"
    required = {
        "shaderTailCropEnabled": True,
        "historyCopyImageEnabled": True,
        "shaderFinalOriginCropEnabled": False,
        "shaderEarlyCompositeCropEnabled": False,
        "shaderFinalDualCropEnabled": False,
        "frustumCropEnabled": False,
        "apertureCullEnabled": expected,
        "resolution": "1920x1080",
        "renderDistance": 12,
        "movingCameraEnabled": True,
    }
    mismatched = {key: (summary.get(key), value)
                  for key, value in required.items()
                  if summary.get(key) != value}
    if mismatched:
        raise AssertionError(f"Incorrect aperture A/B settings for {label}: {mismatched}")

def aperture_telemetry(logs: str) -> dict[str, int]:
    matches = re.findall(
        r"SIMPLY_MIRRORS_APERTURE_CULL sectionsChecked=(\d+)\s+sectionsCulled=(\d+)", logs)
    checks = sum(int(checked) for checked, _ in matches)
    removed = sum(int(culled) for _, culled in matches)
    if checks == 0 or removed == 0 or removed > checks:
        raise AssertionError(
            "Aperture trial lacks proof of active section rejection: "
            f"tested={checks} sectionsCulled={removed}")
    return {"sectionsChecked": checks, "sectionsCulled": removed}

def run_variant(label: str, index: int, stamp: str,
                attempt: int = 0) -> tuple[dict, Path, Path]:
    suffix = f"-retry{attempt}" if attempt else ""
    name = f"fps-aperture-{stamp}-{label}-{index}{suffix}"
    print("SIMPLY_MIRRORS_APERTURE_START", label, index, name, flush=True)
    result = subprocess.run(benchmark_command(label, name), cwd=ROOT, check=False)
    if result.returncode:
        raise RuntimeError(f"Native aperture {label} run {index} returned {result.returncode}")

    evidence = RUNS / f"{name}-evidence"
    summary_file = evidence / "summary.json"
    summary = json.loads(summary_file.read_text(encoding="utf-8"))
    verify_settings(summary, label)
    logs = (evidence / "client.log").read_text(encoding="utf-8", errors="replace")
    summary["gpuProfilerMedians"] = profile_medians(logs)
    if not {"gpuCaptureMs", "gpuWorldMs", "gpuRestoreMs",
            "gpuFinalizeMs"}.issubset(summary["gpuProfilerMedians"]):
        raise AssertionError("Native GPU-stage timestamp data missing")
    if label == "trial":
        summary["apertureTelemetry"] = aperture_telemetry(logs)
    print("SIMPLY_MIRRORS_APERTURE_RUN", label, index,
          json.dumps({key: value["medianAvgFps"] for key, value
                      in summary["stages"].items()}), flush=True)
    return summary, RUNS / f"{name}-client" / "fps-evidence", summary_file

def accept() -> dict:
    if os.name != "nt" or os.environ.get("CI_GRAPHICS_AGENT") != "1":
        raise RuntimeError("Native aperture acceptance requires the trusted Windows GPU agent")

    stamp = uuid.uuid4().hex[:8]
    pairs = []
    for index in range(3):
        for attempt in range(2):
            by_label = {}
            order = ("control", "trial") if index != 1 else ("trial", "control")
            for label in order:
                by_label[label] = run_variant(label, index, stamp, attempt)
            control, control_images, _ = by_label["control"]
            trial, trial_images, _ = by_label["trial"]
            try:
                neutral = assert_stable_pair(control, trial, pairs)
            except UnstableBenchmarkError as unstable:
                print("SIMPLY_MIRRORS_APERTURE_UNSTABLE",
                      json.dumps({"pair": index, "attempt": attempt,
                                  "reason": str(unstable), "retry": attempt == 0}),
                      flush=True)
                if attempt:
                    raise
                continue

            images = {label: compare_capture(
                control_images / f"scene-{scene}.png",
                trial_images / f"scene-{scene}.png", scene)
                for scene, label in SCENES}
            changes = {
                label: round(100 * (
                    trial["stages"][label]["medianAvgFps"]
                    / control["stages"][label]["medianAvgFps"] - 1), 3)
                for _, label in SCENES}
            pairs.append({
                "pair": index, "attempt": attempt,
                "control": fps_values(control), "trial": fps_values(trial),
                "neutralFpsDeltaPct": neutral,
                "fpsChangePct": changes, "pixelComparisons": images,
                "aperture": trial["apertureTelemetry"],
                "stagesMs": {
                    "control": control["gpuProfilerMedians"],
                    "trial": trial["gpuProfilerMedians"],
                },
            })
            print("SIMPLY_MIRRORS_APERTURE_PAIR",
                  json.dumps(pairs[-1]), flush=True)
            break

    if len(pairs) != 3:
        raise AssertionError("Three stable GPU A/B pairs required")
    one = statistics.median(pair["fpsChangePct"]["one-mirror"] for pair in pairs)
    nine = statistics.median(pair["fpsChangePct"]["nine-coplanar-mirrors"] for pair in pairs)
    summary = {
        "pairs": pairs,
        "medianOneMirrorImprovementPct": round(one, 3),
        "medianNineMirrorImprovementPct": round(nine, 3),
    }
    path = RUNS / f"fps-aperture-{stamp}-acceptance-evidence" / "summary.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")

    if one < 3.0 or nine < -2.0:
        raise AssertionError(f"Insufficient native aperture-culling FPS gain: {summary}")
    print("SIMPLY_MIRRORS_APERTURE_ACCEPTANCE_PASS",
          json.dumps(summary), flush=True)
    # Older centralized CI checks this marker for exact-SHA native GPU status.
    print("SIMPLY_MIRRORS_TAIL_ACCEPTANCE_PASS",
          json.dumps(summary), flush=True)
    return summary

if __name__ == "__main__":
    accept()
