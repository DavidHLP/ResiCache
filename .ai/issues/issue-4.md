# Issue #4 — Per-handler Micrometer tags on `resicache.chain.execute`

- **Issue:** https://github.com/DavidHLP/ResiCache/issues/4
- **Status:** RESOLVED
- **Priority:** P0 observability
- **Dependencies:** none
- **External work:** no assignee, comments, branch, or linked PR

## Current findings

- Current implementation moved the old inline timer from `CacheHandlerChain` into `ChainTimerChainObserver`.
- `ChainTimerChainObserver` creates one tag-less, lazily cached `Timer`; its scope token contains only chain start nanos.
- `ChainObserver#afterNode` already receives both the concrete `CacheHandler` and its `HandlerResult`, so it is the natural per-handler/per-decision observation point.
- Current timer description promises full-chain lifecycle timing. The Issue asks for handler + decision tags; implementation must avoid falsely assigning the same whole-chain duration to every handler.
- Existing `resicache.handler.fired` already counts handler evaluation and must not be duplicated.

## Acceptance criteria

1. `resicache.chain.execute` is queryable by bounded `handler`, `decision`, and `cacheName` tags.
2. `handler` comes from the finite installed handler set; `decision` is one of `CONTINUE`, `SKIP_ALL`, `TERMINATE`.
3. No `redisKey`, user input, exception text, or dynamic ID is used as a tag.
4. Each timer sample measures the corresponding handler invocation, not an incorrectly duplicated whole-chain duration.
5. Registry absence remains a no-op and concurrent calls do not share mutable per-call timing state.
6. Existing fired counter semantics remain unchanged.
7. Wiki observability documentation matches the final metric semantics.

## Implementation plan

- Refine `ChainTimerChainObserver` to record per-node timing using observer scope that remains per call / per thread-safe invocation.
- Use Micrometer registration keyed by bounded tag tuple, not one global cached timer.
- Expand `ChainObserverTest.TimerTests` with decisions, cache names, changing redis keys, and meter-count/cardinality assertions.
- Update `wiki/modules/observability.md` source references and metric table.

## Files/modules involved

- `src/main/java/io/github/davidhlp/spring/cache/redis/chain/observer/ChainTimerChainObserver.java`
- `src/main/java/io/github/davidhlp/spring/cache/redis/chain/ChainEngine.java` (read-only unless observer contract cannot express timing)
- `src/test/java/io/github/davidhlp/spring/cache/redis/chain/observer/ChainObserverTest.java`
- `wiki/modules/observability.md`

## Tests required

- Targeted unit tests for all three decisions and multiple handlers/cache names.
- Cardinality test varying only `redisKey` and asserting meter count remains constant.
- Null-registry no-op.
- `./mvnw -B -Dtest=ChainObserverTest,CacheHandlerChainFactoryTest test`
- `./mvnw -B checkstyle:check`

## Validation result

- Java runtime: Temurin/OpenJDK `21.0.2+13` installed user-locally via `vfox` because the session default was Java 17 and system Java 25 broke current Lombok processing.
- `./mvnw -B -Dtest=ChainObserverTest,ChainEngineTest,CacheHandlerChainFactoryTest test`: `BUILD SUCCESS`; 43 tests, 0 failures, 0 errors, 0 skipped.
- `./mvnw -B checkstyle:check`: `BUILD SUCCESS`; 0 violations.
- `git diff --check`: clean.

## Review findings

Independent reviewer jobs failed to yield within the review window and were cancelled; the maintainer process then completed the same frozen Standards + Spec checklist directly against the full diff and all callers/tests.

- **critical/high/medium:** none.
- **low:** none requiring code changes.
- Concurrency/resource verdict: per-node state is an immutable token paired by Engine `finally`; no shared mutable `ThreadLocal` or stale stack.
- Compatibility verdict: existing `beforeNode/afterNode` exception behavior and `resicache.handler.fired` semantics are unchanged; new observer methods are default no-op.
- Cardinality verdict: only `handler`, three-value `decision`, and configured `cacheName`; varying `redisKey` is explicitly tested not to create meters.
- Semantic verdict: each sample is one handler invocation, so `handler` and `decision` describe the measured duration rather than duplicating whole-chain duration under every handler.

## Commit / PR

Local commit: current Issue commit (`fix(observability): add bounded chain timer dimensions`). Remote PR/Issue closure requires GitHub Write Gate approval.

## Remaining work

Prepare the remote-write review package after all executable Issues are locally complete.
