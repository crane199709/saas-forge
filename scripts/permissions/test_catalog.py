import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("catalog.py")


class CatalogCommandTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.directory = Path(directory.name)
        self.output = self.directory / "catalog.json"
        self.previous = self.write("previous.json", {"schemaVersion": 1, "permissions": []})

    def write(self, name, document):
        path = self.directory / name
        path.write_text(json.dumps(document, ensure_ascii=False), encoding="utf-8")
        return path

    def manifest(self, name, service, permissions, **fields):
        return self.write(name, {"service": service, "version": "1", "permissions": permissions, **fields})

    def run_catalog(self, *manifests, extra=()):
        arguments = [sys.executable, str(SCRIPT), "--output", str(self.output),
                     "--previous", str(self.previous)]
        for path in manifests:
            arguments.extend(["--manifest", str(path)])
        return subprocess.run([*arguments, *extra], capture_output=True, text=True)

    def test_merges_current_manifests_in_stable_code_order(self):
        orders = self.manifest("orders.json", "orders", [
            {"code": "order:update", "name": "修改订单", "group": "订单"},
            {"code": "order:read", "name": "查看订单", "group": "订单"},
        ])
        platform = self.manifest("platform.json", "platform", [
            {"code": "audit:read", "name": "查看审计"},
        ], realm="platform")
        result = self.run_catalog(orders, platform)
        self.assertEqual(result.returncode, 0, result.stderr)
        first = self.output.read_bytes()
        self.assertEqual(json.loads(first), {"schemaVersion": 1, "permissions": [
            {"service": "platform", "code": "audit:read", "name": "查看审计", "group": None,
             "realm": "platform", "status": "active"},
            {"service": "orders", "code": "order:read", "name": "查看订单", "group": "订单",
             "realm": "tenant", "status": "active"},
            {"service": "orders", "code": "order:update", "name": "修改订单", "group": "订单",
             "realm": "tenant", "status": "active"},
        ]})
        self.assertIn("查看审计".encode("utf-8"), first)
        self.assertNotIn(b"\r", first)
        self.assertTrue(first.endswith(b"\n"))
        result = self.run_catalog(platform, orders)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.output.read_bytes(), first)

    def test_check_compares_bytes_without_modifying_the_catalog(self):
        manifest = self.manifest("orders.json", "orders", [
            {"code": "order:read", "name": "查看订单"},
        ])
        result = self.run_catalog(manifest, extra=("--check",))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("python3 scripts/permissions/catalog.py", result.stderr)
        self.assertFalse(self.output.exists())

        result = self.run_catalog(manifest)
        self.assertEqual(result.returncode, 0, result.stderr)
        canonical = self.output.read_bytes()
        for content, succeeds in [(canonical, True), (canonical.replace(b"\n", b"\r\n"), False),
                                  (b" " + canonical, False)]:
            with self.subTest(content=content):
                self.output.write_bytes(content)
                modified = self.output.stat().st_mtime_ns
                result = self.run_catalog(manifest, extra=("--check",))
                self.assertEqual(result.returncode == 0, succeeds, result.stderr)
                if not succeeds:
                    self.assertIn("python3 scripts/permissions/catalog.py", result.stderr)
                self.assertEqual(self.output.read_bytes(), content)
                self.assertEqual(self.output.stat().st_mtime_ns, modified)

    def test_check_of_a_malformed_catalog_keeps_it_and_explains_regeneration(self):
        manifest = self.manifest("orders.json", "orders", [{"code": "order:read", "name": "查看订单"}])
        self.output.write_bytes(b"invalid JSON\n")
        self.previous = self.output
        result = self.run_catalog(manifest, extra=("--check",))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("python3 scripts/permissions/catalog.py", result.stderr)
        self.assertEqual(self.output.read_bytes(), b"invalid JSON\n")

    def test_new_actions_require_an_approved_action_with_a_reason(self):
        manifest = self.manifest("orders.json", "orders", [
            {"code": "order:approve", "name": "审核订单"},
        ])
        result = self.run_catalog(manifest)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("order:approve", result.stderr)
        self.assertFalse(self.output.exists())

        policy = self.write("policy.json", {"actions": {"approve": "   "}, "historicalExceptions": {}})
        result = self.run_catalog(manifest, extra=("--policy", str(policy)))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("reason", result.stderr.lower())
        self.assertFalse(self.output.exists())

        self.write("policy.json", {"actions": {"approve": "订单需要独立的审核操作"}, "historicalExceptions": {}})
        result = self.run_catalog(manifest, extra=("--policy", str(policy)))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(self.output.read_bytes())["permissions"][0]["code"], "order:approve")

    def test_historical_exceptions_require_exact_codes_reasons_and_previous_history(self):
        old = {"service": "orders", "code": "legacy:oldverb", "name": "旧动作", "group": None,
               "realm": "tenant", "status": "active"}
        self.previous = self.write("previous.json", {"schemaVersion": 1, "permissions": [old]})
        manifest = self.manifest("orders.json", "orders", [])
        policy = self.write("policy.json", {
            "actions": {"read": "查询资源"}, "historicalExceptions": {"legacy:oldverb": "已有的历史权限码"},
        })
        result = self.run_catalog(manifest, extra=("--policy", str(policy)))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(self.output.read_bytes())["permissions"], [{**old, "status": "inactive"}])

        for exceptions in [{}, {"legacy:*": "按前缀放行"}, {"legacy:oldverb": " "}]:
            with self.subTest(exceptions=exceptions):
                self.write("policy.json", {"actions": {"read": "查询资源"}, "historicalExceptions": exceptions})
                result = self.run_catalog(manifest, extra=("--policy", str(policy)))
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn("Traceback", result.stderr)

        self.write("previous.json", {"schemaVersion": 1, "permissions": []})
        self.write("policy.json", {
            "actions": {"read": "查询资源"}, "historicalExceptions": {"legacy:oldverb": "自称历史的新码"},
        })
        self.manifest("orders.json", "orders", [{"code": "legacy:oldverb", "name": "新动作"}])
        result = self.run_catalog(manifest, extra=("--policy", str(policy)))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("legacy:oldverb", result.stderr)
        self.assertIn("previous", result.stderr.lower())

    def test_actions_match_the_complete_last_colon_segment(self):
        accepted = self.manifest("orders.json", "orders", [
            {"code": "iam:role:manage", "name": "管理角色"},
            {"code": "order:read", "name": "查看订单"},
        ])
        result = self.run_catalog(accepted)
        self.assertEqual(result.returncode, 0, result.stderr)
        for code in ["read", "order:", "order:read:invented", "order:read-extra", "order:Read"]:
            with self.subTest(code=code):
                invalid = self.manifest("invalid.json", "orders", [{"code": code, "name": "违规动作"}])
                result = self.run_catalog(invalid)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(code, result.stderr)

    def test_identical_permission_declarations_are_deduplicated(self):
        manifest = self.manifest("orders.json", "orders", [
            {"code": "order:read", "name": "查看订单", "group": "订单"},
            {"code": "order:read", "name": "查看订单", "group": "订单"},
        ])
        result = self.run_catalog(manifest, manifest)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(json.loads(self.output.read_bytes())["permissions"]), 1)

    def test_conflicting_ownership_or_metadata_fails_without_overwriting_output(self):
        original = {"code": "order:read", "name": "查看订单", "group": "订单"}
        first = self.manifest("first.json", "orders", [original])
        cases = [
            ("other-service", "tenant", original),
            ("orders", "platform", original),
            ("orders", "tenant", {**original, "name": "不同名称"}),
            ("orders", "tenant", {**original, "group": "不同分组"}),
        ]
        for service, realm, permission in cases:
            with self.subTest(service=service, realm=realm, permission=permission):
                second = self.manifest("second.json", service, [permission], realm=realm)
                self.output.write_bytes(b"keep existing catalog\n")
                result = self.run_catalog(first, second)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("order:read", result.stderr)
                self.assertIn("conflict", result.stderr.lower())
                self.assertEqual(self.output.read_bytes(), b"keep existing catalog\n")

    def test_missing_manifest_or_expected_service_fails_with_a_diagnostic(self):
        orders = self.manifest("orders.json", "orders", [])
        for manifests in [(orders,), (orders, self.directory / "shipping.json")]:
            with self.subTest(manifests=manifests):
                result = self.run_catalog(*manifests, extra=(
                    "--expected-service", "orders", "--expected-service", "shipping"))
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("shipping", result.stderr)
                self.assertIn("missing", result.stderr.lower())
                self.assertFalse(self.output.exists())

    def test_invalid_permission_codes_fail_before_writing(self):
        for code in ["order read", "order/read", ":read", "", "x" * 129, None, 42]:
            with self.subTest(code=code):
                orders = self.manifest("orders.json", "orders", [{"code": code, "name": "查看订单"}])
                result = self.run_catalog(orders)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("Invalid Permission Code", result.stderr)
                self.assertIn(str(code), result.stderr)
                self.assertFalse(self.output.exists())

    def test_previous_catalog_retains_removed_codes_and_reactivates_the_same_owner(self):
        self.previous = self.write("previous.json", {"schemaVersion": 1, "permissions": [
            {"service": "orders", "code": "order:delete", "name": "删除订单", "group": "订单",
             "realm": "tenant", "status": "active"},
            {"service": "orders", "code": "order:read", "name": "旧名称", "group": "旧分组",
             "realm": "tenant", "status": "inactive"},
        ]})
        orders = self.manifest("orders.json", "orders", [
            {"code": "order:read", "name": "查看订单", "group": "订单"},
        ])
        self.output = self.previous
        result = self.run_catalog(orders)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(self.output.read_bytes())["permissions"], [
            {"service": "orders", "code": "order:delete", "name": "删除订单", "group": "订单",
             "realm": "tenant", "status": "inactive"},
            {"service": "orders", "code": "order:read", "name": "查看订单", "group": "订单",
             "realm": "tenant", "status": "active"},
        ])
        first = self.output.read_bytes()
        result = self.run_catalog(orders)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.output.read_bytes(), first)

    def test_historical_codes_cannot_be_reassigned_to_another_service_or_realm(self):
        old = {"service": "orders", "code": "order:read", "name": "查看订单", "group": None,
               "realm": "tenant", "status": "inactive"}
        self.previous = self.write("previous.json", {"schemaVersion": 1, "permissions": [old]})
        for service, realm in [("other-orders", "tenant"), ("orders", "platform")]:
            with self.subTest(service=service, realm=realm):
                manifest = self.manifest("orders.json", service, [
                    {"code": "order:read", "name": "查看订单"},
                ], realm=realm)
                result = self.run_catalog(manifest)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("order:read", result.stderr)
                self.assertIn("conflict", result.stderr.lower())
                self.assertFalse(self.output.exists())

    def test_malformed_input_or_history_does_not_silently_drop_permissions(self):
        valid = {"service": "orders", "version": "1", "permissions": []}
        orders = self.write("orders.json", valid)
        invalid_manifests = [
            {**valid, "permissions": None},
            {**valid, "permissions": [{}]},
            {**valid, "permissions": [{"code": "order:read", "name": ""}]},
            {**valid, "service": ""},
            {**valid, "realm": "unknown"},
            {**valid, "realm": ""},
            {**valid, "realm": False},
        ]
        for manifest in invalid_manifests:
            with self.subTest(manifest=manifest):
                self.write("orders.json", manifest)
                result = self.run_catalog(orders)
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn("Traceback", result.stderr)
                self.assertFalse(self.output.exists())
        self.write("orders.json", valid)
        old = {"service": "orders", "code": "order:read", "name": "查看订单", "group": None,
               "realm": "tenant", "status": "active"}
        invalid_histories = [
            {"schemaVersion": 2, "permissions": [old]},
            {"schemaVersion": 1, "permissions": [{**old, "code": "bad/code"}]},
            {"schemaVersion": 1, "permissions": [old, {**old, "service": "other"}]},
        ]
        for history in invalid_histories:
            with self.subTest(history=history):
                self.write("previous.json", history)
                result = self.run_catalog(orders)
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn("Traceback", result.stderr)
                self.assertFalse(self.output.exists())

    def test_keeps_sdk_resource_characters_and_empty_groups(self):
        manifest = self.manifest("orders.json", "orders", [
            {"code": "Legacy.Resource_Action:read", "name": "历史动作", "group": ""},
            {"code": "order:read", "name": "查看订单", "group": "   "},
        ], realm=None)
        result = self.run_catalog(manifest)
        self.assertEqual(result.returncode, 0, result.stderr)
        permissions = json.loads(self.output.read_bytes())["permissions"]
        self.assertEqual([item["group"] for item in permissions], ["", "   "])


if __name__ == "__main__":
    unittest.main()
