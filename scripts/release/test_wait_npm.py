"""通过真实 HTTP 与 CLI 验证发布后的短暂不可用。"""
import json
import os
from pathlib import Path
import subprocess
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote

SCRIPT = Path(__file__).with_name('wait_npm.py')


class AvailabilityTest(unittest.TestCase):
    def run_registry(self, respond, timeout='2'):
        requests = []

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                path = unquote(self.path)
                requests.append(path)
                status, body = respond(path, requests, self.server.server_address[1])
                self.send_response(status)
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            result = subprocess.run([sys.executable, str(SCRIPT), '0.1.0', '--timeout', timeout, '--interval', '0.02'], env={**os.environ, 'NPM_RELEASE_REGISTRY': f'http://127.0.0.1:{server.server_port}', 'NPM_RELEASE_TOKEN': 'test-token'}, text=True, capture_output=True, timeout=5)
            return result, requests
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_transient_metadata_and_tarball_404_eventually_succeed(self):
        def respond(path, requests, port):
            if requests.count(path) == 1:
                return 404, b'not visible yet'
            if path.endswith('.tgz'):
                return 200, b'archive'
            name = path.removesuffix('/0.1.0')
            return 200, json.dumps({'versions': {'0.1.0': {'name': name, 'version': '0.1.0', 'dist': {'tarball': f'http://127.0.0.1:{port}/{name}.tgz'}}}}).encode()

        result, requests = self.run_registry(respond)
        self.assertEqual(result.returncode, 0, result.stderr)
        for name in ('sdk', 'ui', 'sub-app-kit'):
            self.assertIn(f'/@crane199709/{name}.tgz', requests)

    def test_version_endpoint_visible_but_install_packument_missing_is_not_ready(self):
        def respond(path, requests, port):
            if path.endswith('.tgz'):
                return 200, b'archive'
            if path.endswith('/0.1.0'):
                return 200, json.dumps({'version': '0.1.0', 'dist': {'tarball': f'http://127.0.0.1:{port}/package.tgz'}}).encode()
            return 404, b'install metadata still unavailable'

        result, requests = self.run_registry(respond, timeout='0.15')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('timed out', result.stderr)

    def test_packument_without_requested_version_is_not_ready(self):
        result, requests = self.run_registry(lambda *args: (200, b'{"versions":{}}'), timeout='0.15')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('timed out', result.stderr)

    def test_permanent_404_fails_with_shared_timeout(self):
        result, requests = self.run_registry(lambda *args: (404, b'pending'), timeout='0.15')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('timed out', result.stderr)
        self.assertGreater(len(requests), 3)

    def test_authentication_errors_fail_without_retry(self):
        for status in (401, 403):
            with self.subTest(status=status):
                result, requests = self.run_registry(lambda *args: (status, b'denied'))
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(f'HTTP {status}', result.stderr)
                self.assertEqual(len(requests), 1)
                self.assertNotIn('test-token', result.stderr + result.stdout)

    def test_foreign_tarball_origin_is_rejected(self):
        body = b'{"versions":{"0.1.0":{"version":"0.1.0","dist":{"tarball":"https://untrusted.invalid/package.tgz"}}}}'
        result, requests = self.run_registry(lambda *args: (200, body))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('configured registry origin', result.stderr)
        self.assertEqual(len(requests), 1)

    def test_wrong_version_fails_without_downloading_tarball(self):
        result, requests = self.run_registry(lambda *args: (200, b'{"versions":{"0.1.0":{"version":"0.2.0"}}}'))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('version mismatch', result.stderr)
        self.assertEqual(len(requests), 1)


if __name__ == '__main__':
    unittest.main()
