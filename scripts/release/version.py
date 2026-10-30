#!/usr/bin/env python3
"""同步 reactor 与前端版本，或在发布前验证标签与所有内部坐标。"""
import argparse
import json
import re
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}


def poms(path):
    yield path
    tree = ET.parse(path)
    for module in tree.findall('m:modules/m:module', NS):
        yield from poms(path.parent / module.text / 'pom.xml')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['set', 'check'])
    parser.add_argument('version', help='set: 0.1.0；check: v0.1.0')
    args = parser.parse_args()
    version = args.version.removeprefix('v') if args.action == 'check' else args.version
    if not re.fullmatch(r'(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?', version) or 'SNAPSHOT' in version.upper():
        parser.error('需要非 SNAPSHOT 的语义版本')
    if args.action == 'check' and args.version != f'v{version}':
        parser.error('标签必须为 v<version>')
    prerelease = version.partition('-')[2]
    if any(part.isdigit() and len(part) > 1 and part.startswith('0') for part in prerelease.split('.')):
        parser.error('预发布版本的数字段不能带前导零')
    errors = []
    for path in poms(ROOT / 'backend/pom.xml'):
        source = path.read_text()
        # 只更新 io.saasforge 坐标；第三方依赖版本保持原样。
        tree = ET.fromstring(source)
        for node in tree.iter():
            group = node.find('m:groupId', NS)
            value = node.find('m:version', NS)
            if group is not None and group.text == 'io.saasforge' and value is not None:
                if value.text == '${project.version}':
                    continue
                if args.action == 'set':
                    source = re.sub(
                        r'(<groupId>io\.saasforge</groupId>\s*<artifactId>[^<]+</artifactId>\s*<version>)'
                        + re.escape(value.text) + r'(</version>)',
                        lambda match: match[1] + version + match[2], source,
                    )
                elif value.text != version:
                    errors.append(f'{path.relative_to(ROOT)}: {value.text} != {version}')
        if args.action == 'set':
            path.write_text(source)
    packages = [ROOT / 'frontend/package.json', *sorted((ROOT / 'frontend/packages').glob('*/package.json')), *sorted((ROOT / 'frontend/apps').glob('*/package.json'))]
    for path in packages:
        data = json.loads(path.read_text())
        if args.action == 'set':
            data['version'] = version
            path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
        elif data.get('version') != version:
            errors.append(f'{path.relative_to(ROOT)}: {data.get("version")} != {version}')
    if errors:
        parser.exit(1, '\n'.join(errors) + '\n')
    print(version)


if __name__ == '__main__':
    main()
