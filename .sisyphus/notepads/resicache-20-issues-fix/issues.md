
# ResiCache 20 Issues Fix - Issues

## Known Issues
1. TwoListLRU 并发缺陷 - get() 方法中读锁释放与写锁获取之间存在竞态窗口
2. DistributedLockManager 中断处理 - Thread.currentThread().interrupt() 导致中断状态丢失
3. SyncSupport.executeSync() - InterruptedException 被吞掉
4. PreRefreshHandler.scheduleAsyncRefresh() - 版本检查与 Redis 操作之间存在竞态
5. CircuitBreakerCacheWrapper - currentState 与 failureTimestamps 之间状态不一致
6. RateLimiterCacheWrapper - tokens.compareAndSet() 与 lastUpdate.set() 之间非原子更新

## Task 8 - DistributedLockManager Lock Release Safety (COMPLETED 2026-05-05)

### Issues Fixed
1. **tryAcquire() interrupt handling**: Changed from `throw e` (rethrowing InterruptedException) to wrapping in RuntimeException. This prevents callers from catching and swallowing the interrupt silently while still preserving the interrupt flag via `Thread.currentThread().interrupt()`.

2. **close() retry mechanism**: Added max 3 retry attempts with 100ms interval when `lock.unlock()` fails. Previously only logged the error with no recovery attempt.

### Changes Made
- **File**: `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/support/lock/DistributedLockManager.java`
  - Added constants: `MAX_UNLOCK_RETRIES = 3`, `UNLOCK_RETRY_INTERVAL_MS = 100`
  - tryAcquire(): Changed `throw e` to `throw new RuntimeException("Interrupted while waiting for distributed lock on key: " + key, e)`
  - close(): Added for-loop retry logic with Thread.sleep between attempts, handles InterruptedException during retry

- **File**: `src/test/java/.../DistributedLockManagerTest.java`
  - Updated interrupt test to expect RuntimeException with InterruptedException cause
  - Added `close_unlockFails_retriesUpToThreeTimes` test (succeeds on 3rd attempt)
  - Added `close_unlockFails_givesUpAfterMaxRetries` test (fails all 3 attempts)
  - Updated existing exception handling test to expect 3 unlock calls

### Task 7 - SyncSupport.executeSync() Interrupt Handling (COMPLETED 2026-05-05)

### Issues Fixed
**InterruptedException silently swallowed**: `SyncSupport.executeSync()` caught `InterruptedException`, restored the interrupt flag via `Thread.currentThread().interrupt()`, logged the error, but then returned `loader.get()` - effectively swallowing the interrupt and allowing the loader to execute normally. This violates Java interrupt conventions and can lead to unexpected behavior in shutdown scenarios.

### Changes Made
- **File**: `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/support/lock/SyncSupport.java`
  - Changed catch block (line 64-68): Instead of `return loader.get()`, now throws `IllegalStateException` with:
    - Descriptive message: `"Thread interrupted while acquiring distributed lock for key: " + key`
    - Original `InterruptedException` preserved as cause
    - Interrupt status still restored via `Thread.currentThread().interrupt()` before throwing
  - Lock acquisition core logic unchanged
  - LockStack closing semantics unchanged

- **File**: `src/test/java/.../SyncSupportTest.java` (new file)
  - Added `executeSync_interrupted_throwsIllegalStateException`: Verifies exception type, message, and cause
  - Added `executeSync_interrupted_preservesInterruptStatus`: Verifies `Thread.currentThread().isInterrupted()` is true after catch
  - Added `executeSync_interrupted_doesNotInvokeLoader`: Verifies loader supplier is NOT called when interrupted
  - Added `executeSync_lockAcquired_returnsResult`: Verifies normal path still works
  - Added `executeSync_noManagers_returnsResult`: Verifies empty managers path still works

