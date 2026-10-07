#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
tools_dir="${CI_TOOLS_DIR:-$root/target/ci-tools}"
mkdir -p "$tools_dir"
python3 scripts/ci/install-tools.py "$tools_dir"
export PATH="$tools_dir:$PATH"
actionlint -color
shellcheck scripts/ci/*.sh
python3 -m unittest discover -s scripts/ci/tests -v
python3 scripts/ci/check-action-pins.py
