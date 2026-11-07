"""Collect only the eight verified production artifacts; never publish a release."""
from pathlib import Path
import shutil
from release import ROOT, TARGETS, collect, expected_name, version


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


if __name__ == '__main__':
    main()
