"""Verify the optional history bridge against the exact eight checksum-pinned shader jars.

Usage: python tools/verify_shader_history_api.py DIRECTORY_CONTAINING_LOCKED_JARS
Requires a JDK with javap supporting every target's bytecode (JDK 25).
"""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import zipfile


ROOT = Path(__file__).resolve().parents[1]


def verify(artifact, directory):
    path = directory/artifact["filename"]
    if hashlib.sha512(path.read_bytes()).hexdigest() != artifact["sha512"]:
        raise ValueError(f"Pinned SHA-512 mismatch: {path.name}")
    with zipfile.ZipFile(path) as jar:
        names = set(jar.namelist())
    prefix = "net.irisshaders.iris" if "net/irisshaders/iris/Iris.class" in names else "net.coderbot.iris"
    pipeline = prefix + (".pipeline.IrisRenderingPipeline" if prefix.startswith("net.irisshaders")
                         else ".pipeline.DeferredWorldRenderingPipeline")
    targets = ".targets" if prefix.startswith("net.irisshaders") else ".rendertarget"
    properties = ".shaderpack.properties" if prefix.startswith("net.irisshaders") else ".shaderpack"
    required = {
        prefix+".Iris": ["getPipelineManager();"],
        prefix+".pipeline.PipelineManager": ["getPipelineNullable();"],
        pipeline: [" renderTargets;", " packDirectives;", " shadowRenderTargets;"],
        prefix+targets+".RenderTargets": [" get(int);"],
        prefix+targets+".RenderTarget": ["private final int mainTexture;", "private final int altTexture;",
                                       "public int getMainTexture();", "public int getAltTexture();"],
        prefix+properties+".PackDirectives": [" getRenderTargetDirectives();"],
        prefix+properties+".PackRenderTargetDirectives": [" getRenderTargetSettings();"],
        prefix+properties+".PackRenderTargetDirectives$RenderTargetSettings": ["public boolean shouldClear();"],
        prefix+".shadows.ShadowRenderTargets": ["private final boolean[] flipped;", " getBuffersToBeCleared();",
                                              "public int getRenderTargetCount();", " get(int);"],
    }
    output = subprocess.check_output(["javap", "-p", "-classpath", str(path), *required], text=True)
    for class_name, members in required.items():
        start = re.search(r"(?:class|interface) "+re.escape(class_name)+r"(?:\s|<)", output)
        if start is None: raise ValueError(f"Missing class {class_name}: {path.name}")
        body = output[start.start():].split("\n}", 1)[0]
        for member in members:
            if member not in body: raise ValueError(f"Missing {class_name}.{member}: {path.name}")
    return path.name


def main():
    directory = Path(sys.argv[1])
    lock = json.loads((ROOT/"tools/shader/stack-lock.json").read_text())
    artifacts = [artifact for stack in lock["stacks"].values() for artifact in stack
                 if any(name in artifact["filename"] for name in ("iris", "oculus"))]
    with ThreadPoolExecutor(max_workers=4) as pool:
        for name in pool.map(lambda artifact: verify(artifact, directory), artifacts):
            print("PASS pinned history API:", name)
    print(f"Verified {len(artifacts)} pinned Iris/Oculus artifact APIs and SHA-512 hashes")


if __name__ == "__main__":
    main()
