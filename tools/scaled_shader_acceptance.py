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


def projected_fixture_roi(face_index: int, image_size: tuple[int, int],
                          fov_degrees: float, eye_override=None) -> tuple[int, int, int, int]:
    """Project the *actual* nine-block RCON mirror surface, not a central screen crop.

    Uses the same face geometry, player eye, yaw and pitch as the visual fixture.
    A small margin covers dynamic FOV, interpolation, TAA, and mirror edge strips.
    """
    import itertools
    import math
    from visual_smoke import fixture, NORMALS, POSES

    width, height = image_size
    if width < 32 or height < 32 or not (30 <= fov_degrees <= 110):
        raise ValueError("Unsupported capture dimensions or FOV")
    blocks = []
    for command in fixture(face_index):
        words = command.split()
        if words[0] == "setblock" and words[4].startswith("simply_mirrors:mirror"):
            blocks.append(tuple(map(int, words[1:4])))
    if len(blocks) != 9:
        raise AssertionError(f"Expected nine fixture mirror blocks, got {len(blocks)}")
    normal = NORMALS[face_index]
    corners = []
    ranges = []
    for axis in range(3):
        low = min(block[axis] for block in blocks)
        high = max(block[axis] for block in blocks) + 1
        ranges.append((low, high) if normal[axis] == 0 else
                      ((high,) if normal[axis] > 0 else (low,)))
    corners = list(itertools.product(*ranges))

    px, py, pz, yaw, pitch = POSES[face_index]
    eye = eye_override if eye_override is not None else (px, py + 1.62, pz)
    a, b = math.radians(yaw), math.radians(pitch)
    forward = (-math.sin(a)*math.cos(b), -math.sin(b), math.cos(a)*math.cos(b))
    right = (-math.cos(a), 0.0, -math.sin(a))
    up = (right[1]*forward[2]-right[2]*forward[1],
          right[2]*forward[0]-right[0]*forward[2],
          right[0]*forward[1]-right[1]*forward[0])
    focal = height / (2*math.tan(math.radians(fov_degrees)/2))
    pixels = []
    for corner in corners:
        delta = [corner[i]-eye[i] for i in range(3)]
        depth = sum(delta[i]*forward[i] for i in range(3))
        if depth <= 0.05:
            raise AssertionError("Visual fixture mirror crosses camera near plane")
        x = width/2 + focal*sum(delta[i]*right[i] for i in range(3))/depth
        y = height/2 - focal*sum(delta[i]*up[i] for i in range(3))/depth
        pixels.append((x, y))
    xs, ys = zip(*pixels)
    # Capture FOV may be animated slightly; preserve the mirror's entire edge.
    margin_x = max(12, (max(xs)-min(xs))*.16)
    margin_y = max(12, (max(ys)-min(ys))*.16)
    box = (max(0, int(math.floor(min(xs)-margin_x))),
           max(0, int(math.floor(min(ys)-margin_y))),
           min(width, int(math.ceil(max(xs)+margin_x))),
           min(height, int(math.ceil(max(ys)+margin_y))))
    if box[2]-box[0] < 24 or box[3]-box[1] < 24:
        raise AssertionError(f"Projected mirror missing or too small: {box}")
    return box


def compare_images(first: Path, second: Path, face_index: int,
                   fov_degrees: float, eye_override=None) -> dict:
    from PIL import Image, ImageChops, ImageStat
    with Image.open(first) as image_a, Image.open(second) as image_b:
        a, b = image_a.convert("RGB"), image_b.convert("RGB")
    if a.size != b.size:
        raise AssertionError(f"Different shader captures: {a.size} vs {b.size}")
    box = projected_fixture_roi(face_index, a.size, fov_degrees, eye_override)
    difference = ImageChops.difference(a.crop(box), b.crop(box))
    mean_error = sum(ImageStat.Stat(difference).mean) / 3
    if mean_error >= 25:
        raise AssertionError(f"Scaled shader capture mismatch: MAD={mean_error:.2f} at {box}")

    # A full-ROI mean can conceal a badly corrupted 80x80 mirror or a narrow
    # truncated border. Check overlapping local tiles *across all edges* too.
    channels = difference.split()
    severe = ImageChops.lighter(ImageChops.lighter(
        channels[0].point(lambda value: 255 if value >= 80 else 0),
        channels[1].point(lambda value: 255 if value >= 80 else 0)),
        channels[2].point(lambda value: 255 if value >= 80 else 0))
    width, height = difference.size
    tile = min(32, width, height)
    step = max(1, tile // 2)
    xs = list(range(0, width-tile+1, step))
    ys = list(range(0, height-tile+1, step))
    if xs[-1] != width-tile: xs.append(width-tile)
    if ys[-1] != height-tile: ys.append(height-tile)
    peak_mean = peak_severe = 0.0
    for y in ys:
        for x in xs:
            region = (x, y, x+tile, y+tile)
            local_mean = sum(ImageStat.Stat(difference.crop(region)).mean)/3
            severe_fraction = ImageStat.Stat(severe.crop(region)).mean[0]/255
            peak_mean = max(peak_mean, local_mean)
            peak_severe = max(peak_severe, severe_fraction)
            if local_mean >= 22 or severe_fraction >= .10:
                raise AssertionError(
                    f"Scaled shader localized capture mismatch at "
                    f"{(box[0]+x, box[1]+y, box[0]+x+tile, box[1]+y+tile)}: "
                    f"tile MAD={local_mean:.2f}, severe fraction={severe_fraction:.3f}")
    return {"mean_abs_error": round(mean_error, 3), "roi": box,
            "max_tile_abs_error": round(peak_mean, 3),
            "max_tile_severe_fraction": round(peak_severe, 4), "tile_size": tile}


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
        on_face, off_face = results["on"]["faces"][name], results["off"]["faces"][name]
        fov = on_face.get("fovDegrees")
        if fov is None or fov != off_face.get("fovDegrees"):
            raise AssertionError(f"Missing or mismatched FOV for {name}: {on_face}, {off_face}")
        comparisons[name] = compare_images(screenshot("on"), screenshot("off"), index, fov)
    return {"runs": results, "image_comparison": comparisons}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", choices=("forge-1.20.1", "fabric-1.20.1", "neoforge-1.21.1"), default="forge-1.20.1")
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
