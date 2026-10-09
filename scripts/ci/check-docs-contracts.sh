#!/usr/bin/env bash
set -euo pipefail

# Keep this guard small: source/configuration remain the exact contract, while
# this script catches missing entry points and known documentation drift.
docs=(
  README.md README.zh-CN.md AGENTS.md
  STABILITY.md COMPATIBILITY.md CHANGELOG.md CONTRIBUTING.md SECURITY.md PERFORMANCE.md
  docs/README.md docs/PRODUCT.md docs/ARCHITECTURE.md docs/DEVELOPMENT.md
  docs/OPERATIONS.md docs/REFERENCE.md
)
for doc in "${docs[@]}"; do
  if [[ ! -f "$doc" ]]; then
    printf 'Missing canonical documentation entry point: %s\n' "$doc" >&2
    exit 1
  fi
done

for forbidden in \
  'pr-checks.yml' \
  'maven-failsafe-plugin' \
  '| Java | 21+ |' \
  '| JDK | 21+ |' \
  '@ComponentScan' \
  'Java 21+' \
  'JDK 21+'; do
  for doc in "${docs[@]}"; do
    # History may quote superseded guidance without making it current policy.
    case "$doc" in CHANGELOG.md|PERFORMANCE.md) continue ;; esac
    if grep -nF -- "$forbidden" "$doc"; then
      printf 'Forbidden stale documentation value: %s\n' "$forbidden" >&2
      exit 1
    fi
  done
done

while IFS='|' read -r owner required; do
  if ! grep -qF -- "$required" "$owner"; then
    printf 'Missing required contract documentation value in %s: %s\n' "$owner" "$required" >&2
    exit 1
  fi
done <<'CONTRACTS'
COMPATIBILITY.md|PUT, PUT_IF_ABSENT, and CLEAN
COMPATIBILITY.md|Reactive
docs/REFERENCE.md|resi-cache.bloom
CONTRACTS

# README/source checks have one owner so the local guard matches CI.
for symbol in CircuitBreakerCacheWrapper RateLimiterCacheWrapper \
  SpelConditionEvaluator CacheEvictedEvent CacheMetricsRecorder \
  BloomFilterProvider LockProvider RedissonLockProvider; do
  if grep -nF -- "\`$symbol\`" README.md README.zh-CN.md; then
    printf 'Removed symbol referenced in README: %s\n' "$symbol" >&2
    exit 1
  fi
done

for class in RedisCacheAutoConfiguration RedisProCacheProperties RedisProCache \
  RedisProCacheManager CacheHandlerChainFactory RedisCacheInterceptor \
  RedisCacheOperationSource RedissonConfiguration; do
  if [[ -z "$(find src/main/java -name "${class}.java" -print -quit)" ]]; then
    printf 'Key class referenced in documentation missing from source: %s\n' "$class" >&2
    exit 1
  fi
done

# The map must expose every current-state owner; otherwise new pages can become
# orphaned even when the files themselves exist.
for owner in PRODUCT.md ARCHITECTURE.md DEVELOPMENT.md OPERATIONS.md REFERENCE.md; do
  if ! grep -qF -- "$owner" docs/README.md; then
    printf 'Documentation map does not name current-state owner: %s\n' "$owner" >&2
    exit 1
  fi
done

# Dead-javadoc-reference gate: known-removed/private members must not be
# referenced in main source. Add patterns as members are removed.
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
pom_testcontainers_version="$(
  awk '
    /<artifactId>testcontainers-bom<\/artifactId>/ { in_bom=1; next }
    in_bom && /<version>/ {
      sub(/.*<version>/, "")
      sub(/<\/version>.*/, "")
      print
      exit
    }
  ' pom.xml
)"
if [[ -z "$pom_testcontainers_version" ]]; then
  printf 'Could not read Testcontainers BOM version from pom.xml\n' >&2
  exit 1
fi

if ! grep -qF -- "| Testcontainers | $pom_testcontainers_version (test scope) |" COMPATIBILITY.md; then
  printf 'Testcontainers compatibility table must match POM version: %s\n' "$pom_testcontainers_version" >&2
  exit 1
fi

for resource in \
  src/test/resources/testcontainers.properties \
  src/test/resources/docker-java.properties; do
  if [[ ! -f "$resource" ]]; then
    printf 'Missing Testcontainers configuration resource: %s\n' "$resource" >&2
    exit 1
  fi
  resource_version="$(
    awk -F 'testcontainers-bom:' '
      NF > 1 {
        value = $2
        sub(/[^0-9.].*/, "", value)
        print value
        exit
      }
    ' "$resource"
  )"
  if [[ "$resource_version" != "$pom_testcontainers_version" ]]; then
    printf 'Testcontainers BOM mismatch in %s: expected %s, found %s\n' \
      "$resource" "$pom_testcontainers_version" "${resource_version:-missing}" >&2
    exit 1
  fi
done
