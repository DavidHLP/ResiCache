# ResiCache Issue & PR Archive Ledger

**Last synchronized:** 2026-08-23  
**Repository baseline:** `origin/main` @ `75ed279a` | local `main` @ `HEAD`  
**GitHub repository:** `DavidHLP/ResiCache`  
**Total Tracked Objects:** 7 (5 Issues + 3 PRs; note PR #1 is also Issue #1)

---

## 1. Master Status Matrix

| ID | Title | Type | Author | GitHub State | Local State | Labels | Comments / Reviews | Resolution & Evidence |
|---|---|---|---|---|---|---|---|---|
| [#1](issue-1.md) | Add qodana CI checks | PR | `qodana-cloud[bot]` | CLOSED (Unmerged) | CLOSED | - | 1 / 0 | Automated Qodana bot PR; closed without merge; static analysis handled via Checkstyle/JaCoCo |
| [#2](issue-2.md) | Testcontainers Redis Cluster IT | Issue (P1) | `DavidHLP` | CLOSED (2026-07-26) | RESOLVED | `good first issue` | 1 / 0 | Real 3-master Redis Cluster hash-tag slot co-location verified (`037ffe4` via PR #6) |
| [#3](issue-3.md) | Minimal JMH module + PERFORMANCE.md | Issue (P1) | `DavidHLP` | OPEN | RESOLVED | `help wanted`, `performance` | 4 / 0 | External draft PR #7 integrated, 3 API bugs fixed, 2 missing suites added, live JMH executed |
| [#4](issue-4.md) | Per-handler Micrometer tags | Issue (P0) | `DavidHLP` | CLOSED (2026-07-26) | RESOLVED | `help wanted` | 1 / 0 | Bounded `handler`/`decision`/`cacheName` tags added to `resicache.chain.execute` (`01cca01` via PR #6) |
| [#5](issue-5.md) | Serialization migration CLI | Issue (P1) | `DavidHLP` | CLOSED (2026-07-26) | RESOLVED | `help wanted` | 1 / 0 | Phased migration CLI & engine (`shadow-read`→`dual-write`→`cutover`→`rollback`) with 3×P1+3×P2 fixed (`d97f3fd` via PR #6) |
| [#6](pr-6.md) | Resolve observability, cluster, migration | PR | `DavidHLP` | OPEN | COMMITTED | `blocked`, `changes requested` | 1 / 1 (Codex) | Maintainer aggregation PR (14 commits) resolving #2, #4, #5 with full 3×P1+3×P2 review dispositions |
| [#7](pr-7.md) | Add resicache-bench JMH module | PR (Draft) | `Shubh2-0` | OPEN | MERGED_AND_EXTENDED | `changes requested`, `performance` | 1 / 0 | External draft PR for #3; API bugs fixed, 5 full suites completed, raw `jmh-results.json` + `PERFORMANCE.md` backfilled |

---

## 2. External Collaboration & Triage Signals

- **`Shubh2-0` (#3 → PR #7):** Volunteered 2026-07-21, submitted draft PR #7 on 2026-07-27. Maintainer requested scope alignment and raw benchmark data on 2026-07-28. The draft PR has now been imported, repaired, extended to 5 suites, and validated locally.
- **`qodana-cloud[bot]` (PR #1):** Cleanly closed; repository rules enforced via Checkstyle and JaCoCo gates.
- **`chatgpt-codex-connector[bot]` (PR #6):** Automated review comments tracked and addressed in maintainer commits.

---

## 3. Local Implementation & Validation Summary

1. **Unit & Integration Suite (`mvn clean test -B`):**
   - **852 tests run**, 0 failures, 0 errors, 0 skipped.
   - Real Redis standalone + 3-master Redis Cluster integration tests executed under Testcontainers.
2. **JMH Micro-benchmark Suite (`resicache-bench`):**
   - 5 full suites implemented and verified:
     - `BloomFilterBenchmark` (5.85 M ops/s hit, 4.30 M ops/s put)
     - `SyncLockBenchmark` (81.1 M ops/s @ 8T, 455 M ops/s @ 32T)
     - `TtlJitterBenchmark` (54.8 M ops/s compute)
     - `ChainPassThroughBenchmark` (30.97 M ops/s pass-through vs 856 M ops/s map lookup)
     - `HandlerAdditiveCostBenchmark` (+2.5 ~ +3.8 ns marginal cost per handler)
   - Verified executable fat-jar via `mvn -f resicache-bench/pom.xml clean package -DskipTests`.
   - Raw output artifact saved to `resicache-bench/target/jmh-results.json` and documented in `PERFORMANCE.md`.
3. **Repository Cleanliness:**
   - 0 `TODO`, `FIXME`, `XXX`, or `HACK` markers across the entire codebase.
   - Wave 2/3 code deepening completed (dead code cleaned, package structure unified, seam classes consolidated).
