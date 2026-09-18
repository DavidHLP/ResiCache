# Contributing to ResiCache

Thanks for improving ResiCache. This is a small, single-maintainer,
**non-SLA best-effort** project. The repository prefers a small change that
fits the existing contracts over speculative framework surface.

## Before you start

- ResiCache is pre-1.0. If a change alters a documented public API, property
  key, wire format, or SPI behavior, open an issue before implementation.
- Read [`README.md`](README.md) for the runnable entry point and
  [`docs/PRODUCT.md`](docs/PRODUCT.md) for scope and non-goals.
- Read [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for ownership and
  [`STABILITY.md`](STABILITY.md) before depending on a public type.
- Check the existing issues, architecture map, and change history before
  adding a new extension point or repeating a closed design.

## Development setup and checks

Requirements are **JDK 21**, **Maven 3.x** (the bundled `./mvnw` is preferred),
and **Docker** for Testcontainers-backed Redis and Cluster tests. The command
matrix and evidence boundaries are in
[`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md).

At minimum, run the checks relevant to the change:

```bash
./mvnw -Punit test -B
./mvnw clean verify -B
./mvnw checkstyle:check -B
bash scripts/ci/check-test-names.sh
bash scripts/ci/check-docs-contracts.sh
```

The unit profile is a no-Docker path and excludes `**/*IntegrationTest*.java`;
it does not prove real Redis behavior. `clean verify` is the full Redis and
coverage path and enforces 70% line / 40% branch coverage. If code changes,
add or update tests; if a bug is fixed, add a regression test when the failure
is reproducible.

## Architecture pointers

| Change | Start here |
|---|---|
| Product behavior or a new capability | [`docs/PRODUCT.md`](docs/PRODUCT.md) |
| Module boundary or handler order | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) and `chain/HandlerOrder.java` |
| Protection mechanism | internal `cache/` runtime and `CacheHandlerChainFactory` |
| Annotation handling | internal `cache/` annotation pipeline |
| Auto-configuration or properties | `config/RedisCacheAutoConfiguration` and `RedisProCacheProperties` |
| Serialization | `serialization/` and the serializer tests |
| Cache core | `cache/RedisProCache`, `RedisProCacheManager`, `RedisProCacheWriter` |
| Runtime/migration behavior | [`docs/OPERATIONS.md`](docs/OPERATIONS.md) and [`docs/REFERENCE.md`](docs/REFERENCE.md) |

## Adding a protection handler

1. Add the implementation to the internal `cache/` runtime and implement
   `CacheHandler` (extend `AbstractCacheHandler` when its behavior fits).
2. Use `@HandlerPriority(HandlerOrder.YOUR_ORDER)`; `HandlerOrder` is the single
   ordering source and leaves gaps for intentional insertion.
3. Register the handler as an internal `@Component`. The library scan covers
   only the internal runtime package and excludes test classes; host handlers
   must be discovered by the host application or supplied as a bean.
4. Add focused tests and document non-obvious design rationale in public
   Javadoc or the owning current-state document.

The handler/observer protocol, non-null results, flow control, post-processing,
thread-safety, scope tokens, and nested advancement rules are normative in
[`STABILITY.md`](STABILITY.md). The public nested-type list is pinned by the
allowlist and `PublicSurfaceContractTest`.

## Documentation changes

Update the existing canonical page named in [`docs/README.md`](docs/README.md).
Keep README focused on adoption and quick start; do not create a task-, date-,
phase-, or session-specific permanent guide. If a new current-state document is
truly necessary, document its reader, distinct responsibility, and lifecycle in
the documentation map. Preserve changelog history, performance evidence, and
unresolved task entries.

## Pull requests

Use the repository PR template. Summarize the behavior and evidence, identify
compatibility impact, and state what was not run. Documentation-only changes
still need link/reference review and the docs contract check.

Be respectful and constructive. This is a best-effort project; assume good
intent and keep discussions focused on the code and its evidence.

## Maintainers and bus factor

ResiCache is currently a **single-maintainer project** — all merges, releases,
and architectural decisions flow through `DavidHLP` (the only committer with
`CODEOWNERS` write access on `main`; `master` is retained only where legacy
workflow references still exist).

**Bus factor: 1** is the current state, not an aspirational promise. Before a
`1.0.0` tag, this section must document either a named successor or a
succession plan. Pre-1.0, the project remains explicitly best-effort with no
SLA and no pinned production-adopter guarantee.

## Releases and CI infrastructure

CI runs on pushes to `main` or `master` and on pull requests through
[`.github/workflows/ci.yml`](.github/workflows/ci.yml) and
[`.github/workflows/pr.yml`](.github/workflows/pr.yml). The composite
[setup-jdk-21 action](.github/actions/setup-jdk-21/action.yml) centralizes JDK
and Maven cache setup; `pom.xml` remains the Java-version source of truth.

Release-time `OSSRH_*` and `GPG_*` secrets are configured at repository or
environment level out of band. Do not edit `release.yml` to add secrets; open
an issue first. Release behavior and the current publication boundary are in
[`docs/OPERATIONS.md`](docs/OPERATIONS.md).
