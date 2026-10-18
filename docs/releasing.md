# Releasing Simply Mirrors

The **Build and release** workflow follows the Simply Speakers/Simply Screens pattern: a version change on `main` builds the mod and publishes it. It also requires the reflection tests and visual acceptance tests on all eight targets to pass before any publication. Development versions (`-dev`) only produce a verified bundle automatically.

Configure these on **UpperMoon0/Simply-Mirrors → Settings → Secrets and variables → Actions**:

- Repository variable `CURSEFORGE_PROJECT_ID`: the Simply Mirrors project ID. It is intentionally unset until the project is supplied.
- Repository secret `CURSEFORGE_API_TOKEN`: a CurseForge Upload API token with access to that project, using the same secret name as Simply Speakers. Repository secrets are not inherited from other repositories.

The artwork for the CurseForge project is `common-assets/src/main/resources/assets/simply_mirrors/icon.png` (512 × 512, transparent PNG).

To release, update `mod_version` in `gradle.properties`, add `changelog/<version>.md`, and merge the reviewed change to `main`. Stable versions upload as release, `-beta` as beta, and `-alpha`/`-dev` as alpha. An automatic main push with an unchanged version does not republish it.

For a test run, select **Actions → Build and release → Run workflow**, leaving **publish** disabled. This builds, runs all visual tests, and uploads an eight-jar bundle with SHA-256 checksums without requiring CurseForge settings. Explicit manual publication is available on `main` with **publish** enabled, including development versions.

Publication first validates every production jar, its version, assets, loader, Java level, changelog, and CurseForge game-version tags. Missing project ID/token fails clearly before a GitHub release or upload is created. GitHub releases point at the tested commit and contain all eight jars plus `SHA256SUMS`. Fabric uploads declare Fabric API as a required dependency; Forge and NeoForge builds have no additional mod dependencies.

An existing GitHub release stops a rerun, preventing silent replacement or duplicate uploads. A partial CurseForge failure saves successful file IDs in the `curseforge-upload-receipts` artifact. Inspect that artifact and the project files before manually uploading remaining files; do not blindly rerun uploads after a timeout. CurseForge approval is separate from upload success.

API reference: [CurseForge Upload API](https://support.curseforge.com/support/solutions/articles/9000197321).