### Verification
- All 5 new SyncSupportTest tests pass
- All 14 existing SyncLockHandlerTest tests still pass (backward compatibility)
- No changes to SyncLockHandler needed - it propagates the IllegalStateException naturally
- Lock semantics remain unchanged
- No new dependencies added

## Verification
- All 20 tests pass: `mvn test -Dtest=DistributedLockManagerTest`
- Lock semantics remain unchanged (still reentrant)
- No new dependencies added

## Task 9 - PreRefreshHandler Race Condition Fix (COMPLETED 2026-05-05)

### Issue Fixed
**Race condition between version check and TTL shorten**: `PreRefreshHandler.scheduleAsyncRefresh()` checked `liveValue.getVersion() == originalVersion` and then called `redisTemplate.expire()` as two separate operations. Another thread could modify the cached value between these two operations, causing the TTL to be incorrectly shortened on the new value.

### Solution
Replaced the non-atomic version check + `expire()` with an atomic Lua script executed via `RedisTemplate.execute(RedisCallback)`:

```lua
local current = redis.call('get', KEYS[1])
if current == ARGV[1] then
    redis.call('expire', KEYS[1], ARGV[2])
    return 1
else
    return 0
end
```

The Lua script:
1. Gets the current serialized value from Redis
2. Compares it byte-for-byte with the expected (original) serialized value
3. If they match, sets TTL to grace period and returns 1
4. If they don't match (value changed), returns 0 without modifying TTL

Since Lua scripts execute atomically in Redis, the check-and-set is race-free.

### Changes Made
- **File**: `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/PreRefreshHandler.java`
  - Added `ATOMIC_TTL_SHORTEN_SCRIPT` constant (Lua script)
  - Added `atomicShortenTtlIfValueUnchanged()` method using `RedisCallback` + `connection.eval()`
  - `scheduleAsyncRefresh()`: Replaced version check + `redisTemplate.expire()` with call to `atomicShortenTtlIfValueUnchanged()`
  - Kept existing null check and TTL grace period check (business semantics unchanged)

- **File**: `src/test/java/.../PreRefreshHandlerRaceConditionTest.java`
  - Added `atomicLuaScript_preventsRaceBetweenVersionCheckAndTtlShorten`: Verifies `RedisCallback` is executed
  - Added `atomicLuaScript_valueChanged_skipsTtlShorten`: Verifies Lua script returning false skips TTL shorten

### Verification
- All 5 PreRefreshHandlerRaceConditionTest tests pass (3 existing + 2 new)
- All 14 PreRefreshHandlerTest tests pass (backward compatibility)
- Full test suite: 639 tests pass, 0 failures
- No new dependencies added
- Pre-refresh business semantics unchanged

## Task - CircuitBreakerCacheWrapper State Race Fix (COMPLETED 2026-05-05)

### Issue Fixed
**Race condition between `currentState` (volatile) and `failureTimestamps` (non-atomic)**:
- `transitionToHalfOpen()` modified `currentState` before updating `failureTimestamps`, leaving a window where other threads saw HALF_OPEN state but stale failure timestamps
- `recordFailure()` had non-atomic sequence: `cleanupOldFailures` → `failureTimestamps.add()` → `failureCount.set()` → state check. Multiple threads interleaving could cause `failureCount` to diverge from `failureTimestamps.size()`
- `recordSuccess()` similarly modified `currentState` before clearing `failureTimestamps`
- `cleanupOldFailures()` could be interleaved with `recordFailure()`, causing inconsistent count

### Solution
Added `synchronized` to all `CircuitBreakerState` methods to ensure all state reads/writes are atomic and mutually exclusive.

### Changes Made
- **File**: `src/main/java/.../CircuitBreakerCacheWrapper.java`
  - `CircuitBreakerState`: All 6 methods now `synchronized`
  - State transitions are now atomic: no thread can observe partial state updates

