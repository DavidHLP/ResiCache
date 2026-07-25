# Issue #3 — Minimal JMH module and measured performance baseline

- **Issue:** https://github.com/DavidHLP/ResiCache/issues/3
- **Status:** BLOCKED_EXTERNAL_WORK
- **Priority:** P1 performance
- **Dependencies:** process after correctness/compatibility prerequisites and re-sync external work
- **External work:** `Shubh2-0` volunteered on 2026-07-21; no assignee, branch, linked PR, or submitted diff exists as of 2026-07-26

## Current findings

- Repository is currently a single Maven module and has no JMH dependency/profile/module.
- Issue explicitly requests a new `resicache-bench` module, three benchmark suites, and measured `PERFORMANCE.md` values.
- An intent-only comment is recent enough to re-check before takeover, but there is no code to review now.

## Acceptance criteria

1. A real JMH module/harness is discoverable and runnable independently of normal unit tests.
2. Benchmarks cover: chain pass-through vs Spring-native `@Cacheable`, per-handler additive cost, and SyncLock throughput under concurrency.
3. Fixture/bootstrap cost is outside measured operations.
4. Warmup, measurement, forks, JVM, hardware, and parameters are documented.
5. `PERFORMANCE.md` contains actual measured results and no guessed SLO.
6. Standard `./mvnw clean verify -B` remains deterministic and does not run long benchmarks.

## Implementation plan

- Re-sync Issue/PR state immediately before work.
- If still no PR, choose the smallest Maven multi-module conversion that preserves publishing and coverage behavior.
- Implement three JMH suites against production paths and a short smoke/discovery command.
- Run a baseline on the current workstation and document results as environment-specific, not universal promises.

## Files/modules involved

- root `pom.xml`
- potential core child `pom.xml` only if required by multi-module structure
- `resicache-bench/pom.xml`
- `resicache-bench/src/main/java/**` or canonical JMH source layout
- `PERFORMANCE.md`
- `wiki/modules/observability.md`

## Tests required

- Benchmark jar/list discoverability.
- One short JMH smoke for each suite.
- Normal full verify proving benchmark isolation.

## Validation result

- Re-synced after completing #2/#4/#5: no Open PR, linked PR, assignee, remote benchmark branch, or submitted diff.
- `Shubh2-0`'s explicit implementation claim from 2026-07-21 remains the latest activity (five days old), so duplicate implementation is not currently legitimate.
- Full `./mvnw clean verify -B` passes without a JMH module: 873 unit + 18 integration tests, 0 failures/errors/skips. This validates current repository health, not Issue #3 acceptance.

## Review findings

No implementation exists to review. The claimed plan names the required JMH annotation processor, three suites, thread groups, and `PERFORMANCE.md`, matching the Issue direction. Actual benchmark validity remains unverified until a PR exists.

## Commit / PR

Local ledger commit: `docs(issues): record benchmark contributor status`. No GitHub write performed.

## Remaining work

External dependency: wait for `Shubh2-0` to submit or explicitly relinquish the work. Resume by re-reading #3 and Open PRs/remote branches; if the claim becomes stale or abandoned, implement the frozen plan and measure real baselines. A maintainer GitHub comment requesting ETA would be appropriate, but it requires remote-write approval.
