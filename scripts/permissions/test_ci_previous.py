import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("ci_previous.py")
CATALOG_SCRIPT = Path(__file__).with_name("catalog.py")


class CiPreviousCommandTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.directory = Path(directory.name)
        self.script = self.directory / "scripts/permissions/ci_previous.py"
        self.script.parent.mkdir(parents=True)
        shutil.copyfile(SCRIPT, self.script)
        self.catalog = self.directory / "docs/contracts/permission-catalog.json"
        self.catalog.parent.mkdir(parents=True)
        self.output = self.directory / "previous.json"
        self.event = self.directory / "event.json"
        self.git("init", "--initial-branch=main")

    def git(self, *arguments):
        return subprocess.run(["git", *arguments], cwd=self.directory, check=True,
                              capture_output=True, text=True).stdout.strip()

    def commit(self, document=None):
        if document is not None:
            self.catalog.write_text(json.dumps(document), encoding="utf-8")
        self.git("add", ".")
        self.git("-c", "user.name=Catalog Test", "-c", "user.email=catalog@example.invalid",
                 "commit", "--allow-empty", "-m", "fixture")
        return self.git("rev-parse", "HEAD")

    def run_previous(self, event_name, event):
        self.event.write_text(json.dumps(event), encoding="utf-8")
        environment = {**os.environ, "GITHUB_EVENT_NAME": event_name, "GITHUB_EVENT_PATH": str(self.event),
                       "GITHUB_REF": event.get("ref", "refs/heads/main")}
        return subprocess.run([sys.executable, str(self.script), "--output", str(self.output)],
                              cwd=self.directory, env=environment, capture_output=True, text=True)

    def test_pr_and_push_use_the_accepted_git_baseline_and_detect_deleted_history(self):
        old = {"service": "orders", "code": "legacy:read", "name": "历史查询", "group": None,
               "realm": "tenant", "status": "inactive"}
        active = {"service": "orders", "code": "order:read", "name": "查看订单", "group": None,
                  "realm": "tenant", "status": "active"}
        baseline = {"schemaVersion": 1, "permissions": [old, active]}
        base = self.commit(baseline)
        self.commit({"schemaVersion": 1, "permissions": [active]})
        for event_name, event in [("pull_request", {"pull_request": {"base": {"sha": base}}}),
                                  ("push", {"before": base})]:
            with self.subTest(event=event_name):
                result = self.run_previous(event_name, event)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(json.loads(self.output.read_bytes()), baseline)
                manifest = self.directory / "orders.json"
                manifest.write_text(json.dumps({"service": "orders", "permissions": [
                    {"code": "order:read", "name": "查看订单"},
                ]}), encoding="utf-8")
                result = subprocess.run([sys.executable, str(CATALOG_SCRIPT), "--manifest", str(manifest),
                                         "--previous", str(self.output), "--output", str(self.catalog), "--check"],
                                        capture_output=True, text=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("python3 scripts/permissions/catalog.py", result.stderr)

    def test_other_events_use_the_first_parent_and_initial_catalogs_have_empty_history(self):
        initial = self.commit()
        result = self.run_previous("workflow_dispatch", {})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(self.output.read_bytes()), {"schemaVersion": 1, "permissions": []})
        result = self.run_previous("push", {"before": "0" * 40})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(self.output.read_bytes())["permissions"], [])
        result = self.run_previous("pull_request", {"pull_request": {"base": {"sha": initial}}})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(self.output.read_bytes())["permissions"], [])

        baseline = {"schemaVersion": 1, "permissions": [
            {"service": "orders", "code": "old:read", "name": "历史查询", "group": None,
             "realm": "tenant", "status": "inactive"},
        ]}
        self.commit(baseline)
        self.commit({"schemaVersion": 1, "permissions": []})
        for event_name, event in [("workflow_call", {}),
                                  ("push", {"ref": "refs/tags/v1", "before": initial})]:
            with self.subTest(event=event_name, ref=event.get("ref")):
                result = self.run_previous(event_name, event)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(json.loads(self.output.read_bytes()), baseline)

    def test_a_new_permission_cannot_claim_a_historical_exception_in_the_same_change(self):
        base = self.commit({"schemaVersion": 1, "permissions": []})
        new = {"service": "orders", "code": "order:invented", "name": "新违规动作", "group": None,
               "realm": "tenant", "status": "active"}
        self.commit({"schemaVersion": 1, "permissions": [new]})
        result = self.run_previous("pull_request", {"pull_request": {"base": {"sha": base}}})
        self.assertEqual(result.returncode, 0, result.stderr)
        manifest = self.directory / "orders.json"
        manifest.write_text(json.dumps({"service": "orders", "permissions": [new]}), encoding="utf-8")
        policy = self.directory / "policy.json"
        policy.write_text(json.dumps({"actions": {"read": "查询"},
                                      "historicalExceptions": {"order:invented": "自称历史"}}), encoding="utf-8")
        result = subprocess.run([sys.executable, str(CATALOG_SCRIPT), "--manifest", str(manifest),
                                 "--previous", str(self.output), "--output", str(self.catalog),
                                 "--policy", str(policy), "--check"], capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("order:invented", result.stderr)
        self.assertIn("previous", result.stderr.lower())

    def test_git_errors_fail_instead_of_becoming_empty_history(self):
        self.commit()
        for base in ["f" * 40, ""]:
            with self.subTest(base=base):
                result = self.run_previous("pull_request", {"pull_request": {"base": {"sha": base}}})
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("baseline", result.stderr.lower())
                self.assertNotIn("Traceback", result.stderr)
                self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
