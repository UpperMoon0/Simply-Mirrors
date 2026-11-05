#!/usr/bin/env python3
"""Real shader regression: compare on/off reflection scissor with scaled Iris buffers.

Exercises pinned Complementary r5.9.3 with colortex1/7 at 50% resolution on
an isolated Forge 1.20.1 server+client. An image test, not a mock GL test.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from shader_stack import prepare


def make_scaled_pack(original: Path, destination: Path) -> dict:
    """Patch only two known render-target declarations in a pinned fixture copy."""
    destination.parent.mkdir(parents=True, exist_ok=True)
    replacements = {f"size.buffer.colortex{i} = REFLECTION_RES REFLECTION_RES":
                    f"size.buffer.colortex{i} = 0.5 0.5" for i in (1, 7)}
    seen = {key: 0 for key in replacements}
    with zipfile.ZipFile(original) as source, zipfile.ZipFile(destination, "w") as output:
        for entry in source.infolist():
            data = source.read(entry)
            if entry.filename == "shaders/shaders.properties":
                settings = data.decode("utf-8")
                for old, new in replacements.items():
                    seen[old] = settings.count(old)
                    if seen[old] != 1:
                        raise ValueError(f"Unexpected shader declaration count {seen[old]} for {old}")
                    settings = settings.replace(old, new)
                data = settings.encode("utf-8")
            output.writestr(entry, data)
    if not all(count == 1 for count in seen.values()):
        destination.unlink(missing_ok=True)
        raise ValueError("Pinned Complementary pack lacks expected scale declarations")
    return {
        "source_sha256": hashlib.sha256(original.read_bytes()).hexdigest(),
        "scaled_sha256": hashlib.sha256(destination.read_bytes()).hexdigest(),
        "replacements": replacements,
    }


def compare_images(first: Path, second: Path) -> dict:
    from PIL import Image, ImageChops, ImageStat
    with Image.open(first) as image_a, Image.open(second) as image_b:
        a, b = image_a.convert("RGB"), image_b.convert("RGB")
    if a.size != b.size:
        raise AssertionError(f"Different shader captures: {a.size} vs {b.size}")
    w, h = a.size
    box = (int(w * .30), int(h * .22), int(w * .70), int(h * .75))
    difference = ImageChops.difference(a.crop(box), b.crop(box))
    mean_error = sum(ImageStat.Stat(difference).mean) / 3
    # Complementary TAA and lighting can differ slightly on sequential runs.
    # A large mismatch indicates missing/cropped output on a scaled shader FBO.
    if mean_error >= 25:
        raise AssertionError(f"Scaled shader scissor on/off capture mismatch: MAD={mean_error:.2f} at {box}")
    return {"mean_abs_error": round(mean_error, 3), "roi": box}


def run_acceptance(target: str, cache: Path, pack: Path, prefix: str, interactive: bool) -> dict:
    results = {}
    for enabled in (True, False):
        mode = "on" if enabled else "off"
        run_name = f"{prefix}-{mode}"
        env = dict(os.environ)
        # JVM properties are local to the disposable client and server subprocesses.
        env["JAVA_TOOL_OPTIONS"] = (env.get("JAVA_TOOL_OPTIONS", "") +
            f" -Dsimplymirrors.coverageScissor={str(enabled).lower()}").strip()
        command = [sys.executable, str(ROOT / "tools" / "visual_smoke.py"),
                   "--target", target, "--shaders", "--enclosed",
                   "--run-name", run_name, "--shader-pack", str(pack),
                   "--shader-cache", str(cache)]
        if interactive:
            command.append("--interactive-offscreen")
        subprocess.run(command, cwd=ROOT, env=env, check=True)
        evidence = ROOT / target / "run" / f"{run_name}-evidence" / "verified.json"
        if not evidence.exists():
            raise AssertionError(f"No verified screenshot evidence for {mode}: {evidence}")
        faces = json.loads(evidence.read_text())
        if set(faces) != {"north", "south", "east", "west", "up", "down"}:
            raise AssertionError(f"Missing shader reflection faces for {mode}: {sorted(faces)}")
        if not all(face.get("shaderPackActive") and face.get("passes", 0) >= 120 and
                   face.get("glError") == 0 for face in faces.values()):
            raise AssertionError(f"Invalid actual shader passes for {mode}: {faces}")
        results[mode] = {"run_name": run_name, "faces": faces}
    comparisons = {}
    for index, name in enumerate(("north", "south", "west", "east", "up", "down")):
        def screenshot(mode):
            return ROOT / target / "run" / f"{prefix}-{mode}-client" / "visual-evidence" / f"mirror-{index}.png"
        comparisons[name] = compare_images(screenshot("on"), screenshot("off"))
    return {"runs": results, "image_comparison": comparisons}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", choices=("forge-1.20.1",), default="forge-1.20.1")
    parser.add_argument("--shader-cache", type=Path, default=ROOT / "build/shader-dependencies")
    parser.add_argument("--run-prefix", default="scaled-coverage")
    parser.add_argument("--interactive-offscreen", action="store_true")
    args = parser.parse_args()
    import re
    if not re.fullmatch(r"[a-z0-9-]+", args.run_prefix):
        parser.error("run prefix must be lower-case letters, digits or hyphens")
    cache = args.shader_cache.resolve()
    _, original = prepare(args.target, cache)
    pack = (ROOT / "build" / "scaled-shader-fixture" / (original.stem + "-scaled50.zip")).resolve()
    fixture = make_scaled_pack(original, pack)
    report = {"shader": original.name, "fixture": fixture,
              **run_acceptance(args.target, cache, pack, args.run_prefix, args.interactive_offscreen)}
    output = ROOT / args.target / "run" / f"{args.run_prefix}-comparison.json"
    output.write_text(json.dumps(report, indent=2) + "\n")
    print(f"SIMPLY_MIRRORS_SCALED_SHADER_PASS {output}", flush=True)


if __name__ == "__main__":
    main()
