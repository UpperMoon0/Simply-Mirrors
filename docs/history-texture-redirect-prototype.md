# Native history redirection prototype

Not activated, not GPU-tested, not accepted. The first prototype owns same-format,
same-mip scratch textures and restores touched framebuffer attachments. Its goal is
eliminating the measured ~0.72 ms capture + ~0.66 ms restore transfers, rather than
changing reflection resolution or shader quality.

Before activation, route every Oculus 1.8 texture operation (including cached
FinalPassRenderer swap destinations) through the texture map, invalidate active
samplers at scope boundaries, restore touched texture-unit bindings, and cover
DSA plus non-DSA paths. Getter-only replacement is insufficient: FinalPassRenderer
SwapPass captures an integer destination and render FBOs cache attachments.

Source checked: Oculus 1.8 RenderTarget getMainTexture/getAltTexture; dynamic
IrisSamplers supplier resolves those at update; GlFramebuffer keeps its logical
attachment map and binds existing GL objects; IrisRenderSystem has DSA wrappers
for bind, mipmap, texture parameters, copy subimage, image binding and FBO attach.

Required: native GL patterned original/mip/filter isolation and exception cleanup,
then strict three-pair moving-camera FPS/parity validation. Do not wire this lease
into ShaderRenderTargetSnapshot before the complete operation coverage is ready.

Profiler world time includes finalize time; use world or full reflection total
for an overall GPU gate, and report finalize as a nested stage.
