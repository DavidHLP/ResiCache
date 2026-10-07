#!/usr/bin/env bash
# Resolve the packaged POM as an ordinary consumer; no project/test classpath.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
if [[ -n "${RESICACHE_JDK21:-}" ]]; then
  export JAVA_HOME="$RESICACHE_JDK21"
fi
if [[ ! -d "${1:-target/ci-candidate}" ]]; then
  ./mvnw clean package -DskipTests -B
  python3 scripts/ci/pipeline.py candidate target/ci-candidate
fi
candidate="$(realpath "${1:-target/ci-candidate}")"
python3 scripts/ci/pipeline.py verify-candidate "$candidate"
boot_version="$(python3 -c 'import xml.etree.ElementTree as E; print(E.parse("pom.xml").findtext("{*}parent/{*}version"))')"
redisson_version="$(python3 -c 'import xml.etree.ElementTree as E; print(E.parse("pom.xml").findtext("{*}properties/{*}redisson.version"))')"
version="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$candidate/manifest.json")"
task_dir="$(mktemp -d)"
container_id=""
cleanup() {
  if [[ -n "$container_id" ]]; then docker rm -f "$container_id" >/dev/null; fi
  rm -rf "$task_dir"
}
trap cleanup EXIT
cp -R scripts/ci/consumer/. "$task_dir/"
python3 - "$task_dir/pom.xml" "$boot_version" <<'PYGEN'
from pathlib import Path
import sys
path = Path(sys.argv[1])
path.write_text(path.read_text().replace('@BOOT_VERSION@', sys.argv[2]))
PYGEN
./mvnw org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
  -Dfile="$candidate/ResiCache-$version.jar" -DpomFile="$candidate/ResiCache-$version.pom" -B
if [[ "${CONSUMER_REDIS_PORT:-}" == "" ]]; then
  docker info >/dev/null
  container_id="$(docker run -d --rm -p 127.0.0.1::6379 redis:7-alpine)"
  redis_port="$(docker port "$container_id" 6379/tcp | sed 's/.*://')"
else
  redis_port="$CONSUMER_REDIS_PORT"
fi
for profile in minimal redisson observability; do
  ./mvnw -f "$task_dir/pom.xml" -P"$profile" -Dresicache.version="$version" -Dredisson.version="$redisson_version" \
    clean package dependency:build-classpath -Dmdep.includeScope=runtime \
    -Dmdep.outputFile="$task_dir/classpath" -B
  classpath="$(cat "$task_dir/classpath")"
  if [[ "$classpath" =~ junit|mockito|testcontainers|lombok ]]; then
    echo 'Consumer classpath contains test/provided dependencies' >&2; exit 1
  fi
  if [[ "$profile" == minimal && "$classpath" =~ redisson|actuator ]]; then
    echo 'Minimal consumer contains optional dependencies' >&2; exit 1
  fi
  "$JAVA_HOME/bin/java" -cp "$task_dir/target/classes:$classpath" com.example.consumer.ExternalConsumerDemo
  "$JAVA_HOME/bin/java" -cp "$task_dir/target/classes:$classpath" com.example.consumer.BootConsumer "$profile" "$redis_port"
done
