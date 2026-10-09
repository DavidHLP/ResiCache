#!/usr/bin/env bash
# Resolve the packaged POM as an ordinary consumer; no project/test classpath.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
if (( $# > 1 )); then
  echo 'Usage: check-external-consumer.sh [candidate-directory]' >&2
  exit 1
fi
if (( $# == 1 )) && [[ ! -d "$1" ]]; then
  echo "Candidate directory does not exist: $1" >&2
  exit 1
fi
jdk_home="${RESICACHE_JDK21:-${JAVA_HOME:-}}"
if [[ -z "$jdk_home" ]]; then
  if ! java_path="$(command -v java)"; then
    echo 'JDK 21 is required: set RESICACHE_JDK21 or JAVA_HOME, or put it on PATH.' >&2
    exit 1
  fi
  jdk_home="$(dirname "$(dirname "$(realpath "$java_path")")")"
fi
if [[ ! -x "$jdk_home/bin/java" || ! -x "$jdk_home/bin/javac" ]]; then
  echo "JDK 21 java and javac are required in $jdk_home/bin." >&2
  exit 1
fi
if ! java_settings="$("$jdk_home/bin/java" -XshowSettings:properties -version 2>&1)"; then
  echo "Cannot run Java from $jdk_home." >&2
  exit 1
fi
java_version="$(awk '$1 == "java.specification.version" && $2 == "=" {print $3}' <<< "$java_settings")"
if [[ "$java_version" != 21 ]]; then
  echo "JDK 21 is required; found Java ${java_version:-unknown} in $jdk_home." >&2
  exit 1
fi
export JAVA_HOME="$jdk_home"
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
