#!/usr/bin/env bash
set -euo pipefail

status=0
while IFS= read -r file; do
  [[ -f "$file" ]] || continue
  case "$file" in
    *IT.java)
      printf 'Unsupported integration-test suffix: %s\n' "$file" >&2
      status=1
      ;;
  esac
done < <(git ls-files --cached --others --exclude-standard -- 'src/test/java')

exit "$status"
