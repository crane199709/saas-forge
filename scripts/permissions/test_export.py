import json
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path


DIRECTORY = Path(__file__).parent


class ExportCommandTest(unittest.TestCase):
    def test_default_export_cleans_removed_permission_classes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            script = root / "scripts/permissions/catalog.py"
            script.parent.mkdir(parents=True)
            shutil.copyfile(DIRECTORY / "catalog.py", script)
            policy = root / "docs/contracts/permission-policy.json"
            policy.parent.mkdir(parents=True)
            shutil.copyfile(DIRECTORY.parents[1] / "docs/contracts/permission-policy.json", policy)
            stale = root / "backend/examples/demo-service/target/classes/RemovedController.class"
            stale.parent.mkdir(parents=True)
            stale.write_bytes(b"stale compiled permission declaration")
            maven = root / "backend/mvnw"
            # Maven 是 CLI 的外部边界；夹具保留 clean 对 target 的语义及导出可见的旧 class。
            maven.write_text(textwrap.dedent('''\
                #!/usr/bin/env python3
                import json
                import shutil
                import sys
                from pathlib import Path

                backend = Path.cwd()
                if "clean" in sys.argv:
                    shutil.rmtree(backend / "examples/demo-service/target", ignore_errors=True)
                if "test" in sys.argv:
                    stale = backend / "examples/demo-service/target/classes/RemovedController.class"
                    for service, module in [("forge-iam", "forge-iam"),
                                            ("forge-iam-platform", "forge-iam"),
                                            ("forge-platform", "forge-platform"),
                                            ("demo-service", "examples/demo-service")]:
                        permissions = [{"code": "obsolete:read", "name": "已删除的权限"}] if stale.exists() else []
                        output = backend / module / "target/permission-manifests" / service / "resource-manifest.json"
                        output.parent.mkdir(parents=True, exist_ok=True)
                        output.write_text(json.dumps({"service": service, "permissions": permissions
                                                      if service == "demo-service" else []}), encoding="utf-8")
                '''), encoding="utf-8")
            maven.chmod(0o755)
            result = subprocess.run([sys.executable, str(script), "--offline"], cwd=root,
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            output = root / "docs/contracts/permission-catalog.json"
            self.assertEqual(json.loads(output.read_bytes()), {"schemaVersion": 1, "permissions": []})


if __name__ == "__main__":
    unittest.main()
