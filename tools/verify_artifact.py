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
        assert "pack.mcmeta" in names, "Mod resource pack metadata missing"
        pack = json.loads(jar.read("pack.mcmeta"))["pack"]
        if target["minecraft"] == "26.1.2":
            assert pack["min_format"] == 84 and pack["max_format"] == [101, 1], "Modern resource/data pack format range incorrect"
        else:
            assert pack["pack_format"] == {"1.16.5": 6, "1.20.1": 15, "1.21.1": 34}[target["minecraft"]]
        assert "LICENSE" in names, "License missing"
        assert "NOTICE" in names and "licenses/Immersive-Portals-CE-Apache-2.0.txt" in names, "CE attribution missing"
        for cl in ["block/MirrorBlock", "block/MirrorBlockEntity", "client/MirrorRenderer", "math/ImmersiveMirrorTransform"]:
            assert f"com/nstut/simplymirrors/{cl}.class" in names, f"Missing implementation: {cl}"
        assert "simplymirrors.mixins.json" in names, "Client rendering mixins missing"
        mixins = json.loads(jar.read("simplymirrors.mixins.json"))
        assert mixins.get("client") and not mixins.get("mixins"), "Renderer mixins must remain client-only"
        for resource in ["blockstates/mirror.json", "models/block/mirror.json", "models/item/mirror.json", "lang/en_us.json"]:
            json.loads(jar.read(f"assets/simply_mirrors/{resource}"))
        singular = target["minecraft"] in ["1.21.1", "26.1.2"]
        folder = "recipe" if singular else "recipes"
        recipe = json.loads(jar.read(f"data/simply_mirrors/{folder}/mirror.json"))
        assert recipe["result"].get("id", recipe["result"].get("item")) == "simply_mirrors:mirror"
        assert not any("VisualProbe" in name or "/visualtest/" in name or "/visualsupport/" in name for name in names), "Visual test code packaged"
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
