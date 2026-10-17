"""Check a production jar's identity, loader, version range and Java compatibility."""
from __future__ import annotations
import json
from pathlib import Path
import sys
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TARGETS = {t["target"]: t for t in json.loads((ROOT / "tools/targets.json").read_text())}

def verify(path, target):
    with zipfile.ZipFile(path) as jar:
        names = set(jar.namelist())
        assert "LICENSE" in names, "License missing"
        assert "com/nstut/simplymirrors/SimplyMirrors.class" in names, "Shared bootstrap missing"
        assert "com/nstut/simplymirrors/math/MirrorPlane.class" in names, "Shared reflection core missing"
        expected_java = int(target["java"]) + 44
        for name in names:
            if name.endswith(".class"):
                data = jar.read(name)
                assert data[:4] == b"\xca\xfe\xba\xbe", f"Invalid class: {name}"
                assert int.from_bytes(data[6:8], "big") <= expected_java, f"Java target exceeded: {name}"
        loader = target["loader"]
        metadata = "fabric.mod.json" if loader == "fabric" else ("META-INF/mods.toml" if loader == "forge" else "META-INF/neoforge.mods.toml")
        raw = jar.read(metadata).decode()
        assert "${" not in raw, "Unexpanded metadata"
        if loader == "fabric":
            data = json.loads(raw)
            assert data["id"] == "simply_mirrors"
            assert data["depends"]["minecraft"] == target["minecraft"]
            assert data["depends"]["java"] == f">={target['java']}"
            for entry in data["entrypoints"]["main"]:
                assert entry.replace(".", "/") + ".class" in names, "Entrypoint missing"
        else:
            data = tomllib.loads(raw)
            assert data["mods"][0]["modId"] == "simply_mirrors"
            dependencies = {d["modId"]: d for d in data["dependencies"]["simply_mirrors"]}
            assert dependencies["minecraft"]["versionRange"] == f"[{target['minecraft']}]"
            assert loader in dependencies
            expected = f"com/nstut/simplymirrors/{loader}/SimplyMirrors{loader.title()}.class"
            assert expected in names, "Entrypoint missing"
        assert not any(n.startswith("org/junit/") for n in names), "Test dependency packaged"
        for other in ["fabric.mod.json", "META-INF/mods.toml", "META-INF/neoforge.mods.toml"]:
            assert other == metadata or other not in names, f"Wrong loader metadata: {other}"

def main():
    name = sys.argv[1]
    target = TARGETS[name]
    # The mod version deliberately contains 'dev'; select by exact expected artifact name instead.
    version = next(line.split("=", 1)[1].strip() for line in (ROOT / "gradle.properties").read_text().splitlines() if line.startswith("mod_version="))
    jars = [ROOT / name / "build/libs" / f"simply_mirrors-{name}-{version}.jar"]
    for path in jars:
        assert path.is_file(), f"Production jar missing: {path}"
        verify(path, target)
        print(f"Verified {path.name}")

if __name__ == "__main__":
    main()
