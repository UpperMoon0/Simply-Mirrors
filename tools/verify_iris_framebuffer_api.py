#!/usr/bin/env python3
"""Check the exact optional Iris 1.8.x framebuffer hook contract used in production.

This intentionally depends only on checksum-pinned renderer JARs, not a modpack.
A Mixin injection with require=0 silently stops running if Iris renames the
method; that regression previously made native GL unit tests pass while actual
shader reflections failed. Pair this API check with real visual acceptance.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import zipfile

from shader_stack import load_lock, ROOT


IRIS_CLASS = "net.irisshaders.iris.gl.framebuffer.GlFramebuffer"
REQUIRED_METHODS = (
    "public void addDepthAttachment(int);",
    "public void bind();",
    "public void bindAsDrawBuffer();",
    "public void bindAsReadBuffer();",
    "public int getId();",
)
MIXIN_NAME = "IrisFramebufferParityMixin"


def verify(cache: Path) -> None:
    stack = load_lock()["stacks"]["neoforge-1.21.1"]
    filenames = {entry["filename"]: entry for entry in stack}
    iris_name = "iris-neoforge-1.8.12+mc1.21.1.jar"
    sodium_name = "sodium-neoforge-0.6.13+mc1.21.1.jar"
    if iris_name not in filenames or sodium_name not in filenames:
        raise AssertionError("Renderer lock changed; review the Iris FBO hook against the new versions")
    iris = cache / iris_name
    if not iris.is_file():
        raise FileNotFoundError(f"Missing checksum-pinned Iris dependency: {iris}")
    digest = hashlib.sha512(iris.read_bytes()).hexdigest()
    if digest != filenames[iris_name]["sha512"]:
        raise AssertionError(f"Iris JAR SHA-512 mismatch: {iris}")
    with zipfile.ZipFile(iris) as archive:
        expected_class = IRIS_CLASS.replace(".", "/") + ".class"
        if expected_class not in archive.namelist():
            raise AssertionError(f"Pinned Iris is missing {expected_class}")
    output = subprocess.check_output(
        ["javap", "-p", "-classpath", str(iris), IRIS_CLASS],
        text=True, timeout=30)
    for signature in REQUIRED_METHODS:
        if signature not in output:
            raise AssertionError(f"Iris FBO method missing: {signature}")
    resources = ROOT / "common-1.21.1/src/main/resources/simplymirrors.mixins.json"
    mixins = json.loads(resources.read_text())
    if MIXIN_NAME not in mixins.get("client", []):
        raise AssertionError("Production Iris parity mixin is not registered in client mixin metadata")
    source = (ROOT / "common-1.21.1/src/main/java/com/nstut/simplymirrors/"
              "client/mixin/IrisFramebufferParityMixin.java").read_text()
    if IRIS_CLASS not in source:
        raise AssertionError("Production mixin targets the wrong Iris framebuffer class")
    for method in ("addDepthAttachment", "bind", "bindAsDrawBuffer", "bindAsReadBuffer"):
        if f'@Inject(method = "{method}"' not in source:
            raise AssertionError(f"Required Iris reflection hook missing: {method}")
    if source.count("ShaderReflection.onIrisFramebufferBound(getId())") < 4:
        raise AssertionError("Iris hooks no longer call the scoped framebuffer repair")
    print(f"PASS Iris reflection FBO hook: {iris_name}, Sodium lock: {sodium_name}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=ROOT / "build/shader-dependencies")
    args = parser.parse_args()
    verify(args.cache)
