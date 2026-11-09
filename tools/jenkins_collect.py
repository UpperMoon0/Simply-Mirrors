"""Collect only the eight verified production artifacts; never publish a release."""
from pathlib import Path
import hashlib
import json
import re
import shutil
import subprocess
from release import ROOT, TARGETS, collect, expected_name, version


def write_provenance(output, sha, mod_version, targets):
    if not re.fullmatch(r'[a-f0-9]{40}', sha):
        raise ValueError('Invalid release source revision')
    path = output / 'provenance.json'
    path.write_text(json.dumps({'source_sha':sha,'version':mod_version,'targets':sorted(targets)},indent=2)+'\n')
    with (output/'SHA256SUMS').open('a') as stream:
        stream.write(hashlib.sha256(path.read_bytes()).hexdigest()+'  provenance.json\n')


def main():
    staging = ROOT / 'release-input'
    staging.mkdir()
    for target in TARGETS:
        name = expected_name(target, version())
        source = ROOT / target / 'build' / 'libs' / name
        if not source.is_file():
            raise ValueError(f'Missing production artifact: {target}')
        destination = staging / target
        destination.mkdir()
        shutil.copyfile(source, destination / name)
    collect(staging, ROOT / 'release-output')
    sha = subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    write_provenance(ROOT/'release-output',sha,version(),TARGETS)


if __name__ == '__main__':
    main()
