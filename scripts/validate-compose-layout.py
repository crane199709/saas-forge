#!/usr/bin/env python3
"""不读取实际 .env、不启动容器，检查独立生命周期与验收组合边界。"""
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
ENVIRONMENT = ROOT / 'deploy/compose/compose.yaml'
ACCEPTANCE = ROOT / 'deploy/acceptance/compose.yaml'
APPLICATIONS = [ROOT / 'gateway/compose.yaml',
                *sorted((ROOT / 'saas-forge-services').glob('*/compose.yaml')),
                *sorted((ROOT / 'test-support').glob('*/compose.yaml'))]
OVERLAYS = sorted(ACCEPTANCE.parent.glob('*.override.yaml'))
INFRASTRUCTURE = {'postgres', 'redis', 'kafka', 'mailpit', 'otel-collector', 'nacos', 'nacos-init'}
FILES = [ENVIRONMENT, ACCEPTANCE, *APPLICATIONS, *OVERLAYS,
         ENVIRONMENT.parent / 'local-https-development.override.yaml']

# 仅构造语法校验占位值；不继承用户凭据、项目名或 COMPOSE_* 配置。
environment = {key: os.environ[key] for key in ('PATH', 'HOME') if key in os.environ}
for file in FILES:
    for key in re.findall(r'\$\{([A-Z][A-Z0-9_]*):\?', file.read_text()):
        environment[key] = ('1000' if key.endswith(('UID', 'GID')) else
                            '/tmp/saas-forge-compose-validation/' + key.lower()
                            if key.endswith(('FILE', 'CERT', 'KEY')) else 'compose-validation')
environment['IAM_JWT_ISSUER'] = 'https://api.saas.forge.test'


def configuration(files, project=None):
    command = ['docker', 'compose', '--env-file', os.devnull]
    if project:
        command += ['--project-name', project]
    for file in files:
        command += ['--file', str(file)]
    result = subprocess.run(command + ['--profile', '*', 'config', '--format', 'json'],
                            env=environment, capture_output=True, text=True, cwd=ROOT)
    if result.returncode:
        raise RuntimeError(result.stderr.strip())
    model = json.loads(result.stdout)
    for service in model['services'].values():
        for mount in service.get('volumes', []):
            if mount['type'] != 'bind' or mount['target'].startswith('/run/'):
                continue
            source = Path(mount['source'])
            # 前端制品由构建阶段生成；本检查不要求预先构建或创建占位目录。
            if 'dist' not in source.parts:
                assert source.exists(), f'挂载源丢失: {source}'
        if isinstance(service.get('build'), dict):
            build = service['build']
            assert (Path(build['context']) / build['dockerfile']).is_file(), 'Dockerfile 路径失效'
    return model


def main():
    base = configuration([ENVIRONMENT])
    assert set(base['services']) == INFRASTRUCTURE, '运行环境混入应用或缺少依赖'
    assert base['name'] == 'compose', '默认环境项目名变化会切换既有数据卷'
    for volume in ('postgres-data', 'redis-data', 'kafka-data'):
        assert base['volumes'][volume]['name'] == f'compose_{volume}'
    local_models = [configuration([file]) for file in APPLICATIONS]
    names = [base['name'], *(model['name'] for model in local_models)]
    assert len(names) == len(set(names)), '独立应用不能共享 Compose 项目名'
    for file, model in zip(APPLICATIONS, local_models):
        assert model['networks']['default']['external'] is True
        assert model['networks']['default']['name'] == base['networks']['default']['name']
        assert not model.get('volumes'), '应用项目不得拥有共享环境数据卷'
        services = model['services']
        for name, service in services.items():
            assert set(service.get('depends_on', {})) <= set(services), '存在跨项目启动依赖'
            if name.endswith('-migrate'):
                application = name.removesuffix('-migrate') + '-service'
                assert services[application]['depends_on'][name]['condition'] == 'service_completed_successfully'
            elif name not in ('gateway', 'platform-console', 'tenant-console') and not name.endswith('-service'):
                assert service.get('profiles'), f'维护任务 {name} 会随普通启动执行'
        declared = set(re.findall(r'^([A-Z][A-Z0-9_]*)=', (file.parent / '.env.example').read_text(), re.M))
        required = set(re.findall(r'\$\{([A-Z][A-Z0-9_]*)', file.read_text()))
        assert required <= declared, f'{file}: 模板缺少 {required - declared}'
    for project in ('acceptance-layout-a', 'acceptance-layout-b'):
        for overlay in [None, *OVERLAYS]:
            scenario = [ACCEPTANCE]
            model = configuration(scenario + ([overlay] if overlay else []), project)
            assert model['networks']['default'].get('external', False) is False
            assert model['networks']['default']['name'] == f'{project}_default'
            assert all(v['name'].startswith(project + '_') for v in model['volumes'].values())
            assert model['services']['gateway']['depends_on']['iam-service']['condition'] == 'service_started'
            if overlay and overlay.name == 'tenant-lifecycle-e2e.override.yaml':
                for service, variable in (
                    ('iam-service', 'SAAS_FORGE_IAM_SESSION_REVOCATION_WORKER_DELAY'),
                    ('tenant-access-service', 'SAAS_FORGE_TENANT_ACCESS_LIFECYCLE_RECOVERY_DELAY'),
                ):
                    assert model['services'][service]['environment'].get(variable) == 'PT1H', (
                        f'{service}: 生命周期验收必须延迟后台接管，{variable} 应为 PT1H'
                    )
    edge = configuration([ENVIRONMENT, ENVIRONMENT.parent / 'local-https-development.override.yaml'])
    assert not edge['services']['local-https-edge'].get('depends_on'), 'HTTPS 入口不能自动启动应用'
    print(f'通过：{len(APPLICATIONS)} 个独立应用、{len(OVERLAYS)} 个验收场景、项目/网络/卷隔离、挂载与迁移门禁。')


if __name__ == '__main__':
    try:
        main()
    except (AssertionError, RuntimeError) as error:
        print(f'Compose 布局校验失败：{error}', file=sys.stderr)
        sys.exit(1)
