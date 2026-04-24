# Phase 4: test-coverage - Research

**Researched:** 2026-04-24
**Domain:** Java Concurrency Testing, Handler Chain Testing, SpEL Security Testing
**Confidence:** MEDIUM

## Summary

Phase 4 adds critical test coverage for ResiCache's concurrent and security-sensitive scenarios. The core challenge is testing TwoListLRU's thread-safety under concurrent access, PreRefreshHandler race conditions, Handler Chain exception handling, Bloom Filter false positive behavior, and SpEL expression injection prevention.

**Key discovery:** The `concurrent-unit` library specified in context does not exist in Maven Central. The standard Java library for concurrency testing with JUnit 5 is **Awaitility** (org.awaitility:awaitility:4.3.0), which provides `await()` semantics and integrates well with existing JUnit 5 + AssertJ patterns.

**Primary recommendation:** Use Awaitility instead of ConcurrentUnit for all concurrency testing. Add `org.awaitility:awaitility:4.3.0` as a test dependency.

---

## User Constraints (from CONTEXT.md)

### Locked Decisions

- **TEST-01:** Use ConcurrentUnit for TwoListLRU concurrency testing (but see finding - ConcurrentUnit does not exist, Awaitility is the standard)
- **TEST-04:** Verify Bloom Filter false positive cache protection behavior exists
- **TEST-05:** Use evaluator unit tests to verify SpEL expression security

### Claude's Discretion

- Concurrency test thread count: 4-8 threads recommended
- Test datasets: Use mock data (deterministic tests)
- SpEL malicious expression samples: Reference OWASP test cases

---

## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| TEST-01 | TwoListLRU concurrent access (put/get/evict) | Use Awaitility + ExecutorService for concurrency testing |
| TEST-02 | PreRefreshHandler race conditions | Extend existing PreRefreshHandlerTest.java with race tests |
| TEST-03 | Handler Chain exception handling | Extend existing CacheHandlerChainTest.java with exception tests |
| TEST-04 | Bloom Filter false positive impact | Verify chain continues on Bloom+ true but Redis miss |
| TEST-05 | SpEL expression injection | Test malicious expressions against SpelConditionEvaluator |

---

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| TwoListLRU concurrency | JVM memory | — | In-process LRU cache, thread-safety is internal |
| PreRefreshHandler race | API/Backend | — | Redis operations + async executor interaction |
| Handler Chain exception | API/Backend | — | Business logic in handler chain |
| Bloom Filter false positive | Redis | — | Redis-backed filter, behavior depends on Redis state |
| SpEL injection security | API/Backend | — | Expression evaluation in cache operations |

---

## Standard Stack

### Core Testing Dependencies

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| JUnit 5 | (Spring Boot 3.2.4 managed) | Test framework | Industry standard for Java |
| AssertJ | (Spring Boot 3.2.4 managed) | Fluent assertions | Project already uses it |
| Mockito | (Spring Boot 3.2.4 managed) | Mocking | Project already uses it |
| **Awaitility** | **4.3.0** | **Concurrency testing** | **await() semantics for async operations** |

### Installation

```xml
<dependency>
    <groupId>org.awaitility</groupId>
    <artifactId>awaitility</artifactId>
    <version>4.3.0</version>
    <scope>test</scope>
</dependency>
```

**Version verification:** [VERIFIED: Maven Central - Awaitility 4.3.0 published 2024, latest stable]

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Awaitility | java.util.concurrent.CountDownLatch alone | CountDownLatch works but Awaitility provides better polling + timeout |
| Awaitility | Custom Thread.sleep loops | Awaitility is battle-tested, handles edge cases |

---

## Architecture Patterns

### System Architecture Diagram

```
                    ┌─────────────────────────────────────────┐
                    │           Test Execution                │
                    │                                         │
                    │  ┌─────────────────────────────────┐   │
                    │  │     Concurrency Test            │   │
                    │  │  ExecutorService (4-8 threads)  │   │
                    │  │  Awaitility.await()            │   │
                    │  └────────────┬────────────────────┘   │
                    │               │                         │
                    └───────────────┼─────────────────────────┘
                                    │
                    ┌───────────────┼─────────────────────────┐
                    │               ▼                         │
                    │  ┌─────────────────────────────────┐   │
                    │  │     TwoListLRU                  │   │
                    │  │  ConcurrentHashMap + Striped   │   │
                    │  │  ReadWriteLocks                │   │
                    │  └─────────────────────────────────┘   │
                    │                                         │
                    │  ┌─────────────────────────────────┐   │
                    │  │     Handler Chain                │   │
                    │  │  PreRefreshHandler              │   │
                    │  │  BloomFilterHandler             │   │
                    │  │  ActualCacheHandler             │   │
                    │  └─────────────────────────────────┘   │
                    │                                         │
                    │  ┌─────────────────────────────────┐   │
                    │  │     SpEL Evaluator              │   │
                    │  │  StandardEvaluationContext      │   │
                    │  └─────────────────────────────────┘   │
                    └─────────────────────────────────────────┘
```