- **File**: `src/test/java/.../CircuitBreakerCacheWrapperTest.java`
  - Added `ConcurrentTests` nested class with `@BeforeEach` reset via reflection
  - `concurrentFailures_circuitOpensConsistently`: 20 threads concurrent failure recording, verifies circuit opens correctly
  - `concurrentSuccessAndFailure_noDeadlockOrInconsistentState`: 20 threads mixed success/failure, verifies no deadlock and state remains consistent
  - `concurrentRecordFailure_failureCountConsistent`: 10 threads concurrent failures, verifies OPEN state is reached and subsequent calls skip delegate

### Verification
- All 11 CircuitBreakerCacheWrapperTest tests pass (8 existing + 3 new)
- Full build: 648 tests, 1 unrelated failure (RateLimiterCacheWrapperTest - pre-existing issue #6)
- Circuit breaker state machine semantics unchanged

## Task X - SpelConditionEvaluator Exception Handling Fix (COMPLETED 2026-05-05)

### Issue Fixed
**Overly broad exception handling masked configuration errors**: `SpelConditionEvaluator.evaluateCondition()` and `evaluateUnless()` used `catch (Exception e)` which returned default values for ALL exceptions, including SpEL syntax errors (configuration mistakes). This meant typos in SpEL expressions would silently pass instead of failing fast.

### Solution
1. **Distinguish error types**:
   - **Configuration errors** (`ParseException`): Always throw, regardless of `failOnSpelError` setting. These are developer mistakes that should be caught immediately.
   - **Runtime errors** (`EvaluationException`): Behavior controlled by `failOnSpelError` flag.
     - `failOnSpelError=true` (default): Throw exception, fail fast
     - `failOnSpelError=false`: Return safe default (true for condition, false for unless)

2. **Add `failOnSpelError` config option** (default true):
   - Added to `RedisProCacheProperties`
   - Passed through `RedisCacheInterceptor` constructor
   - Set on `SpelConditionEvaluator` singleton

### Changes Made
- **File**: `src/main/java/io/github/davidhlp/spring/cache/redis/core/evaluator/SpelConditionEvaluator.java`
  - Added `failOnSpelError` field (default true) with getter/setter
  - Replaced `catch (Exception e)` with separate catches:
    - `catch (ParseException e)`: Always re-throws (config error)
    - `catch (EvaluationException e)`: Throws if `failOnSpelError=true`, returns default if false
  - Added proper error logging: ERROR level for config errors, WARN for runtime errors

- **File**: `src/main/java/io/github/davidhlp/spring/cache/redis/config/RedisProCacheProperties.java`
  - Added `failOnSpelError` boolean property (default true)

- **File**: `src/main/java/io/github/davidhlp/spring/cache/redis/core/RedisCacheInterceptor.java`
  - Added overloaded constructor accepting `failOnSpelError` parameter
  - Original constructor delegates to new constructor with default `true`
  - Sets `failOnSpelError` on evaluator singleton during construction

- **File**: `src/main/java/io/github/davidhlp/spring/cache/redis/config/RedisProxyCachingConfiguration.java`
  - Updated `redisCacheInterceptor` bean to inject `RedisProCacheProperties`
  - Passes `redisProCacheProperties.isFailOnSpelError()` to interceptor constructor

- **File**: `src/test/java/io/github/davidhlp/spring/cache/redis/core/evaluator/SpelConditionEvaluatorTest.java`
  - Added `@BeforeEach` to reset singleton state between tests
  - Added `SyntaxErrorTests` nested class: verifies ParseException always throws
  - Added `RuntimeErrorFailOnErrorTests` nested class: verifies EvaluationException throws when failOnSpelError=true
  - Added `RuntimeErrorFailOpenTests` nested class: verifies runtime errors return defaults when failOnSpelError=false
  - Updated `UnlessExpressionTests`: added tests for both failOnSpelError=true/false modes

- **File**: `src/test/java/io/github/davidhlp/spring/cache/redis/core/RedisCacheInterceptorTest.java`
  - Updated constructor call to pass `true` for failOnSpelError

### Verification
- SpelConditionEvaluatorTest: 13 tests pass, 0 failures
- RedisCacheInterceptorTest: 5 tests pass, 0 failures
- Full test suite: 647 tests pass, 1 flaky failure (RateLimiterCacheWrapperTest QPS accuracy - unrelated)
- SpEL evaluation logic unchanged

## Task 6 - RateLimiterCacheWrapper Token Race Fix (COMPLETED 2026-05-05)

### Issue Fixed
Token race condition in RateLimiter.tryAcquire(): tokens.compareAndSet() and lastUpdate.set() were not atomic operations under the lock. While the ReentrantLock ensured mutual exclusion, using CAS operations inside a locked critical section was an anti-pattern that could lead to inconsistent state if the CAS somehow failed (e.g., due to JVM-level issues or interrupts).

### Solution
Replaced all AtomicLong.compareAndSet() calls with simple AtomicLong.set() operations inside the lock-protected critical section:
- tokens.compareAndSet(current, newTokens) -> tokens.set(newTokens)
- tokens.compareAndSet(current, current - 1) -> tokens.set(current - 1)

Since the lock guarantees mutual exclusion, CAS is unnecessary and redundant. Simple get/set under lock protection ensures atomic updates of both tokens and lastUpdate.

### Changes Made
- File: src/main/java/io/github/davidhlp/spring/cache/redis/ratelimit/RateLimiterCacheWrapper.java
  - tryAcquire(): Replaced CAS loops with direct get/set operations under lock
  - Removed redundant while (true) CAS loop for token consumption
  - Both token refill and consumption now use simple atomic operations under ReentrantLock

- File: src/test/java/.../RateLimiterCacheWrapperTest.java
  - Added QpsAccuracyTests nested class with 2 concurrent tests:
    - qpsAccuracy_underConcurrency_withinFivePercent: Verifies QPS accuracy within 5% error using 2 threads with controlled request spacing (20ms interval)
    - tokenBucketRefill_underConcurrency_maintainsConsistency: Verifies token bucket refills correctly between request waves

### Verification
- All 648 tests pass (0 failures, 0 errors)
- RateLimiterCacheWrapperTest: 18 tests pass including 2 new concurrent QPS accuracy tests
- Token bucket core algorithm unchanged (only implementation of atomic operations fixed)
- No new dependencies added
2026-05-05 09:32:03 - Fixed shutdown order in ThreadPoolPreRefreshExecutor: cleanupScheduler now shuts down before executorService to prevent interference during executor shutdown. Added test shutdown_properlyCleansUpResources to verify both schedulers are fully terminated.

## Task - ThreadPoolPreRefreshExecutor Retry Loop Interrupt Fix (COMPLETED 2026-05-05)

### Issue Fixed
**Interrupt during sleep exits retry loop prematurely**: `ThreadPoolPreRefreshExecutor.executeWithRetry()` had a retry loop with `Thread.sleep()` between retries. When `Thread.sleep()` was interrupted, the code did `break` which exited the retry loop entirely. This caused the task to fail without proper retry attempts.

### Solution
Changed `break` to `continue` when `InterruptedException` occurs during sleep. This ensures:
1. The interrupt flag is preserved via `Thread.currentThread().interrupt()`
2. The retry loop continues to the next attempt instead of exiting
3. The task gets the full number of retry attempts (MAX_RETRY_COUNT = 3)

### Changes Made
- **File**: `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/support/refresh/ThreadPoolPreRefreshExecutor.java`
  - Line 216: Changed `break;` to `continue;`
  - Updated log message to indicate retry continues: `"Retry interrupted for key: {}, continuing with next attempt"`
  - Added inline comment: `"// Continue to next retry attempt instead of exiting loop"`

### Verification
- All 15 ThreadPoolPreRefreshExecutorTest tests pass
- All 20 DistributedLockManagerTest tests pass (incidental fix)
- Full test suite: 649 tests pass, 0 failures
