> Release publication has moved to NsTut-CICD. The Actions release/recovery workflow was removed; release namespace and recovery references below describe historical artifacts only. Follow [the current release guide](releasing.md). Existing build/visual/shader fallback artifacts are unchanged.

# Private CI artifacts in Cloudflare R2

The private Standard bucket `nstut-ci-artifacts` stores temporary archives only.
Objects expire after seven days and incomplete multipart uploads after one day.
No public bucket URL, Worker, or public endpoint is needed. Published GitHub
release JARs and CurseForge files remain permanent and separate.

Repository secrets `R2_ACCESS_KEY_ID` and `R2_SECRET_ACCESS_KEY` must belong to an
R2 **Object Read & Write** token scoped only to this bucket. `R2_ENDPOINT` is a
repository variable containing the account's S3 endpoint. Never use the local
Wrangler OAuth credential in CI. Reusable workflows receive only the two named
secrets; fork PR jobs are skipped before they enter private runners.

Archives are keyed by repository/workflow/run/attempt/commit/artifact name.
Each upload creates an immutable content-addressed ZIP followed by an immutable
manifest. Consumers list manifests only, require the exact eight build names,
and verify size and SHA-256 before extracting. Unsafe archive paths are rejected.
The S3 client retries transient operations up to five times. An incomplete upload
has no manifest and cannot be consumed; lifecycle expiration cleans its data.
Uploads over 512 MiB fail so runaway diagnostics do not accumulate silently.

The `build`, `visual`, `shaders`, and `release` namespaces remain distinct even
when called in one release run. Matrix artifact names contain their target.
Missing/expired artifacts fail explicitly. Run **all jobs** again to obtain a
complete new attempt; failed-job-only reruns cannot mix older attempts.

Release dispatch and publication authorization are unchanged. Recovery is a
main-only manual dispatch, resolves the original release run's exact attempt
and SHA through GitHub, fetches its bundle and receipts from R2, then applies
the original acceptance-job and published-asset digest verification. Recovery
must occur within retention; after expiration, inspect the permanent release
assets and CurseForge receipts manually rather than silently rebuilding a
different bundle. Pre-migration GitHub artifact receipts require the original
workflow version/manual recovery; this workflow does not silently mix stores.

For private evidence, use an authenticated S3 client and the exact manifest
namespace, or the `tools/r2_artifacts.py download` command with the workflow,
run, attempt and SHA. No presigned URLs or credentials are printed in CI logs.

Validation: `python -m unittest discover -s tools -p 'test_*.py'`, actionlint,
and live R2 round trips. Unit tests cover parallel isolation, missing/partial
objects, corrupt archives, immutable writes, path traversal, exclusions and
bundle extraction. Real builds and shader acceptance remain independent gates.
