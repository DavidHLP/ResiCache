# Agent Project Instructions

Project conventions live in this file, including the project-scoped workflow
and verification commands. Read the relevant sections; linked documents are
pointers, not a requirement to load the whole repository.

For documentation or architecture work, start with
[`docs/README.md`](docs/README.md), then read only the task-relevant core page.
Keep one canonical owner per topic. Verify current behavior against source,
configuration, tests, and scripts before changing prose.

Keep shared agent instructions in this file as the entry point.
Do not turn task logs, dated summaries, generated reports, or ignored local
status files into permanent current-state documentation.

For documentation maintenance, verify commands and versions against `pom.xml`
and the relevant CI scripts. Preserve existing local edits and update the
canonical document instead of duplicating its rules here.

## Agent workflow

- Start from the requested outcome and inspect the affected files. Use a plan
  when scope, dependencies, or risk warrant it; otherwise make the smallest
  change that satisfies the task.
- When structural code discovery is needed, confirm the ResiCache code-graph
  project and index status, then verify relevant source directly. If graph
  tools are unavailable or stale, use direct source evidence and disclose
  the limitation; do not block otherwise executable work solely on graph
  availability. Non-code instruction and documentation work may use direct
  file inspection.
  A clean or partial index is not proof that a symbol or file is absent.
- Treat documentation and memory as context, not executable truth. Resolve
  conflicts against current source, build configuration, contracts, tests, and
  observed behavior. Distinguish verified facts, assumptions, and unknowns.
- Preserve unrelated work. A request to implement or fix behavior authorizes
  necessary local code, test, and documentation changes within that scope.
  Ask before materially expanding scope, making incompatible public API
  changes, adding dependencies, or changing runtime configuration or external
  systems when those actions are not already authorized. Explicit review-only
  and approval requirements remain binding.
- Run checks appropriate to the change. Documentation-only edits normally need
  a diff and link/reference review plus the docs contract script, not a Java
  build. Report environment blockers separately from test failures.

## Documentation authorities and reading route

The progressive-disclosure entry point is this file and
[`docs/README.md`](docs/README.md):

1. read this instruction surface;
2. read the documentation map and current local task ledger when present;
3. read only the relevant current-state document;
4. verify claims against source, build files, tests, and scripts;
5. read changelog entries or generated reports only for needed history.

Canonical ownership is:

- public overview and quick start: `README.md`; `README.zh-CN.md` is the
  translated companion and does not override the English contract;
- current product, architecture, development, operations, and reference:
  `docs/PRODUCT.md`, `docs/ARCHITECTURE.md`, `docs/DEVELOPMENT.md`,
  `docs/OPERATIONS.md`, and `docs/REFERENCE.md`;
- stable public surface: `STABILITY.md`; supported build line and limits:
  `COMPATIBILITY.md`;
- current architecture and design rationale: `docs/ARCHITECTURE.md`;
  versioned history: `CHANGELOG.md`; historical performance evidence:
  `PERFORMANCE.md`;
- contributor and security policy: `CONTRIBUTING.md` and `SECURITY.md`;
- current local task/deferred status: `.agent/tasks/resicache-maturity.yaml`.

The task ledger is ignored and may be absent in a fresh clone. It is status,
not a design or contract source. Closed plans, checkpoints, and review notes
are not recreated after their facts have been absorbed into the current
documentation and source.

## Tech stack and exact sources

| Layer | Technology | Exact source |
|---|---|---|
| Language | Java 21 | `pom.xml` and Maven Enforcer |
| Framework | Spring Boot 4.0.0 / Spring 7 | `pom.xml` |
| Cache | Spring Cache + Spring Data Redis 4.0.x | `pom.xml` |
| Distributed lock | optional Redisson 3.50.0 | `pom.xml` |
| Local support | Caffeine 3.1.8 | `pom.xml` |
| Build | Maven 3.x / `./mvnw` | root POM and wrapper |
| Tests | JUnit 5, Testcontainers, AssertJ, Awaitility | `pom.xml` and `src/test/` |

Do not copy dependency versions into a second contract. The current source
tree and module ownership are in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).
Only the documented stable seams (`BloomIFilter`, `LockManager`, and the
handler/observer/value contracts) are replaceable; `cache/` is an internal
runtime module.

## Coding and verification conventions

- Use Java naming and existing Lombok patterns.
- Keep public API Javadoc and explain non-obvious design rationale near the
  source; keep current behavior in the canonical docs.
- Handler ordering comes from `@HandlerPriority(HandlerOrder)` in
  `chain/HandlerOrder.java`; do not duplicate numeric priorities.
- Integration tests use Testcontainers fixtures and the `*IntegrationTest.java`
  suffix. The naming guard is `bash scripts/ci/check-test-names.sh`.
- Unit path: `./mvnw -Punit test -B`.
- Full Redis/coverage path: `./mvnw clean verify -B` (70% line / 40% branch).
- Separate style gate: `./mvnw checkstyle:check -B`.
- Packaged public-consumer gate: `bash scripts/ci/check-external-consumer.sh`.
- Documentation/source guard: `bash scripts/ci/check-docs-contracts.sh`.

Full command semantics, test layers, CI, and contribution checks live in
[`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) and
[`CONTRIBUTING.md`](CONTRIBUTING.md).