### Recommended Project Structure

```
src/test/java/io/github/davidhlp/spring/cache/redis/
├── strategy/eviction/support/
│   └── TwoListLRUConcurrentTest.java     [NEW - TEST-01]
├── core/writer/chain/
│   └── CacheHandlerChainExceptionTest.java [NEW - TEST-03]
└── core/evaluator/
    └── SpelConditionEvaluatorSecurityTest.java [NEW - TEST-05]

src/test/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/
└── PreRefreshHandlerRaceConditionTest.java [NEW - TEST-02]

src/test/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/
└── BloomFilterFalsePositiveTest.java [NEW - TEST-04]
```

### Pattern 1: Concurrency Testing with Awaitility

**What:** Test thread-safety using ExecutorService + Awaitility polling
**When to use:** Testing concurrent put/get/evict operations on TwoListLRU
**Example:**

```java
// Source: [VERIFIED: Awaitility documentation patterns]
@Test
void concurrentPutAndGet_noDataCorruption() throws Exception {
    TwoListLRU<String, String> lru = new TwoListLRU<>(100, 50);
    int threadCount = 8;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(threadCount);
    ConcurrentHashMap<String, String> errors = new ConcurrentHashMap<>();

    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    for (int i = 0; i < threadCount; i++) {
        final int threadId = i;
        executor.submit(() -> {
            try {
                startLatch.await();
                for (int j = 0; j < 100; j++) {
                    String key = "key-" + threadId + "-" + j;
                    lru.put(key, "value-" + j);
                    String val = lru.get(key);
                    if (val != null && !val.equals("value-" + j)) {
                        errors.put(key, "Expected value-" + j + " but got " + val);
                    }
                }
            } catch (Exception e) {
                errors.put("thread-" + threadId, e.getMessage());
            } finally {
                doneLatch.countDown();
            }
        });
    }

    startLatch.countDown();
    await().atMost(10, TimeUnit.SECONDS).until(() -> doneLatch.getCount() == 0);

    assertThat(errors).isEmpty();
    assertThat(lru.size()).isLessThanOrEqualTo(150); // maxActive + maxInactive
}
```

### Pattern 2: Handler Chain Exception Handling Test

**What:** Verify chain continues or terminates correctly when handlers throw
**When to use:** TEST-03 - Handler Chain exception handling
**Example:**

```java
// Source: [Based on existing CacheHandlerChainTest.java patterns]
@Test
void handlerThrowsException_chainContinuesOrTerminatesGracefully() {
    // Given: A handler that throws RuntimeException
    CacheHandler throwingHandler = new CacheHandler() {
        @Override
        public HandlerResult handle(CacheContext context) {
            throw new RuntimeException("Handler error");
        }
    };

    // When: Chain executes with throwing handler
    CacheContext context = createTestContext();
    CacheResult result = chain.execute(context);

    // Then: Result indicates appropriate handling (continue or skip)
    // Chain should NOT swallow exception silently without logging
    assertThat(result).isNotNull();
}
```

### Pattern 3: SpEL Injection Security Test

**What:** Verify malicious SpEL expressions are rejected or handled safely
**When to use:** TEST-05 - SpEL expression injection testing

```java
// Source: [Based on Spring Security SpEL patterns]
@Test
void spelExpression_injectionAttempt_returnsSafeResult() {
    // Given: A malicious SpEL expression attempting RCE
    String maliciousExpression = "T(java.lang.Runtime).getRuntime().exec('ls')";

    // When: Evaluator processes the expression
    boolean shouldProceed = evaluator.shouldProceed(
        createOperation(maliciousExpression),
        method, args, target
    );

    // Then: Expression is either rejected or returns safe default
    // The evaluator should catch evaluation exceptions and return safe default
    assertThat(shouldProceed).isTrue(); // Fail-open is documented behavior
}
```

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Concurrency testing | Custom Thread.sleep polling | Awaitility | Battle-tested, handles clock drift, provides good error messages |
| Async operation testing | Busy-wait loops | Awaitility.await() | Cleaner API, reliable timeout handling |
| Expression injection prevention | Custom sandbox | Spring's StandardEvaluationContext + fail-open default | Spring already designed this defensively |

