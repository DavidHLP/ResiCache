---
phase: "04-test-coverage"
plan: "GAP-02"
type: "execute"
wave: 1
depends_on: []
files_modified:
  - "src/main/java/io/github/davidhlp/spring/cache/redis/strategy/eviction/support/TwoListLRU.java"
autonomous: true
requirements:
  - "TECH-01"
gap_closure: true
gap_source: "04-VERIFICATION.md"
gap_id: "Gap 2"

must_haves:
  truths:
    - "TwoListLRU concurrent access does not corrupt linked list data structures"
    - "No NullPointerException on node.prev during concurrent put/get operations"
  artifacts:
    - path: "src/main/java/io/github/davidhlp/spring/cache/redis/strategy/eviction/support/TwoListLRU.java"
      provides: "Thread-safe LRU eviction with properly synchronized linked list operations"
      min_lines: 550
  key_links:
    - from: "TwoListLRU.java"
      to: "ConcurrentHashMap nodeMap"
      via: "nodeMap.get/put/remove"
      pattern: "nodeMap\\.(get|put|remove)"
    - from: "TwoListLRU.java"
      to: "Striped locks"
      via: "lockFor(key)"
      pattern: "lockFor.*writeLock"
---

<objective>
Fix the concurrency bug in TwoListLRU where node.prev becomes null under concurrent access, causing NullPointerException in linked list operations. This is a TECH-01 regression exposed by TwoListLRUConcurrentTest.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
</execution_context>

<context>
## Bug Analysis

The bug is in `demoteOrEvictOldestActiveUnsafe()` (lines 405-446).

**Problem flow:**
1. A candidate node is selected from the active list
2. `removeNodeUnsafe(candidate)` is called, which sets `candidate.prev = null` and `candidate.next = null`
3. The code checks if inactive list has space
4. If inactive is full, `evictOldestInactiveUnsafe()` is called to make room
5. If `evictOldestInactiveUnsafe()` returns false (all inactive entries protected), the code falls through to `evictNode(candidate)`
6. But `evictNode` doesn't properly handle the case where the candidate was already removed

**Root cause:** The node's prev/next pointers are nullified before the demotion/eviction is complete. If any code path tries to use these null pointers, an NPE occurs.

**Key code in `demoteOrEvictOldestActiveUnsafe()`:**
```java
removeNodeUnsafe(candidate);  // nulls candidate.prev and candidate.next
activeSizeCounter.decrementAndGet();

if (inactiveSizeCounter.get() < maxInactiveSize) {
    demoteToInactive(candidate);  // uses candidate.prev and candidate.next!
} else if (evictOldestInactiveUnsafe()) {
    demoteToInactive(candidate);  // uses candidate.prev and candidate.next!
} else {
    evictNode(candidate);  // only uses node.key and node.value - OK
}
```

## Fix Strategy

The fix needs to ensure proper synchronization when demoting/evicting nodes:
1. When `evictOldestInactiveUnsafe()` returns false, the candidate node is already removed from active list but not properly handled
2. Add a re-check after `evictOldestInactiveUnsafe()` to handle the case where no eviction was possible
3. Ensure the candidate is either properly demoted (when space becomes available) or cleanly evicted

**Fix location:** `demoteOrEvictOldestActiveUnsafe()` method, specifically the else branch after `evictOldestInactiveUnsafe()` returns false.
</context>

<tasks>

<task type="auto">
  <name>Fix TwoListLRU concurrency bug in demoteOrEvictOldestActiveUnsafe</name>
  <files>src/main/java/io/github/davidhlp/spring/cache/redis/strategy/eviction/support/TwoListLRU.java</files>
  <read_first>src/main/java/io/github/davidhlp/spring/cache/redis/strategy/eviction/support/TwoListLRU.java</read_first>
  <action>
    Fix the concurrency bug in `demoteOrEvictOldestActiveUnsafe()` method (around line 405).

    The issue: When `evictOldestInactiveUnsafe()` returns false (all inactive entries protected), the code falls through to `evictNode(candidate)` without re-checking if space became available after the eviction attempt.

    **The fix:** Add a re-check after `evictOldestInactiveUnsafe()` returns true. If it returns true, re-check inactive capacity before demoting. If it returns false, proceed with direct eviction.

    Replace the else branch (starting around line 421):
    ```java
    } else {
        // 无法淘汰Inactive节点，直接淘汰当前节点
        evictNode(candidate);
        if (log.isDebugEnabled()) {
            log.debug(
                    "Evicted entry from active list (inactive full): key={}",
                    candidate.key);
        }
    }
    ```

    With:
    ```java
    } else {
        // 无法淘汰Inactive节点，尝试重新检查inactive空间
        // (evictOldestInactiveUnsafe可能删除了部分节点)
        if (inactiveSizeCounter.get() < maxInactiveSize) {
            demoteToInactive(candidate);
        } else {
            // 确实无法腾出空间，直接淘汰当前节点
            evictNode(candidate);
            if (log.isDebugEnabled()) {
                log.debug(
                        "Evicted entry from active list (inactive full): key={}",
                        candidate.key);
            }
        }
    }
    ```

    This ensures that after `evictOldestInactiveUnsafe()` makes room, we re-check capacity before demoting.
  </action>
  <verify>
    <automated>mvn test -Dtest=TwoListLRUConcurrentTest -q 2>&1 | tail -30</automated>
  </verify>
  <done>TwoListLRU concurrent access tests pass without NullPointerException on node.prev</done>
  <acceptance_criteria>
  - "grep -n 'inactiveSizeCounter.get() < maxInactiveSize' TwoListLRU.java returns re-check logic after evictOldestInactiveUnsafe"
  - "mvn test -Dtest=TwoListLRUConcurrentTest -q 2>&1 | grep -i 'nullpointer' returns empty"
  - "mvn test -Dtest=TwoListLRUConcurrentTest -q 2>&1 | tail -5 shows BUILD SUCCESS"
  </acceptance_criteria>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| {thread} -> {TwoListLRU} | Concurrent access to shared linked list data structures |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-04-GAP-02-01 | Race Condition | TwoListLRU.demoteOrEvictOldestActiveUnsafe | mitigate | Add re-check after evictOldestInactiveUnsafe to handle case where eviction made space available |
| T-04-GAP-02-02 | Data Corruption | TwoListLRU linked list | mitigate | Ensure node.prev/next are not accessed after removal without proper synchronization |
</threat_model>

<verification>
Run TwoListLRUConcurrentTest to verify the fix resolves the NullPointerException on node.prev under concurrent access.
</verification>

<success_criteria>
- mvn test -Dtest=TwoListLRUConcurrentTest passes
- No NullPointerException in test output
- Concurrent put/get operations complete without data structure corruption
</success_criteria>

<output>
After completion, create `.planning/phases/04-test-coverage/04-GAP-02-SUMMARY.md`
</output>
