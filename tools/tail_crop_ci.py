"""Single-use native graphics acceptance for experimental shader-tail cropping.

Only launch from the reviewed graphics Jenkins agent. Benchmark all pairs on its
same private GPU, sequentially, without touching the player's Minecraft client.
A missing optimized draw, invalid image, or nonrepeatable FPS gain is a failure.
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

from PIL import Image, ImageChops, ImageStat

ROOT = Path(__file__).resolve().parents[1]
TARGET = "forge-1.20.1"
RUNS = ROOT / TARGET / "run"
SCENES = ((8, "one-mirror"), (9, "nine-coplanar-mirrors"))


class UnstableBenchmarkError(AssertionError):
    """Environmental drift invalidates a pair; it is not performance evidence."""


def fps_values(summary: dict) -> dict:
    return {name: stage["medianAvgFps"] for name, stage in summary["stages"].items()}


def assert_stable_pair(control: dict, trial: dict, accepted: list[dict]) -> float:
    """Reject noise that could manufacture an apparent mirror optimization gain.

    Compare every scene with the first accepted pair, not just the no-mirror
    stage: one contended nine-mirror control once halved its measured FPS.
    Image quality and the no-mirror 4.5% gate are unchanged.
    """
    control_fps, trial_fps = fps_values(control), fps_values(trial)
    neutral = 100 * (trial_fps["no-mirror"] / control_fps["no-mirror"] - 1)
    if abs(neutral) > 4.5:
        raise UnstableBenchmarkError(f"No-mirror A/B drift {neutral:.3f}% exceeds 4.5%")
    if accepted:
        reference = accepted[0]
        for variant, current in (("control", control_fps), ("trial", trial_fps)):
            for scene in ("no-mirror", "one-mirror", "nine-coplanar-mirrors"):
                original = reference[variant][scene]
                drift = 100 * (current[scene] / original - 1)
                if abs(drift) > 8.0:
                    raise UnstableBenchmarkError(
                        f"{variant} {scene} drift {drift:.3f}% exceeds 8% "
                        "against the first accepted pair")
    return round(neutral, 3)


def compare_capture(control: Path, trial: Path, scene: int) -> dict:
    """Pixel and local-window quality; calibrated against actual 512/256 captures."""
    with Image.open(control) as source, Image.open(trial) as candidate:
        if source.size != candidate.size:
            raise AssertionError("Experimental mirror resolution changed")
        a, b = source.convert("RGB"), candidate.convert("RGB")
    w, h = a.size
    if w < 1280 or h < 720:
        raise AssertionError("Not an accepted native 1080p capture")
    bounds = ((int(.30*w), int(.20*h), int(.70*w), int(.80*h))
              if scene == 8 else
              (int(.20*w), int(.10*h), int(.80*w), int(.90*h)))
    diff = ImageChops.difference(a.crop(bounds), b.crop(bounds))
    mad = sum(ImageStat.Stat(diff).mean) / 3
    max_tile = 0.
    tile = 32
    step = 16
    tw, th = diff.size
    for y in range(0, th - tile + 1, step):
        for x in range(0, tw - tile + 1, step):
            error = sum(ImageStat.Stat(diff.crop((x, y, x + tile, y + tile))).mean) / 3
            max_tile = max(max_tile, error)
    # Control-vs-safe crop measured ~0.3-0.4 MAD, 1.6-2.5 tile peak.
    # The rejected 256px variant measured 1.30 MAD, 9.2 tile peak.
    if mad >= 1.0 or max_tile >= 5.0:
        raise AssertionError(
            f"Shader-tail crop damages scene {scene}: MAD={mad:.3f}, "
            f"peak32={max_tile:.3f}, roi={bounds}")
    return {"meanRgbError": round(mad, 4),
            "peak32RgbError": round(max_tile, 4), "roi": list(bounds)}


def benchmark_command(label: str, name: str) -> list[str]:
    command = [
        sys.executable, "tools/fps_benchmark.py", "--target", TARGET,
        "--width", "1920", "--height", "1080", "--render-distance", "12",
        "--rounds", "1", "--profile-gpu", "--moving-camera",
        "--startup-timeout", "1800", "--rcon-timeout", "90",
        "--shader-cache", str((ROOT / "build/shader-dependencies").resolve()),
        "--run-name", name,
    ]
    if label == "trial":
        # Keep the final shader fully rasterized: native image A/B rejected the
        # origin-preserving final crop (peak 32px RGB error 7.642 > 5.0).
        # Direct GPU image copies avoid per-mip FBO blits and attachment
        # churn while preserving the exact history pixels. The flag is
        # experimental and has an OpenGL 4.3 runtime capability fallback.
        # Its effect must be measured, not assumed.
        command.extend(("--shader-tail-crop", "--history-copy-image"))
    return command


def run_variant(label: str, index: int, stamp: str, attempt: int = 0) -> tuple[dict, Path, Path]:
    # Use a fresh directory for every retry, preserving failed-run evidence.
    suffix = f"-retry{attempt}" if attempt else ""
    name = f"fps-tail-{stamp}-{label}-{index}{suffix}"
    command = benchmark_command(label, name)
    print("SIMPLY_MIRRORS_TAIL_ACCEPTANCE_START", label, index, name, flush=True)
    result = subprocess.run(command, cwd=ROOT, check=False)
    if result.returncode:
        raise RuntimeError(f"Native shader-tail {label} round {index} returned {result.returncode}")
    summary_file = RUNS / f"{name}-evidence" / "summary.json"
    summary = json.loads(summary_file.read_text(encoding="utf-8"))
    if (summary.get("shaderTailCropEnabled") != (label == "trial") or
        summary.get("historyCopyImageEnabled") != (label == "trial") or
        summary.get("resolution") != "1920x1080" or
        summary.get("renderDistance") != 12 or
        not summary.get("movingCameraEnabled")):
        raise AssertionError(f"Unexpected native benchmark settings: {summary_file}")
    client_log = RUNS / f"{name}-evidence" / "client.log"
    if label == "trial":
        logs = client_log.read_text(encoding="utf-8", errors="replace")
        counts = [int(x) for x in re.findall(
            r"SIMPLY_MIRRORS_SHADER_CROP[^\n]*quadsClipped=(\d+)", logs)]
        if not counts or max(counts) <= 0:
            raise AssertionError("Experimental shader-tail crop never clipped a named pass")
    print("SIMPLY_MIRRORS_TAIL_ACCEPTANCE_RUN", label, index,
          json.dumps({k: v["medianAvgFps"] for k, v in summary["stages"].items()}),
          flush=True)
    return summary, RUNS / f"{name}-client" / "fps-evidence", summary_file


def accept() -> dict:
    if os.name != "nt" or os.environ.get("CI_GRAPHICS_AGENT") != "1":
        raise RuntimeError("Only the reviewed Windows native GPU graphics agent can run acceptance")
    stamp = uuid.uuid4().hex[:8]
    all_pairs = []
    for index in range(3):
        # Retry a complete A/B pair once after environmental drift only.
        # A failed image, shader or GL check is never retried or accepted.
        for attempt in range(2):
            by_label = {}
            order = ("control", "trial") if index != 1 else ("trial", "control")
            for label in order:
                by_label[label] = run_variant(label, index, stamp, attempt)
            control, control_images, _ = by_label["control"]
            trial, trial_images, _ = by_label["trial"]
            deltas = {}
            images = {}
            for scene, label in SCENES:
                baseline_fps = control["stages"][label]["medianAvgFps"]
                candidate_fps = trial["stages"][label]["medianAvgFps"]
                deltas[label] = round(100 * (candidate_fps / baseline_fps - 1), 3)
                images[label] = compare_capture(
                    control_images / f"scene-{scene}.png",
                    trial_images / f"scene-{scene}.png", scene)
            try:
                neutral = assert_stable_pair(control, trial, all_pairs)
            except UnstableBenchmarkError as unstable:
                print("SIMPLY_MIRRORS_TAIL_ACCEPTANCE_UNSTABLE", json.dumps({
                    "pair": index, "attempt": attempt, "reason": str(unstable),
                    "control": fps_values(control), "trial": fps_values(trial),
                    "retry": attempt == 0,
                }), flush=True)
                if attempt:
                    raise
                continue
            all_pairs.append({
                "pair": index, "attempt": attempt,
                "fpsChangePct": deltas, "pixelComparisons": images,
                "neutralFpsDeltaPct": neutral,
                "control": fps_values(control), "trial": fps_values(trial),
            })
            print("SIMPLY_MIRRORS_TAIL_ACCEPTANCE_PAIR",
                  json.dumps(all_pairs[-1]), flush=True)
            break
    one = statistics.median(p["fpsChangePct"]["one-mirror"] for p in all_pairs)
    nine = statistics.median(p["fpsChangePct"]["nine-coplanar-mirrors"] for p in all_pairs)
    result = {"pairs": all_pairs,
              "medianOneMirrorImprovementPct": round(one, 3),
              "medianNineMirrorImprovementPct": round(nine, 3)}
    path = ROOT / TARGET / "run" / f"fps-tail-{stamp}-acceptance-evidence" / "summary.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    # Require meaningful repeatable improvement; one lucky run is not proof.
    if one < 3.0 or nine < -2.0:
        raise AssertionError(f"Insufficient native FPS improvement: {result}")
    print("SIMPLY_MIRRORS_TAIL_ACCEPTANCE_PASS", json.dumps(result), flush=True)
    return result


if __name__ == "__main__":
    accept()
