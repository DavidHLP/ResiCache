#!/usr/bin/env python3
"""Resolve both Maven graphs and compare OSV advisories without privileged PR tokens."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import urllib.parse
import urllib.request

from pipeline import coordinates, require, verify_candidate

ROOT = Path(__file__).resolve().parents[2]


def graphs(directory, candidate, output):
    # Graph resolution needs the checked-out core POM, never the old Central POM
    # with the same coordinates. No code from the dummy installation is executed.
    manifest = verify_candidate(candidate)
    with (output / "install.log").open("w") as log:
        subprocess.run([str(ROOT / "mvnw"), "org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file", "-B",
                        "-Dfile=" + str(candidate / f"ResiCache-{manifest['version']}.jar"),
                        "-DpomFile=" + str(directory / "pom.xml")], check=True, stdout=log, stderr=subprocess.STDOUT, timeout=300)
    packages = set()
    for label, pom in (("core", "pom.xml"), ("bench", "resicache-bench/pom.xml")):
        destination = output / (label + ".json")
        print(f"Resolving {label} dependency graph", flush=True)
        with (output / (label + ".log")).open("w") as log:
            subprocess.run([str(ROOT / "mvnw"), "-f", str(directory / pom), "-B",
                            "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:tree",
                            "-DoutputType=json", "-DoutputFile=" + str(destination)], check=True, stdout=log, stderr=subprocess.STDOUT, timeout=300)
        graph = json.loads(destination.read_text())
        require(graph.get("children"), f"No resolved {label} dependencies")

        def visit(node):
            if (node["groupId"], node["artifactId"]) != ("io.github.davidhlp", "ResiCache"):
                packages.add((node["groupId"] + ":" + node["artifactId"], node["version"]))
            for child in node.get("children", []):
                visit(child)

        for child in graph["children"]:
            visit(child)
    return packages


def scan(packages):
    ordered = sorted(packages)
    query = {"queries": [{"package": {"ecosystem": "Maven", "name": name}, "version": version}
                         for name, version in ordered]}
    request = urllib.request.Request("https://api.osv.dev/v1/querybatch", json.dumps(query).encode(),
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=60) as response:
        results = json.load(response)["results"]
    require(len(results) == len(ordered), "Incomplete OSV batch response")
    ids = {item["id"] for result in results for item in result.get("vulns", [])}

    def advisory(identifier):
        with urllib.request.urlopen("https://api.osv.dev/v1/vulns/" + urllib.parse.quote(identifier, safe=""), timeout=30) as response:
            return identifier, json.load(response)

    with ThreadPoolExecutor(max_workers=8) as executor:
        details = dict(executor.map(advisory, sorted(ids)))
    return [{"package": name, "version": version, "id": vuln["id"],
             "severity": details[vuln["id"]].get("database_specific", {}).get("severity", "UNKNOWN"),
             "advisory": details[vuln["id"]]}
            for (name, version), result in zip(ordered, results) for vuln in result.get("vulns", [])]


def new_risks(head, base):
    known = {(item["package"], item["id"]) for item in base}
    return [item for item in head if (item["package"], item["id"]) not in known
            and item["severity"] in ("HIGH", "CRITICAL", "UNKNOWN")]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="")
    parser.add_argument("--event", required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    candidate = args.candidate.resolve()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    head_dir = output / "head"
    head_dir.mkdir(exist_ok=True)
    head_packages = graphs(ROOT, candidate, head_dir)
    print(f"Scanning {len(head_packages)} resolved core/benchmark packages", flush=True)
    head = scan(head_packages)
    (head_dir / "advisories.json").write_text(json.dumps(head, indent=2) + "\n")
    base = []
    if args.event == "pull_request":
        require(args.base and set(args.base) != {"0"}, "Missing PR base SHA")
        base_sha = subprocess.check_output(["git", "merge-base", args.base, "HEAD"], text=True).strip()
        base_dir = output / "base"
        base_dir.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory() as scratch:
            archive = subprocess.check_output(["git", "archive", base_sha])
            with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
                tar.extractall(scratch, filter="data")
            base = scan(graphs(Path(scratch), candidate, base_dir))
        (base_dir / "advisories.json").write_text(json.dumps(base, indent=2) + "\n")
    risks = new_risks(head, base)
    summary = {"resolved_packages": len(head_packages), "advisories": len(head), "new_high_or_unknown": len(risks)}
    (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary))
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as report:
            report.write(f"### Dependency evidence\n\nResolved {len(head_packages)} core/benchmark packages; "
                         f"{len(head)} existing advisories; {len(risks)} new high/critical/unknown advisories.\n")
    require(args.event != "pull_request" or not risks, "New high/critical dependency risk (unknown severity also requires review)")


if __name__ == "__main__":
    main()
