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


def hide_values(text, secrets):
    for secret in secrets:
        text = text.replace(secret, '[redacted value]')
    return text


def secret_values(value):
    if isinstance(value, dict):
        return [text for item in value.values() for text in secret_values(item)]
    if isinstance(value, list):
        return [text for item in value for text in secret_values(item)]
    return [value] if isinstance(value, str) and len(value) >= 8 and not value.startswith('$') else []


def known_secrets(files):
    secrets = {value for name, value in os.environ.items() if SENSITIVE.search(name) and len(value) >= 8}
    def from_json(value):
        if isinstance(value, dict):
            for key, item in value.items():
                if SENSITIVE.search(key):
                    secrets.update(secret_values(item))
                else:
                    from_json(item)
        elif isinstance(value, list):
            for item in value:
                from_json(item)
    for path in files:
        if path.suffix not in {'.json', '.log', '.yml', '.yaml', '.xml', '.properties', '.env'}:
            continue
        text = path.read_text(errors='replace')
        if path.suffix == '.json':
            try:
                from_json(json.loads(text))
            except ValueError:
                pass  # 失败时可能只写出半份证据，原始文件仍留在 private。
        for match in re.finditer(r"(?i)[\w.-]*(?:password|passwd|token|secret|credential)[\w.-]*\s*[:=]\s*[\"']?([^\s\"',}<]+)", text):
            secrets.update(secret_values(match[1]))
        secrets.update(re.findall(r'(?i)Bearer ([A-Za-z0-9_.-]{8,})', text))
    return sorted(secrets, key=len, reverse=True)


def clean_text(text, secrets):
    # 失败行原样保留业务错误与栈；任何凭证上下文整行丢弃，URL 整体替换而非仅删签名。
    text = hide_values(text, secrets)
    return '\n'.join('[redacted credential line]' if SENSITIVE.search(line) else
                     JWT.sub('[redacted jwt]', URL.sub('[redacted url]', line))
                     for line in text.splitlines()) + '\n'


def clean_json(value, secrets):
    if isinstance(value, dict):
        return {key: '[redacted]' if SENSITIVE.search(key) else clean_json(item, secrets)
                for key, item in value.items()}
    if isinstance(value, list):
        return [clean_json(item, secrets) for item in value]
    if isinstance(value, str):
        return JWT.sub('[redacted jwt]', URL.sub('[redacted url]', hide_values(value, secrets)))
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
    files = []
    for directory, directories, names in os.walk(private):
        directories[:] = [name for name in directories if name not in EXCLUDED]
        files.extend(Path(directory) / name for name in names if not (Path(directory) / name).is_symlink())
    secrets = known_secrets(files)
    for source in files:
        name = source.name
        relative = source.relative_to(private)
        if source.suffix == '.log':
            payload = clean_text(source.read_text(errors='replace'), secrets)
        elif name in JSON_NAMES:
            try:
                value = json.loads(source.read_text())
            except ValueError:
                value = {'diagnostic': 'incomplete JSON; inspect private original'}
            payload = json.dumps(clean_json(value, secrets), ensure_ascii=False, indent=2) + '\n'
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
    (output / 'summary.json').write_text(json.dumps(clean_json(state, secrets), ensure_ascii=False, indent=2) + '\n')
    print(f'可上传诊断：{output}')


if __name__ == '__main__':
    main()
