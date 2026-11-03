"""Keep test instrumentation available without weakening the unit/full gates."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


class TestJvmConfigurationTests(unittest.TestCase):
    def setUp(self):
        self.ns = {"m": "http://maven.apache.org/POM/4.0.0"}
        self.pom = ET.parse(Path(__file__).resolve().parents[3] / "pom.xml").getroot()

    def plugin(self, parent, artifact):
        return next(p for p in parent.findall("m:build/m:plugins/m:plugin", self.ns)
                    if p.findtext("m:artifactId", namespaces=self.ns) == artifact)

    def test_both_test_paths_start_mockito_and_preserve_jacoco(self):
        properties = self.pom.find("m:properties", self.ns)
        self.assertIsNotNone(properties.find("m:argLine", self.ns))
        self.assertIsNotNone(properties.find("m:jacocoArgLine", self.ns))
        self.assertEqual(
            '@{jacocoArgLine} ${argLine} -javaagent:"${settings.localRepository}/org/mockito/mockito-core/'
            '${mockito.version}/mockito-core-${mockito.version}.jar"',
            properties.findtext("m:test.jvm.args", namespaces=self.ns))
        unit = next(p for p in self.pom.findall("m:profiles/m:profile", self.ns)
                    if p.findtext("m:id", namespaces=self.ns) == "unit")
        for parent in (self.pom, unit):
            plugin = self.plugin(parent, "maven-surefire-plugin")
            self.assertEqual("${test.jvm.args}", plugin.findtext(
                "m:configuration/m:argLine", namespaces=self.ns),
                "unit" if parent is unit else "full")
        excludes = self.plugin(unit, "maven-surefire-plugin").findall(
            "m:configuration/m:excludes/m:exclude", self.ns)
        self.assertEqual(["**/*IntegrationTest*.java"], [e.text for e in excludes])

    def test_coverage_thresholds_remain_unchanged(self):
        jacoco = self.plugin(self.pom, "jacoco-maven-plugin")
        self.assertEqual("0.8.11", jacoco.findtext("m:version", namespaces=self.ns))
        executions = {e.findtext("m:id", namespaces=self.ns): e
                      for e in jacoco.findall("m:executions/m:execution", self.ns)}
        for name in ("prepare-agent", "report", "check"):
            self.assertEqual(name, executions[name].findtext(
                "m:goals/m:goal", namespaces=self.ns))
        self.assertEqual("test", executions["report"].findtext("m:phase", namespaces=self.ns))
        self.assertEqual("jacocoArgLine", executions["prepare-agent"].findtext(
            "m:configuration/m:propertyName", namespaces=self.ns))
        check = next(e for e in jacoco.findall("m:executions/m:execution", self.ns)
                     if e.findtext("m:id", namespaces=self.ns) == "check")
        self.assertEqual("BUNDLE", check.findtext(
            "m:configuration/m:rules/m:rule/m:element", namespaces=self.ns))
        limits = check.findall("m:configuration/m:rules/m:rule/m:limits/m:limit", self.ns)
        self.assertEqual({"LINE": "0.70", "BRANCH": "0.40"}, {
            item.findtext("m:counter", namespaces=self.ns):
            item.findtext("m:minimum", namespaces=self.ns) for item in limits})
        self.assertTrue(all(item.findtext("m:value", namespaces=self.ns) == "COVEREDRATIO"
                            for item in limits))

    def test_full_and_unit_test_boundaries_remain_distinct(self):
        full = self.plugin(self.pom, "maven-surefire-plugin").find("m:configuration", self.ns)
        self.assertIsNone(full.find("m:excludes", self.ns))
        self.assertEqual("${testcontainers.ryuk.disabled}", full.findtext(
            "m:environmentVariables/m:TESTCONTAINERS_RYUK_DISABLED", namespaces=self.ns))
        for parent in [self.pom] + self.pom.findall("m:profiles/m:profile", self.ns):
            for config in parent.findall("m:build/m:plugins/m:plugin/m:configuration", self.ns):
                for flag in ("skip", "skipTests", "testFailureIgnore"):
                    self.assertIsNone(config.find("m:" + flag, self.ns))

    def test_mockito_version_is_boot_managed_and_not_duplicated(self):
        properties = self.pom.find("m:properties", self.ns)
        self.assertIsNone(properties.find("m:mockito.version", self.ns))
        self.assertFalse(any(d.findtext("m:artifactId", namespaces=self.ns) == "mockito-core"
                             for d in self.pom.findall("m:dependencies/m:dependency", self.ns)))

    def test_instrumentation_guard_rejects_lost_agent_and_late_binding(self):
        original = ET.tostring(self.pom)
        mutations = ((b"@{jacocoArgLine}", b"${jacocoArgLine}"),
                     (b" ${argLine} ", b" "),
                     (b"-javaagent:", b"-DnotAnAgent="),
                     (b"${test.jvm.args}", b"", 1))
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                self.pom = ET.fromstring(original.replace(*mutation))
                with self.assertRaises(AssertionError):
                    self.test_both_test_paths_start_mockito_and_preserve_jacoco()
        self.pom = ET.fromstring(original)


if __name__ == "__main__":
    unittest.main()
