#!/usr/bin/env bash
set -euo pipefail

# Abstract fixtures and helpers whose source documents or supplies the
# container infrastructure but does not itself represent an executable
# container test.
allowlisted_files=(
  "src/test/java/io/github/davidhlp/spring/cache/redis/cache/AbstractRedisIntegrationTest.java"
  "src/test/java/io/github/davidhlp/spring/cache/redis/cache/AbstractRedisClusterIntegrationTest.java"
  "src/test/java/io/github/davidhlp/spring/cache/redis/cache/DockerApiVersionLauncherSessionListener.java"
  "src/test/java/io/github/davidhlp/spring/cache/redis/cache/DockerApiVersionResolver.java"
  "src/test/java/io/github/davidhlp/spring/cache/redis/cache/SerializationPreFlightProbeTest.java"
  "src/test/java/io/github/davidhlp/spring/cache/redis/cache/TestApplication.java"
  "src/test/java/io/github/davidhlp/spring/cache/redis/cache/TestCacheService.java"
  "src/test/java/io/github/davidhlp/spring/cache/redis/cache/TestRedisConfiguration.java"
)

is_allowlisted() {
  local file="$1"
  local allowed
  for allowed in "${allowlisted_files[@]}"; do
    if [[ "$file" == "$allowed" ]]; then
      return 0
    fi
  done
  return 1
}

has_container_marker() {
  grep -Eq \
    'extends[[:space:]]+AbstractRedis(Cluster)?IntegrationTest|@Testcontainers|@Container|GenericContainer' \
    "$1"
}

is_abstract_class() {
  grep -Eq \
    '^[[:space:]]*((public|protected|private)[[:space:]]+)?abstract[[:space:]]+class[[:space:]]' \
    "$1"
}

status=0
while IFS= read -r file; do
  [[ -f "$file" ]] || continue
  is_allowlisted "$file" && continue
  has_container_marker "$file" || continue
  is_abstract_class "$file" && continue

  if [[ "$file" != *IntegrationTest.java ]]; then
    printf 'Container test must use *IntegrationTest.java: %s\n' "$file" >&2
    status=1
  fi
done < <(git ls-files --cached --others --exclude-standard -- 'src/test/java')

exit "$status"
