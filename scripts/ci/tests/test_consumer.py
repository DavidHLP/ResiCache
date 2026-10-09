import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class ConsumerBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        scripts = self.root / 'scripts/ci'
        scripts.mkdir(parents=True)
        shutil.copy(Path(__file__).resolve().parents[1] / 'check-external-consumer.sh', scripts)
        self.executable(self.root / 'mvnw', '#!/bin/sh\ntouch maven-called\nexit 74\n')
        (scripts / 'pipeline.py').write_text(
            'import os, pathlib, sys\n'
            'pathlib.Path("selected-jdk").write_text(os.environ["JAVA_HOME"])\n'
            'sys.exit(73)\n')
        self.env = os.environ.copy()
        self.env.pop('JAVA_HOME', None)
        self.env.pop('RESICACHE_JDK21', None)

    def executable(self, path, content):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        path.chmod(0o755)

    def jdk(self, name, version='21', compiler=True):
        home = self.root / name
        self.executable(home / 'bin/java',
                        '#!/bin/sh\necho "    java.specification.version = ' + version + '" >&2\n')
        if compiler:
            self.executable(home / 'bin/javac', '#!/bin/sh\nexit 0\n')
        return home

    def run_consumer(self, *args):
        return subprocess.run(['bash', str(self.root / 'scripts/ci/check-external-consumer.sh'), *args],
                              cwd=self.root, env=self.env, capture_output=True, text=True)

    def test_missing_explicit_candidate_never_packages(self):
        self.env['JAVA_HOME'] = str(self.jdk('jdk'))
        result = self.run_consumer('missing-candidate')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Candidate directory does not exist', result.stderr)
        self.assertFalse((self.root / 'maven-called').exists())
        self.assertFalse((self.root / 'selected-jdk').exists())

    def test_wrong_or_incomplete_jdk_fails_before_packaging(self):
        for home in [self.jdk('jdk17', '17'), self.jdk('jre21', compiler=False), self.root / 'missing-jdk']:
            with self.subTest(home=home):
                self.env['JAVA_HOME'] = str(home)
                self.assertNotEqual(0, self.run_consumer().returncode)
                self.assertFalse((self.root / 'maven-called').exists())

    def test_jdk_precedence_and_path_fallback(self):
        candidate = self.root / 'candidate'
        candidate.mkdir()
        path_jdk = self.jdk('path-jdk')
        home_jdk = self.jdk('home-jdk')
        override_jdk = self.jdk('override-jdk')
        self.env['PATH'] = str(path_jdk / 'bin') + os.pathsep + self.env['PATH']
        for overrides, expected in [({}, path_jdk),
                                    ({'JAVA_HOME': str(home_jdk)}, home_jdk),
                                    ({'RESICACHE_JDK21': str(override_jdk)}, override_jdk)]:
            self.env.update(overrides)
            with self.subTest(expected=expected):
                self.assertEqual(73, self.run_consumer(str(candidate)).returncode)
                self.assertEqual(str(expected), (self.root / 'selected-jdk').read_text())
                self.assertFalse((self.root / 'maven-called').exists())

    def test_invalid_override_does_not_fall_back_to_valid_java_home(self):
        self.env['JAVA_HOME'] = str(self.jdk('jdk21'))
        self.env['RESICACHE_JDK21'] = str(self.jdk('override17', '17'))
        self.assertNotEqual(0, self.run_consumer().returncode)
        self.assertFalse((self.root / 'maven-called').exists())


if __name__ == '__main__':
    unittest.main()
