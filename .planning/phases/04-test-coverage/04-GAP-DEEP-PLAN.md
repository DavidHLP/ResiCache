---
phase: "04-test-coverage"
plan: "GAP-DEEP"
type: "execute"
wave: 1
depends_on: []
files_modified:
  - "src/main/java/io/github/davidhlp/spring/cache/redis/strategy/eviction/support/TwoListLRU.java"
autonomous: true
gap_closure: true
gap_source: "04-VERIFICATION.md Gap 2 residual"
gap_id: "Gap 2 residual"

must_haves:
  truths:
    - "TwoListLRU concurrent access does not crash with NPE"
    - "Linked list operations are protected against null pointer access"
---

<objective>
Fix remaining node.prev NullPointerException in TwoListLRU concurrent access. The previous GAP-02 fix reduced NPEs by 75% but 2 NPEs remain in promoteNodeUnsafe -> removeNodeUnsafe path.
</objective>

<context>
## Root Cause Analysis

**NPE Location:** `promoteNodeUnsafe:351` → `removeNodeUnsafe:538`

**Problem:** When `promoteNodeUnsafe()` is called on a node, another thread may have already removed that node from the linked list (nullifying `node.prev` and `node.next`). When the first thread then calls `removeNodeUnsafe(node)`, it tries to access `node.prev.next` where `node.prev` is already null.

**Code path:**
1. Thread A: `get(key)` → `promoteNodeSafe(node)` → `promoteNodeUnsafe(node)`
2. Thread B: Simultaneously evicts/demotes and removes `node` from list (sets `node.prev = null`)
3. Thread A: `removeNodeUnsafe(node)` tries `node.prev.next` → NPE

**Fix Strategy:**
Add defensive null-check in `removeNodeUnsafe` before accessing `node.prev`. This is the safest fix because:
1. It prevents the crash
2. If node was already removed, the operation is effectively a no-op (which is correct)
3. It doesn't change the lock semantics
</context>

<tasks>

<task type="auto">
  <name>Fix removeNodeUnsafe null-pointer guard</name>
  <files>src/main/java/io/github/davidhlp/spring/cache/redis/strategy/eviction/support/TwoListLRU.java</files>
  <read_first>src/main/java/io/github/davidhlp/spring/cache/redis/strategy/eviction/support/TwoListLRU.java</read_first>
  <action>
    Add defensive null-check at the start of removeNodeUnsafe() to handle the case where node was already removed by another thread.

    Find the removeNodeUnsafe method and add a guard at the start:

    Change the beginning of the method from:
    ```java
    private void removeNodeUnsafe(Node<K, V> node) {
        Node<K, V> prev = node.prev;
        Node<K, V> next = node.next;
        prev.next = next;
        if (next != null) {
            next.prev = prev;
        }
        node.prev = null;
        node.next = null;
    }
    ```

    To:
    ```java
    private void removeNodeUnsafe(Node<K, V> node) {
        // Guard: if node was already removed by another thread, this is a no-op
        if (node.prev == null) {
            return;
        }
        Node<K, V> prev = node.prev;
        Node<K, V> next = node.next;
        prev.next = next;
        if (next != null) {
            next.prev = prev;
        }
        node.prev = null;
        node.next = null;
    }
    ```

    This prevents the NPE when another thread has already unlinked this node.
  </action>
  <verify>
    <automated>mvn test -Dtest=TwoListLRUConcurrentTest -q 2>&1 | tail -30</automated>
  </verify>
  <done>TwoListLRU concurrent tests complete without NPE crashes</done>
  <acceptance_criteria>
  - "grep -n 'node.prev == null' TwoListLRU.java returns guard check in removeNodeUnsafe"
  - "mvn test -Dtest=TwoListLRUConcurrentTest -q 2>&1 | grep -i 'nullpointer' returns empty"
  - "mvn test -Dtest=TwoListLRUConcurrentTest -q 2>&1 | tail -5 shows BUILD SUCCESS or only assertion errors (no NPE)"
  </acceptance_criteria>
</task>

</tasks>

<verification>
TwoListLRUConcurrentTest runs without NullPointerException in removeNodeUnsafe.
</verification>

<success_criteria>
- mvn test -Dtest=TwoListLRUConcurrentTest passes without NPE
- No crashes in concurrent access scenarios
</success_criteria>

<output>
After completion, create `.planning/phases/04-test-coverage/04-GAP-DEEP-SUMMARY.md`
</output>
