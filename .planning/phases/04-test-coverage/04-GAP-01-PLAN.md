---
phase: "04-test-coverage"
plan: "GAP-01"
type: "execute"
wave: 1
depends_on: []
files_modified:
  - "src/test/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/PreRefreshHandlerRaceConditionTest.java"
autonomous: true
requirements:
  - "TEST-02"
gap_closure: true
gap_source: "04-VERIFICATION.md"
gap_id: "Gap 1"

must_haves:
  truths:
    - "PreRefreshHandler race condition tests run without Mockito errors"
  artifacts:
    - path: "src/test/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/PreRefreshHandlerRaceConditionTest.java"
      provides: "Race condition tests that execute without stubbing errors"
      min_lines: 200
  key_links:
    - from: "PreRefreshHandlerRaceConditionTest.java"
      to: "PreRefreshHandler.java"
      via: "handler.doHandle(context)"
      pattern: "doHandle.*context"
---

<objective>
Fix Mockito UnnecessaryStubbing error in PreRefreshHandlerRaceConditionTest that prevents tests from running cleanly.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
</execution_context>

<context>
## Gap Description
Line 105: `when(valueOperations.get("test:key")).thenReturn(cachedValue)` causes UnnecessaryStubbing error because the handler returns early before reaching that line. The stub is never used.

## Code Path Analysis
The test sets up a stub at line 105, but the PreRefreshHandler.doHandle() path for ASYNC mode with preRefresh enabled checks `shouldPreRefresh` and may return before ever calling `valueOperations.get()`.

## Fix Strategy
Use `lenient()` on stubs that may not be reached due to early returns, or restructure test to only stub what's actually used in each specific test path.
</context>

<tasks>

<task type="auto">
  <name>Fix Mockito UnnecessaryStubbing in PreRefreshHandlerRaceConditionTest</name>
  <files>src/test/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/PreRefreshHandlerRaceConditionTest.java</files>
  <read_first>src/test/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/PreRefreshHandlerRaceConditionTest.java</read_first>
  <action>
    Apply `lenient()` to the stub at line 105 that causes UnnecessaryStubbing error:

    Change:
    ```java
    when(valueOperations.get("test:key")).thenReturn(cachedValue);
    ```

    To:
    ```java
    lenient().when(valueOperations.get("test:key")).thenReturn(cachedValue);
    ```

    Add import if not present:
    ```java
    import static org.mockito.Mockito.lenient;
    ```

    The lenient() annotation allows the stub to exist even if not strictly verified, which is appropriate for test setup that may not be reached due to early return paths in the handler.
  </action>
  <verify>
    <automated>mvn test -Dtest=PreRefreshHandlerRaceConditionTest -q 2>&1 | tail -20</automated>
  </verify>
  <done>PreRefreshHandlerRaceConditionTest runs without UnnecessaryStubbing errors</done>
  <acceptance_criteria>
  - "grep -n 'lenient()' PreRefreshHandlerRaceConditionTest.java returns line with lenient stub"
  - "grep -n 'import.*lenient' PreRefreshHandlerRaceConditionTest.java returns import statement"
  - "mvn test -Dtest=PreRefreshHandlerRaceConditionTest -q 2>&1 | grep -i 'unnecessary' returns empty (no stubbing errors)"
  </acceptance_criteria>
</task>

</tasks>

<verification>
All PreRefreshHandler race condition tests execute without Mockito errors.
</verification>

<success_criteria>
- mvn test -Dtest=PreRefreshHandlerRaceConditionTest passes
- No UnnecessaryStubbing error in test output
</success_criteria>

<output>
After completion, create `.planning/phases/04-test-coverage/04-GAP-01-SUMMARY.md`
</output>
