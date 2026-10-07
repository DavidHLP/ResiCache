import contextlib
import http.server
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import central
import dependencies
import pipeline


@contextlib.contextmanager
def workspace():
    previous = Path.cwd()
    with tempfile.TemporaryDirectory() as directory:
        os.chdir(directory)
        try:
            yield Path(directory)
        finally:
            os.chdir(previous)


def pom(version='0.0.8'):
    return f'<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>io.github.davidhlp</groupId><artifactId>ResiCache</artifactId><version>{version}</version></project>'


def candidate():
    Path('pom.xml').write_text(pom())
    Path('target').mkdir()
    for suffix in ('.jar', '-sources.jar', '-javadoc.jar'):
        Path('target/ResiCache-0.0.8' + suffix).write_bytes(b'candidate bytes')
    with patch('pipeline.subprocess.check_output', return_value='a' * 40 + '\n'), patch.dict(os.environ, {'GITHUB_RUN_ID': '123', 'GITHUB_REPOSITORY': 'DavidHLP/ResiCache'}):
        pipeline.candidate(Path('candidate'))
    return pipeline.verify_candidate(Path('candidate'))


class PipelineTests(unittest.TestCase):
    def test_docs_allowlist_and_full_fallback(self):
        self.assertTrue(pipeline.docs_only(['README.md', 'docs/OPERATIONS.md']))
        for path in ['scripts/ci/x.sh', '.mvn/wrapper/maven-wrapper.properties', '.github/workflows/ci.yml', 'pom.xml', 'new-file', 'docs/example.java', 'src/test/A.java']:
            self.assertFalse(pipeline.docs_only(['README.md', path]))
        self.assertFalse(pipeline.docs_only([]))

    def test_gate_requires_exact_inventory_and_explicit_skips(self):
        pipeline.gate({'build': {'result': 'success'}}, ['build'], [])
        pipeline.gate({'build': {'result': 'skipped'}}, ['build'], ['build'])
        for results in [{}, {'extra': {'result': 'success'}}, {'build': {'result': 'skipped'}}, {'build': {'result': 'cancelled'}}, {'build': {'result': 'failure'}}, {'build': {'result': None}}, {'build': 'success'}]:
            with self.subTest(results=results), self.assertRaises(ValueError):
                pipeline.gate(results, ['build'], [])

    def test_gate_does_not_accept_valid_prefix_of_invalid_json(self):
        script = Path(pipeline.__file__).with_name('require-all-green.sh')
        for raw in ['{"build":{"result":"success"}} trailing', '{"build":', '', '[]']:
            env = os.environ | {'RESULTS': raw, 'EXPECTED_JOBS': 'build'}
            self.assertNotEqual(0, subprocess.run(['bash', str(script)], env=env, capture_output=True).returncode)

    def test_release_plugins_do_not_enter_normal_build_and_sign_before_upload(self):
        import xml.etree.ElementTree as ET
        root = ET.parse(Path(__file__).resolve().parents[3] / 'pom.xml').getroot()
        ns = pipeline.NS
        normal = {node.findtext('m:artifactId', namespaces=ns) for node in root.findall('m:build/m:plugins/m:plugin', ns)}
        self.assertNotIn('maven-gpg-plugin', normal)
        self.assertNotIn('central-publishing-maven-plugin', normal)
        release = next(node for node in root.findall('m:profiles/m:profile', ns) if node.findtext('m:id', namespaces=ns) == 'release')
        plugins = {node.findtext('m:artifactId', namespaces=ns): node for node in release.findall('m:build/m:plugins/m:plugin', ns)}
        self.assertEqual('verify', plugins['maven-gpg-plugin'].findtext('m:executions/m:execution/m:phase', namespaces=ns))
        publishing = plugins['central-publishing-maven-plugin']
        self.assertEqual('true', publishing.findtext('m:configuration/m:autoPublish', namespaces=ns))
        self.assertEqual('published', publishing.findtext('m:configuration/m:waitUntil', namespaces=ns))

    def test_semver(self):
        for tag in ['v0.0.8', 'v1.2.3-preview.1', 'v1.2.3-foo-bar']:
            self.assertEqual(tag[1:], pipeline.release_version(tag))
        for tag in ['0.0.8', 'v01.2.3', 'v1.2.3-01', 'v1.2.3-', 'v1.2.3+build', 'v1.2.3-a..b']:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                pipeline.release_version(tag)

    def test_candidate_binding_and_tamper(self):
        with workspace():
            candidate()
            pipeline.verify_candidate(Path('candidate'), 'a' * 40, '0.0.8')
            with self.assertRaises(ValueError):
                pipeline.verify_candidate(Path('candidate'), 'b' * 40)
            Path('candidate/ResiCache-0.0.8.jar').write_bytes(b'tampered')
            with self.assertRaises(ValueError):
                pipeline.verify_candidate(Path('candidate'))

    def test_report_rejects_missing_skipped_failed_and_unit_integration(self):
        with workspace():
            Path('reports').mkdir()
            Path('src').mkdir()
            Path('src/RedisIntegrationTest.java').write_text('package sample; class RedisIntegrationTest {}')
            report = Path('reports/TEST-sample.xml')
            for case in ['', '<testcase classname="sample.OtherTest"/>', '<testcase classname="sample.RedisIntegrationTest"><skipped/></testcase>', '<testcase classname="sample.RedisIntegrationTest"><error/></testcase>']:
                name = 'sample.OtherTest' if 'sample.OtherTest' in case else 'sample.RedisIntegrationTest'
                report.write_text('<testsuite name="' + name + '">' + case + '</testsuite>')
                with self.assertRaises(ValueError):
                    pipeline.report_tests(Path('reports'), Path('src'), False)
            report.write_text('<testsuite name="sample.RedisIntegrationTest" tests="0"><testcase classname="nested display name"/></testsuite>')
            pipeline.report_tests(Path('reports'), Path('src'), False)
            with self.assertRaises(ValueError):
                pipeline.report_tests(Path('reports'), Path('src'), True)

    def test_preflight_coordinates_changelog_and_ancestry(self):
        with workspace():
            subprocess.run(['git', 'init', '-b', 'main', '-q'], check=True)
            Path('pom.xml').write_text(pom())
            Path('resicache-bench').mkdir()
            Path('resicache-bench/pom.xml').write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><properties><resicache.version>0.0.8</resicache.version></properties></project>')
            Path('CHANGELOG.md').write_text('## [0.0.8]\nRelease notes\n')
            subprocess.run(['git', 'add', 'pom.xml', 'resicache-bench/pom.xml', 'CHANGELOG.md'], check=True)
            subprocess.run(['git', '-c', 'user.name=CI', '-c', 'user.email=ci@tests.invalid', 'commit', '-qm', 'fixture'], check=True)
            subprocess.run(['git', 'tag', 'v0.0.8'], check=True)
            subprocess.run(['git', 'update-ref', 'refs/remotes/origin/main', 'HEAD'], check=True)
            with patch('pipeline.urllib.request.urlopen', side_effect=pipeline.urllib.error.HTTPError('url', 404, 'missing', {}, None)):
                self.assertEqual('0.0.8', pipeline.preflight('v0.0.8'))
            with patch('pipeline.urllib.request.urlopen'):
                with self.assertRaisesRegex(ValueError, 'already exist'):
                    pipeline.preflight('v0.0.8')
            with patch('pipeline.urllib.request.urlopen', side_effect=pipeline.urllib.error.HTTPError('url', 503, 'unavailable', {}, None)):
                with self.assertRaises(ValueError):
                    pipeline.preflight('v0.0.8')
            Path('CHANGELOG.md').write_text('## [Unreleased]\n')
            with self.assertRaisesRegex(ValueError, 'Changelog'):
                pipeline.preflight('v0.0.8', recovery=True)

    def test_new_high_dependency_policy(self):
        old = {'package': 'x:y', 'version': '1', 'id': 'GHSA-old', 'severity': 'HIGH'}
        new = {'package': 'a:b', 'version': '2', 'id': 'GHSA-new', 'severity': 'CRITICAL'}
        low = new | {'id': 'GHSA-low', 'severity': 'LOW'}
        unknown = new | {'id': 'OSV-unknown', 'severity': 'UNKNOWN'}
        self.assertEqual([new, unknown], dependencies.new_risks([old | {'version': '2'}, new, low, unknown], [old]))


