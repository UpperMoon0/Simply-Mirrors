#!/usr/bin/env python3
"""Install checksum-pinned, loader-specific shader development dependencies.

Only official Modrinth CDN artifacts from the reviewed lock are accepted. No
third-party metadata is patched to force an incompatible stack to load.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import zipfile
import tomllib
import urllib.request
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / 'tools/shader/stack-lock.json'

def load_lock():
    data = json.loads(LOCK.read_text())
    if data.get('schema') != 1: raise ValueError('unknown shader lock schema')
    for target, artifacts in data['stacks'].items():
        loader, game = target.split('-', 1)
        for artifact in artifacts:
            if game not in artifact['game_versions'] or loader not in artifact['loaders']:
                raise ValueError(f'incompatible locked artifact for {target}: {artifact["filename"]}')
    for artifact in [data['shaderpack'], *sum(data['stacks'].values(), [])]:
        if urlsplit(artifact['url']).netloc != 'cdn.modrinth.com': raise ValueError('untrusted artifact host')
        if len(artifact['sha512']) != 128 or any(c not in '0123456789abcdef' for c in artifact['sha512']):
            raise ValueError('invalid artifact checksum')
        if Path(artifact['filename']).name != artifact['filename']: raise ValueError('unsafe artifact name')
    return data

def download(artifact, cache):
    cache.mkdir(parents=True, exist_ok=True)
    path = cache / artifact['filename']
    if path.exists() and hashlib.sha512(path.read_bytes()).hexdigest() == artifact['sha512']:
        return path
    request = urllib.request.Request(artifact['url'], headers={'User-Agent':'SimplyMirrors-shader-tests/1.0'})
    with urllib.request.urlopen(request, timeout=120) as response:
        content = response.read()
    if hashlib.sha512(content).hexdigest() != artifact['sha512']:
        raise ValueError(f'checksum mismatch: {artifact["filename"]}')
    temporary = path.with_suffix(path.suffix+'.tmp')
    temporary.write_bytes(content)
    temporary.replace(path)
    return path

def native_mod_ids(path, loader):
    """Read the actual downloaded native manifest, not the hosting site's project slug."""
    with zipfile.ZipFile(path) as jar:
        if loader == 'fabric':
            return [json.loads(jar.read('fabric.mod.json'))['id']]
        metadata = 'META-INF/mods.toml' if loader == 'forge' else 'META-INF/neoforge.mods.toml'
        return [m['modId'] for m in tomllib.loads(jar.read(metadata).decode())['mods']]

def prepare(target, cache):
    lock = load_lock()
    dependencies = [download(a, cache) for a in lock['stacks'][target]]
    pack = download(lock['shaderpack'], cache)
    return dependencies, pack

def install_pack(client, pack, enabled=True, loader="fabric"):
    shaders = client / 'shaderpacks'
    config = client / 'config'
    shaders.mkdir(parents=True, exist_ok=True)
    config.mkdir(parents=True, exist_ok=True)
    shutil.copy2(pack, shaders / pack.name)
    # Oculus1.8 forks the public API but reads a different config filename.
    filename='oculus.properties' if loader=='forge' else 'iris.properties'
    (config/filename).write_text(f'enableShaders={str(enabled).lower()}\nshaderPack={pack.name}\n')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target', required=True, choices=load_lock()['stacks'])
    parser.add_argument('--cache', type=Path, default=ROOT/'build/shader-dependencies')
    parser.add_argument('--client', type=Path, help='Install shaderpack/config into this development client directory')
    args=parser.parse_args()
    dependencies, pack = prepare(args.target, args.cache.resolve())
    if args.client: install_pack(args.client, pack, loader=args.target.split('-',1)[0])
    print(json.dumps({'target':args.target,'jars':[str(p) for p in dependencies],'shaderpack':str(pack),
                      'gradle_property':f'-PmirrorsShaderStack={args.cache.resolve()}'},indent=2))

if __name__ == '__main__': main()
