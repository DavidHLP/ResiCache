# Phase 3: 性能优化 - Context

**Gathered:** 2026-04-24
**Status:** Ready for planning

<domain>
## Phase Boundary

优化 ResiCache 三个关键性能瓶颈：TwoListLRU 写锁竞争、Bloom Filter 哈希重复计算、inFlight Map 内存泄漏风险。
</domain>

<decisions>
## Implementation Decisions

### PERF-01: TwoListLRU put() 写锁优化

- **D-01:** 使用 StripedReadWriteLock 分段锁替代全局单一锁
  - 将 key 空间分片（如 16 或 32 个分区），每个分区独立加锁
  - 降低写锁竞争，提高高并发put场景吞吐量
  - 文件: `TwoListLRU.java` (lines 102-139, 148-161)
  - 原因: 最小改动，最大并发收益，与现有 AtomicInteger 读优化兼容

[auto] PERF-01 — Q: "TwoListLRU put() 写锁优化方式?" → Selected: "StripedReadWriteLock 分段锁" (recommended default)

### PERF-02: Bloom Filter 哈希缓存

- **D-02:** 使用 Caffeine 或 ConcurrentHashMap + TTL 缓存哈希位置
  - key: cacheName + key 组合，value: int[] positions
  - 缓存大小限制：每个 cacheName 最多缓存 10000 个 key 的哈希
  - 使用 LRU 淘汰策略防止内存无限增长
  - 文件: `RedisBloomIFilter.java`
  - 原因: 频繁访问的 key 哈希计算开销显著，缓存收益高

[auto] PERF-02 — Q: "Bloom Filter 哈希缓存策略?" → Selected: "ConcurrentHashMap + LRU 限制" (recommended default)

### PERF-03: inFlight Map 自动清理机制

- **D-03:** 依赖已实现的 ScheduledExecutorService 定期清理
  - Phase 1 已添加 cleanFinished() 定期调用机制
  - 无需额外 WeakHashMap，scheduled cleanup 已足够
  - 文件: `ThreadPoolPreRefreshExecutor.java` (Phase 1 实现)
  - 原因: 避免 WeakHashMap 的 GC 不确定性，scheduled cleanup 更可控

[auto] PERF-03 — Q: "inFlight Map 清理机制?" → Selected: "沿用 ScheduledExecutorService cleanup" (recommended default)

### Claude's Discretion
- StripedReadWriteLock 分片数：建议 16 或 32，根据并发压测调整
- 哈希缓存 LRU 上限：10000 entry/缓存，可配置化
- 哈希缓存 key 设计：使用 cacheName::key 组合避免跨缓存冲突
</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Requirements
- `.planning/REQUIREMENTS.md` § Phase 3 — PERF-01 到 PERF-03 详细描述
- `.planning/codebase/CONCERNS.md` § Performance Bottlenecks — 性能问题的完整分析

### Prior Phase Context
- `.planning/phases/01-tech-debt/01-CONTEXT.md` — Phase 1 决策（AtomicInteger 读优化、ScheduledExecutorService）
- `.planning/phases/02-safe-hardening/02-CONTEXT.md` — Phase 2 决策（安全相关）

### Relevant Source Files
- `src/main/java/io/github/davidhlp/spring/cache/redis/strategy/eviction/support/TwoListLRU.java` — 写锁优化 (lines 102-139, 148-161)
- `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/support/protect/bloom/filter/RedisBloomIFilter.java` — 哈希缓存
- `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/support/refresh/ThreadPoolPreRefreshExecutor.java` — 清理机制

### Java Coding Standards
- `~/.claude/rules/java/coding-style.md` — 并发编程、原子操作规范
- `~/.claude/rules/java/patterns.md` — ConcurrentHashMap、Caffeine 使用模式
</canonical_refs>

<codebase>
## Existing Code Insights

### Reusable Assets
- `AtomicInteger` — Phase 1 已用于 TwoListLRU size 计数器
- `ScheduledExecutorService` — Phase 1 已添加定期清理
- `ConcurrentHashMap` — RedisBloomIFilter、ThreadPoolPreRefreshExecutor 都在用

### Established Patterns
- `StripedReadWriteLock` — Netty 等高性能库常用模式
- Caffeine Cache — 本地缓存标准库，支持 LRU、TTL、最大容量

### Integration Points
- TwoListLRU: put/get 修改链表，需协调现有 AtomicInteger 计数器
- RedisBloomIFilter: hashStrategy.positionsFor() 是计算热点
- ThreadPoolPreRefreshExecutor: inFlight map 清理依赖 cleanFinished()
</codebase>

<specifics>
## Specific Ideas

- 哈希缓存 key 格式：`cacheName::key` 确保跨缓存隔离
- StripedReadWriteLock 分片数通过构造函数参数化，便于调优
- 哈希缓存大小通过 RedisProCacheProperties 配置化
</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope
</deferred>

---

*Phase: 03-performance-optimization*
*Context gathered: 2026-04-24*
