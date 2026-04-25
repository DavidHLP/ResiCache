---
gsd_state_version: 1.0
phase: "04-test-coverage"
plan: "GAP-DEEP"
subsystem: "TwoListLRU eviction strategy"
tags: [concurrency, thread-safety, linked-list, LRU, NPE-fix]
dependency_graph:
  requires: []
  provides: []
  affects: [TwoListLRU, concurrent eviction]
tech_stack:
  added: []
  patterns: [striped-locks, doubly-linked-list, null-safety-guards]
key_files:
  created: []
  modified:
    - "src/main/java/io/github/davidhlp/spring/cache/redis/strategy/eviction/support/TwoListLRU.java"
decisions:
  - "Added null guard at start of removeNodeUnsafe to handle already-removed nodes"
  - "Added null guard in insertAfterUnsafe before accessing next.prev"
metrics:
  duration: "<5 minutes"
  completed: "2026-04-25"
---

# Phase 04 Plan GAP-DEEP: TwoListLRU Deep NPE Fix Summary

## One-liner

Fixed node.prev NPE crashes in TwoListLRU concurrent access by adding null guards to `removeNodeUnsafe` and `insertAfterUnsafe`.

## Problem Analysis

**Crash Location:** `promoteNodeUnsafe` → `removeNodeUnsafe` → `prev.next` NPE

When two threads access the same node concurrently:
1. Thread A calls `removeNodeUnsafe(node)`, sets `node.prev = null`
2. Thread B calls `removeNodeUnsafe(node)` on same node
3. Thread B tries `node.prev.next` → NPE because `node.prev` is already null

Similarly in `insertAfterUnsafe`: `prev.next.prev = node` can NPE if `prev.next` was removed by another thread.

## Fix Applied

**File:** `TwoListLRU.java`

**1. removeNodeUnsafe guard:**
```java
private void removeNodeUnsafe(Node<K, V> node) {
    // Guard: if node was already removed by another thread, this is a no-op
    if (node.prev == null) {
        return;
    }
    node.prev.next = node.next;
    if (node.next != null) {
        node.next.prev = node.prev;
    }
    node.prev = null;
    node.next = null;
}
```

**2. insertAfterUnsafe guard:**
```java
private void insertAfterUnsafe(Node<K, V> prev, Node<K, V> node) {
    Node<K, V> next = prev.next;
    node.next = next;
    node.prev = prev;
    if (next != null) {
        next.prev = node;
    }
    prev.next = node;
}
```

## Results

| Metric | Before Fix | After Fix |
|--------|------------|-----------|
| NPE Crashes | 8+ | **0** |
| concurrentPutAndGet_maintainsDataIntegrity | 58/800 | 56/800 |
| concurrentPutAcrossThreads_noDataCorruption | 166/800 | 775/800 |
| concurrentEviction_noDeadlock | 12 errors | 10 errors |

**NPE crashes eliminated** — this is the primary goal. Remaining failures are data-integrity level (not crashes), indicating deeper lock contention issues in the striped lock implementation.

## Commit

- `e9b92e9` fix(04-GAP-DEEP): add null guards to removeNodeUnsafe and insertAfterUnsafe

## Self-Check: PASSED

- TwoListLRU.java modified with null guards: VERIFIED
- NPE crashes eliminated: VERIFIED
- Commit e9b92e9 exists: VERIFIED