class CentralTests(unittest.TestCase):
    def record(self):
        return {'deployment_id': '12345678-1234-1234-1234-123456789012', 'deployment_name': 'candidate', 'purl': 'pkg:maven/io.github.davidhlp/ResiCache@0.0.8'}

    def test_poll_requires_published_correct_coordinates_and_identity(self):
        record = self.record()
        valid = {'deploymentId': record['deployment_id'], 'deploymentName': 'candidate', 'purls': [record['purl']]}
        responses = [json.dumps(valid | {'deploymentState': state}).encode() for state in ['PENDING', 'VALIDATING', 'PUBLISHING', 'PUBLISHED']]
        with patch('central.request', side_effect=responses):
            self.assertEqual('PUBLISHED', central.poll(record, timeout=1, interval=0)['deploymentState'])
        for status in [valid | {'deploymentState': 'FAILED'}, valid | {'deploymentState': 'unknown'}, valid | {'deploymentState': 'PUBLISHED', 'purls': []}, valid | {'deploymentState': 'PUBLISHED', 'deploymentId': 'other'}]:
            with patch('central.request', return_value=json.dumps(status).encode()), self.assertRaises(ValueError):
                central.poll(record, timeout=1, interval=0)
        with self.assertRaisesRegex(ValueError, 'timed out'):
            central.poll(record, timeout=0)

    def test_actual_http_upload_and_status(self):
        record = self.record()
        requests = []

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                requests.append((self.path, self.headers.get('Authorization')))
                self.rfile.read(int(self.headers.get('Content-Length', '0')))
                self.send_response(201 if '/upload?' in self.path else 200)
                self.end_headers()
                if '/upload?' in self.path:
                    self.wfile.write(record['deployment_id'].encode())
                else:
                    self.wfile.write(json.dumps({'deploymentId': record['deployment_id'],
                        'deploymentName': record['deployment_name'], 'deploymentState': 'PUBLISHED',
                        'purls': [record['purl']]}).encode())

            def log_message(self, *args):
                pass

        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with workspace(), patch.dict(os.environ, {'CENTRAL_USERNAME': 'fixture', 'CENTRAL_PASSWORD': 'fixture'}):
                Path('publication').mkdir()
                Path('publication/publication.json').write_text(json.dumps({'deployment_name': 'candidate', 'purl': record['purl']}))
                Path('publication/central-bundle.zip').write_bytes(b'zip')
                base = 'http://127.0.0.1:' + str(server.server_port)
                uploaded = central.upload(Path('publication'), base=base)
                self.assertEqual(record['deployment_id'], uploaded['deployment_id'])
                self.assertEqual('PUBLISHED', central.poll(uploaded, base=base, timeout=1)['deploymentState'])
                self.assertIn('publishingType=AUTOMATIC', requests[0][0])
                self.assertTrue(all(auth.startswith('Bearer ') for _, auth in requests))
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_published_bytes_must_equal_candidate(self):
        with workspace():
            candidate()
            class Response:
                def __enter__(self):
                    return self
                def __exit__(self, *args):
                    pass
                def read(self):
                    return b'tampered public artifact'
            with patch('central.urllib.request.urlopen', return_value=Response()), self.assertRaisesRegex(ValueError, 'differ'):
                central.verify_published(Path('candidate'))

    def test_upload_timeout_is_not_retried(self):
        with workspace():
            Path('publication').mkdir()
            Path('publication/publication.json').write_text(json.dumps({'deployment_name': 'candidate'}))
            Path('publication/central-bundle.zip').write_bytes(b'zip')
            with patch('central.request', side_effect=TimeoutError) as request, self.assertRaises(TimeoutError):
                central.upload(Path('publication'))
            self.assertEqual(1, request.call_count)

    def test_signed_bundle_and_recovery(self):
        with workspace() as root:
            candidate()
            home = root / 'gpg'
            home.mkdir(mode=0o700)
            env = os.environ | {'GNUPGHOME': str(home)}
            subprocess.run(['gpg', '--batch', '--passphrase', '', '--quick-generate-key', 'CI fixture <ci@tests.invalid>', 'rsa2048', 'sign', '1d'], env=env, check=True, capture_output=True)
            keys = subprocess.check_output(['gpg', '--with-colons', '--list-secret-keys'], env=env, text=True)
            fingerprint = next(line.split(':')[9] for line in keys.splitlines() if line.startswith('fpr:'))
            private = subprocess.check_output(['gpg', '--batch', '--armor', '--export-secret-keys', fingerprint], env=env).decode()
            with patch.dict(os.environ, {'GPG_PRIVATE_KEY': private, 'GPG_PASSPHRASE': ''}):
                record = central.bundle(Path('candidate'), Path('publication'), fingerprint)
            record['deployment_id'] = self.record()['deployment_id']
            Path('publication/publication.json').write_text(json.dumps(record))
            central.recovery(Path('candidate'), Path('publication'), record['deployment_id'])
            with self.assertRaises(ValueError):
                central.recovery(Path('candidate'), Path('publication'), 'other')
            Path('publication/ResiCache-0.0.8.jar.asc').unlink()
            with self.assertRaises(ValueError):
                central.recovery(Path('candidate'), Path('publication'), record['deployment_id'])
            subprocess.run(['gpgconf', '--kill', 'gpg-agent'], env=env, check=True)


if __name__ == '__main__':
    unittest.main()
