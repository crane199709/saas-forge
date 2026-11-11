"""只验证不改写文件的参数校验；版本同步本身由发布流程的 check 覆盖。"""
from pathlib import Path
import subprocess
import sys
import unittest

SCRIPT = Path(__file__).with_name('version.py')


def run(*args):
    return subprocess.run([sys.executable, str(SCRIPT), *args], text=True, capture_output=True, timeout=10)


class VersionArgumentTest(unittest.TestCase):
    def test_release_tag_rejects_snapshot(self):
        result = run('check', 'v0.2.0-SNAPSHOT')
        self.assertEqual(result.returncode, 2)
        self.assertIn('SNAPSHOT', result.stderr)

    def test_set_rejects_snapshot_not_as_suffix(self):
        for version in ('0.2.0-snapshot', '0.2.0-SNAPSHOT.1'):
            with self.subTest(version=version):
                self.assertEqual(run('set', version).returncode, 2)

    def test_allow_snapshot_only_for_check(self):
        self.assertEqual(run('set', '0.2.0-SNAPSHOT', '--allow-snapshot').returncode, 2)
        self.assertEqual(run('check', '--allow-snapshot', 'v0.2.0-snapshot').returncode, 2)

    def test_dev_takes_no_version(self):
        self.assertEqual(run('dev', '0.2.0-SNAPSHOT').returncode, 2)

    def test_set_and_check_require_version(self):
        for action in ('set', 'check'):
            with self.subTest(action=action):
                self.assertEqual(run(action).returncode, 2)


if __name__ == '__main__':
    unittest.main()
