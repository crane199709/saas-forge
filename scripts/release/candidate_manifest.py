#!/usr/bin/env python3
"""记录并核对候选来源及实际 Maven/npm 包，避免 SNAPSHOT 名称掩盖内容变化。"""
import hashlib
import json
from pathlib import Path
import subprocess
import sys


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    mode, directory, *arguments = sys.argv[1:]
    work = Path(directory).resolve()
    manifest = work / 'candidate.json'
    if mode == 'create':
        root, version = arguments
        files = sorted(path for name in ('maven-repository', 'tarballs')
                       for path in (work / name).rglob('*') if path.is_file())
        data = {
            'sourceCommit': subprocess.check_output(['git', '-C', root, 'rev-parse', 'HEAD'], text=True).strip(),
            'sourceVersion': version,
            'sourceChanges': subprocess.check_output(['git', '-C', root, 'diff', '--name-only', 'HEAD'], text=True).splitlines(),
            'mavenRepository': (work / 'maven-repository').as_uri(),
            'npmSource': 'tarballs/; candidate.sh use 重建本机临时 registry',
            'artifacts': {str(path.relative_to(work)): digest(path) for path in files},
        }
        manifest.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
        print(f'候选来源与制品摘要：{manifest}')
    elif mode == 'verify':
        data = json.loads(manifest.read_text())
        if data['sourceChanges']:
            raise SystemExit('候选包含未提交源码，须从干净提交重新构建')
        actual = {str(path.relative_to(work)) for directory in ('maven-repository', 'tarballs')
                  for path in (work / directory).rglob('*') if path.is_file()}
        if not data['artifacts'] or actual != set(data['artifacts']):
            raise SystemExit('候选制品清单与目录内容不一致')
        for name, expected in data['artifacts'].items():
            path = (work / name).resolve()
            if not path.is_relative_to(work) or not path.is_file() or digest(path) != expected:
                raise SystemExit(f'候选制品不存在或摘要不符：{name}')
        print(f"候选校验通过：{data['sourceCommit']} / {data['sourceVersion']}")
    else:
        raise SystemExit('模式必须为 create 或 verify')


if __name__ == '__main__':
    main()
