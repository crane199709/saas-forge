#!/usr/bin/env python3
"""从构建期 Resource Manifest 生成可审查的权限目录。"""

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / "docs/contracts/permission-catalog.json"
# 与 SDK ResourceManifest.validate() 的语法和列宽保持一致；动作词策略由独立门禁负责。
PERMISSION_CODE = re.compile(r"[A-Za-z0-9][A-Za-z0-9:._-]{0,127}")
MANIFESTS = {
    "forge-iam": ROOT / "backend/forge-iam/target/permission-manifests/forge-iam/resource-manifest.json",
    "forge-iam-platform": ROOT / "backend/forge-iam/target/permission-manifests/forge-iam-platform/resource-manifest.json",
    "forge-platform": ROOT / "backend/forge-platform/target/permission-manifests/forge-platform/resource-manifest.json",
    "demo-service": ROOT / "backend/examples/demo-service/target/permission-manifests/demo-service/resource-manifest.json",
}


def read_document(path):
    if not path.is_file():
        raise ValueError(f"Missing Manifest or catalog file: {path}")
    document = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(document, dict):
        raise ValueError(f"Expected JSON object: {path}")
    return document


def require_text(value, label, max_length=120):
    if not isinstance(value, str) or not value.strip() or len(value) > max_length:
        raise ValueError(f"Invalid {label}: {value}")
    return value


def permission_entry(permission, service, realm, status):
    if not isinstance(permission, dict):
        raise ValueError(f"Invalid permission in service {service}: {permission}")
    code = permission.get("code")
    if not isinstance(code, str) or not PERMISSION_CODE.fullmatch(code):
        raise ValueError(f"Invalid Permission Code: {code} (service {service})")
    name = require_text(permission.get("name"), f"name of {code}")
    group = permission.get("group")
    if group is not None and (not isinstance(group, str) or len(group) > 120):
        raise ValueError(f"Invalid group of {code}: {group}")
    if realm not in ("tenant", "platform"):
        raise ValueError(f"Invalid Realm for service {service}: {realm}")
    if status not in ("active", "inactive"):
        raise ValueError(f"Invalid status of {code}: {status}")
    return {"service": require_text(service, "service", 64), "code": code, "name": name,
            "group": group, "realm": realm, "status": status}


def add_permission(permissions, entry):
    existing = permissions.get(entry["code"])
    if existing is not None and existing != entry:
        raise ValueError(f"Permission conflict for {entry['code']}: {existing} vs {entry}")
    permissions[entry["code"]] = entry


def generate(manifests, expected_services, previous):
    permissions = {}
    services = set()
    for path in manifests:
        manifest = read_document(path)
        service = require_text(manifest.get("service"), "service", 64)
        realm = "tenant" if manifest.get("realm") is None else manifest["realm"]
        if realm not in ("tenant", "platform"):
            raise ValueError(f"Invalid Realm for service {service}: {realm}")
        if not isinstance(manifest.get("permissions"), list):
            raise ValueError(f"Invalid permissions in Manifest: {path}")
        services.add(service)
        for permission in manifest["permissions"]:
            add_permission(permissions, permission_entry(permission, service, realm, "active"))
    missing = set(expected_services) - services
    if missing:
        raise ValueError("Missing expected service Manifest: " + ", ".join(sorted(missing)))
    if previous is not None:
        history = read_document(previous)
        if history.get("schemaVersion") != 1 or not isinstance(history.get("permissions"), list):
            raise ValueError(f"Invalid previous catalog schema: {previous}")
        historical_permissions = {}
        for old in history["permissions"]:
            if not isinstance(old, dict):
                raise ValueError(f"Invalid previous permission: {old}")
            entry = permission_entry(old, old.get("service"), old.get("realm"), old.get("status"))
            add_permission(historical_permissions, entry)
        for code, old in historical_permissions.items():
            current = permissions.get(code)
            if current is not None and (current["service"], current["realm"]) != (old["service"], old["realm"]):
                raise ValueError(f"Historical permission conflict for {code}: {old} vs {current}")
            if current is None:
                permissions[code] = {**old, "status": "inactive"}
    return {"schemaVersion": 1, "permissions": sorted(permissions.values(), key=lambda item: item["code"])}


def export_manifests(offline):
    backend = ROOT / "backend"
    command = [str(backend / "mvnw")]
    if offline:
        command.append("-o")
    modules = "forge-iam,forge-platform,examples/demo-service"
    # 先安装本次源码，确保单模块导出不误用本机旧 SDK。
    subprocess.run([*command, "-pl", modules, "-am", "install", "-DskipTests"], cwd=backend, check=True)
    # 导出测试被删除或未执行时不能沿用旧文件掩盖缺失。
    for path in MANIFESTS.values():
        path.unlink(missing_ok=True)
    subprocess.run([*command, "-pl", modules, "test", "-Dtest=*ManifestExportTest"], cwd=backend, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, action="append",
                        help="合并已有 Resource Manifest，可重复；提供时跳过构建导出")
    parser.add_argument("--offline", action="store_true", help="Maven 使用离线模式")
    parser.add_argument("--skip-export", action="store_true", help="复用已有 target 产物，不构建导出")
    parser.add_argument("--expected-service", action="append", default=[],
                        help="必须存在的服务，可重复")
    parser.add_argument("--output", type=Path, default=CATALOG, help="目录输出路径")
    parser.add_argument("--previous", type=Path, help="上一版入库目录（默认仓内目录，首次不存在时为空）")
    args = parser.parse_args()
    try:
        if not args.manifest and not args.skip_export:
            export_manifests(args.offline)
        paths = args.manifest or list(MANIFESTS.values())
        expected = args.expected_service if args.manifest else [*MANIFESTS, *args.expected_service]
        previous = args.previous or (CATALOG if CATALOG.exists() else None)
        document = generate(paths, expected, previous)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Permission catalog: {error}", file=sys.stderr)
        return 1
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes((json.dumps(document, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
