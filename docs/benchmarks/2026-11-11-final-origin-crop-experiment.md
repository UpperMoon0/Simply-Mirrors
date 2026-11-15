# Final shader origin-preserving crop experiment

Status: **not accepted**. Experimental branch `exp/final-origin-safe-crop` based on Simply Mirrors PR #11 at `21c8de1`. Normal player rendering is unchanged.

## Failure addressed

Complementary Reimagined r5.9.3's `shaders/program/final.glsl` calls `clearSSBOs()` when world-space player reflections are enabled. Its implementation in `lib/voxelization/SSBOs/clearSSBOs.glsl` performs storage-buffer writes only for `gl_FragCoord.x + gl_FragCoord.y < 1.5`, i.e. the framebuffer-origin pixel.

A conventional centered final-pass scissor can omit this write. The earlier native full-final crop failed its 32-pixel image threshold (7.642 RGB error versus maximum 5). This experiment uses one conservative rectangle extending from the framebuffer origin to the mirror bounds with 96 pixels of additional padding. It refuses cropping when the final framebuffer viewport differs from native 0,0 dimensions or the rectangle would cover at least 82% of the viewport. It never reduces resolution or skips a reflection frame.

**This does not establish parity for other packs or configurations.** The option is enabled only by the explicit `--shader-final-origin-crop` GPU-test flag (JVM: `-Dsimplymirrors.shaderFinalOriginCrop=true`). It is disabled by default.

## Native test design

The accepted baseline is the previous combination `--shader-tail-crop --history-copy-image` from GPU build #46 (median +5.209% FPS for one mirror versus completely unoptimized rendering). Both arms of this *new* experiment enable those flags. Only the trial enables `--shader-final-origin-crop`.

The paired, alternating 1920x1080 / render-distance-12 / moving-camera test retains the same requirements:

- Every mirror frame actually rendered; no GL errors, launcher failures or frozen captures
- One-mirror median gain at least 3% **beyond the already accepted combination**
- Nine-mirror median regression no worse than 2%
- Mirror ROI mean RGB error below 1.0 and peak 32x32 RGB error below 5.0
- No-mirror within-pair FPS drift at most 4.5% and cross-pair scene drift at most 8%; one bounded full-pair retry for drift only
- Proof that the named `final` program was actually cropped, including exact final fragment pixels avoided

The test rejects unstable FPS evidence **before** image comparison, rather than misclassifying an environmentally unstable pair as a shader corruption. True pixel mismatches on stable pairs remain hard failures and are never retried as mere noise.

## Next action

After the prior full build #47 has stopped, integrate onto PR #11, compile, run the native GPU lane, and inspect actual image/FPS data. If accepted, re-run full eight-target CI on that exact new SHA. Do not enable it by default, merge the release PR, or claim a substantial FPS improvement before these gates pass.