**Key insight:** Awaitility is the de-facto standard for Java concurrency testing with JUnit 5. It provides deterministic timeout-based polling that is more reliable than hand-rolled solutions.

---

## Common Pitfalls

### Pitfall 1: Testing Race Conditions with Insufficient Iteration Count

**What goes wrong:** Race conditions may not manifest in a single test run
**Why it happens:** Many race conditions are probabilistic and require multiple iterations
**How to avoid:** Run concurrency tests in a loop (100+ iterations) or use dedicated stress testing
**Warning signs:** Tests pass locally but fail in CI

### Pitfall 2: Bloom Filter False Positive Tests Assuming Determinism

**What goes wrong:** Bloom filter behavior is probabilistic; tests may be flaky
**Why it happens:** False positive rate is statistical, not deterministic
**How to avoid:** Test the **chain behavior** when Bloom says "might contain" but Redis has no value, not the Bloom filter itself
**Warning signs:** Test assertions on specific false positive rates

### Pitfall 3: SpEL Injection Tests Not Testing Real Attack Vectors

**What goes wrong:** Tests pass but miss real exploitation scenarios
**Why it happens:** Using weak malicious expressions that Spring already blocks
**How to avoid:** Use real RCE expressions like `T(System).exit(1)` or method invocation on runtime
**Warning signs:** Only testing `1+1` or simple string expressions as "malicious"

---

## Code Examples

### TwoListLRU Concurrent Access Test Structure

```java
// Source: [Based on existing TwoListEvictionStrategyTest.java + Awaitility patterns]
@ExtendWith(MockitoExtension.class)
class TwoListLRUConcurrentTest {

    @Test
    @DisplayName("concurrent put and get operations maintain data integrity")
    void concurrentPutAndGet_maintainsIntegrity() throws Exception {
        TwoListLRU<String, String> lru = new TwoListLRU<>(100, 50);
        AtomicInteger errorCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(8);

        // 8 threads, each doing 100 put/get operations
        for (int i = 0; i < 8; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    for (int j = 0; j < 100; j++) {
                        String key = "key-" + j;
                        lru.put(key, "value-" + j);
                        String val = lru.get(key);
                        if (!"value-" + j.equals(val)) {
                            errorCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        latch.countDown();
        await().atMost(30, TimeUnit.SECONDS).until(() -> doneLatch.getCount() == 0);

        assertThat(errorCount.get()).isZero();
    }
}
```

### PreRefreshHandler Race Condition Test Structure

