#!/usr/bin/env python3
"""Bind recovery artifacts to a previously verified tag run in this repository."""
import json
import os
from pathlib import Path
import subprocess

from pipeline import require, verify_candidate

run_id = os.environ["CANDIDATE_RUN_ID"]
require(run_id.isdigit(), "Candidate run ID must be numeric")
repository = os.environ["GITHUB_REPOSITORY"]
run = json.loads(subprocess.check_output(["gh", "api", f"repos/{repository}/actions/runs/{run_id}"]))
require(run["event"] == "push" and run["head_sha"] == os.environ["GITHUB_SHA"], "Recovery run is not the original tag commit")
require(run["path"] == ".github/workflows/release.yml", "Recovery run is not a release workflow")
jobs = json.loads(subprocess.check_output(["gh", "api", "--paginate", "--slurp", f"repos/{repository}/actions/runs/{run_id}/jobs?per_page=100"]))
gates = [job for page in jobs for job in page["jobs"] if job["name"].endswith("/ Verification gate")]
require(len(gates) == 1 and gates[0]["conclusion"] == "success", "Original full verification did not pass")
manifest = verify_candidate(Path("candidate"), os.environ["GITHUB_SHA"], os.environ["RELEASE_VERSION"])
require(str(manifest["run_id"]) == run_id, "Candidate artifact belongs to another run")
