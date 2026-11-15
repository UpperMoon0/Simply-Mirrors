"""Launch the exact exported Loom game JVM after Gradle has exited."""
from __future__ import annotations
import json
import os
from pathlib import Path
import subprocess


def quote_java_argument(value: str) -> str:
    if "\n" in value or "\r" in value or "\x00" in value:
        raise ValueError("Unexpected line break in native launch argument")
    return '"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"'


def export_launch(command: list[str], root: Path, manifest: Path,
                  log_path: Path, env: dict, timeout: float) -> tuple[list[str], Path, dict]:
    invocation = command + ["--init-script", str(root / "tools/export_visual_launch.gradle"),
                            "-PvisualLaunchManifest=" + str(manifest.resolve())]
    # Do not inherit experimental render flags into the preparation JVM.
    preparation = dict(env)
    preparation.pop("JAVA_TOOL_OPTIONS", None)
    with log_path.open("w", encoding="utf-8") as log:
        subprocess.run(invocation, cwd=root, env=preparation, stdout=log,
                       stderr=subprocess.STDOUT, check=True, timeout=timeout,
                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    data = json.loads(manifest.read_text(encoding="utf-8"))
    if data.get("schema") != 1 or set(data["environment"]) - {"MOD_CLASSES", "MCP_MAPPINGS"}:
        raise RuntimeError("Unexpected exported native launch schema/environment")
    java = Path(data["java"])
    work = Path(data["cwd"]).resolve()
    if not java.is_file() or not work.is_relative_to(root.resolve()):
        raise RuntimeError("Exported game launch escaped the isolated checkout")
    arguments = data["arguments"]
    if not arguments or not all(isinstance(arg, str) and not arg.startswith("@") for arg in arguments):
        raise RuntimeError("Invalid or nested native launch arguments")
    arg_file = manifest.with_suffix(".args")
    arg_file.write_text("\n".join(quote_java_argument(arg) for arg in arguments) + "\n", encoding="utf-8")
    work.mkdir(parents=True, exist_ok=True)
    return [str(java), "@" + str(arg_file.resolve())], work, data["environment"]
