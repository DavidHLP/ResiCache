# Development

This document is the contributor and maintainer execution guide. Exact
versions remain in the build files and CI configuration; this page explains
which command proves which boundary.

## Environment

- JDK 21, matching `pom.xml` and the Maven Enforcer range.
- Maven 3.x; the bundled `./mvnw` pins its distribution in
  `.mvn/wrapper/maven-wrapper.properties`.
- Docker for Redis/Testcontainers integration, Cluster, Sentinel and TLS tests.
- OpenSSL and JDK keytool for temporary TLS certificates; Python 3.12+ and GPG
  for CI contract checks. Linux x86-64 is the CI runner platform.
- A working local Maven cache; no committed credentials are required.

The root build is the core library. `resicache-bench/` is a separate JMH module
with its own POM and is not a release-compatibility proof for the core library.

## Command matrix

Run the smallest command that covers the change, then the broader gate required
by the change type.

| Command | Evidence |
|---|---|
| `./mvnw -Punit test -B` | no-Docker unit path; excludes `**/*IntegrationTest*.java` |
| `./mvnw clean verify -B` | full core build, Redis/Testcontainers tests, Javadoc attachment, and JaCoCo gate |
| `./mvnw checkstyle:check -B` | explicit Checkstyle gate; it is separate from `verify` |
| `bash scripts/ci/check-test-names.sh` | all executable container tests use `*IntegrationTest.java` |
| `bash scripts/ci/check-docs-contracts.sh` | stale contract strings, removed Javadoc references, and required docs guards |
| `bash scripts/ci/check-external-consumer.sh` | isolated packaged-JAR consumer, Boot discovery, Redis read/write, optional Redisson and observability paths |
| `bash scripts/ci/check-workflows.sh` | actionlint, ShellCheck, SHA pins and CI/release contract regression tests |
| `python3 scripts/ci/pipeline.py reports` | inspect reports immediately after a clean full test run; rejects skipped/missing integration tests |
| `python3 scripts/ci/pipeline.py reports --unit` | inspect reports immediately after `./mvnw -Punit clean test -B`; rejects integration evidence on the unit path |
| `./mvnw clean package -DskipTests -B` | packaged artifact without test execution |
| `./mvnw javadoc:javadoc -B` | Javadoc generation when API comments change; the POM disables doclint, so manually review links and semantics |
| `./mvnw -f resicache-bench/pom.xml clean package -DskipTests -B` | standalone JMH build after installing the matching core; benchmark commands are in [`PERFORMANCE.md`](../PERFORMANCE.md) |

`verify` enforces at least 70% line and 40% branch coverage. The no-Docker
profile does not establish Redis, Redis Cluster, or Testcontainers behavior.
Local Maven tests disable Ryuk through the root POM; CI explicitly enables it
with `-Dtestcontainers.ryuk.disabled=false`. Registry access and available
container images are therefore part of the CI environment.
Use a real Docker environment for those boundaries and report infrastructure
failures separately from test failures.

## Test layers

- **Unit tests** use Boot-managed JUnit Jupiter and cover parsers, properties,
  chain decisions, serialization rules, and public value contracts without Redis.
- **Redis integration tests** use `AbstractRedisIntegrationTest` and
  Testcontainers with Redis 7-based fixtures.
- **Redis Cluster tests** use the separate
  `AbstractRedisClusterIntegrationTest` fixture and prove topology-sensitive
  behavior such as lock/data key slot co-location.
- Classes containing container markers must use the `*IntegrationTest.java`
  suffix, with only the explicit helper allowlist exempted by the naming script.
- **Public-surface tests** compare the compiled package against the allowlists.
- **External-consumer tests** resolve the packaged POM in a separate Maven
  project, excluding project test/provided dependencies and optional dependencies
  unless explicitly selected. They run the public value protocol plus a real
  Boot application against Redis in minimal, Redisson and observability modes.
  Use `bash scripts/ci/check-external-consumer.sh <candidate-directory>` to reuse
  an existing verified candidate; an explicitly missing directory fails rather
  than rebuilding. Without an argument, the script reuses `target/ci-candidate`
  or packages it if absent. JDK selection uses `RESICACHE_JDK21`, then
  `JAVA_HOME`, then the Java installation on `PATH`; it requires JDK 21 before
  packaging or installing anything.
  `CONSUMER_REDIS_PORT` can point to an existing localhost Redis instead of Docker.
- **Topology smoke tests** prove Sentinel master discovery and data/lock access,
  and TLS trusted/untrusted certificate behavior in both clients. They do not
  establish Sentinel failover availability or latency SLOs.

Tests mirror the source package under `src/test/java`. Integration fixtures and
application test resources are part of the test contract; do not change a test
profile to make a local environment appear green.

## Source and style pointers

The module map and dependency direction are in
[`ARCHITECTURE.md`](ARCHITECTURE.md). The public stability rules are in
[`STABILITY.md`](../STABILITY.md). Follow Java naming, existing Lombok usage,
focused classes, and public Javadoc conventions. Put design rationale in
Javadoc only when it explains a non-obvious constraint; keep current behavior
in the canonical docs instead of duplicating it in comments and guides.

## Documentation changes

Update the owner in [`docs/README.md`](README.md) when a current fact changes.
Keep README's quick start runnable and move detailed semantics to the relevant
core page. Update `STABILITY.md`, `COMPATIBILITY.md`, or `CHANGELOG.md` when
the change actually affects that document's contract or history.

For a documentation-only change, run the link/reference checks and the docs
contract script; a Java build is not required unless source or generated API
behavior also changed. For code or build changes, use the command matrix and
follow [`CONTRIBUTING.md`](../CONTRIBUTING.md)'s PR checklist.

## CI shape

PR, main push, merge-group, manual verification, weekly verification and release
share `_verify.yml`. Only a PR whose changed paths are all explicitly recognized
documentation can skip lint, unit, full build, consumer, benchmark and dependency
jobs. Wrapper configuration, CI scripts, unknown paths and classification errors
never downgrade verification. Docs and workflow checks always run.
The explicit docs-only paths are in
`scripts/ci/pipeline.py`; Java comments and PR/issue templates are outside that
allowlist. `check-workflows.sh` downloads checksum-pinned Linux actionlint and
ShellCheck into `target/ci-tools` when absent; it needs network access then.

Fast checks and full verification run concurrently. The full build uses Ryuk,
executes every integration test, enforces 70% line / 40% branch coverage and
creates one `core-candidate` artifact (JAR, sources, Javadoc, POM, checksums and
commit/run manifest). Consumers and benchmarks download this artifact instead of
rebuilding the core. The JMH storage round-trip is a bounded protocol smoke,
not a performance SLO. `ci-ok` is the stable PR/main protection check; it fails
on missing, malformed, failed, cancelled or unexpectedly skipped jobs.

Dependency Review rejects newly introduced High/Critical vulnerabilities on
PRs. Maven-resolved core and benchmark dependency trees are also scanned against
OSV and compared with the PR merge base, so transitive-dependency evidence does
not depend on privileged snapshot submission from forks. New advisories with
unknown severity require review. Resolver/scanner failures fail the check;
existing advisories are reported without silently treating them as fixed.
Weekly verification, CodeQL and optional Qodana keep existing risk visible.
Dependabot opens bounded weekly Maven/Actions update PRs; Actions are SHA-pinned.
Reports and candidate artifacts identify their commit and run; reports are not
compatibility guarantees. Release credentials are used only after verification,
as described in [`OPERATIONS.md`](OPERATIONS.md).
