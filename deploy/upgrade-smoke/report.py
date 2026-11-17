#!/usr/bin/env python3
"""只导出来源、结果、脱敏日志和场景截图；原始工程与凭证留在本地 private。"""
import json
import os
from pathlib import Path
import re
import shutil
import sys

JSON_NAMES = {'candidate.json', 'baseline.json', 'provenance.json', 'http-evidence.json',
              'upgrade-audit.json', 'package-audit.json', 'baseline-source-sha256.json'}
EXCLUDED = {'m2', 'node_modules', 'maven-repository', '.git', 'dist', 'target', 'old-kit'}
SENSITIVE = re.compile(r'password|passwd|token|secret|authorization|credential|cookie', re.I)
URL = re.compile(r'(?:https?|file|jdbc):[^\s\"\'<>]+', re.I)
JWT = re.compile(r'eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+')


def clean_text(text):
    # 失败行原样保留业务错误与栈；任何凭证上下文整行丢弃，URL 整体替换而非仅删签名。
    return '\n'.join('[redacted credential line]' if SENSITIVE.search(line) else
                     JWT.sub('[redacted jwt]', URL.sub('[redacted url]', line))
                     for line in text.splitlines()) + '\n'


def clean_json(value):
    if isinstance(value, dict):
        return {key: '[redacted]' if SENSITIVE.search(key) else clean_json(item)
                for key, item in value.items()}
    if isinstance(value, list):
        return [clean_json(item) for item in value]
    if isinstance(value, str):
        return JWT.sub('[redacted jwt]', URL.sub('[redacted url]', value))
    return value


def main():
    work = Path(sys.argv[1]).resolve()
    private, output = work / 'private', work / 'diagnostics'
    output.mkdir(exist_ok=True)
    # 每次重新生成，不让先前报告遗留的材料混入本次上传。
    for item in output.iterdir():
        if item.is_dir():
            shutil.rmtree(item)
        else:
            item.unlink()
    for directory, directories, names in os.walk(private):
        directories[:] = [name for name in directories if name not in EXCLUDED]
        for name in names:
            source = Path(directory) / name
            if source.is_symlink():
                continue
            relative = source.relative_to(private)
            if source.suffix == '.log':
                payload = clean_text(source.read_text(errors='replace'))
            elif name in JSON_NAMES:
                payload = json.dumps(clean_json(json.loads(source.read_text())), ensure_ascii=False, indent=2) + '\n'
            elif source.suffix == '.png' and 'evidence' in relative.parts:
                target = output / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, target)
                continue
            else:
                continue
            target = output / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(payload)
    state = json.loads((private / 'state.json').read_text())
    (output / 'summary.json').write_text(json.dumps(clean_json(state), ensure_ascii=False, indent=2) + '\n')
    print(f'可上传诊断：{output}')


if __name__ == '__main__':
    main()
