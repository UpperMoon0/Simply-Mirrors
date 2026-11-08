# Releasing Simply Mirrors

Publishing is owned by [NsTut-CICD](https://github.com/UpperMoon0/NsTut-CICD), alongside Powered Repair. The source repository has no publishing workflow or upload implementation. Its Jenkinsfile delegates to `nstutMinecraftMod(project: 'simply-mirrors')`.

The CurseForge project is **1751860** ([author dashboard](https://authors.curseforge.com/#/projects/1751860)). The author token comes from the shared `nstutcommonkv/curseforge-api-token` vault secret, never the mod's Actions secrets or ordinary build agents.

1. Update `mod_version` and `changelog/<version>.md`, then merge the reviewed source to main.
2. Wait for the entire Jenkins matrix to succeed: shared tests, all eight production builds, native OpenGL tests, all eight vanilla visual and Complementary shader checks, and the NeoForge regression suite. The existing graphics qualification gates remain required.
3. In NsTut-CICD, set `simply-mirrors.request` in `jenkins/artifacts/mod-releases.json` to the exact source SHA, successful Jenkins build number, version and channel (`alpha`, `beta` or `release`). Review and merge that request. A null request disables publication; a source version bump or tag cannot authorize it.

The central publisher requires the same exact-SHA build to have broker-verified success and R2 evidence. It validates the complete collected eight-JAR bundle, checksums, source provenance, versioned notes and CurseForge tags before uploading anything. Fabric uploads require Fabric API; Forge and NeoForge uploads do not add mod dependencies. Client/Server and Java tags are retained.

A GitHub draft receives all eight JARs, checksums and receipts. The draft becomes public only after CurseForge confirms all eight upload IDs. Moderation is separate from acceptance of an upload. A pending upload receipt blocks automatic retries after an uncertain response. Confirmed IDs, artifact hashes and per-version state are retained by the central host and encrypted backups.

See the [central release guide](https://github.com/UpperMoon0/NsTut-CICD/blob/main/jenkins/minecraft-mods/releases.md) for release requests and recovery. Existing GitHub releases and historical Actions/R2 receipts are preserved; they are never silently replaced or automatically imported into a new release.
