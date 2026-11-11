#!/usr/bin/env python3
"""同步 reactor 与前端版本，或验证所有内部坐标与发布标签 / 开发版本一致。"""
import argparse
import json
import re
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
SEMVER = r'(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?'
# sdkRuntime 写在源码里供运行时识别模块身份，不在 package.json 中，须随版本一起同步。
MAVEN_CONFIG = ROOT / 'backend/.mvn/maven.config'
REVISION_PATTERN = r'^-Drevision=(\S+)$'
SDK_RUNTIME = ROOT / 'frontend/packages/sdk/src/index.ts'
SDK_RUNTIME_PATTERN = r"(sdkRuntime = Object\.freeze\(\{ version: ')([^']+)('\s*\}\))"


def poms(path):
    yield path
    tree = ET.parse(path)
    for module in tree.findall('m:modules/m:module', NS):
        yield from poms(path.parent / module.text / 'pom.xml')


def sync(version, write):
    """write 为真时把全部内部坐标改为 version，否则返回与 version 不一致之处。"""
    errors = []
    # 后端版本只在 .mvn/maven.config 的 revision 维护；POM 中的 Forge 坐标一律写 ${revision}，由 flatten 在安装与发布时替换。
    config = MAVEN_CONFIG.read_text()
    match = re.search(REVISION_PATTERN, config, re.M)
    if match is None:
        errors.append(f'{MAVEN_CONFIG.relative_to(ROOT)}: 找不到 -Drevision')
    elif write:
        MAVEN_CONFIG.write_text(config[:match.start(1)] + version + config[match.end(1):])
    elif match[1] != version:
        errors.append(f'{MAVEN_CONFIG.relative_to(ROOT)}: revision {match[1]} != {version}')
    for path in poms(ROOT / 'backend/pom.xml'):
        tree = ET.parse(path).getroot()
        for node in tree.iter():
            group = node.find('m:groupId', NS)
            value = node.find('m:version', NS)
            if group is not None and group.text == 'io.saasforge' and value is not None \
                    and value.text not in ('${revision}', '${project.version}', '${forge.version}'):
                errors.append(f'{path.relative_to(ROOT)}: Forge 坐标须写 ${{revision}}，实际为 {value.text}')
        sdk_version = tree.find('m:properties/m:forge.version', NS)
        if sdk_version is not None and sdk_version.text != '${revision}':
            errors.append(f'{path.relative_to(ROOT)}: forge.version 须为 ${{revision}}，实际为 {sdk_version.text}')
    packages = [ROOT / 'frontend/package.json', *sorted((ROOT / 'frontend/packages').glob('*/package.json')), *sorted((ROOT / 'frontend/apps').glob('*/package.json'))]
    for path in packages:
        data = json.loads(path.read_text())
        if write:
            data['version'] = version
            path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
        elif data.get('version') != version:
            errors.append(f'{path.relative_to(ROOT)}: {data.get("version")} != {version}')
    source = SDK_RUNTIME.read_text()
    match = re.search(SDK_RUNTIME_PATTERN, source)
    if match is None:
        errors.append(f'{SDK_RUNTIME.relative_to(ROOT)}: 找不到 sdkRuntime 版本')
    elif write:
        SDK_RUNTIME.write_text(source[:match.start(2)] + version + source[match.end(2):])
    elif match[2] != version:
        errors.append(f'{SDK_RUNTIME.relative_to(ROOT)}: sdkRuntime {match[2]} != {version}')
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['set', 'check', 'dev'],
                        help='set：改写版本；check：发布前验证标签；dev：验证源码处于统一的 SNAPSHOT 开发版本')
    parser.add_argument('version', nargs='?', help='set: 0.2.0 或 0.2.0-SNAPSHOT；check: v0.2.0；dev 不需要')
    # 只供 publish.sh local（模板冒烟）使用：临时仓库验证开发中的源码，正式发布与标签仍拒绝 SNAPSHOT。
    parser.add_argument('--allow-snapshot', action='store_true', help='check 时接受 -SNAPSHOT 开发版本')
    args = parser.parse_args()
    if args.allow_snapshot and args.action != 'check':
        parser.error('--allow-snapshot 只用于 check')
    if args.action == 'dev':
        if args.version is not None:
            parser.error('dev 不接受版本参数')
        # 发布标签之后源码必须立刻进入下一个 SNAPSHOT，避免同一版本号对应多份内容。
        # 以前端根版本为基准；其余坐标与它不一致由 sync 报出。
        version = json.loads((ROOT / 'frontend/package.json').read_text())['version']
        if not version.endswith('-SNAPSHOT'):
            parser.exit(1, f'源码版本 {version} 不是 SNAPSHOT：发布后须执行 version.py set <下一版本>-SNAPSHOT\n')
    else:
        if args.version is None:
            parser.error(f'{args.action} 需要版本参数')
        version = args.version.removeprefix('v') if args.action == 'check' else args.version
        if not re.fullmatch(SEMVER, version):
            parser.error('需要语义版本')
        snapshot = version.endswith('-SNAPSHOT')
        snapshot_allowed = args.action == 'set' or args.allow_snapshot
        if 'SNAPSHOT' in version.upper() and not (snapshot_allowed and snapshot):
            parser.error('发布版本不能是 SNAPSHOT；开发版本只能以 -SNAPSHOT 结尾')
        if args.action == 'check' and args.version != f'v{version}':
            parser.error('标签必须为 v<version>')
        prerelease = version.partition('-')[2]
        if any(part.isdigit() and len(part) > 1 and part.startswith('0') for part in prerelease.split('.')):
            parser.error('预发布版本的数字段不能带前导零')
    errors = sync(version, args.action == 'set')
    if errors:
        parser.exit(1, '\n'.join(errors) + '\n')
    print(version)


if __name__ == '__main__':
    main()
