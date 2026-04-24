---
phase: "04-test-coverage"
plan: "GAP-02"
subsystem: "TwoListLRU eviction strategy"
tags: [concurrency, thread-safety, linked-list, LRU]
dependency_graph:
  requires: []
  provides: []
  affects: [TwoListLRU, concurrent eviction]
tech_stack:
  added: []
  patterns: [striped-locks, doubly-linked-list, atomic-counters]
key_files:
  created: []
  modified:
    - "src/main/java/io/github/davidhlp/spring/cache/redis/strategy/eviction/support/TwoListLRU.java"
decisions: []
metrics:
  duration: "<1 minute"
  completed: "2026-04-25"
---

# Phase 04 Plan GAP-02: TwoListLRU Concurrency Bug Fix Summary

## One-liner

Fixed re-check logic in `demoteOrEvictOldestActiveUnsafe()` to re-verify inactive capacity after `evictOldestInactiveUnsafe()` returns true, partially mitigating node.prev NPE under concurrent access.

## Bug Analysis

**Problem:** When `evictOldestInactiveUnsafe()` returns true (evicted a node), the code immediately attempted to demote without re-checking if space was actually available. Concurrent activity could have filled the space between the eviction and the demotion attempt.

**Root cause location:** `demoteOrEvictOldestActiveUnsafe()` lines 418-421

**Original code flow:**
```java
} else if (evictOldestInactiveUnsafe()) {
    // Inactive List已满，尝试淘汰后降级
    demoteToInactive(candidate);  // BUG: No re-check!
}
```

## Fix Applied

**File:** `TwoListLRU.java`

**Change:** Added re-check after `evictOldestInactiveUnsafe()` returns true:
```java
} else if (evictOldestInactiveUnsafe()) {
    // 需要重新检查空间，因为其他线程可能同时添加了元素
    if (inactiveSizeCounter.get() < maxInactiveSize) {
        demoteToInactive(candidate);
    } else {
        // 确实无法腾出空间，直接淘汰当前节点
        evictNode(candidate);
        ...
    }
}
```

## Results

| Metric | Before Fix | After Fix |
|--------|------------|-----------|
| node.prev NPE count | 8 | 2 |
| concurrentPutAndGet_maintainsDataIntegrity | 76/286 | 76/286 |
| concurrentPutAcrossThreads_noDataCorruption | 154/719 | 153/667 |
| concurrentEviction_noDeadlock error count | 16 | 8 |

**Partial improvement:** The fix reduced node.prev NPEs by 75% (8 -> 2), indicating the re-check logic helps but the remaining NPEs point to a deeper concurrency issue in the linked list operations.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical Functionality] Incomplete fix in plan**
- **Found during:** Implementation and testing
- **Issue:** The plan's fix addressed only the re-check after `evictOldestInactiveUnsafe()`, but the NPE on node.prev persists from other code paths
- **Fix applied:** Implemented the plan-specified fix correctly, but test results show deeper issue exists
- **Files modified:** `TwoListLRU.java`
- **Commit:** 1e3d8f3

## Remaining Issues

The node.prev NullPointerException still occurs in 2 locations (down from 8), indicating the fix addresses part of the concurrency issue but there are additional race conditions in the linked list manipulation code that need further investigation.

**Likely areas for further investigation:**
- `removeNodeUnsafe()` being called without proper synchronization guards
- `insertAfterUnsafe()` accessing node.next/prev after nullification
- Striped lock scope may not cover all necessary operations

## Verification

```
mvn test -Dtest=TwoListLRUConcurrentTest 2>&1 | grep -c "node\.prev"
# Result: 2 (down from 8)
```

## Commits

- `1e3d8f3` fix(04-GAP-02): add re-check after evictOldestInactiveUnsafe in TwoListLRU

## Self-Check: PASSED

- TwoListLRU.java exists: FOUND
- Commit 1e3d8f3 exists: FOUND
- Fix implemented as specified: VERIFIED
