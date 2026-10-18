# Changelog

## 0.1.0-dev.5

- Make all six block faces reflective, including floors and ceilings, independently of placement direction.
- Skip hidden internal faces between adjacent mirrors and allow six visible planes per frame.
- Verify actual reflected pixels and angled views for each face on all eight targets.

## 0.1.0-dev.4

- Fix angled-view mirror hangs in vanilla camera-frustum fitting (1.20.1 and 1.21.1).
- Add a generated frameless mirror texture and a simple mod icon in every loader's metadata.
- Add angled visual regression checks and a gated GitHub/CurseForge release workflow.

## 0.1.0-dev.3

- Replace the framed wall panel with a full solid mirror block and an edge-to-edge reflective face.
- Remove the built-in wooden frame so players can build their own surrounds.
- Make adjacent mirror faces meet without inset borders and use a glass-and-iron recipe.

## 0.1.0-dev.2

- Add a wall-mounted mirror block, block item, creative tab, recipe, and drops on every target.
- Adapt Immersive Portals CE reflection transforms for standalone native reflection passes.
- Add oblique wall clipping, coplanar pass sharing, a two-plane frame budget, and GPU resource cleanup.
- Add reflection-transform/projection regression tests and preserve CE attribution.
- Experimental renderer: visual acceptance in progress; no shader-pack or recursive-reflection support claimed.

## 0.1.0-dev.1

- Establish Fabric and Forge targets for Minecraft 1.16.5 and 1.20.1.
- Establish Fabric and NeoForge targets for Minecraft 1.21.1 and 26.1.2.
- Add shared Java 8 reflection math with regression tests.
- Add separate production artifact checks and an eight-target CI matrix.
- Gameplay and reflection rendering remain under development; this is not a playable release.
