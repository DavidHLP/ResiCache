# Development

This document is the contributor and maintainer execution guide. Exact
versions remain in the build files and CI configuration; this page explains
which command proves which boundary.

## Environment

- JDK 21, matching `pom.xml` and the Maven Enforcer range.
- Maven 3.x or the bundled `./mvnw` wrapper.
- Docker for Redis/Testcontainers integration and cluster tests.
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
| `bash scripts/ci/check-external-consumer.sh` | packaged-JAR public contract; no Spring context or Redis claim |
| `./mvnw clean package -DskipTests -B` | packaged artifact without test execution |
| `./mvnw javadoc:javadoc -B` | Javadoc source consistency when public API docs change |

`verify` enforces at least 70% line and 40% branch coverage. The no-Docker
profile does not establish Redis, Redis Cluster, or Testcontainers behavior.
Use a real Docker environment for those boundaries and report infrastructure
failures separately from test failures.

## Test layers

- **Unit tests** cover parsers, properties, chain decisions, serialization
  rules, and public value contracts without Redis.
- **Redis integration tests** use `AbstractRedisIntegrationTest` and
  Testcontainers with Redis 7-based fixtures.
- **Redis Cluster tests** use the separate
  `AbstractRedisClusterIntegrationTest` fixture and prove topology-sensitive
  behavior such as lock/data key slot co-location.
- Classes containing container markers must use the `*IntegrationTest.java`
  suffix, with only the explicit helper allowlist exempted by the naming script.
- **Public-surface tests** compare the compiled package against the allowlists.
- **External-consumer tests** compile against the packaged JAR and declared
  compile dependencies only. They prove importability and value-path protocol,
  not a real Redis deployment.

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

Pushes to `main`/`master` and pull requests run lint, docs consistency, quality,
core build, benchmark, and packaging jobs. The docs job is a required input to
the build job. Product packaging is conditional in the PR pipeline, but the
core build and docs gates still run for documentation changes. Release tags
use the separate release workflow described in [`OPERATIONS.md`](OPERATIONS.md).
