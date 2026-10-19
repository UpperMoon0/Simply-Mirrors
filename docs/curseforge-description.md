# Simply Mirrors

**Real reflections. Six reflective sides.**

Simply Mirrors adds a craftable, full-block mirror that reflects the world around it—including players and other entities. Every side reflects, so you can build wall mirrors, mirrored pillars, reflective floors, or mirrored ceilings with the same block.

## Build it your way

- **All six faces reflect:** the four sides, top, and bottom work regardless of placement direction.
- **No built-in frame:** use wood, stone, metal, or any other blocks to match your build.
- **Seamless larger mirrors:** place mirror blocks together to create a continuous reflective surface.
- **A dedicated creative tab:** find the block in the Simply Mirrors tab.
- **A simple survival recipe:** surround one iron ingot with eight glass blocks to craft one mirror block.
- **Shader reflections in 0.1.1:** use the tested Iris/Oculus and Complementary Reimagined combinations below.

Try a bathroom mirror, a grand hall of mirrors, a polished dance floor, or a ceiling that reflects the room below.

## Supported versions

| Minecraft | Mod loaders |
| --- | --- |
| 1.16.5 | Fabric, Forge |
| 1.20.1 | Fabric, Forge |
| 1.21.1 | Fabric, NeoForge |
| 26.1.2 | Fabric, NeoForge |

Install the file matching your Minecraft version and loader. Fabric versions also require **[Fabric API](https://www.curseforge.com/minecraft/mc-mods/fabric-api)**. Install Simply Mirrors on both the client and server when playing multiplayer.

**Immersive Portals and Architectury API are not required.**

## Compatibility and performance

Mirrors render when you are within 32 blocks, showing currently loaded terrain and entities. Up to six visible mirror planes can render per frame; larger flat surfaces share a reflection, while viewing more separate planes can reduce performance.

Version **0.1.1** adds Iris/Oculus shader reflections, including fixes for mirror clipping, reflected visibility with modern Sodium, and camera, shadow, and temporal-state isolation. The tested combinations all use **Complementary Reimagined r5.9.3**:

| Minecraft | Loader | Shader loader | Terrain renderer |
| --- | --- | --- | --- |
| 1.16.5 | Fabric | Iris 1.4.5 | Sodium 0.2.0 |
| 1.16.5 | Forge | Oculus 1.4.8 | Rubidium 0.2.13 |
| 1.20.1 | Fabric | Iris 1.7.6 | Sodium 0.5.12 beta 2 |
| 1.20.1 | Forge | Oculus 1.8.0 | Embeddium 0.3.31 |
| 1.21.1 | Fabric | Iris 1.8.8 | Sodium 0.6.13 |
| 1.21.1 | NeoForge | Iris 1.8.12 | Sodium 0.6.13 |
| 26.1.2 | Fabric / NeoForge | Iris 1.11.4 | Sodium 0.9.2 |

Shader mods and packs are optional: install the matching combination on your client if you want shaders. They are not bundled with Simply Mirrors or required on a dedicated server. Other shader packs and versions have not been validated. See the [shader validation details and capture](https://github.com/UpperMoon0/Simply-Mirrors/blob/main/docs/shader-validation.md).

Mirrors reflecting other mirrors recursively are not supported. Reflections preserve the main view's temporal history but do not yet accumulate separate per-mirror history. Minecraft 26.1.2 currently requires the OpenGL renderer.

## Feedback and credits

Found a problem? Please include your Minecraft version, mod loader, and latest log when [reporting an issue](https://github.com/UpperMoon0/Simply-Mirrors/issues).

Inspired by the mirrors in **Immersive Portals CE**. Simply Mirrors runs independently and is licensed under **MIT**.

[Source code](https://github.com/UpperMoon0/Simply-Mirrors)
