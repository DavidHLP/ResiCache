#!/usr/bin/env bash
# ==============================================================================
# require-all-green.sh
#
# Aggregates GitHub Actions job results from the RESULTS environment variable
# (populated by `toJSON(needs)`). Validates that every job either succeeded or
# was legitimately skipped. Rejects failures and cancellations.
# ==============================================================================

set -euo pipefail

if [[ -z "${RESULTS:-}" ]]; then
  echo "::error::RESULTS environment variable is empty or not set."
  exit 1
fi

echo "Evaluating workflow job execution results..."
echo "--------------------------------------------------"

if ! command -v jq >/dev/null 2>&1; then
  echo "::error::jq is required but not found in PATH."
  exit 1
fi

failed=0
total=0

# Parse JSON into a list of "job_name:result" lines
while IFS="=" read -r job result; do
  total=$((total + 1))
  case "$result" in
    "success")
      echo "  [OK] $job -> $result"
      ;;
    "skipped")
      echo "  [SKIPPED] $job -> $result"
      ;;
    "failure")
      echo "::error::Job '$job' failed (result: $result)"
      failed=$((failed + 1))
      ;;
    "cancelled")
      echo "::error::Job '$job' was cancelled (result: $result)"
      failed=$((failed + 1))
      ;;
    *)
      echo "::error::Job '$job' has unexpected state: '$result'"
      failed=$((failed + 1))
      ;;
  esac
done < <(echo "$RESULTS" | jq -r 'to_entries[] | "\(.key)=\(.value.result // .value)"')

echo "--------------------------------------------------"
echo "Total evaluated jobs: $total, Failures/Cancellations: $failed"

if [[ $total -eq 0 ]]; then
  echo "::error::No job results found in RESULTS."
  exit 1
fi

if [[ $failed -gt 0 ]]; then
  echo "::error::Pipeline failed! $failed job(s) did not complete cleanly."
  exit 1
fi

echo "All required pipeline jobs completed successfully (or cleanly skipped)."
exit 0
