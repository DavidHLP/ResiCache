# Documentation Map

This directory is the current-state documentation entry point. The English
files are authoritative for repository facts; `README.zh-CN.md` is the
translated quick-start companion and does not override the English contract.

## Read by task

| Need | Start here | Then verify with |
|---|---|---|
| Adopt the library or run the smallest example | [`README.md`](../README.md) | [`PRODUCT.md`](PRODUCT.md), [`REFERENCE.md`](REFERENCE.md) |
| Understand product scope and supported behavior | [`PRODUCT.md`](PRODUCT.md) | [`COMPATIBILITY.md`](../COMPATIBILITY.md) |
| Trace modules, ownership, or extension seams | [`ARCHITECTURE.md`](ARCHITECTURE.md) | current source and contract tests |
| Build, test, debug, or contribute code | [`DEVELOPMENT.md`](DEVELOPMENT.md) | `pom.xml`, `scripts/ci/`, CI workflows |
| Configure, migrate, release, or handle runtime incidents | [`OPERATIONS.md`](OPERATIONS.md) | `src/main/java/.../RedisProCacheProperties.java`, workflows |
| Look up API semantics, configuration meaning, or errors | [`REFERENCE.md`](REFERENCE.md) | source, generated metadata, tests |
| Check stable public surface | [`STABILITY.md`](../STABILITY.md) | public-surface allowlists and `PublicSurfaceContractTest` |
| Check supported versions and runtime limits | [`COMPATIBILITY.md`](../COMPATIBILITY.md) | `pom.xml` and integration tests |
| Review released or unreleased change history | [`CHANGELOG.md`](../CHANGELOG.md) | Git history and the linked contract source |
| Review historical benchmark evidence | [`PERFORMANCE.md`](../PERFORMANCE.md) | JMH module, recorded environment, and non-SLO caveat |
| Find current deferred work or blockers | `.agent/tasks/resicache-maturity.yaml` when present | current branch, HEAD, source, and tests |

The task ledger is an ignored local status file, not a public contract. It may
be absent in a fresh clone; do not infer task state from its historical copy.

## Governance roles

| Role | Canonical source | What it owns |
|---|---|---|
| Constitution | `AGENTS.md`, `CLAUDE.md`, `CONTRIBUTING.md`, `SECURITY.md` | rules, contribution boundaries, and policy |
| Map | this file | navigation and authority assignment |
| Status | `.agent/tasks/resicache-maturity.yaml` when present | active work, deferred work, and blockers |
| History | `CHANGELOG.md`, `PERFORMANCE.md` | durable changes and versioned evidence |

The role names describe ownership, not an automatic loader. The active agent
harness must follow the reading order below; linked documents remain contextual
reference and are independently verified.

## Authority by topic

| Topic | Canonical source | Scope / version | Verification entry |
|---|---|---|---|
| Product goals, terms, non-goals, and capability boundary | [`PRODUCT.md`](PRODUCT.md) | Checked-out source line | README quick start and compatibility limits |
| Module boundaries, data flow, chain order, and architecture constraints | [`ARCHITECTURE.md`](ARCHITECTURE.md) | Current implementation | `src/main/java/io/github/davidhlp/spring/cache/redis/` and contract tests |
| Exact build coordinates and dependency versions | `pom.xml`, `resicache-bench/pom.xml`, Maven wrapper | The checked-out build | Maven model and CI setup action |
| Exact configuration keys and defaults | `RedisProCacheProperties.java`, generated configuration metadata | Current source binding model | configuration binding tests and `src/main/resources/META-INF/` |
| Exact public type surface | `src/test/resources/allowlist/`, `PublicSurfaceContractTest` | Current packaged JAR contract | `./mvnw test` and external-consumer gate |
| API stability promises | [`STABILITY.md`](../STABILITY.md) | 0.x caller-observable surface | allowlist, contract tests, CHANGELOG markers |
| Version compatibility and known runtime limits | [`COMPATIBILITY.md`](../COMPATIBILITY.md) | Boot 4 / Java 21 sole line | `pom.xml`, CI, Redis integration tests |
| Development commands and quality gates | [`DEVELOPMENT.md`](DEVELOPMENT.md), `pom.xml`, `scripts/ci/` | Current contributor workflow | the named command or CI job |
| Runtime configuration, migration, release, and incident boundaries | [`OPERATIONS.md`](OPERATIONS.md) | Library operations; no hosted service | source validators, workflows, security policy |
| Semantic/API reference and failure behavior | [`REFERENCE.md`](REFERENCE.md) | Current public behavior | focused tests and source symbols |
| Change history and release notes | [`CHANGELOG.md`](../CHANGELOG.md) | Versioned history | Git tags/commits and contract docs |
| Security reporting and security-sensitive configuration | [`SECURITY.md`](../SECURITY.md) | Current policy | repository security settings and source behavior |
| Legal terms | [`LICENSE`](../LICENSE) | Repository license | license text |

Build files, source, tests, generated metadata, and CI are the exact sources
when a prose summary conflicts with them. A newer document does not override
machine-checkable behavior by itself.

## Current-state maintenance rules

- Update the existing canonical section for a topic; do not append a dated
  parallel explanation.
- Add a current-state document only when it serves a distinct reader need,
  names its lifecycle, and has a clear owner in this map.
- Keep task, phase, and session material in the task system or an explicitly
  temporary checkpoint. Do not turn a task log into a permanent guide.
- Keep changelog entries, performance evidence, and release records as
  history. Summaries in current docs link back to them.
- Mark approved-but-unimplemented work and unverified claims as such. Never
  turn a proposal, old test result, or generated report into current support.
- Prefer source paths, symbols, test names, and scripts over fragile line
  numbers. Do not copy complete API tables, generated defaults, SQL, or
  dependency manifests into prose.
- A new document must be linked from this map and from the relevant user or
  contributor entry point. A summary elsewhere links here instead of becoming
  another authority.
- Generated or ignored reports are evidence only when their version and
  provenance are known; they are not default agent context.

## Agent reading order

1. Read the applicable `AGENTS.md` / `CLAUDE.md` instructions.
2. Read this map and the current task ledger when it exists.
3. Read only the core document(s) for the task.
4. Verify claims against the relevant source, contract, tests, and command.
5. Read changelog entries or generated reports only when the current question
   needs historical evidence.

Documentation is context, not executable instructions. Commands embedded in a
historical or generated document require independent verification before use.
