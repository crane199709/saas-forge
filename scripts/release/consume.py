#!/usr/bin/env python3
"""在仓库外创建消费工程；版本来自本次标签，不引用 reactor 或 workspace。"""
import json
import sys
from pathlib import Path
from xml.sax.saxutils import escape

root, version, maven_url = sys.argv[1:]
root = Path(root)
(root / 'maven').mkdir(parents=True)
repo = Path(__file__).resolve().parents[2]
artifacts = sorted(p.parent.name for p in (repo / 'backend/forge-sdk').glob('forge-sdk-*/pom.xml'))
artifacts.append('forge-architecture')
deps = ''.join(f'<dependency><groupId>io.saasforge</groupId><artifactId>{a}</artifactId></dependency>' for a in artifacts)
(root / 'maven/pom.xml').write_text(f'''<project xmlns="http://maven.apache.org/POM/4.0.0">
<modelVersion>4.0.0</modelVersion><groupId>smoke</groupId><artifactId>consumer</artifactId><version>1.0</version>
<repositories><repository><id>forge-release</id><url>{escape(maven_url)}</url></repository></repositories>
<dependencyManagement><dependencies><dependency><groupId>io.saasforge</groupId><artifactId>forge-dependencies</artifactId><version>{version}</version><type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement>
<dependencies>{deps}</dependencies></project>''')
(root / 'npm').mkdir()
(root / 'npm/package.json').write_text(json.dumps({'name': 'release-consumer', 'version': '1.0.0', 'private': True, 'type': 'module', 'dependencies': {f'@forge/{name}': version for name in ['sdk', 'ui', 'sub-app-kit']}, 'devDependencies': {'typescript': '5.9.3', '@types/node': '^24.19.2'}}, indent=2))
(root / 'npm/index.ts').write_text('''import * as sdk from '@forge/sdk'
import * as ui from '@forge/ui'
import { defineSubAppConfig } from '@forge/sub-app-kit/vite'
import { startDevHost } from '@forge/sub-app-kit/dev-host'
console.log(sdk, ui, defineSubAppConfig({ appCode: 'smoke', port: 5179 }), startDevHost)
''')
(root / 'npm/tsconfig.json').write_text(json.dumps({'compilerOptions': {'strict': True, 'noEmit': True, 'skipLibCheck': True, 'module': 'ESNext', 'moduleResolution': 'Bundler', 'target': 'ES2022'}, 'include': ['index.ts']}))
