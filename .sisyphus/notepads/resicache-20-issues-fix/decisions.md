# ResiCache 20 Issues Fix - Decisions

## Architecture Decisions
1. TwoListLRU 并发修复: 采用方案 A (移除读锁优化，统一使用写锁) - 更安全且简单
2. PreRefreshHandler 竞态: 使用 Redis 原子操作确保 TTL 缩短的原子性

## Configuration Decisions
1. JaCoCo 阈值: 0.60 (60%)
2. Checkstyle 启用规则: UnusedImports, RedundantImport, EmptyBlock, NeedBraces (保留 MagicNumber 为 ignore)
3. 锁前缀默认: "cache:lock:" (向后兼容)

## Test Strategy
- 使用 JUnit 5 + Mockito + AssertJ
- TDD: 先写测试再修复
- Testcontainers 用于集成测试

## Task - CircuitBreakerCacheWrapper State Race Fix (COMPLETED 2026-05-05)

### Decision
**采用 `synchronized` 方法而非 `AtomicReference`**:
- `synchronized` 更简洁，不需要把状态对象设计成不可变快照
- `CircuitBreakerState` 包含可变集合 `failureTimestamps`（ConcurrentLinkedDeque），用 `AtomicReference` 需要每次修改都深拷贝集合，性能开销大
- 每个缓存名称只有一个 `CircuitBreakerState` 实例，锁竞争范围可控（仅同缓存名的请求竞争）
- `synchronized` 保证同一实例上所有状态操作（读/写/清理）的串行化，彻底消除 `currentState` 与 `failureTimestamps` 之间的可见性窗口

### Implementation Details
- 给 `CircuitBreakerState` 的 6 个方法全部加 `synchronized`:
  - `getCurrentState()`
  - `shouldTryHalfOpen()`
  - `transitionToHalfOpen()`
  - `recordFailure()`
  - `recordSuccess()`
  - `cleanupOldFailures()`
- 保留 `volatile currentState` 和 `AtomicInteger/AtomicLong` 字段（在 synchronized 下冗余但不影响功能，最小化改动）
- 保留 `ConcurrentLinkedDeque`（在 synchronized 下冗余，但无需改动）
