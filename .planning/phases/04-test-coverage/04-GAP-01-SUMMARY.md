---
gsd_state_version: 1.0
phase: "04-test-coverage"
plan: "GAP-01"
subsystem: "test"
tags: ["test", "mockito", "race-condition", "gap-closure"]
dependency_graph:
  requires: []
  provides: ["TEST-02"]
  affects: ["PreRefreshHandler"]
tech_stack:
  added: []
  patterns: ["lenient stubbing for early-return test paths"]
key_files:
  created: []
  modified:
    - "src/test/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/PreRefreshHandlerRaceConditionTest.java"
decisions:
  - "Use lenient() on stubs that may not be reached due to early return paths in PreRefreshHandler.doHandle()"
metrics:
  duration: "short"
  completed: "2026-04-25"
---

# Phase 04 Plan GAP-01: PreRefreshHandler Race Condition Test Fix Summary

## One-liner
Fixed Mockito UnnecessaryStubbing errors in PreRefreshHandler race condition tests by applying lenient() to stubs that may not be reached due to early return paths.

## Objective
Fix Mockito UnnecessaryStubbing error in PreRefreshHandlerRaceConditionTest that prevents tests from running cleanly.

## Completed Tasks

| Task | Name | Commit | Files |
| ---- | ---- | ------ | ----- |
| 1 | Fix Mockito UnnecessaryStubbing in PreRefreshHandlerRaceConditionTest | cdf9c0d | PreRefreshHandlerRaceConditionTest.java |

## What Was Done

Applied `lenient()` to all stubs in PreRefreshHandlerRaceConditionTest that may not be reached due to early return paths in PreRefreshHandler.doHandle():

1. **Import added**: `import static org.mockito.Mockito.lenient;`

2. **Test 1 - asyncRefreshAndEvict_concurrentNoCorruption**:
   - `lenient().when(valueOperations.get("test:key")).thenReturn(cachedValue)`
   - `lenient().when(ttlPolicy.shouldPreRefresh(anyLong(), anyLong(), anyDouble())).thenReturn(true)`

3. **Test 2 - asyncRefreshAndPut_concurrentCorrectPrecedence**:
   - `lenient().when(valueOperations.get("test:key")).thenAnswer(...)` (originalValue)
   - `lenient().when(ttlPolicy.shouldPreRefresh(...)).thenReturn(true)`
   - `lenient().when(valueOperations.get("test:key")).thenReturn(newValue)` (after handler call)

4. **Test 3 - multipleAsyncRefreshes_onlyLatestWins**:
   - All 4 `when(valueOperations.get(...))` stubs made lenient
   - `lenient().when(ttlPolicy.shouldPreRefresh(...))` made lenient

## Root Cause
PreRefreshHandler.doHandle() has early return paths at line 69 (`return HandlerResult.continueChain()`) that skip subsequent code paths where stubs would be used. Mockito strict stubming (default in JUnit 5 MockitoExtension) flags these as unnecessary stubbings.

## Verification
- All 3 tests pass: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`
- No UnnecessaryStubbing errors in output
- BUILD SUCCESS

## Deviations from Plan

None - plan executed exactly as written.

## Auth Gates
None.

## Threat Flags
None.

## Test Results
```
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```
