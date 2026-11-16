#!/usr/bin/env python3
"""发布前检查实际 npm tarball 的入口与 workspace 依赖。"""
import json
from pathlib import Path
import sys
import tarfile


def check_tarball(path):
    with tarfile.open(path) as archive:
        package = json.load(archive.extractfile('package/package.json'))
        assert not package.get('private'), path.name
        assert 'workspace:' not in json.dumps(package), path.name
        targets = list(package.get('bin', {}).values())
        for export in package['exports'].values():
            targets.extend([export] if isinstance(export, str) else export.values())
        for target in targets:
            assert 'package/' + target.removeprefix('./') in archive.getnames(), target
        assert all(target.endswith('.js') for target in package.get('bin', {}).values())


if __name__ == '__main__':
    for path in Path(sys.argv[1]).glob('*.tgz'):
        check_tarball(path)
