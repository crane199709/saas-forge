"""通过发布脚本使用的 CLI 验证 npm 包导出和制品完整性。"""
import io
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('check_tarballs.py')


class TarballCheckTest(unittest.TestCase):
    def run_check(self, package, files):
        with tempfile.TemporaryDirectory() as directory:
            with tarfile.open(Path(directory) / 'package.tgz', 'w:gz') as archive:
                contents = {'package.json': json.dumps(package), **dict.fromkeys(files, '')}
                for name, content in contents.items():
                    data = content.encode()
                    info = tarfile.TarInfo('package/' + name)
                    info.size = len(data)
                    archive.addfile(info, io.BytesIO(data))
            return subprocess.run([sys.executable, str(SCRIPT), directory], text=True, capture_output=True, timeout=5)

    def test_conditional_exports_and_javascript_bin_pass(self):
        package = {'exports': {'.': {'types': './dist/index.d.ts', 'default': './dist/index.js'}},
                   'bin': {'forge-cli': './dist/cli.js'}}
        result = self.run_check(package, ['dist/index.d.ts', 'dist/index.js', 'dist/cli.js'])
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_mixed_conditional_and_string_exports_pass(self):
        package = {'exports': {'.': {'types': './dist/index.d.ts', 'default': './dist/index.js'},
                               './theme.css': './dist/theme.css'}}
        result = self.run_check(package, ['dist/index.d.ts', 'dist/index.js', 'dist/theme.css'])
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_missing_export_or_bin_target_is_rejected(self):
        for entry in ({'exports': {'./theme.css': './dist/theme.css'}},
                      {'exports': {'.': {'types': './dist/index.d.ts', 'default': './dist/index.js'}}},
                      {'exports': {}, 'bin': {'forge-cli': './dist/cli.js'}}):
            with self.subTest(package=entry):
                result = self.run_check(entry, [])
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('AssertionError', result.stderr)

    def test_private_workspace_and_typescript_bin_are_rejected(self):
        for entry in ({'private': True},
                      {'dependencies': {'@crane199709/sdk': 'workspace:*'}},
                      {'bin': {'forge-cli': './dist/cli.ts'}}):
            with self.subTest(package=entry):
                result = self.run_check({'exports': {}, **entry}, ['dist/cli.ts'])
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('AssertionError', result.stderr)


if __name__ == '__main__':
    unittest.main()
