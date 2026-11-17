"""从外部诊断 CLI 验证发布质量门上传边界。"""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class UpgradeReportTest(unittest.TestCase):
    def test_failed_upgrade_retains_diagnostics_without_secrets_or_raw_urls(self):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            private = work / 'private'
            private.mkdir()
            (private / 'state.json').write_text(json.dumps({'result': 'failed', 'stage': 'storage'}))
            (private / 'storage.log').write_text(
                '[ERROR] StorageUpgradeAcceptanceTest: expected 200 but was 500\n'
                'password: qV7pX0nZ4mA8rB2c\nAuthorization: Bearer W7nM3aL9kQ2sH8xD\n'
                'Value: qV7pX0nZ4mA8rB2c\nretry rejected W7nM3aL9kQ2sH8xD\n'
                'download https://store.invalid/file?X-Amz-Signature=opaque\n')
            (private / 'storage').mkdir()
            (private / 'storage' / 'http-evidence.json').write_text(json.dumps({
                'result': 'failed', 'password': 'qV7pX0nZ4mA8rB2c', 'token': 'W7nM3aL9kQ2sH8xD',
                'url': 'https://user:pass@store.invalid/file?secret=opaque',
                'migrationCount': 15}))
            (private / 'storage' / 'settings.xml').write_text('private-setting')
            subprocess.run([sys.executable, str(ROOT / 'deploy/upgrade-smoke/report.py'), str(work)], check=True)
            diagnostic = work / 'diagnostics'
            contents = '\n'.join(p.read_text() for p in diagnostic.rglob('*') if p.is_file())
            self.assertIn('expected 200 but was 500', contents)
            self.assertIn('migrationCount', contents)
            for secret in ('qV7pX0nZ4mA8rB2c', 'W7nM3aL9kQ2sH8xD', 'opaque', 'store.invalid', 'private-setting'):
                self.assertNotIn(secret, contents)
            self.assertEqual(json.loads((diagnostic / 'summary.json').read_text())['result'], 'failed')

    def test_incomplete_evidence_does_not_hide_the_original_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            private = work / 'private'
            private.mkdir()
            (private / 'state.json').write_text(json.dumps({'result': 'failed', 'stage': 'io'}))
            (private / 'io.log').write_text('[ERROR] IoUpgradeAcceptanceTest: expected 201 but was 500\n')
            (private / 'http-evidence.json').write_text('{"result":')
            subprocess.run([sys.executable, str(ROOT / 'deploy/upgrade-smoke/report.py'), str(work)], check=True)
            self.assertIn('expected 201 but was 500', (work / 'diagnostics/io.log').read_text())
            self.assertEqual(json.loads((work / 'diagnostics/summary.json').read_text())['result'], 'failed')


if __name__ == '__main__':
    unittest.main()
