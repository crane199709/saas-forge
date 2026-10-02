#!/usr/bin/env python3
"""检查运行输出；错误只报告位置和规则，不回显日志中的值。"""
import argparse
from collections import Counter
import json
from pathlib import Path
import re

try:
    from jsonschema import Draft202012Validator, FormatChecker
except ImportError:
    raise SystemExit('需要 jsonschema[format-nongpl]==4.25.1 测试工具；准备方式见 Example README。')

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = json.loads((ROOT / 'saas-forge-contracts/logging/application-log.schema.json').read_text())
Draft202012Validator.check_schema(SCHEMA)
VALIDATOR = Draft202012Validator(SCHEMA, format_checker=FormatChecker())
POLICIES = {entry['event'] for entry in json.loads((ROOT / 'saas-forge-contracts/logging/policy.json').read_text())['eventPolicies']}
CANARIES = ('never-export-secret@example.test', 'never-export-kafka-secret', 'password=secret-value', 'token=raw-value', 'sensitive@example.test')


def validate_outputs(paths, required_services):
    counts = Counter()
    events = Counter()
    for path in paths:
        for number, line in enumerate(path.read_text().splitlines(), 1):
            # Maven / Testcontainers 的控制台诊断不是应用日志，不能计入通过数。
            if not line.startswith('{'):
                continue
            try:
                event = json.loads(line)
            except ValueError:
                raise SystemExit(f'{path.name}:{number}: 非法 JSON')
            if not isinstance(event, dict) or 'timestamp' not in event:
                raise SystemExit(f'{path.name}:{number}: 缺少应用日志标识')
            error = next(VALIDATOR.iter_errors(event), None)
            if error:
                raise SystemExit(f'{path.name}:{number}: Schema 规则 {error.validator} 失败')
            if event['event'] not in POLICIES or any(value in line for value in CANARIES):
                raise SystemExit(f'{path.name}:{number}: 事件策略或敏感值检查失败')
            counts[event['service']] += 1
            events[event['event']] += 1
    if not counts or set(required_services) - counts.keys():
        raise SystemExit('缺少要求的服务真实输出，不能用空输入通过校验')
    print(json.dumps({'validatedLogs': dict(counts), 'events': dict(events)}, ensure_ascii=False))


def validate_trace_chain(path):
    spans = {}
    for resource in re.split(r'ResourceSpans #\d+', path.read_text()):
        service = re.search(r'service.name: Str\(([^)]+)\)', resource)
        if not service:
            continue
        for block in re.split(r'(?m)^Span #\d+', resource)[1:]:
            fields = {}
            for line in block.splitlines():
                match = re.match(r'^\s*(Trace ID|Parent ID|ID|Name|Kind)\s*:[ \t]*(.*)$', line)
                if match:
                    fields[match[1]] = match[2].strip()
            if {'ID', 'Trace ID', 'Parent ID', 'Name', 'Kind'} <= fields.keys():
                fields['service'] = service[1]
                spans[fields['ID']] = fields
    chains = 0
    for consumer in spans.values():
        if consumer['Name'] != 'example.fact.consume':
            continue
        try:
            producer = spans[consumer['Parent ID']]
            example = spans[producer['Parent ID']]
            gateway = spans[example['Parent ID']]
            chain = (gateway, example, producer, consumer)
            assert [span['service'] for span in chain] == ['gateway', 'project-service', 'project-service', 'audit-service']
            assert [span['Kind'] for span in chain] == ['Server', 'Server', 'Producer', 'Consumer']
            assert producer['Name'] == 'example.fact.publish'
            assert len({span['Trace ID'] for span in chain}) == 1
        except (KeyError, AssertionError):
            raise SystemExit('Collector 中缺少完整 Gateway → Example → Producer → Consumer 父子链')
        chains += 1
    if chains < 6 or 'http.url' in path.read_text() or any(value in path.read_text() for value in CANARIES):
        raise SystemExit('Collector 的六条事实链或敏感值检查失败')
    print(json.dumps({'completeTraceChains': chains}))


def check_negative_cases():
    good = dict(timestamp='2026-10-02T00:00:00Z', level='INFO', service='test', environment='test',
                event='http.request.completed', message='diagnostic', schemaVersion=1)
    negatives = (dict(good, authorization='forbidden'), dict(good, traceId='1' * 32),
                 dict(good, timestamp='invalid'), dict(good, http=dict(method='GET', route='/', statusCode=200, durationMs=1)))
    if not VALIDATOR.is_valid(good) or any(VALIDATOR.is_valid(value) for value in negatives):
        raise SystemExit('日志 Schema 正向或负向校验器检查失败')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('logs', nargs='+', type=Path)
    parser.add_argument('--required-service', action='append', default=[])
    parser.add_argument('--collector', type=Path)
    args = parser.parse_args()
    check_negative_cases()
    validate_outputs(args.logs, args.required_service)
    if args.collector:
        validate_trace_chain(args.collector)
