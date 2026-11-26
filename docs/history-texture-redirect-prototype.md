# Native history redirection prototype

Opt-in integration implemented; not GPU-tested or accepted. The first prototype owns same-format,
same-mip scratch textures and restores touched framebuffer attachments. Its goal is
eliminating the measured ~0.72 ms capture + ~0.66 ms restore transfers, rather than
changing reflection resolution or shader quality.

Integration routes Oculus texture binds, copies, mipmap and parameter operations
through the texture map, including cached FinalPassRenderer swap binds; clears
active samplers at scope boundaries and restores touched sampler/image units.
DSA and fallback paths still require actual runtime validation. Getter-only replacement is insufficient: FinalPassRenderer
SwapPass captures an integer destination and render FBOs cache attachments.

Source checked: Oculus 1.8 RenderTarget getMainTexture/getAltTexture; dynamic
IrisSamplers supplier resolves those at update; GlFramebuffer keeps its logical
attachment map and binds existing GL objects; IrisRenderSystem has DSA wrappers
for bind, mipmap, texture parameters, copy subimage, image binding and FBO attach.

Required: native GL patterned original/mip/filter isolation and exception cleanup,
then strict three-pair moving-camera FPS/parity validation. The native A/B runs
the GL regression first and enables the opt-in only in the trial arm. The rejected
cloud and volumetric arithmetic hypotheses are disabled in both arms.

Profiler world time includes finalize time; use world or full reflection total
for an overall GPU gate, and report finalize as a nested stage.
