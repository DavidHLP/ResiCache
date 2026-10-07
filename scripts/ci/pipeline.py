#!/usr/bin/env python3
"""Dependency-free CI contracts shared by local checks and Actions."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
VERSION = r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def coordinates(pom):
    root = ET.parse(pom).getroot()
    return tuple(root.findtext(f"m:{key}", namespaces=NS) for key in ("groupId", "artifactId", "version"))


def release_version(tag):
    match = re.fullmatch("v" + VERSION, tag)
    require(match is not None, "Expected vX.Y.Z[-prerelease], without leading zeroes")
    if match[4]:
        require(all(not (part.isdigit() and len(part) > 1 and part.startswith("0"))
                    for part in match[4].split(".")), "Numeric prerelease identifier has a leading zero")
    return tag[1:]


def docs_only(paths):
    # New/unknown paths always get full verification; deletions are included.
    return bool(paths) and all(re.fullmatch(
        r"(?:docs/[^\n]+\.md|(?:README(?:\.zh-CN)?|AGENTS|CHANGELOG|COMPATIBILITY|CONTRIBUTING|PERFORMANCE|SECURITY|STABILITY)\.md|LICENSE)",
        path) for path in paths)


def gate(results, expected, allowed):
    require(isinstance(results, dict), "RESULTS must be a JSON object")
    require(expected and len(set(expected)) == len(expected), "Expected jobs must be nonempty and unique")
    require(set(results) == set(expected), "Missing or unexpected jobs in RESULTS")
    require(set(allowed) <= set(expected), "Unknown allowed skip")
    for job in expected:
        result = results[job]
        require(isinstance(result, dict), f"Malformed result for {job}")
        state = result.get("result")
        require(state == "success" or (state == "skipped" and job in allowed), f"Required job {job}: {state}")


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def candidate(output):
    group, artifact, version = coordinates("pom.xml")
    output.mkdir(parents=True, exist_ok=False)
    for suffix in (".jar", "-sources.jar", "-javadoc.jar"):
        source = Path("target") / f"{artifact}-{version}{suffix}"
        require(source.is_file(), f"Missing artifact: {source}")
        shutil.copyfile(source, output / source.name)
    shutil.copyfile("pom.xml", output / f"{artifact}-{version}.pom")
    manifest = {"group": group, "artifact": artifact, "version": version,
                "sha": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
                "repository": os.environ.get("GITHUB_REPOSITORY", "DavidHLP/ResiCache"),
                "run_id": os.environ.get("GITHUB_RUN_ID", "local"),
                "files": {p.name: digest(p) for p in sorted(output.iterdir())}}
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    hashes = {p.name: digest(p) for p in sorted(output.iterdir())}
    (output / "SHA256SUMS").write_text("".join(f"{value}  {name}\n" for name, value in hashes.items()))


def verify_candidate(directory, sha=None, version=None):
    manifest = json.loads((directory / "manifest.json").read_text())
    require(manifest.get("repository", "").lower() == "davidhlp/resicache", "Wrong repository")
    require(sha is None or manifest["sha"] == sha, "Candidate commit mismatch")
    require(version is None or manifest["version"] == version, "Candidate version mismatch")
    group, artifact, ver = manifest["group"], manifest["artifact"], manifest["version"]
    require((group, artifact) == ("io.github.davidhlp", "ResiCache"), "Wrong coordinates")
    require(re.fullmatch(VERSION, ver) is not None, "Invalid candidate version")
    expected = {f"{artifact}-{ver}{suffix}" for suffix in (".jar", "-sources.jar", "-javadoc.jar", ".pom")}
    require(set(manifest["files"]) == expected, "Candidate artifact set mismatch")
    for name, value in manifest["files"].items():
        require(digest(directory / name) == value, f"Candidate checksum mismatch: {name}")
    require(coordinates(directory / f"{artifact}-{ver}.pom") == (group, artifact, ver), "POM coordinates mismatch")
    sums = (directory / "SHA256SUMS").read_text().splitlines()
    require(len(sums) == 5, "Candidate checksum inventory mismatch")
    expected_sums = {f"{digest(directory / name)}  {name}" for name in expected | {"manifest.json"}}
    require(set(sums) == expected_sums, "Candidate checksum inventory mismatch")
    return manifest


def report_tests(report_dir, test_root, unit):
    reports = list(report_dir.glob("TEST-*.xml"))
    require(reports, "No Surefire XML reports")
    cases = []
    actual = set()
    for report in reports:
        suite = ET.parse(report).getroot()
        executed = list(suite.iter("testcase"))
        cases.extend(executed)
        # JUnit @DisplayName can replace testcase.classname (especially @Nested).
        # Surefire's suite identity is the compiled class, even when tests=0 on
        # the outer suite and its nested cases appear inside the same XML.
        if executed:
            actual.add(suite.get("name", "").split("$")[0])
    require(cases, "No test cases executed")
    require(not any(case.find("failure") is not None or case.find("error") is not None for case in cases), "Failed test cases")
    require(not any(case.find("skipped") is not None for case in cases), "Skipped tests are not CI evidence")
    if unit:
        require(not any("IntegrationTest" in name for name in actual), "Unit profile executed integration tests")
    else:
        for path in test_root.rglob("*IntegrationTest.java"):
            source = path.read_text()
            if re.search(r"\babstract\s+class\b", source):
                continue
            package = re.search(r"\bpackage\s+([\w.]+)\s*;", source)[1]
            require(package + "." + path.stem in actual, f"Integration test did not execute: {path}")
    print(f"Verified {len(cases)} executed test cases ({'unit' if unit else 'full'})")


def preflight(tag, published_url=None, recovery=False):
    version = release_version(tag)
    group, artifact, pom_version = coordinates("pom.xml")
    require(version == pom_version, "Tag/POM version mismatch; update the POM in a PR first")
    bench = ET.parse("resicache-bench/pom.xml").getroot()
    require(bench.findtext("m:properties/m:resicache.version", namespaces=NS) == version, "Benchmark core version mismatch")
    require(re.search(r"^## \[" + re.escape(version) + r"\]", Path("CHANGELOG.md").read_text(), re.M), "Missing versioned Changelog entry")
    subprocess.run(["git", "merge-base", "--is-ancestor", "HEAD", "origin/main"], check=True)
    require(subprocess.check_output(["git", "rev-parse", f"refs/tags/{tag}^{{commit}}"], text=True).strip()
            == subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(), "Tag/checkout mismatch")
    if not recovery:
        url = published_url or f"https://repo.maven.apache.org/maven2/{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.pom"
        try:
            urllib.request.urlopen(urllib.request.Request(url, method="HEAD"), timeout=30).close()
        except urllib.error.HTTPError as error:
            require(error.code == 404, f"Central existence check failed: {error.code}")
        else:
            raise ValueError("Maven Central coordinates already exist")
    return version


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("gate")
    classify = sub.add_parser("classify")
    classify.add_argument("base")
    classify.add_argument("head")
    create = sub.add_parser("candidate")
    create.add_argument("directory", type=Path)
    verify = sub.add_parser("verify-candidate")
    verify.add_argument("directory", type=Path)
    verify.add_argument("--sha")
    verify.add_argument("--version")
    reports = sub.add_parser("reports")
    reports.add_argument("--unit", action="store_true")
    reports.add_argument("--directory", type=Path, default=Path("target/surefire-reports"))
    reports.add_argument("--sources", type=Path, default=Path("src/test/java"))
    pre = sub.add_parser("preflight")
    pre.add_argument("tag")
    pre.add_argument("--recovery", action="store_true")
    args = parser.parse_args()
    if args.command == "gate":
        gate(json.loads(os.environ.get("RESULTS", "")), os.environ.get("EXPECTED_JOBS", "").split(),
             os.environ.get("ALLOWED_SKIPS", "").split())
        print("All required jobs succeeded; conditional skips were explicitly allowed")
    elif args.command == "classify":
        paths = subprocess.check_output(["git", "diff", "--name-only", "--no-renames", "-z", args.base + "..." + args.head]).decode().split("\0")
        print("docs_only=" + str(docs_only([path for path in paths if path])).lower())
    elif args.command == "candidate":
        candidate(args.directory)
    elif args.command == "verify-candidate":
        verify_candidate(args.directory, args.sha, args.version)
    elif args.command == "reports":
        report_tests(args.directory, args.sources, args.unit)
    else:
        print(preflight(args.tag, recovery=args.recovery))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError, ET.ParseError, subprocess.CalledProcessError) as error:
        print(f"::error::{error}", file=sys.stderr)
        sys.exit(1)