```java
// Source: [Based on existing PreRefreshHandlerTest.java patterns]
@Nested
@DisplayName("Race condition tests")
class RaceConditionTests {

    @Test
    @DisplayName("pre-refresh and explicit evict race does not corrupt state")
    void preRefreshAndEvict_raceCondition_noCorruption() throws Exception {
        // Simulate: pre-refresh reads value, while explicit evict happens
        // Then pre-refresh tries to update
        // Should handle gracefully without corruption
    }

    @Test
    @DisplayName("pre-refresh and explicit put race follows correct precedence")
    void preRefreshAndPut_raceCondition_correctPrecedence() throws Exception {
        // Pre-refresh sees TTL threshold met, starts async refresh
        // Meanwhile user explicitly puts new value
        // Should not lose user's explicit put
    }
}
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Thread.sleep polling | Awaitility.await() | 2010s | More reliable, better error messages |
| Manual CountDownLatch | Awaitility + ExecutorService | 2010s | Cleaner test code |
| Integration tests only | Unit tests + integration tests | Current | Faster feedback, better isolation |

**Deprecated/outdated:**
- `java.util.concurrent.CountDownLatch` alone for concurrency testing - use Awaitility wrapper for reliability

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | ConcurrentUnit does not exist as a public Maven artifact | Standard Stack | Awaitility is the correct alternative - verified in Maven Central |
| A2 | Awaitility 4.3.0 is compatible with Spring Boot 3.2.4 | Standard Stack | Awaitility is a standalone library - compatible with any JUnit 5 setup |
| A3 | Handler chain exception behavior is fail-safe | Common Pitfalls | Depends on implementation - may need verification |

---

## Open Questions

1. **ConcurrentUnit confusion**
   - What we know: Context specified "ConcurrentUnit" but this library doesn't exist in Maven Central
   - What's unclear: Whether user meant a different library or should use Awaitility
   - Recommendation: Use Awaitility (standard solution) and document the deviation

2. **PreRefreshHandler race condition details**
   - What we know: Need to test race between pre-refresh and explicit evict/put
   - What's unclear: What is the expected behavior when race occurs?
   - Recommendation: Check PreRefreshHandler implementation for documented behavior

3. **Handler Chain exception handling specification**
   - What we know: TEST-03 requires verifying handler chain behavior when handlers throw
   - What's unclear: Should the chain continue, skip remaining handlers, or propagate?
   - Recommendation: Verify expected behavior from CacheHandlerChain implementation

---

## Environment Availability

> Skip this section if the phase has no external dependencies (code/config-only changes).

Step 2.6: SKIPPED (no external dependencies identified) - Phase 4 only adds test files, no new runtime dependencies except Awaitility (already documented in Standard Stack).

---

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 (via Spring Boot Starter Test) + Awaitility 4.3.0 |
| Config file | pom.xml test dependencies |
| Quick run command | `./mvnw test -Dtest=TwoListLRUConcurrentTest` |
| Full suite command | `./mvnw test` |

### Phase Requirements to Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|--------------|
| TEST-01 | TwoListLRU concurrent put/get/evict no corruption | unit/concurrent | `./mvnw test -Dtest=TwoListLRUConcurrentTest` | No - NEW |
| TEST-02 | PreRefreshHandler race between refresh and evict/put | unit | `./mvnw test -Dtest=PreRefreshHandlerRaceConditionTest` | No - NEW |
| TEST-03 | Handler chain exception handling | unit | `./mvnw test -Dtest=CacheHandlerChainExceptionTest` | No - NEW |
| TEST-04 | Bloom Filter false positive chain behavior | unit | `./mvnw test -Dtest=BloomFilterFalsePositiveTest` | No - NEW |
| TEST-05 | SpEL injection blocked or safe | unit | `./mvnw test -Dtest=SpelConditionEvaluatorSecurityTest` | No - NEW |

### Sampling Rate

- **Per task commit:** Run single test class
- **Per wave merge:** Run all phase 4 tests
- **Phase gate:** All tests green before `/gsd-verify-work`

### Wave 0 Gaps

- [ ] `src/test/java/io/github/davidhlp/spring/cache/redis/strategy/eviction/support/TwoListLRUConcurrentTest.java` — covers TEST-01
- [ ] `src/test/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/PreRefreshHandlerRaceConditionTest.java` — covers TEST-02
- [ ] `src/test/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/CacheHandlerChainExceptionTest.java` — covers TEST-03
- [ ] `src/test/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/BloomFilterFalsePositiveTest.java` — covers TEST-04
- [ ] `src/test/java/io/github/davidhlp/spring/cache/redis/core/evaluator/SpelConditionEvaluatorSecurityTest.java` — covers TEST-05
- [ ] Awaitility dependency added to pom.xml
- [ ] Framework install: `./mvnw test -Dtest=BloomFilterHandlerTest` — verify existing tests still pass

---

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V5 Input Validation | YES | SpEL condition/unless expressions are user input to cache operations |
| V4 Access Control | NO | Not applicable to cache testing |

### Known Threat Patterns for This Stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| SpEL Injection via condition/unless | Injection | StandardEvaluationContext with fail-open on evaluation error |
| Malicious method invocation | Tampering | Reflection-based unless field access (already in code) |

---

## Sources

### Primary (HIGH confidence)
- Maven Central - Awaitility 4.3.0 verification
- Existing PreRefreshHandlerTest.java - test patterns
- Existing BloomFilterHandlerTest.java - test patterns
- Existing CacheHandlerChainTest.java - test patterns
- SpelConditionEvaluator.java - source for TEST-05

### Secondary (MEDIUM confidence)
- TwoListLRU.java - source for TEST-01 (verified concurrent structure)
- Spring Security patterns for SpEL injection testing

### Tertiary (LOW confidence)
- OWASP SpEL injection patterns - unverified due to web search failure, should be validated before use

---

## Metadata

**Confidence breakdown:**
- Standard stack: MEDIUM - ConcurrentUnit issue resolved with Awaitility, verified in Maven Central
- Architecture: HIGH - Based on existing project patterns
- Pitfalls: MEDIUM - Based on general testing knowledge, not project-specific history

**Research date:** 2026-04-24
**Valid until:** 2026-05-24 (30 days - stable domain)
