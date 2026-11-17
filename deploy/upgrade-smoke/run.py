#!/usr/bin/env python3
"""一次候选构建/消费，依次验证 service、Sub-App、storage、IO；任何失败立即失败。"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / 'deploy/upgrade-smoke'
SCENARIOS = ('business-service', 'sub-app', 'storage', 'io')


def record(work, state):
    (work / 'private/state.json').write_text(json.dumps(state, ensure_ascii=False, indent=2) + '\n')


def execute(command, work, state, stage):
    state.update(stage=stage, result='running')
    record(work, state)
    print(f'升级质量门：{stage}', flush=True)
    with (work / f'private/{stage}.log').open('w') as output:
        result = subprocess.run([str(value) for value in command], cwd=ROOT, stdout=output, stderr=subprocess.STDOUT)
    if result.returncode:
        state.update(result='failed', exitCode=result.returncode)
        record(work, state)
        raise SystemExit(result.returncode)
    state['passed'].append(stage)
    record(work, state)


def scenarios(work):
    state = json.loads((work / 'private/state.json').read_text())
    for name in SCENARIOS:
        cache = os.environ.get('FORGE_UPGRADE_NPM_BASELINE' if name == 'sub-app' else 'FORGE_UPGRADE_MAVEN_BASELINE', '')
        args = ['bash', SCRIPTS / f'{name}.sh', work / f'private/{name}']
        if cache and name != 'io':
            args.append(cache)
        execute(args, work, state, name)
    state.update(result='passed', stage='complete')
    record(work, state)


def main():
    if len(sys.argv) == 3 and sys.argv[1] == '--scenarios':
        scenarios(Path(sys.argv[2]))
        return
    if len(sys.argv) not in (2, 3):
        raise SystemExit('用法：run.sh <新的仓库外证据目录> [已有固定候选目录]')
    work = Path(sys.argv[1]).resolve()
    if work == ROOT or ROOT in work.parents:
        raise SystemExit('证据目录必须位于仓库外')
    work.mkdir()  # 不覆盖前次结果。
    (work / 'private').mkdir()
    state = {'result': 'running', 'stage': 'prepare', 'passed': []}
    record(work, state)
    try:
        candidate = Path(sys.argv[2]).resolve() if len(sys.argv) == 3 else work / 'private/candidate'
        if len(sys.argv) == 2:
            execute(['bash', ROOT / 'scripts/release/candidate.sh', 'build', candidate], work, state, 'candidate-build')
        execute(['python3', ROOT / 'scripts/release/candidate_manifest.py', 'verify', candidate], work, state, 'candidate-verify')
        manifest = candidate / 'candidate.json'
        identity = json.loads(manifest.read_text())
        state['candidate'] = {key: identity[key] for key in ('sourceCommit', 'sourceVersion', 'sourceChanges')}
        state['candidate']['manifestSha256'] = hashlib.sha256(manifest.read_bytes()).hexdigest()
        record(work, state)
        state['stage'] = 'candidate-use'
        record(work, state)
        # 使用同一原始候选包；临时 registry 在全部场景完成或失败后由 candidate.sh 清理。
        with (work / 'private/session.log').open('w') as output:
            result = subprocess.run(['bash', str(ROOT / 'scripts/release/candidate.sh'), 'use', str(candidate),
                                     sys.executable, str(SCRIPTS / 'run.py'), '--scenarios', str(work)],
                                    cwd=ROOT, stdout=output, stderr=subprocess.STDOUT)
        state = json.loads((work / 'private/state.json').read_text())
        if result.returncode:
            state.update(result='failed', exitCode=result.returncode)
            record(work, state)
            raise SystemExit(result.returncode)
    except BaseException:
        state = json.loads((work / 'private/state.json').read_text())
        if state['result'] != 'failed':
            state['result'] = 'failed'
            record(work, state)
        raise
    finally:
        subprocess.run([sys.executable, str(SCRIPTS / 'report.py'), str(work)], check=True)
    print(f'四个通用升级场景通过：{work / "diagnostics/summary.json"}')


if __name__ == '__main__':
    main()
