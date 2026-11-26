# Dependency-aware native reflection regions (experimental)

This first implementation is default OFF. Set SIMPLY_MIRRORS_DEPENDENCY_ROI=1 for an isolated fps_benchmark.py run. Conflicting older crop and shader rewrite switches are rejected at initialization.

The planner follows versioned pass outputs backwards, propagates maximum bounded footprints, and forces full producers on global/unknown/invalid dependencies. The initial profile covers the exact pinned Complementary Reimagined r5.9.3 ZIP, default options, Overworld, native-size viewport. Bloom/SSR/mipmap producers remain full. The unproven local-tail prototype used 52/48/8-pixel margins. This is now intentionally disabled: composite5 is a mipmapped producer for composite6, composite6 writes persistent TAA history (colortex2), and composite7 cannot assume that a later final crop will qualify. All producers remain full. Final is restricted only if its output aliases the reflected texture and GL 4.3 resource introspection confirms no active SSBO, image uniform or atomic counter, and only the redirect color attachment is enabled. No resolution or cadence change. This remains an experiment: archive identity is not proof that an arbitrary other mod has not rewritten source, so no default activation before native A/B and broader compatibility tests.

Profile-only runs now register program labels. Compare exact same revision with and without the env flag, 1080p/r12 and moving camera, minimum three alternating pairs, mean RGB <1 / worst 32-pixel tile <5, no GL errors or missing frame markers. Require ROI draw and avoided-pixel counters and inspect per-program timings. Final must stay full if storage blocks are active or the target does not match. Do not claim a gain until the native gate passes.

A fully rejected final crop produces no activation evidence; such a trial is a failed optimization, not a pass. Native FPS improvement remains unproven.

Follow-up: generalize profile metadata to actual compiled source/attachment signatures, verify active graph including flips and custom images, then evaluate shadow reuse based on measured shadow timings. No unknown pack is eligible.

## Build #136 diagnosis

Oculus 1.8.0 `NamespacedId.toString()` emits `NamespacedId{namespace='minecraft', name='overworld'}` rather than `minecraft:overworld`. The old check rejected every Overworld frame before archive verification. The corrected profile inspects the actual namespace and name (or ResourceLocation path) and logs explicit fail-closed rejection reasons. Native GL resource metadata is cached per registered linked program; target and viewport are still checked each draw. The native final-pass median in #136 was about 0.168 ms; eliminating all of that alone cannot be expected to deliver the 3% whole-frame acceptance target at ~40 FPS. Retain the throughput gate and do not promote a merely activated terminal crop. History/mipmap producers remain full until their actual dependencies can be proven.
