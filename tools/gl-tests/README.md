# Native shader history regression

Run on Linux with Java 8 and 21 installed (Gradle runs on 21), Xvfb, and Mesa:

```sh
sudo apt-get install xvfb libgl1-mesa-dri libglx-mesa0 libxrandr2 libxcursor1 libxi6 libxinerama1
LIBGL_ALWAYS_SOFTWARE=true xvfb-run -a ./gradlew -Ptarget=common :common:shaderHistoryGlTest --no-daemon --console=plain
```

The `Shader history isolation (OpenGL)` CI job runs this same command. It executes
the production `ShaderRenderTargetSnapshot` and `GlTextureSnapshot`, with small
Iris API-shaped fixtures for selecting persistent targets, in a real GLFW OpenGL
context. It checks both color-buffer identities, every allocated mip level,
RGBA8/sRGB/float/signed and unsigned integer formats, exact restored image bytes,
zero reflected history, untouched nonpersistent targets, sampler flags and
filters, GL bindings/scissor/write masks, repeated throwing render passes,
capture failures, idempotent close, and released GL allocations.

This is a focused regression, not a replacement for the eight Minecraft shader
acceptance runs or a claim of hardware-vendor coverage.

The fixture also checks persistent shadow color history and the shadow flip array.
To verify the reflection API against downloaded jars from every locked stack,
run with JDK 25's `javap` on `PATH`:

```sh
python tools/verify_shader_history_api.py /path/to/checksum-pinned-shader-jars
```

The check first verifies each jar's SHA-512 against `tools/shader/stack-lock.json`,
then checks every field and method the history bridge uses. It does not download
or alter third-party jars.
