import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / 'scripts/ci/check-docs-contracts.sh'


class DocsContractTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        for source in ROOT.glob('*.md'):
            shutil.copy2(source, self.root / source.name)
        for directory in ('docs', 'src/main/java', 'src/test/resources'):
            shutil.copytree(ROOT / directory, self.root / directory)
        shutil.copy2(ROOT / 'pom.xml', self.root / 'pom.xml')
        subprocess.run(['git', 'init', '-q'], cwd=self.root, check=True)
        subprocess.run(['git', 'add', 'src/main/java'], cwd=self.root, check=True)

    def guard(self, succeeds):
        result = subprocess.run(['bash', str(SCRIPT)], cwd=self.root, capture_output=True, text=True)
        self.assertEqual(succeeds, result.returncode == 0, result.stdout + result.stderr)

    def replace(self, name, old, new):
        path = self.root / name
        contents = path.read_text()
        self.assertIn(old, contents)
        path.write_text(contents.replace(old, new))

    def test_contract_cannot_be_satisfied_by_another_owner(self):
        self.guard(True)
        statement = 'PUT, PUT_IF_ABSENT, and CLEAN'
        self.replace('COMPATIBILITY.md', statement, 'write operations')
        with (self.root / 'README.md').open('a') as document:
            document.write('\n' + statement + '\n')
        self.guard(False)

    def test_historical_quotes_do_not_become_current_guidance(self):
        for name in ('CHANGELOG.md', 'PERFORMANCE.md'):
            with (self.root / name).open('a') as document:
                document.write('\nHistorical example: Java 21+ and pr-checks.yml.\n')
        self.guard(True)
        with (self.root / 'docs/DEVELOPMENT.md').open('a') as document:
            document.write('\nJava 21+\n')
        self.guard(False)

    def test_version_change_requires_current_docs_and_resources(self):
        ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
        pom = ET.parse(self.root / 'pom.xml').getroot()
        dependency = next(node for node in pom.findall('m:dependencyManagement/m:dependencies/m:dependency', ns)
                          if node.findtext('m:artifactId', namespaces=ns) == 'testcontainers-bom')
        version = dependency.findtext('m:version', namespaces=ns)
        updated = '99.0.1'
        self.replace('pom.xml', '<version>' + version + '</version>', '<version>' + updated + '</version>')
        for name in ('src/test/resources/testcontainers.properties', 'src/test/resources/docker-java.properties'):
            self.replace(name, 'testcontainers-bom:' + version, 'testcontainers-bom:' + updated)
        self.guard(False)
        self.replace('COMPATIBILITY.md', '| Testcontainers | ' + version, '| Testcontainers | ' + updated)
        self.guard(True)

    def test_local_guard_preserves_readme_and_source_checks(self):
        with (self.root / 'README.md').open('a') as document:
            document.write('\n`BloomFilterProvider`\n')
        self.guard(False)
        self.replace('README.md', '\n`BloomFilterProvider`\n', '\n')
        source = next((self.root / 'src/main/java').rglob('RedisCacheAutoConfiguration.java'))
        source.unlink()
        self.guard(False)


if __name__ == '__main__':
    unittest.main()
