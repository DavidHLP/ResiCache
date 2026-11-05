"""Check selected root-POM instrumentation declarations.

These static contracts do not evaluate Maven profile/CLI overrides or establish
that execution data exists. Runtime coverage still requires full verification.
"""
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

    def set_value(self, parent, path, value):
        for name in path.split("/"):
            child = parent.find("m:" + name, self.ns)
            if child is None:
                child = ET.SubElement(parent, "{" + self.ns["m"] + "}" + name)
            parent = child
        parent.text = value

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
        for name, phase in (("prepare-agent", "initialize"), ("check", "verify")):
            self.assertIn(executions[name].findtext("m:phase", namespaces=self.ns),
                          (None, phase))
        for flag, safe in (("jacoco.skip", "false"), ("jacoco.haltOnFailure", "true")):
            self.assertIn(self.pom.findtext("m:properties/m:" + flag, namespaces=self.ns),
                          (None, safe), flag)
        for owner in (jacoco, executions["prepare-agent"], executions["report"], check):
            self.assertIn(owner.findtext("m:configuration/m:skip", namespaces=self.ns),
                          (None, "false"))
        for owner in (jacoco, check):
            for flag, safe_default in (("skip", "false"), ("haltOnFailure", "true")):
                value = owner.findtext("m:configuration/m:" + flag, namespaces=self.ns)
                self.assertIn(value, (None, safe_default), flag)
            for filtering in ("includes", "excludes"):
                self.assertIsNone(owner.find("m:configuration/m:" + filtering, self.ns))
        rules = check.findall("m:configuration/m:rules/m:rule", self.ns)
        required = {("LINE", "COVEREDRATIO", "0.70"),
                    ("BRANCH", "COVEREDRATIO", "0.40")}
        # Class-specific or filtered rules cannot replace the bundle-wide gate.
        self.assertTrue(any(
            rule.findtext("m:element", namespaces=self.ns) == "BUNDLE"
            and rule.find("m:includes", self.ns) is None
            and rule.find("m:excludes", self.ns) is None
            and required.issubset({
                tuple(limit.findtext("m:" + field, namespaces=self.ns)
                      for field in ("counter", "value", "minimum"))
                for limit in rule.findall("m:limits/m:limit", self.ns)})
            for rule in rules), "Missing unfiltered bundle coverage limits")

    def test_coverage_guard_rejects_disabled_or_non_blocking_check(self):
        original = ET.tostring(self.pom)
        for location in ("plugin", "execution"):
            for flag, unsafe in (("skip", "true"), ("haltOnFailure", "false")):
                with self.subTest(location=location, flag=flag):
                    self.pom = ET.fromstring(original)
                    jacoco = self.plugin(self.pom, "jacoco-maven-plugin")
                    owner = jacoco if location == "plugin" else next(
                        e for e in jacoco.findall("m:executions/m:execution", self.ns)
                        if e.findtext("m:id", namespaces=self.ns) == "check")
                    self.set_value(owner, "configuration/" + flag, unsafe)
                    with self.assertRaises(AssertionError):
                        self.test_coverage_thresholds_remain_unchanged()
        self.pom = ET.fromstring(original)

    def test_class_rule_cannot_hide_weaker_or_filtered_bundle_gate(self):
        jacoco = self.plugin(self.pom, "jacoco-maven-plugin")
        check = next(e for e in jacoco.findall("m:executions/m:execution", self.ns)
                     if e.findtext("m:id", namespaces=self.ns) == "check")
        config = check.find("m:configuration", self.ns)
        # Test the guard with an independent rule fixture. Mutating the first
        # live rule is ambiguous when valid extra rules or counters are present.
        rules = check.find("m:configuration/m:rules", self.ns)
        config.remove(rules)
        rules = ET.fromstring('''
            <rules xmlns="http://maven.apache.org/POM/4.0.0"><rule>
                <element>BUNDLE</element><limits>
                    <limit><counter>LINE</counter><value>COVEREDRATIO</value>
                        <minimum>0.70</minimum></limit>
                    <limit><counter>BRANCH</counter><value>COVEREDRATIO</value>
                        <minimum>0.40</minimum></limit>
                </limits>
            </rule></rules>''')
        config.append(rules)
        bundle = rules.find("m:rule", self.ns)
        extra = ET.fromstring(ET.tostring(bundle))
        extra.find("m:element", self.ns).text = "CLASS"
        rules.insert(0, extra)
        self.test_coverage_thresholds_remain_unchanged()
        minimum = bundle.find("m:limits/m:limit/m:minimum", self.ns)
        original = minimum.text
        minimum.text = "0.00"
        with self.assertRaises(AssertionError):
            self.test_coverage_thresholds_remain_unchanged()

        minimum.text = original
        exclusions = ET.SubElement(bundle, "{" + self.ns["m"] + "}excludes")
        ET.SubElement(exclusions, "{" + self.ns["m"] + "}exclude").text = "*"
        with self.assertRaises(AssertionError):
            self.test_coverage_thresholds_remain_unchanged()

    def test_rule_mutation_fixture_accepts_extra_valid_live_rules(self):
        jacoco = self.plugin(self.pom, "jacoco-maven-plugin")
        check = next(e for e in jacoco.findall("m:executions/m:execution", self.ns)
                     if e.findtext("m:id", namespaces=self.ns) == "check")
        rules = check.find("m:configuration/m:rules", self.ns)
        bundle = next(rule for rule in rules.findall("m:rule", self.ns)
                      if rule.findtext("m:element", namespaces=self.ns) == "BUNDLE")
        limits = bundle.find("m:limits", self.ns)
        for counter in ("METHOD", "INSTRUCTION"):
            extra = ET.fromstring(ET.tostring(limits.find("m:limit", self.ns)))
            extra.find("m:counter", self.ns).text = counter
            limits.insert(0, extra)
        rules.append(ET.fromstring(ET.tostring(bundle)))
        filtered = ET.fromstring(ET.tostring(bundle))
        self.set_value(filtered, "excludes/exclude", "some-bundle")
        rules.insert(0, filtered)
        self.test_coverage_thresholds_remain_unchanged()
        self.test_class_rule_cannot_hide_weaker_or_filtered_bundle_gate()

    def test_coverage_guard_rejects_properties_filters_and_unbound_goals(self):
        original = ET.tostring(self.pom)
        mutations = [
            ("root", "properties/jacoco.skip", "true"),
            ("root", "properties/jacoco.haltOnFailure", "false"),
            ("check", "configuration/excludes/exclude", "*"),
            ("check", "configuration/includes/include", "OnlyOneClass"),
            ("check", "phase", "none"),
            ("prepare-agent", "phase", "none"),
            ("prepare-agent", "configuration/skip", "true"),
            ("report", "configuration/skip", "true"),
        ]
        for location, path, value in mutations:
            with self.subTest(location=location, path=path):
                self.pom = ET.fromstring(original)
                jacoco = self.plugin(self.pom, "jacoco-maven-plugin")
                owner = self.pom if location == "root" else next(
                    e for e in jacoco.findall("m:executions/m:execution", self.ns)
                    if e.findtext("m:id", namespaces=self.ns) == location)
                self.set_value(owner, path, value)
                with self.assertRaises(AssertionError):
                    self.test_coverage_thresholds_remain_unchanged()
        self.pom = ET.fromstring(original)

    def test_negative_regressions_accept_explicit_safe_flags_and_class_first(self):
        jacoco = self.plugin(self.pom, "jacoco-maven-plugin")
        check = next(e for e in jacoco.findall("m:executions/m:execution", self.ns)
                     if e.findtext("m:id", namespaces=self.ns) == "check")
        self.set_value(check, "configuration/haltOnFailure", "true")
        rules = check.find("m:configuration/m:rules", self.ns)
        bundle = next(rule for rule in rules.findall("m:rule", self.ns)
                      if rule.findtext("m:element", namespaces=self.ns) == "BUNDLE")
        extra = ET.fromstring(ET.tostring(bundle))
        extra.find("m:element", self.ns).text = "CLASS"
        rules.insert(0, extra)
        self.test_coverage_thresholds_remain_unchanged()
        self.test_coverage_guard_rejects_disabled_or_non_blocking_check()
        self.test_class_rule_cannot_hide_weaker_or_filtered_bundle_gate()

    def test_coverage_guard_allows_plugin_updates_but_rejects_weaker_limits(self):
        jacoco = self.plugin(self.pom, "jacoco-maven-plugin")
        jacoco.find("m:version", self.ns).text = "0.8.15"
        self.test_coverage_thresholds_remain_unchanged()
        limits = jacoco.findall(
            "m:executions/m:execution/m:configuration/m:rules/m:rule/m:limits/m:limit",
            self.ns)
        for counter in ("LINE", "BRANCH"):
            # Mutate every occurrence together; the separate class-rule test
            # proves that one weaker bundle limit cannot be masked by another
            # rule with the same counter.
            minima = [limit.find("m:minimum", self.ns) for limit in limits
                      if limit.findtext("m:counter", namespaces=self.ns) == counter
                      and limit.findtext("m:value", namespaces=self.ns) == "COVEREDRATIO"
                      and limit.find("m:minimum", self.ns) is not None]
            original = [minimum.text for minimum in minima]
            with self.subTest(counter=counter):
                try:
                    for minimum in minima:
                        minimum.text = "0.00"
                    with self.assertRaises(AssertionError):
                        self.test_coverage_thresholds_remain_unchanged()
                finally:
                    for minimum, value in zip(minima, original):
                        minimum.text = value

    def test_plugin_update_mutation_allows_maximum_only_limit(self):
        jacoco = self.plugin(self.pom, "jacoco-maven-plugin")
        check = next(e for e in jacoco.findall("m:executions/m:execution", self.ns)
                     if e.findtext("m:id", namespaces=self.ns) == "check")
        bundle = next(rule for rule in check.findall(
            "m:configuration/m:rules/m:rule", self.ns)
            if rule.findtext("m:element", namespaces=self.ns) == "BUNDLE")
        limits = bundle.find("m:limits", self.ns)
        limits.append(ET.fromstring('''
            <limit xmlns="http://maven.apache.org/POM/4.0.0">
                <counter>LINE</counter><value>MISSEDCOUNT</value><maximum>100</maximum>
            </limit>'''))
        self.test_coverage_thresholds_remain_unchanged()
        self.test_coverage_guard_allows_plugin_updates_but_rejects_weaker_limits()
        self.test_coverage_thresholds_remain_unchanged()

    def test_plugin_update_regression_allows_additional_class_line_rule(self):
        jacoco = self.plugin(self.pom, "jacoco-maven-plugin")
        check = next(e for e in jacoco.findall("m:executions/m:execution", self.ns)
                     if e.findtext("m:id", namespaces=self.ns) == "check")
        rules = check.find("m:configuration/m:rules", self.ns)
        rules.append(ET.fromstring('''
            <rule xmlns="http://maven.apache.org/POM/4.0.0">
                <element>CLASS</element>
                <limits><limit>
                    <counter>LINE</counter><value>COVEREDRATIO</value><minimum>0.70</minimum>
                </limit></limits>
            </rule>'''))
        self.test_coverage_thresholds_remain_unchanged()
        self.test_coverage_guard_allows_plugin_updates_but_rejects_weaker_limits()
        self.test_coverage_thresholds_remain_unchanged()

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
