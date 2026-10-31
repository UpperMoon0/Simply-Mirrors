# ATM10 To the Sky — versioned native acceptance record (pack 2.3.1)

This is a versioned test record, not evergreen documentation.

## Exact test environment

Minecraft 1.21.1, NeoForge 21.1.250 and the official ATM10SKY 2.3.1 archive.
Archive SHA-256: 3703e6b5655eaffa89efa747656908d2d5fec09a5b67a13a1c9fb98f91cf3cdf.

The 337 artifact IDs and SHA-256 hashes are pinned in tools/atm10-sky/lock-2.3.1.json.
The full pack includes Iris 1.8.12, Sodium 0.6.13 and Euphoria Patcher 1.10.5,
with official Complementary Reimagined r5.9.3, Complementary Unbound r5.9.3 and Bliss v2.1.2.

## Native test design

The GitHub-hosted workflow .github/workflows/atm10-sky.yml downloads and
checks the exact modpack files, creates a fresh enclosed six-face mirror world
using a temporary native NeoForge server, and opens that world using the
integrated server of the complete modpack client. The dev-only client probe
hides GLFW windows and records actual Minecraft framebuffer PNGs and logs.

Separate fresh launches cover shaders off (complete pack), plain Complementary
(one Euphoria Patcher mod removed for the control case), and Euphoria with
the full pack. The artifact downloader and visual runner refuse to reuse
existing saves or profiles.

Each face captures 120 reflected/angle frames, checks shader state and
OpenGL errors, and rejects absent or disordered reflected red, green and blue
geometry. Failures preserve screenshots and logs.

Reproduce on Linux with JDK 21, Pillow, Xvfb and Mesa after cloning:

    python tools/atm10_sky.py --output build/atm10-sky/game
    xvfb-run -a -s '-screen 0 1280x720x24' python tools/atm10_sky_visual.py --game build/atm10-sky/game --mode euphoria

Versioned evidence paths are under neoforge-1.21.1/run/atm10-<mode>-evidence/
and neoforge-1.21.1/run/atm10-<mode>-client/visual-evidence/.

## Limitations and merge gate

This protocol tests real client framebuffer pixels, not mocked OpenGL. The
GitHub-hosted Mesa software renderer differs from a physical GPU. The three
shader variants are separate launches; a hot shader toggle requires a further
transition test. Passing colored marker checks does not by itself prove
correct player or mob reflections, motion, or visual lighting fidelity:
inspect the preserved screenshots.

PR #6 must remain draft until the full-pack runs and visual inspection prove
issue #5 is resolved. A green dependency downloader, compilation or minimal
shader smoke must never be described as a resolved user report.
