# Dependency-aware native reflection regions (experimental)

This first implementation is default OFF. Set SIMPLY_MIRRORS_DEPENDENCY_ROI=1 for an isolated fps_benchmark.py run. Do not combine it with older crop switches.

The planner follows versioned pass outputs backwards, propagates maximum bounded footprints, and forces full producers on global/unknown/invalid dependencies. The initial profile covers the exact pinned Complementary Reimagined r5.9.3 ZIP, default options, Overworld, native-size viewport. Bloom/SSR/mipmap producers remain full. The local tail uses conservative 52/48/8-pixel margins, in addition to the existing >=96-pixel coverage padding. Final is restricted only if its output aliases the reflected texture and GL 4.3 resource introspection confirms no active SSBO. No resolution or cadence change. This remains an experiment: archive identity is not proof that an arbitrary other mod has not rewritten source, so no default activation before native A/B and broader compatibility tests.

Profile-only runs now register program labels. Compare exact same revision with and without the env flag, 1080p/r12 and moving camera, minimum three alternating pairs, mean RGB <1 / worst 32-pixel tile <5, no GL errors or missing frame markers. Require ROI draw and avoided-pixel counters and inspect per-program timings. Final must stay full if storage blocks are active or the target does not match. Do not claim a gain until the native gate passes.

Follow-up: generalize profile metadata to actual compiled source/attachment signatures, verify active graph including flips and custom images, then evaluate shadow reuse based on measured shadow timings. No unknown pack is eligible.
