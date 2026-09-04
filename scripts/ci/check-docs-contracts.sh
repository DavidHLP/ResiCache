#!/usr/bin/env bash
set -euo pipefail

docs=(README.md README.zh-CN.md STABILITY.md COMPATIBILITY.md CHANGELOG.md CONTRIBUTING.md CLAUDE.md)

for forbidden in \
  'pr-checks.yml' \
  'maven-failsafe-plugin' \
  'Testcontainers | 1.20.4' \
  '| Java | 21+ |' \
  '| JDK | 21+ |' \
  '@ComponentScan' \
  'Java 21+' \
  'JDK 21+'; do
  if git grep -nF -- "$forbidden" -- "${docs[@]}"; then
    printf 'Forbidden stale documentation value: %s\n' "$forbidden" >&2
    exit 1
  fi
done

for required in \
  'PUT, PUT_IF_ABSENT, and CLEAN' \
  'Reactive' \
  'Testcontainers | 1.20.6' \
  'resi-cache.bloom'; do
  if ! git grep -qF -- "$required" -- "${docs[@]}"; then
    printf 'Missing required contract documentation value: %s\n' "$required" >&2
    exit 1
  fi
done
