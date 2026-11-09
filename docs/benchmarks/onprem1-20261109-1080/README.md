# Real-GPU mirror FPS acceptance — on-prem-1, 2026-11-09

## Method

- Hardware: Intel Core i3-8100, NVIDIA GeForce GTX 1050 Ti 4 GiB, driver 581.15; Windows 10.
- Minecraft Forge 1.20.1, Oculus 1.8.0, Embeddium 0.3.31, Complementary Reimagined r5.9.3 **HIGH**.
- Same isolated flat fixture: no mirror, one mirror, nine coplanar mirrors. The nine coplanar blocks form one reflection plane, **not nine full world passes**.
- VSync off, 260 FPS cap, fullscreen off, GPU verified via in-game GL vendor, zero GL errors; one test round each. Every completed run passed screenshot/reflection markers and motion/coverage checks.
- 720p: 1280×720, render distance 4. 1080p: 1920×1080, render distance 12. The control runs used a temporary harness-only copy with the same view-distance settings; PR #11 now exposes `--render-distance 12` natively.
- **Baseline control** is the same PR #11 renderer with `-Dsimplymirrors.coverageScissor=false`. It is *not* a complete checkout of the older pre-PR renderer.
- **Current PR**: commit `4ac69ea`, coverage-scissor ON. **Frustum trial**: isolated experimental branch commit `8514f49`, coverage scissor ON plus padded reflection-frustum culling. The experimental branch is not merged.
- Results were recorded sequentially, not simultaneously. These are single exploratory rounds subject to temperature, scheduling and shader startup variance, not statistically significant confidence intervals.

## 1280 × 720, render distance 4

| Variant | No mirrors FPS | One mirror FPS | Nine coplanar FPS | One p95 ms | Nine p95 ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| Coverage scissor OFF | 156.12 | 66.87 | 72.68 | 18.49 | 14.23 |
| Current PR #11 | 158.50 | 80.43 | 77.86 | 12.55 | 13.09 |
| Experimental frustum culling | 162.12 | 83.75 | 80.05 | 12.09 | 12.64 |

The late-run profiled GPU reflected-world stage was about 6.67 ms (scissor off), 5.83 ms (scissor on), and 5.53 ms (frustum trial), respectively. These are representative profiler readings, **not** per-scenario means.

## 1920 × 1080, render distance 12

| Variant | No mirrors FPS | One mirror FPS | Nine coplanar FPS | One p95 ms | Nine p95 ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| Coverage scissor OFF | 76.27 | 36.97 | 36.56 | 27.36 | 27.58 |
| Current PR #11 | 75.50 | 40.55 | 38.01 | 25.23 | 26.28 |
| Experimental frustum culling | 76.04 | 40.48 | 38.60 | 25.43 | 26.61 |

**Decision:** retain PR #11's existing coverage optimization. The frustum trial is correct visually but yields no dependable benefit at 1080p: one-mirror FPS is slightly lower, nine-mirror FPS slightly higher, and both p95 values slightly worse. **Do not merge it as a claimed FPS improvement.**

## Rejected experiment

An earlier experimental reflection-target resize failed Oculus framebuffer-history isolation. A revised native-size-target/smaller-viewport trial completed numerical FPS samples but rendered a blank/dark mirror (zero reflected red marker pixels). That is an acceptance failure even if its FPS appears higher. Both resizing paths have been removed from the isolated branch; shader history must not be bypassed or disabled to fake performance gains.

## Acceptance requirements for a real next-stage optimization

1. Keep both native main-camera shader history and reflected-camera shader history correct, including resizing, teleportation, transparent content and repeated world changes.
2. Test GPU timing, p95/p99 game-frame times, and screenshots on this same 1080p/r12 shader profile. Add camera translation/rotation and animated entities, not just a static pose.
3. Implement genuinely cheaper reflected-world rendering. Candidate architecture: isolated shader-compatible lower-resolution reflection pipeline with correctly sized history attachments, or full depth-aware temporal reprojection with disocclusion repair. Merely shortening the culling frustum or disabling shader post-processing will not meet the quality bar.
4. Reject any run with missing mirror pixels, invalid GL state, shader-history corruption, or stale/ghosted dynamic reflections. Preserve exact-render fallback on unsupported shader configurations.
5. Repeat at least three randomized/alternating rounds, confirm no more than one GPU-intensive benchmark at once, and report both absolute FPS and paired control deltas.

## Reproduction

```powershell
python tools/fps_benchmark.py --target forge-1.20.1 --width 1920 --height 1080 --render-distance 12 --rounds 3 --profile-gpu --startup-timeout 1800 --rcon-timeout 90 --shader-cache "<local validated shader dependency cache>"
```

For a scissor-disabled control, add `--disable-coverage-scissor`. The harness uses disposable server/client worlds and requires a private GPU session; never run its server on a production Minecraft world. Local raw screenshot/frame JSON evidence was retained in the isolated `fps-*-20261109-client/fps-evidence` runs under `D:\Minecraft\Mods\SimplyMirrorsPR11`; the large binaries are not checked into Git.

Cloud-1's Jenkins build workers were not activated for any of these tests.
