# Releasing Simply Mirrors

The **Build and release** workflow validates version changes on `main`; publication requires a manually authorized main-branch dispatch with **publish** enabled. Reflection tests, native OpenGL state isolation, and vanilla plus shader acceptance on all eight targets must pass before publication.

Configure these on **UpperMoon0/Simply-Mirrors â†’ Settings â†’ Secrets and variables â†’ Actions**:

- Repository variable `CURSEFORGE_PROJECT_ID`: `1751860`, already configured for [Simply Mirrors](https://authors.curseforge.com/#/projects/1751860).
- Repository secret `CURSEFORGE_API_TOKEN`: a CurseForge Upload API token with access to that project, using the same secret name as Simply Speakers. Repository secrets are not inherited from other repositories.
- Repository secrets `R2_ACCESS_KEY_ID` and `R2_SECRET_ACCESS_KEY`, and variable `R2_ENDPOINT`: [private temporary CI artifact storage](ci-r2-artifacts.md). These are required for both validation and release runs.

The artwork for the CurseForge project is `common-assets/src/main/resources/assets/simply_mirrors/icon.png` (512 Ã— 512, transparent PNG).

To release, update `mod_version` in `gradle.properties`, add `changelog/<version>.md`, merge the reviewed change to `main`, and explicitly dispatch the workflow with **publish** enabled. Stable versions upload as release, `-beta` as beta, and `-alpha`/`-dev` as alpha. Automatic main pushes never publish.

For a test run, select **Actions â†’ Build and release â†’ Run workflow**, leaving **publish** disabled. This builds, runs all visual tests, and uploads an eight-jar bundle with SHA-256 checksums without requiring CurseForge settings. Explicit manual publication is available on `main` with **publish** enabled, including development versions.

Publication first validates every production jar, its version, assets, loader, Java level, changelog, and CurseForge game-version tags. Missing project ID/token fails clearly before a GitHub release or upload is created. GitHub releases point at the tested commit and contain all eight jars plus `SHA256SUMS`. Fabric uploads declare Fabric API as a required dependency; Forge and NeoForge builds have no additional mod dependencies.

An existing GitHub release stops a rerun, preventing silent replacement or duplicate uploads. A partial CurseForge failure saves successful file IDs in the private R2 `curseforge-upload-receipts` artifact. Inspect it and the project files before a main-branch recovery dispatch with **publish** enabled and **resume_run** set to the failed release run ID. Temporary bundles and receipts expire after seven days; see the [R2 recovery and retention notes](ci-r2-artifacts.md). Do not blindly rerun uploads after a timeout. CurseForge approval is separate from upload success.

API reference: [CurseForge Upload API](https://support.curseforge.com/support/solutions/articles/9000197321).
