# Simply Mirrors

A standalone Minecraft mod project for placeable mirrors that reflect the surrounding world.

**Status: initial development foundation.** This repository currently contains loader bootstraps, the eight-target build matrix, and a tested version-independent reflection-math core. A placeable mirror block, world-reflection renderer, configuration screen, and crafting gameplay are not implemented yet. Build artifacts are development scaffolds, not playable mirror releases.

## Target matrix

| Minecraft | Fabric | Forge / NeoForge | Java |
|---|---|---|---|
| 1.16.5 | Fabric | Forge 36.2.42 | 8 |
| 1.20.1 | Fabric | Forge 47.4.0 | 17 |
| 1.21.1 | Fabric | NeoForge 21.1.228 | 21 |
| 26.1.2 | Fabric | NeoForge 26.1.2.76 | 25 |

Each row produces two separate jars; install only the artifact matching your Minecraft version and loader. Forge is used for the older versions, NeoForge for the newer versions. The initial bootstrap requires no Architectury API or Fabric API runtime dependency.

## Build

Install Python 3.11+ and JDK 21 for the legacy build and JDK 25 for the 26.1.2 builds. Gradle toolchains resolve the compilation JDKs (8, 17, 21, 25). Set `JAVA_HOME` to JDK 21 for legacy commands or JDK 25 when building Minecraft 26.1.2. No machine-specific paths are checked in.

```sh
python tools/build.py                       # shared tests + all eight production jars
python tools/build.py --target forge-1.16.5 # tests + one target
```

On Windows, use `gradlew.bat` instead of `./gradlew` for direct Gradle commands:

```sh
./gradlew -Ptarget=common :common:test
./gradlew -Ptarget=fabric-1.20.1 :fabric-1.20.1:build
cd fabric-26.1.2
./gradlew build
```

The root wrapper is Gradle 8.14. Each 26.1.2 module has an independent Gradle 9.2.1 wrapper because the Minecraft 26 toolchain differs from the older Loom builds. `tools/build.py` selects the correct build root and verifies each production jar's metadata, entrypoint, included shared classes, and Java bytecode level.

## Layout

- `common`: loader- and Minecraft-independent Java 8 sources and reflection-math regression tests.
- `common-<version>`: locations reserved for version-specific Minecraft integrations shared by both loaders.
- `fabric-<version>`, `forge-<version>`, `neoforge-<version>`: platform bootstraps, exact-version metadata, and development launch configurations.
- `tools/targets.json`: complete eight-target packaging matrix.
- `.github/workflows/build.yml`: shared tests and separate artifact builds for every target on pushes and pull requests.

## Rendering milestones

1. Register a wall-mounted mirror block and item on every target, including placement, rotation, crafting, and drops.
2. Implement bounded offscreen planar reflections with correct camera transformation, clipping, depth occlusion, and guaranteed state restoration.
3. Group coplanar connected mirrors to reuse reflection passes; cap distance, resolution, and reflection count.
4. Validate entities, the local player, dimension changes, chunk unloads, resource reloads, and GPU resource disposal.
5. Capture rendered evidence on all eight targets, then evaluate Sodium, Iris, Embeddium, and Oculus compatibility explicitly.

No shader compatibility or in-game visual verification is claimed by this initial commit. CI build success verifies packaging and reflection math, not a working visual renderer. No automatic CurseForge or Modrinth publication is configured.

## License

MIT. Copyright 2026 NsTut. Part of the Simply series alongside [Simply Speakers](https://github.com/UpperMoon0/Simply-Speakers) and [Simply Screens](https://github.com/UpperMoon0/Simply-Screens).
