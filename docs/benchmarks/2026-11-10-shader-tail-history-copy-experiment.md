# Native shader optimization — next 1080p acceptance

The latest draft PR #11 optimization remains off by default for normal clients.
This experiment combines **dependency-local postprocess shader cropping** with
**direct GL 4.3 image copies of Iris/Oculus history snapshots**. Neither changes
the reflection target dimensions, projection, camera motion or shader pack.
Direct image copying automatically falls back to framebuffer blitting on a
GL 3.3-only context.

## Tested and rejected approaches

- Reducing the entire reflection viewport caused incorrect reflection pixels.
- Cropping the shader `final` pass changed a local 32-pixel-tile by 7.642 RGB
  levels, above the unchanged maximum of 5.0. The final shader has an
  origin-dependent SSBO clear; it is deliberately left full resolution and
  full rasterization.
- Changing only reflected geometry frustum culling did not produce a
  repeatable 1080p improvement.

The pinned 1080p/r12 baseline still loses nearly half its FPS when one
mirror is placed. Prior safe postprocess-tail results showed a roughly
3–5% single-mirror improvement but **were not a completed current-HEAD
acceptance**. The combined direct-copy variant has **no proven FPS gain yet**.

## Reproduction

Use the central Jenkins pipeline's `CI_TIER=gpu` on the latest trusted,
same-repository PR HEAD. It runs one isolated on-prem-1 native graphics job
with pinned Forge 1.20.1, Oculus 1.8.0, Embeddium 0.3.31 and Complementary
Reimagined r5.9.3 at 1920×1080, render distance 12, moving camera.

`tools/tail_crop_ci.py` runs three complete alternating baseline/trial
pairs, with a bounded repeat of one environmentally unstable pair. The
control uses normal history blits and no shader-tail crop. Trial uses
`--shader-tail-crop --history-copy-image` with no final-pass crop.

Unchanged acceptance gates:

- Native FPS gain: median one-mirror improvement at least 3%, and
  nine-mirror regression no worse than 2%.
- Pixel comparison: mean RGB error below 1, worst 32×32 tile below 5,
  over actual full-resolution captured reflected scenes.
- Environment: no-mirror control drift no greater than 4.5%, plus cross-pair
  per-scene stability checks. Bad data must not be relabeled as an FPS win.
- All reflected frames must be rendered. No skipped reflection passes,
  temporally frozen images, shader-related crashes, or GL errors.

Each result now carries separate CPU and GPU medians for world rendering,
finalization, and history capture/restoration. These are aggregated over
both mirror scenes and are **not** a scene-specific timing claim. They
should distinguish driver-side snapshot overhead from shader fragment cost
before the next redesign.

The visual and shader smoke suites no longer implicitly start these GPU
runs. After the targeted native gate, use `CI_TIER=full` for the eight-loader
release matrix and existing regressions at the **same exact SHA**.

Do not merge, publish or enable these experimental toggles by default until
the native A/B and full compatibility checks succeed. The normal Minecraft
client and the cloud-1 worker policy remain untouched.
