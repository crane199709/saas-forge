import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from changes import SCOPES, checks_for_path, select_checks


class DependencySelectionTest(unittest.TestCase):
    def test_changed_files_select_their_consumers(self):
        cases = {
            "README.md": set(),
            ".scratch/tenant-ip-allowlist/spec.md": set(),
            "docs/main-flow-e2e.md": set(),
            "docs/standards/business-service.md": {"backend", "frontend", "template-smoke"},
            "docs/standards/sub-app.md": {"backend", "frontend", "template-smoke"},
            "frontend/e2e/visual/__screenshots__/roles-desktop.png": set(),
            "frontend/e2e/import-confirmation.spec.ts": {"frontend"},
            "frontend/e2e/visual/support.ts": {"frontend"},
            "frontend/apps/tenant-app/src/security/Settings.test.ts": {"frontend"},
            "frontend/apps/platform-console/src/tenants/TenantsPage.vue": {"frontend"},
            "frontend/playwright.config.ts": {"frontend"},
            "frontend/playwright.visual.config.ts": {"frontend"},
            "frontend/tsconfig.tools.json": {"frontend"},
            "frontend/e2e/main-flow/main-flow.spec.ts": {"frontend", "main-flow"},
            "frontend/e2e/main-flow/template-smoke.spec.ts": {"frontend", "template-smoke"},
            "frontend/e2e/main-flow/support.ts": {"frontend", "main-flow", "template-smoke"},
            "frontend/playwright.main-flow.config.ts": {"frontend", "main-flow", "template-smoke"},
            "frontend/apps/tenant-app/src/auth/session.ts": {"frontend", "main-flow", "template-smoke"},
            "frontend/packages/sdk/src/http.ts": {"frontend", "main-flow", "template-smoke"},
            "frontend/packages/sub-app-kit/templates/src/App.vue": {"frontend", "main-flow", "template-smoke"},
            "frontend/pnpm-lock.yaml": {"frontend", "main-flow", "template-smoke"},
            "frontend/pnpm-workspace.yaml": {"frontend", "main-flow", "template-smoke"},
            "frontend/apps/platform-console/package.json": {"frontend", "main-flow", "template-smoke"},
            "backend/forge-iam/src/main/java/AuthService.java": {"backend", "main-flow", "template-smoke"},
            "backend/forge-sdk/forge-sdk-security/src/main/java/Tokens.java": {"backend", "main-flow", "template-smoke"},
            "backend/pom.xml": {"backend", "main-flow", "template-smoke"},
            "backend/.mvn/wrapper/maven-wrapper.properties": {"backend", "main-flow", "template-smoke"},
            "backend/forge-iam/src/test/java/AuthTest.java": {"backend"},
            "backend/forge-service-archetype/src/it/basic/archetype.properties": {"backend"},
            "backend/forge-service-archetype/src/main/resources/archetype-resources/src/test/java/ContextTest.java": {"backend", "main-flow", "template-smoke"},
            "deploy/e2e/run.sh": {"main-flow", "template-smoke"},
            "deploy/postgres/init/lib/provision-service.sql": {"backend", "main-flow", "template-smoke"},
            "deploy/nacos/config/forge-common.yaml": {"backend", "main-flow", "template-smoke"},
            "deploy/template-smoke/runtime.sh": {"template-smoke"},
            "scripts/release/consume.py": {"template-smoke"},
            ".github/workflows/frontend-quality.yml": {"frontend"},
            ".github/workflows/backend-quality.yml": {"backend"},
            ".github/workflows/main-flow-e2e.yml": {"main-flow"},
            ".github/workflows/template-smoke.yml": {"template-smoke"},
            ".github/workflows/release.yml": SCOPES,
            ".github/actions/ci-scope/action.yml": SCOPES,
            "scripts/ci/changes.py": SCOPES,
            ".gitattributes": SCOPES,
            "new-build-config.json": SCOPES,
        }
        for path, expected in cases.items():
            with self.subTest(path=path):
                self.assertEqual(checks_for_path(path), expected)

    def test_full_validation_for_manual_and_release_events(self):
        for name, ref in [
            ("workflow_dispatch", "refs/heads/main"),
            ("workflow_call", "refs/heads/main"),
            ("push", "refs/tags/v0.1.0"),
        ]:
            with self.subTest(event=name, ref=ref):
                selected, _ = select_checks(name, ref, {}, "/does-not-exist")
                self.assertEqual(selected, SCOPES)


class GitComparisonTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.repo = Path(self.directory.name)
        self.git("init", "--initial-branch=main")
        self.initial = self.commit("README.md")

    def git(self, *args):
        return subprocess.check_output(
            ["git", "-c", "user.name=CI test", "-c", "user.email=ci@example.invalid",
             "-c", "commit.gpgsign=false", *args],
            cwd=self.repo,
            stderr=subprocess.PIPE,
        ).decode().strip()

    def commit(self, *paths):
        for name in paths:
            path = self.repo / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(path.read_text() + "changed\n" if path.exists() else "changed\n")
        self.git("add", "--all")
        self.git("commit", "-m", "fixture")
        return self.git("rev-parse", "HEAD")

    def push_selection(self, before, after):
        return select_checks("push", "refs/heads/main", {"before": before, "after": after}, self.repo)[0]

    def test_push_includes_every_commit_in_the_push(self):
        self.commit("frontend/e2e/import-confirmation.spec.ts")
        head = self.commit("backend/forge-iam/src/test/java/AuthTest.java")
        self.assertEqual(self.push_selection(self.initial, head), {"frontend", "backend"})

    def test_pr_uses_merge_base_and_excludes_new_base_branch_changes(self):
        self.git("checkout", "-b", "feature")
        head = self.commit("frontend/e2e/storage-purpose.spec.ts")
        self.git("checkout", "main")
        base = self.commit("backend/forge-iam/src/main/java/AuthService.java")
        event = {"pull_request": {"base": {"sha": base}, "head": {"sha": head}}}
        selected, _ = select_checks("pull_request", "refs/pull/1/merge", event, self.repo)
        self.assertEqual(selected, {"frontend"})

    def test_rename_keeps_the_old_path_in_scope(self):
        before = self.commit("frontend/packages/sdk/src/api.ts")
        target = self.repo / "docs/moved.md"
        target.parent.mkdir()
        (self.repo / "frontend/packages/sdk/src/api.ts").rename(target)
        after = self.commit()
        self.assertEqual(self.push_selection(before, after), {"frontend", "main-flow", "template-smoke"})

    def test_deleted_runtime_file_still_triggers_consumers(self):
        before = self.commit("backend/forge-sdk/forge-sdk-core/src/main/java/Core.java")
        (self.repo / "backend/forge-sdk/forge-sdk-core/src/main/java/Core.java").unlink()
        after = self.commit()
        self.assertEqual(self.push_selection(before, after), {"backend", "main-flow", "template-smoke"})

    def test_unusual_filenames_and_more_than_300_files(self):
        paths = [f"docs/{index}.md" for index in range(305)]
        head = self.commit(*paths, "frontend/e2e/space and\nnewline.spec.ts")
        self.assertEqual(self.push_selection(self.initial, head), {"frontend"})

    def test_docs_only_and_empty_diff_skip_heavy_checks(self):
        head = self.commit("docs/guide.md", ".scratch/ci/spec.md")
        self.assertEqual(self.push_selection(self.initial, head), set())
        self.assertEqual(self.push_selection(head, head), set())

    def test_new_branch_and_unreachable_before_run_every_check(self):
        for before in ["0" * 40, "f" * 40, "", "--help"]:
            with self.subTest(before=before):
                self.assertEqual(self.push_selection(before, self.initial), SCOPES)

    def test_cli_writes_github_outputs_and_summary(self):
        head = self.commit("frontend/e2e/tenant-ip-allowlist.spec.ts")
        event_path = self.repo / "event.json"
        event_path.write_text(json.dumps({"before": self.initial, "after": head}))
        for scope in sorted(SCOPES):
            output = self.repo / f"{scope}.output"
            summary = self.repo / f"{scope}.summary"
            env = {
                **os.environ,
                "GITHUB_EVENT_NAME": "push",
                "GITHUB_REF": "refs/heads/main",
                "GITHUB_EVENT_PATH": str(event_path),
                "GITHUB_WORKSPACE": str(self.repo),
                "GITHUB_OUTPUT": str(output),
                "GITHUB_STEP_SUMMARY": str(summary),
                "CI_FORCE_FULL": "false",
            }
            command = [sys.executable, str(Path(__file__).with_name("changes.py")), "--scope", scope]
            with self.subTest(scope=scope):
                subprocess.run(command, env=env, check=True, capture_output=True)
                self.assertEqual(output.read_text(), f"run={str(scope == 'frontend').lower()}\n")
                self.assertIn(f"CI 范围：{scope}", summary.read_text())
                env["CI_FORCE_FULL"] = "true"
                subprocess.run(command, env=env, check=True, capture_output=True)
                self.assertTrue(output.read_text().endswith("run=true\n"))
        event_path.write_text("invalid json")
        env["CI_FORCE_FULL"] = "false"
        subprocess.run(command, env=env, check=True, capture_output=True)
        self.assertTrue(output.read_text().endswith("run=true\n"))


if __name__ == "__main__":
    unittest.main()
