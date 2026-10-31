#!/usr/bin/env python3
"""等待指定 npm 版本的元数据与 tarball 均可下载。"""
import argparse
import json
import os
import subprocess
import tempfile
import time
from pathlib import Path
from urllib.parse import quote, urlsplit

PACKAGES = ('sdk', 'ui', 'sub-app-kit')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('version')
    parser.add_argument('--timeout', type=float, default=1200)
    parser.add_argument('--interval', type=float, default=15)
    args = parser.parse_args()
    registry = os.environ['NPM_RELEASE_REGISTRY'].rstrip('/')
    if args.timeout <= 0 or args.interval <= 0:
        parser.error('timeout and interval must be positive')
    deadline = time.monotonic() + args.timeout
    pending = set(PACKAGES)
    # npm 扫描期间可先出现元数据、后出现 tarball，两者必须同时可读。
    with tempfile.TemporaryDirectory() as work:
        target = Path(work) / 'response'

        def fetch(url, save=False):
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return False
            if urlsplit(url)[:2] != urlsplit(registry)[:2]:
                raise SystemExit('npm tarball must use the configured registry origin')
            # 凭证走 stdin，不进入进程参数或错误输出；不跟随重定向外泄凭证。
            token = os.environ.get('NPM_RELEASE_TOKEN', '')
            config = 'header = "Accept: application/vnd.npm.install-v1+json"\n'
            if token:
                config += 'header = ' + json.dumps('Authorization: Bearer ' + token)
            result = subprocess.run([
                'curl', '--disable', '--silent', '--max-time', str(min(30, remaining)),
                '--config', '-', '--output', str(target) if save else os.devnull,
                '--write-out', '%{http_code}', url,
            ], input=config, text=True, capture_output=True)
            if result.returncode:
                if result.returncode in (6, 7, 18, 28, 52, 55, 56):
                    return False
                raise SystemExit(f'npm availability request failed: curl exit {result.returncode}')
            status = int(result.stdout)
            if status == 200:
                return True
            if status in (404, 408, 425, 429, 500, 502, 503, 504):
                return False
            raise SystemExit(f'npm availability request failed: HTTP {status}')

        while pending and time.monotonic() < deadline:
            for package in sorted(pending):
                name = '@crane199709/' + package
                package_path = quote(name, safe='@').replace('%2F', '%2f')
                if not fetch(registry + '/' + package_path, save=True):
                    continue
                packument = json.loads(target.read_text())
                metadata = packument.get('versions', {}).get(args.version)
                if metadata is None:
                    continue
                if metadata.get('version') != args.version:
                    raise SystemExit(f'npm version mismatch: {name}@{args.version}')
                tarball = metadata.get('dist', {}).get('tarball')
                if not isinstance(tarball, str) or not tarball:
                    raise SystemExit(f'npm metadata missing tarball: {name}@{args.version}')
                if fetch(tarball):
                    pending.remove(package)
                    print(f'npm available: {name}@{args.version}', flush=True)
            if pending:
                remaining = deadline - time.monotonic()
                if remaining > 0:
                    print('Waiting for npm visibility: ' + ', '.join(sorted(pending)), flush=True)
                    time.sleep(min(args.interval, remaining))
        if pending:
            raise SystemExit(f'npm availability timed out after {args.timeout:g}s: ' + ', '.join('@crane199709/' + name + '@' + args.version for name in sorted(pending)))
    print(f'npm packages available: {args.version}')


if __name__ == '__main__':
    main()
