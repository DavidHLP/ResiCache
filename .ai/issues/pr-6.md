# Pull Request #6 — Resolve Observability, Cluster, and Migration Issues

- **PR:** https://github.com/DavidHLP/ResiCache/pull/6
- **Author:** `DavidHLP`
- **Base Branch:** `main`
- **Status (GitHub):** MERGED (Merged on 2026-08-23 into `main`)
- **Status (Local):** MERGED into local `main`
- **Labels:** `blocked`, `changes requested`
- **Comments count:** 1 (`DavidHLP` maintainer review note)
- **Reviews count:** 1 (`chatgpt-codex-connector[bot]`, State: COMMENTED)
- **Resolves:** Issue #2, Issue #4, Issue #5

## Summary of Commits Included

1. `01cca01` `fix(observability): add bounded chain timer dimensions` (Fixes #4)
2. `037ffe4` `test(redis): prove cluster lock slot co-location` (Fixes #2)
3. `d97f3fd` `feat(migration): add serialization cutover workflow` (Fixes #5)
4. `86e2128` `docs(issues): record benchmark contributor status`
5. `2a0edce` `ci(setup): stop forwarding empty server-id to setup-java`
6. `bb02b70` `test(testcontainers): drop DOCKER_API_VERSION=1.54 override`
7. `2886297` `ci(workflows): install socat on ubuntu-latest before running tests`
8. `cab9c22` `docs: remove obsolete wiki knowledge base and docs content`
9. `afc7553` `refactor(chain,config): Wave 2 deepening — refresh cancel seam + dead config cleanup`
10. `7895130` `docs: strip references to deleted wiki/ADR/operational-log from comments`
11. `489c406` `fix(test): negotiate Docker API version dynamically`
12. `cfe70bd` `test: convert Redis-data-path mock tests to real Redis; drop socat`
13. `a1c1064` `chore: switch .gitignore to whitelist (opt-in) mode`
14. `4c83cd4` `chore: strip injected RTK instructions block from AGENTS.md`

## Review Disposition (3×P1 + 3×P2 Review Gate)

| ID | Priority | Finding | Code Location & Resolution | Verification |
|---|---|---|---|---|
| P1-1 | P1 | Class instantiation before validation in native deserialization | `LegacyValueDecoder.RestrictedObjectInputStream#resolveClass`: checks `WhitelistPolicy` on descriptor name before instantiation | `LegacyValueDecoderTest` |
| P1-2 | P1 | Rollback recreating absent source keys | `SerializationMigrationEngine`: validates key existence in Redis before restoring legacy format | `SerializationMigrationIntegrationTest` |
| P1-3 | P1 | Rejected/failed keys not counted toward `max-keys` | `SerializationMigrationEngine`: advances key scan counter unconditionally per key | `SerializationMigrationEngineTest` |
| P2-1 | P2 | Advance bounded `SHADOW_READ` progress | Cursor and pagination offsets saved across batch iterations | Integration test |
| P2-2 | P2 | Custom Generic Jackson type-hint property support | `LegacyValueDecoder.validateJsonTypeIds`: supports custom `typeProperty` and `@class` | `LegacyValueDecoderTest` |
| P2-3 | P2 | Bounded cache-name metric cardinality | Observers tag cache names safely without dynamic key interpolation | `ChainObserverTest` |

## Validation & Verification

- CI on PR: 1/1 passed.
- Full local suite: `mvn clean test -B` → 852 passed, 0 failures, 0 errors, 0 skipped.
- Real 3-master Redis Cluster verified under Testcontainers.
