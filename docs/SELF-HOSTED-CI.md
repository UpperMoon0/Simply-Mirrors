# Self-hosted CI for Simply Mirrors

All Build, Visual, Shader and release-bundle jobs use the private `UpperMoon0/NsTut-Actions-Runner` just-in-time Linux fleet:

```yaml
runs-on: [self-hosted, linux, x64, nstut-linux-pool, nstut-profile-minecraft-ci]
```

The fleet picks an eligible physical node; there is no host-specific data path, and test worlds are disposable. The manager's `minecraft-ci` profile is socketless/non-root, limited to **6 GiB RAM**, **2 CPUs** (1 CPU on the VPS when protected game-server traffic is present), **1024 PIDs**, and **one concurrent Minecraft job per physical node**. Each JIT worker/container runs just one job and is removed.

Minecraft's headless GUI needs Mesa, Xvfb, Xauth, OpenAL and fonts. These are built into the manager's runner image as root **at image creation time**. Do not reintroduce `sudo apt-get` in the non-root job. Tests use `LIBGL_ALWAYS_SOFTWARE=true` and their pinned shader/mod dependencies. Java and Python toolchains are still installed with official setup actions.

## Trust model

The repository is public. Jobs that execute checked-out PR code on private runners explicitly require a trusted same-repository PR:

```yaml
if: github.event_name != 'pull_request' || github.event.pull_request.head.repo.full_name == github.repository
```

The runner manager independently rejects fork-PR job requests even when this repo is allowlisted. Pull requests from forks must **never** run through `pull_request_target`, acquire runner secrets, or have an exception added to the fleet manager. Review outside code in a separate unprivileged environment. The release workflow only operates on `main` push/manual dispatch; publishing still requires manual `publish=true` and the existing release preflight.

## Validation and rollout

1. Update the private runner manager and worker image with `UpperMoon0/Simply-Mirrors` on the explicit allowlist and the `minecraft-ci` profile, then verify a healthy manager on each opted-in node.
2. Merge the public CI workflow change. Retarget PR branches as necessary so their current workflow revisions use the fleet.
3. Check actual GitHub Actions job annotations and the manager's logs, not only YAML syntax. The runner pool cannot bypass an account-level GitHub billing suspension: GitHub must queue jobs before a JIT worker can execute them.
4. Run `python -m unittest discover -s tools -p 'test_*.py'` and the manager's own unit tests. Inspect Build, Visual and Shader jobs and artifacts before merging the performance stack.

A failing or unavailable runner is a **failed release gate**, not grounds to skip visual, shader, or eight-platform validation. The hardware NVIDIA performance benchmark remains a separate local, explicitly opt-in validation; headless Mesa success is not a substitute for real-GPU FPS measurements.
