# Shader validation

Simply Mirrors 0.1.0 does **not** currently render mirrors correctly with the tested Complementary shader stack. Installing the renderer mods and successfully loading a shader pack is insufficient to establish compatibility.

## Reproduced result

On Windows with an NVIDIA GeForce GTX 1050 Ti, Forge 1.20.1, Embeddium 0.3.31, Oculus 1.8.0, and Complementary Reimagined r5.9.3, the north-facing mirror shows a flat sky-blue surface instead of the red, green, and blue fixture behind the player. The wall and surrounding world visibly receive shader lighting.

The capture reported 120 completed reflection passes, 120 angled-view frames, a registered creative tab, `glError=0`, and `shaderPackActive=true`. The image check correctly failed with zero reflected red-marker pixels. A successful launch or absence of a crash must not be reported as shader support.

![Released renderer with Complementary enabled](images/mirror-shader-oculus-unsupported.png)

The original baseline also reproduced a missing-color failure with Fabric 1.20.1 / Iris 1.7.6 / Sodium 0.5.12 and NeoForge 1.21.1 / Iris 1.8.12 / Sodium 0.6.13. Other combinations should be evaluated from their own capture results; the presence of a pinned dependency stack does not imply working reflections.

## Reproduction

Use JDK 21 for the Gradle invocation and install Python 3.11+ with Pillow. The game uses the target's matching Java toolchain.

```sh
python tools/visual_smoke.py --target forge-1.20.1 --shaders --run-name shader-baseline
```

The installer downloads only checksum-pinned native artifacts. Forge 1.16.5 uses Oculus/Rubidium, Forge 1.20.1 uses Oculus/Embeddium, and Fabric/modern NeoForge use Iris/Sodium. Every target uses Complementary Reimagined r5.9.3. Development dependencies and the shader pack remain local and are excluded from production jars.

Each run starts a disposable server without shader mods and a shader-enabled client, rebuilds the fixture for each of the six orientations, and checks actual captured colors and handedness. An image mismatch is retained while the remaining faces are captured; the run fails after clean client shutdown. Startup, OpenGL, or missing-pack failures stop the run immediately. The harness refuses to reuse a saved test world; choose a fresh `--run-name` when retrying.

Results and logs are written under `<target>/run/<run-name>-evidence` and screenshots under `<target>/run/<run-name>-client/visual-evidence`. Only a fully passing run creates `verified.json`; failed image runs create `results.json` and `failure.txt`.

The **Mirror shader acceptance** workflow is a manually dispatched diagnostic. Its failures record the current unsupported shader combination and are separate from ordinary vanilla acceptance and release builds. A production compatibility fix must pass these image checks before shader support is advertised.
