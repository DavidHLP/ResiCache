# Phase 1: 技术债务修复 - Context

**Gathered:** 2026-04-24
**Status:** Ready for planning

<domain>
## Phase Boundary

修复 ResiCache 中的 4 个技术债务问题，确保缓存库在高并发场景下的性能和正确性。
</domain>

<decisions>
## Implementation Decisions

### TECH-01: TwoListLRU 读锁优化
- **D-01:** 使用 AtomicInteger 维护 activeSize 和 inactiveSize 计数器
  - 优点：无锁读取，完全避免读锁竞争
  - put/promote/evict 时使用 CAS 更新计数器
  - 文件: `TwoListLRU.java` (lines 225-246)

### TECH-02: ThreadPoolPreRefreshExecutor 清理机制
- **D-02:** 添加独立的定期清理机制，不依赖 getActiveCount() 调用
  - 使用 ScheduledExecutorService 定期调用 cleanFinished()
  - 清理间隔可配置，默认 30 秒
  - 文件: `ThreadPoolPreRefreshExecutor.java` (lines 262-268)

### TECH-03: PreRefreshHandler TTL 竞态条件
- **D-03:** 填充前检查 TTL，避免覆盖已过期数据
  - 在预刷新任务执行前检查当前缓存值的剩余 TTL
  - 如果 TTL 即将过期（< 刷新提前量），跳过填充
  - 文件: `PreRefreshHandler.java`

### TECH-04: RedisProCacheWriter.getChain() DCL 简化
- **D-04:** 使用 synchronized 替代 volatile + 内层 synchronized
  - 简化后模式：`synchronized` 包裹整个初始化块
  - 保持线程安全且更易理解
  - 文件: `RedisProCacheWriter.java` (lines 303-313)

### Claude's Discretion
- AtomicInteger CAS 失败重试策略：使用无限循环直到成功（JDK 风格）
- 清理任务线程安全：cleanFinished() 需处理 ConcurrentHashMap 迭代的线程安全问题
</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Requirements
- `.planning/REQUIREMENTS.md` § Phase 1 — TECH-01 到 TECH-04 详细描述

### Codebase Analysis
- `.planning/codebase/CONCERNS.md` — 所有技术债务问题的完整分析

### Relevant Source Files
- `src/main/java/io/github/davidhlp/spring/cache/redis/strategy/eviction/support/TwoListLRU.java` — 读锁问题 (lines 225-246)
- `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/support/refresh/ThreadPoolPreRefreshExecutor.java` — 清理机制 (lines 262-268)
- `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/PreRefreshHandler.java` — TTL 竞态
- `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/RedisProCacheWriter.java` — DCL 模式 (lines 303-313)

### Java Coding Standards
- `~/.claude/rules/java/coding-style.md` — AtomicInteger、不可变模式规范
</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `AtomicInteger` / `AtomicLong` — JDK 并发原子类，用于无锁计数器
- `ConcurrentHashMap` — ThreadPoolPreRefreshExecutor 已使用，清理时需注意安全迭代

### Established Patterns
- `ReentrantReadWriteLock` — TwoListLRU 现有锁模式，优化后保留写锁但移除读锁
- `CompletableFuture` — 预刷新任务使用，cleanFinished() 检查 isDone() 状态

### Integration Points
- TwoListLRU: getActiveSize/getInactiveSize 是 public API，部分代码可能依赖其锁行为
- ThreadPoolPreRefreshExecutor: cleanFinished() 改动影响 inFlight Map 的内存管理
- PreRefreshHandler: TTL 检查需访问缓存当前状态，可能需要 CacheContext 扩展
</code_context>

<specifics>
## Specific Ideas

- TwoListLRU 计数器必须线程安全更新，不能有竞争条件下的数据丢失
- cleanFinished() 定期执行但不能阻塞主线程
- PreRefreshHandler TTL 检查应在获取锁之前进行，避免额外锁竞争
</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope
</deferred>

---

*Phase: 01-tech-debt*
*Context gathered: 2026-04-24*
