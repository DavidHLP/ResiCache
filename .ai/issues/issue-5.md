# Issue #5 — Serialization migration CLI (`shadow-read → dual-write → cutover → rollback`)

- **Issue:** https://github.com/DavidHLP/ResiCache/issues/5
- **Author:** `DavidHLP`
- **Type:** Issue (P1 Compatibility & Tooling)
- **Status (GitHub):** CLOSED (Closed on 2026-07-26 by commit `d97f3fd` via PR #6)
- **Status (Local):** RESOLVED & COMMITTED
- **Labels:** `help wanted`
- **Comments count:** 1 (`DavidHLP`)

## Context & Objectives

Provide a production-grade, zero-downtime migration workflow and CLI tool to transition live Redis cache values from legacy formats (`GenericJackson2JsonRedisSerializer` or Java JDK serialization) into ResiCache's secure versioned envelope format (`SecureJacksonRedisSerializer` with `VersionEnvelope`).

## Architecture & Phased Workflow

1. **`SHADOW_READ` Phase:** Probe existing cache keys, decode with fallback while maintaining security whitelists, track metrics without mutating Redis data.
2. **`DUAL_WRITE` Phase:** Re-encode legacy keys into version envelopes in-place or write new format concurrently.
3. **`CUTOVER` Phase:** Lua CAS atomic conditional update with TTL preservation and rollback safeguard.
4. **`ROLLBACK` Phase:** Safe reversibility without recreating absent keys or corrupting concurrent updates.

## Detailed Disposition of Review Findings (3×P1 + 3×P2)

### 3×P1 Security & Correctness Findings

1. **P1: Prevent native deserialization from instantiating allowed application classes before validation**
   - **Resolution:** `LegacyValueDecoder.RestrictedObjectInputStream` overrides `resolveClass(ObjectStreamClass descriptor)`. Validation against `WhitelistPolicy` is enforced on the descriptor class name *before* `super.resolveClass` or class instantiation occurs.
   - **Evidence:** `LegacyValueDecoderTest.java` lines 80-120; test invalid/malicious classes rejected before instantiation.

2. **P1: Do not recreate absent source keys during rollback**
   - **Resolution:** Rollback Lua script and engine verify source key existence in Redis before applying rollback write; missing or expired keys are strictly skipped without creating phantom empty keys.
   - **Evidence:** `SerializationMigrationEngine.java` rollback handler & `SerializationMigrationIntegrationTest.java`.

3. **P1: Count rejected or failed actionable keys toward `max-keys`**
   - **Resolution:** The scanning loop in `SerializationMigrationEngine` increments key progress counter on every processed key regardless of whether it succeeded, was skipped, or failed, preventing infinite loops on problematic keys.
   - **Evidence:** `SerializationMigrationEngineTest.java` bounded scan assertions.

### 3×P2 Robustness & Observability Findings

1. **P2: Persist or advance bounded `SHADOW_READ` progress**
   - **Resolution:** `SerializationMigrationReport` and scan cursor record pagination offsets; batch scanning respects bounded batch sizes across standalone and Cluster nodes.
2. **P2: Honor custom Generic Jackson type-hint properties**
   - **Resolution:** `LegacyValueDecoder.validateJsonTypeIds` streams tokens with `JsonParser`, checking both standard `@class` and configured `typeProperty` (e.g. `@type`).
3. **P2: Bound and normalize cache-name metric cardinality**
   - **Resolution:** Metric tags use normalized fallback strings for unmapped cache names; raw keys are excluded from metrics.

## Validation & Code Evidence

- **Commit:** `d97f3fd` (`feat(migration): add serialization cutover workflow`).
- **Files:** `src/main/java/.../serialization/migration/SerializationMigrationCli.java`, `SerializationMigrationEngine.java`, `LegacyValueDecoder.java`.
- **Tests:** `src/test/java/.../integration/SerializationMigrationIntegrationTest.java` (exercising real Redis bytes, TTL preservation, rollback, idempotency, cluster scan).
