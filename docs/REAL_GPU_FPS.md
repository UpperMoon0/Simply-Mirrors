# Real Minecraft + Complementary GPU FPS benchmark

This uses a real Minecraft client, dedicated server, and the active Windows GPU.
It is NOT a Mesa compositor estimate or synthetic FPS measurement.

## On-prem-1

Use an isolated checkout, Java 21 for Gradle, Python, and the existing
checksum-verified shader stack. The tested configuration is Forge 1.20.1,
Oculus 1.8.0, Embeddium 0.3.31, and Complementary Reimagined r5.9.3.

    $env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
    $env:GRADLE_USER_HOME = 'D:\DevCaches\.gradle'
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
    python tools/fps_benchmark.py --target forge-1.20.1 --width 1280 --height 720 --rounds 3 --shader-cache "D:\Workspaces\Minecraft Projects\MC Mods\Simply-Mirrors\build\shader-dependencies"

The isolated graphical client is launched in the logged-in interactive Windows
session, not Session 0, and its own non-minimized GLFW window is moved offscreen.
No existing user game or Prism instance is changed.

## Controlled A/B/C comparison

1. No-mirror baseline: a white wall, fixed colored markers, steady sunlight.
2. One mirror: same world, same camera, one mirror block replacing wall.
3. Nine coplanar mirrors: extend to a 3x3 mirror array in the same plane.

The same shader ZIP, renderer stack, resolution, player camera, seed and world
stay fixed. Each stage uses 8 seconds of game-frame warmup followed by at least
15 seconds of measured frames. For --rounds 3, each scenario is repeated three
times in alternating order (ABC/CBA/ABC); every mirror case is compared to the
baseline recorded in the same round, then a median across rounds is reported. The test-only mixin samples elapsed monotonic
time between consecutive Minecraft.runTick TAIL events. Frame intervals
include real render scheduling and buffer swaps; they are not per-pass GPU
timestamp queries. Fps = number of frames / sum(frame intervals in seconds).
Median and p95 frame times are also recorded.

The client asserts that shaders are ACTIVE, the GLFW window is not minimized,
the OpenGL error flag is clear, and the expected reflection pass counts
(no-mirror = 0, mirror stages > 0) are observed. Screenshots are captured
after each measurement window, not while timing. The OpenGL vendor/renderer
and NVIDIA utilization/memory/temperature snapshots are part of the evidence.

## Retained files

Each isolated run keeps its server.log and client.log, summary.json,
gpu-*.txt, failure.txt (if any), plus scene-7/8/9.json raw frame intervals
and scene-7/8/9.png screenshots in the disposable client directory.

A failed shader, missing mirror pass, minimized GLFW window, GL error,
missing screenshot, too few frames, client crash or server failure must
not produce a success report. The user is never sent a synthetic number
under a real-game label.

A single A/B/C run is exploratory. Use --rounds 3 or more for repeated,
alternating trial comparisons; preserve per-round variation and inspect the
captured reflections. Hardware driver data must not be conflated with llvmpipe CI
compositor timings. The script cleans up only its own launched client and server.
