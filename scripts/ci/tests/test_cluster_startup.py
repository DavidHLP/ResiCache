"""Exercise the fixture's actual shell command offline, without Docker or Redis."""

import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]
FIXTURE = ROOT / 'src/test/java/io/github/davidhlp/spring/cache/redis/cache/AbstractRedisClusterIntegrationTest.java'


class ClusterStartupTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        # Java string literals here use JSON-compatible escapes. Execute the
        # assembled production command rather than a second copy of its logic.
        method = FIXTURE.read_text().split('private static String clusterStartupCommand() {', 1)[1]
        self.command = ''.join(json.loads(literal) for literal in re.findall(r'"(?:[^"\\]|\\.)*"', method))
        self.command = self.command.replace('/data/', str(self.root / 'data') + '/')
        self.install('hostname', '#!/bin/sh\necho 127.0.0.1\n')
        self.install('redis-server', '#!/bin/sh\necho node-log > "${1%/*}/redis.log"\n')
        self.install('sleep', '#!/bin/sh\nexit 0\n')
        self.install('tail', '#!/bin/sh\nexit 0\n')
        self.install('redis-cli', '''#!/usr/bin/env python3
import json
import os
from pathlib import Path
import sys
root = Path(os.environ['FAKE_REDIS_STATE'])
args = sys.argv[1:]
state_path = root / 'state.json'
state = json.loads(state_path.read_text()) if state_path.exists() else {}
mode = os.environ.get('FAKE_REDIS_MODE', '')
if args[:2] == ['--cluster', 'create']:
    if any(state.get('ping-' + str(port), 0) < (3 if port == 7002 else 1)
           for port in range(7000, 7003)):
        sys.exit('cluster creation attempted before all nodes responded')
    state['created'] = True
    state_path.write_text(json.dumps(state))
    sys.exit(1 if mode == 'create-fails' else 0)
port = args[args.index('-p') + 1]
phase = 'ping' if args[-1] == 'ping' else 'health'
key = phase + '-' + port
state[key] = state.get(key, 0) + 1
state_path.write_text(json.dumps(state))
if phase == 'health' and not state.get('created'):
    sys.exit('health checked before creation')
if port == '7002' and (state[key] < 3 or mode == phase + '-fails'):
    if phase == 'health':
        print('cluster_state:fail')
    elif mode == 'ping-fails':
        # Redis may return an error reply with a successful process exit code.
        print('LOADING Redis is loading the dataset in memory')
    else:
        sys.exit(1)
else:
    print('PONG' if phase == 'ping' else 'cluster_state:ok\\r')
''')

    def install(self, name, contents):
        path = self.bin / name
        path.write_text(contents)
        path.chmod(0o755)

    def run_startup(self, mode=''):
        env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ['PATH'],
                   FAKE_REDIS_STATE=str(self.root), FAKE_REDIS_MODE=mode)
        result = subprocess.run(['sh', '-c', self.command], env=env,
                                capture_output=True, text=True, timeout=20)
        return result, json.loads((self.root / 'state.json').read_text())

    def test_waits_for_every_node_before_create_and_ready_marker(self):
        result, state = self.run_startup()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('CLUSTER_READY', result.stdout)
        self.assertEqual(3, state['ping-7002'])
        self.assertEqual(3, state['health-7002'])
        for port in range(7000, 7003):
            self.assertIn('health-' + str(port), state)

    def test_node_readiness_failure_is_bounded_and_prevents_creation(self):
        result, state = self.run_startup('ping-fails')
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn('created', state)
        self.assertEqual(100, state['ping-7002'])
        self.assertIn('Redis node 7002 did not become ready', result.stderr)
        self.assertIn('node-log', result.stderr)
        self.assertNotIn('CLUSTER_READY', result.stdout)

    def test_cluster_health_failure_is_bounded_and_prevents_ready_marker(self):
        result, state = self.run_startup('health-fails')
        self.assertNotEqual(0, result.returncode)
        self.assertTrue(state['created'])
        self.assertEqual(100, state['health-7002'])
        self.assertIn('Redis node 7002 did not reach cluster_state:ok', result.stderr)
        self.assertNotIn('CLUSTER_READY', result.stdout)

    def test_creation_failure_is_not_masked(self):
        result, state = self.run_startup('create-fails')
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn('health-7000', state)
        self.assertNotIn('CLUSTER_READY', result.stdout)


if __name__ == '__main__':
    unittest.main()
