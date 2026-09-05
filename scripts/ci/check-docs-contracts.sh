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

# Dead-javadoc-reference gate (Phase 8-A): known-removed/private members must not
# be {@link}-referenced in main source. Add patterns as members are removed.
for dead_ref in \
  'RedisProCache#lookupOperation' \
  'RedisProCacheWriter#resolveOperation' \
  'BloomRebuilder'; do
  if git grep -nF -- "$dead_ref" -- 'src/main/java'; then
    printf 'Dead javadoc/source reference: %s (member removed/internalized — update the link)\n' "$dead_ref" >&2
    exit 1
  fi
done

# Test resources must describe the same Testcontainers BOM as pom.xml.
for resource in \
  src/test/resources/testcontainers.properties \
  src/test/resources/docker-java.properties; do
  if grep -nF -- '1.20.4' "$resource"; then
    printf 'Forbidden stale resource value in %s\n' "$resource" >&2
    exit 1
  fi
done
