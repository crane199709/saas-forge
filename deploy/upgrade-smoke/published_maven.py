#!/usr/bin/env python3
"""只读下载实际发布的 0.1.0，隔离本机同坐标 install 缓存。"""
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

ORIGIN = 'https://maven.pkg.github.com'
REPOSITORY = ORIGIN + '/crane199709/saas-forge'
VERSION = '0.1.0'
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def fetch(url, authorization):
    opener = urllib.request.build_opener(NoRedirect)
    for hop in range(6):
        headers = {'Authorization': authorization} if hop == 0 else {}
        try:
            with opener.open(urllib.request.Request(url, headers=headers), timeout=60) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            if error.code not in (301, 302, 303, 307, 308):
                raise RuntimeError(f'发布输入下载 HTTP {error.code}') from None
            url = urllib.parse.urljoin(url, error.headers['Location'])
            target = urllib.parse.urlparse(url)
            if target.scheme != 'https' or target.username or target.password:
                raise RuntimeError('拒绝不安全的发布输入重定向')
    raise RuntimeError('发布输入重定向过多')


def main(destination, cache=None):
    destination = Path(destination)
    destination.mkdir(parents=True, exist_ok=True)
    cache = Path(cache) if cache else None
    cached = {}
    if cache and (cache / 'maven-provenance.json').exists():
        cached = {Path(entry['file']).name: entry for entry in json.loads((cache / 'maven-provenance.json').read_text())
                  if entry.get('status') == 200}
    elif cache and (cache / 'provenance.json').exists():
        manifest = json.loads((cache / 'provenance.json').read_text())
        if manifest['version'] != VERSION or manifest['repository'] != REPOSITORY:
            raise RuntimeError('发布缓存的版本或来源不符')
        cached = {Path(entry['file']).name: entry for entry in manifest['artifacts']}
    authorization = None
    records = []

    def download(artifact, suffix):
        nonlocal authorization
        filename = f'{artifact}-{VERSION}.{suffix}'
        relative = f'io/saasforge/{artifact}/{VERSION}/{filename}'
        url = REPOSITORY + '/' + relative
        output = destination / relative
        previous = cached.get(filename)
        cached_file = (cache / previous['file']).resolve() if previous else None
        if cached_file and not cached_file.is_relative_to(cache.resolve()):
            raise RuntimeError('发布缓存文件超出缓存目录')
        if previous and previous['url'] == url and cached_file.exists():
            data = cached_file.read_bytes()
            if hashlib.sha256(data).hexdigest() != previous['sha256']:
                raise RuntimeError(f'已发布缓存摘要不符：{filename}')
        else:
            if authorization is None:
                token = os.environ.get('GITHUB_TOKEN') or os.environ.get('GH_TOKEN')
                if not token:
                    token = subprocess.check_output(['gh', 'auth', 'token'], text=True).strip()
                authorization = 'Basic ' + base64.b64encode(('forge-consumer:' + token).encode()).decode()
            for attempt in range(3):
                try:
                    data = fetch(url, authorization)
                    break
                except urllib.error.URLError:
                    if attempt == 2:
                        raise RuntimeError(f'发布输入网络读取失败：{filename}') from None
                    time.sleep(2)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(data)
        records.append({'url': url, 'file': relative, 'sha256': hashlib.sha256(data).hexdigest(), 'size': len(data)})
        return data

    queue = ['forge-service-archetype', 'forge-dependencies']
    visited = set()
    while queue:
        artifact = queue.pop(0)
        if artifact in visited:
            continue
        visited.add(artifact)
        pom = ET.fromstring(download(artifact, 'pom'))
        if pom.findtext('m:packaging', 'jar', NS) not in ('pom',):
            download(artifact, 'jar')
        for dependency in pom.findall('.//m:dependency', NS) + pom.findall('m:parent', NS):
            if dependency.findtext('m:groupId', '', NS) == 'io.saasforge':
                queue.append(dependency.findtext('m:artifactId', '', NS))
    (destination / 'provenance.json').write_text(json.dumps({'version': VERSION, 'repository': REPOSITORY,
        'artifacts': records}, ensure_ascii=False, indent=2) + '\n')
    print(f'实际发布输入：{len(records)} 文件；来源与摘要：{destination / "provenance.json"}', flush=True)


if __name__ == '__main__':
    main(*sys.argv[1:])
