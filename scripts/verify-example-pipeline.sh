#!/usr/bin/env bash
set -euo pipefail
# 仅后端诊断：受控 JWKS/发现夹具与隔离依赖，不能作为 Tenant Shell/Remote 验收。
cd "$(dirname "$0")/.."
python3 -c "import jsonschema"
mkdir -p .scratch/example-pipeline
./mvnw -pl gateway,saas-forge-services/audit-service -am -DskipTests package
# 仅接受本轮刚构建的可执行制品，不把源码目录当应用发布物。
gateway_jar=$(python3 - <<'EOF'
from pathlib import Path
from zipfile import ZipFile
files=[]
for p in Path('gateway/target').glob('*.jar'):
    with ZipFile(p) as archive:
        if b'Start-Class: io.saas.forge.gateway.GatewayApplication' in archive.read('META-INF/MANIFEST.MF'):
            files.append(str(p.resolve()))
assert len(files)==1, 'Expected one executable Gateway artifact'
print(files[0])
EOF
)
audit_jar=$(python3 - <<'EOF'
from pathlib import Path
from zipfile import ZipFile
files=[]
for p in Path('saas-forge-services/audit-service/target').glob('*.jar'):
    with ZipFile(p) as archive:
        if b'Start-Class: io.saas.forge.audit.AuditServiceApplication' in archive.read('META-INF/MANIFEST.MF'):
            files.append(str(p.resolve()))
assert len(files)==1, 'Expected one executable Audit artifact'
print(files[0])
EOF
)
./mvnw -pl examples/project-service -am test \
  -Dtest=ProjectHttpIT#gatewayExampleKafkaAuditAndCollectorDiagnostic \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dexample.gateway.jar="$gateway_jar" -Dexample.audit.jar="$audit_jar" 2>&1 | tee .scratch/example-pipeline/example.log

python3 scripts/validate-application-logs.py \
  .scratch/example-pipeline/example.log .scratch/example-pipeline/gateway.log .scratch/example-pipeline/audit.log \
  --required-service gateway --required-service project-service --required-service audit-service \
  --collector .scratch/example-pipeline/collector.log
