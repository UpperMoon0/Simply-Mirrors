# Simply Mirrors GPU reflection performance fix — on-prem-1 (2026-11-05)

## Actual hardware test environment

All measurements were made using a real Minecraft **Forge 1.20.1 graphical client**
connected to an isolated local dedicated server, on the user's on-prem-1
Windows computer. The game client ran in the logged-in user's actual NVIDIA
GPU session and its own GLFW window was moved off-screen without touching the
user's existing PrismLauncher instances, profiles, worlds or other windows.

- GPU: NVIDIA GeForce GTX 1050 Ti (4 GiB), driver 581.15; CPU: i3-8100
- Shader stack: Oculus 1.8.0, Embeddium 0.3.31, Complementary Reimagined
  r5.9.3 HIGH profile
- SHA-256 shader ZIP:
  fed6c879e732cf7fc7bc6570ebb1def43235aa7e96f62859fbdbdbfa0b484b38
- Resolution: 1280×720; VSync off; view distance 4; locked time/weather,
  fixed camera and world. Each case includes 8 seconds warmup, then 15 seconds
  of **real game-frame measurements** rather than synthetic compositor timings.
- Same source SHA in BOTH tests:
  6832a1762ad1d252b86b7f329019cc58f49ce12f
- The only runtime difference was disabling coverage scissor for the control
  with the Java property **-Dsimplymirrors.coverageScissor=false**.

## Same-revision before/after comparison

| Scene | No coverage optimization | Coverage optimization enabled | Relative FPS gain |
| --- | ---: | ---: | ---: |
| No mirror | 160.749 FPS | 162.427 FPS | +1.04% |
| One mirror | **75.968 FPS** | **115.845 FPS** | **+52.49%** |
| Nine coplanar mirrors | **75.022 FPS** | **99.353 FPS** | **+32.43%** |

Actual asynchronous OpenGL GPU timestamp profiling attributed about
**6.29 ms** to the full-screen reflected shader-world stage without coverage
scissoring, reduced to about **1.79 ms** for the one-mirror
322×323 pixel conservative scissor. With nine coplanar mirrors the
larger rectangle was 582×581 and reflected world GPU time was about
3.12 ms. The actual reflection texture stays at **1280×720**, and
shader shadow-map rendering and full-screen initialization are not clipped.

## Visual correctness

The benchmark required real NVIDIA GL, active Complementary shader,
non-minimized game window, zero GL errors, actual reflection passes, and
saved game framebuffer PNGs. The nine-mirror colored target ordering passed,
and one-mirror imagery was visibly different from the wall-only control.

In the same-commit coverage-on versus coverage-off screenshot comparison,
the single-mirror region's mean absolute RGB difference was **2.40/255**,
and only **0.118%** of pixels differed by over 20 intensity levels.
For nine mirrors, mean absolute difference was **4.43/255**,
and **2.886%** exceeded 20, with no differences above 40.
Small shader exposure/temporal differences between separate Minecraft
sessions do not constitute evidence of black/missing reflections.

**Independent six-face visual acceptance** on the same on-prem-1 NVIDIA
hardware with Complementary r5.9.3 in an enclosed room also passed
north, south, west, east, up and down. All six directions rendered
**120 reflection frames**, with valid reflected red/green/blue marker
ordering, shader-active state, zero GL errors and a clean shutdown.

## Retained reproducible evidence

- [Source-locked baseline summary, coverage OFF](off-summary.json)
- [Source-locked optimized summary, coverage ON](on-summary.json)
- [Earlier three-round full-screen GPU baseline](../BENCHMARKS-REAL-GPU-20261105.md)

Full per-frame JSON timing arrays, shader client/server logs, NVIDIA
usage snapshots and nine original framebuffer PNGs remain on on-prem-1
in the disposable benchmark workspace. The separate six-orientation visual
capture also retains PNG images and a verified results JSON.

## Limits

This is one same-commit paired hardware round for each mode, supplemented
by an earlier three-round full-screen baseline and independent
six-orientation acceptance. These numbers are NOT a universal guarantee
for other GPUs, large shader packs or the user's more demanding ~10 FPS world.

The algorithm preserves exact reflection texture resolution and falls back
to full-screen rendering when the projected face crosses unsafe clip planes
or occupies most of the viewport. Independent visible reflection planes
still require separate reflected world rendering. The change must be
reviewed and pass release validation before publishing.


## Scaled Iris render-target review regression (2026-11-05)

Independent review identified that an unconditional screen-size scissor must not
remain active during Iris deferred/composite/final passes whose bound buffers
are resized by `size.buffer.*`. The fix now disables mirror scissor during
Iris fullscreen quads, whole deferred stages, final compositing, and their
framebuffer clears, then restores the previous scissor state for normal
full-resolution reflected geometry.

A **second, real NVIDIA shader acceptance** verified a disposable copy of
the exact pinned Complementary r5.9.3 pack with both
`size.buffer.colortex1` and `size.buffer.colortex7` forced to
**0.5 × 0.5**. On the same revision of the corrected renderer, isolated
Forge 1.20.1 + Oculus/Embeddium graphical sessions were run with
`simplymirrors.coverageScissor=true` versus `false`. Each of the six
mirror orientations passed 120 actual reflection frames in both modes,
with the shader active, correct mirror handedness, no GL error and exit 0.

| Face | Central reflected-image on/off mean absolute RGB error (0–255) |
| --- | ---: |
| North | 0.667 |
| South | 0.835 |
| West | 0.935 |
| East | 0.860 |
| Up | 1.035 |
| Down | 0.695 |

The check compares the same shaderpack and scene, changed only by the
scissor optimization. Recorded raw PNG captures and manifests remain in
the isolated on-prem-1 paths
`forge-1.20.1/run/scissor-scaled50-{on,off}-20261105-*/`.
The regression is now reproducible by running
`python tools/scaled_shader_acceptance.py`; CI's
`scaled-target-parity` job will verify it and upload all six pairs
of screenshots once GitHub Actions can start jobs again.
