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

The same native GL suite also runs `depthStorageParity()` to verify mirror-depth format alignment without changing the main depth texture or leaking texture bindings, and `depthStencilAttachmentCollision()` to check incompatible split depth/stencil FBO repair, restoration, Iris stencil A-to-B replacement transitions, completeness and draw/read binding preservation. `depthStencilArrayLayerRestoration()` additionally verifies non-layered array stencil attachments are restored to the original layer (including layer zero), with original completeness and bindings intact.

For the pinned Iris framebuffer integration API, use:

```sh
python tools/verify_iris_framebuffer_api.py --cache build/shader-dependencies
```

These checks use Mesa software OpenGL, not physical GPU drivers; passing does not establish vendor-specific behavior.

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

## Large mirror compositor benchmark

Run the production OpenGL compositor through its historical per-block path,
per-plane batching, and exact greedy rectangle merging, with identical mirror
geometry, varying-color texture, camera, framebuffer, and output pixels:

```sh
LIBGL_ALWAYS_SOFTWARE=true xvfb-run -a ./gradlew -Ptarget=common :common:mirrorCompositeBenchmark --no-daemon --console=plain
```

The benchmark reports the median of four measured runs at 64, 256, 1,024, 4,096,
and 8,192 coplanar faces. It asserts exact RGBA pixel parity before timings and
prints quad counts (N to M). It also checks exact pixel parity across
all six face normals with an interior hole, rather than treating a filled wall
as sufficient evidence. It uses Mesa software GL, **not** a real
Minecraft client with Complementary; the world renders, shadow pipeline, shader
color-history snapshots, driver, and GPU are excluded from those timings.
Do not interpret these isolated compositor figures as full shader FPS.
The complete Complementary acceptance remains the shader and visual workflows.
