# Phase 3: 性能优化 - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-24
**Phase:** 3-performance-optimization
**Areas discussed:** PERF-01 (TwoListLRU put 写锁优化), PERF-02 (Bloom Filter 哈希缓存), PERF-03 (inFlight Map 清理机制)

---

## PERF-01: TwoListLRU put() 写锁优化

| Option | Description | Selected |
|--------|-------------|----------|
| StripedReadWriteLock 分段锁 | 将 key 空间分片，每个分区独立加锁，降低竞争 | ✓ |
| 无锁数据结构 | 使用 ConcurrentLinkedQueue 等完全无锁结构 | |
| 乐观锁 (CAS) | 使用 AtomicReference 更新链表节点 | |
| 读写分离 | Active/Inactive 操作使用不同锁 | |

**User's choice:** StripedReadWriteLock 分段锁 (recommended default)
**Notes:** 最小改动，最大并发收益，与现有 AtomicInteger 读优化兼容

---

## PERF-02: Bloom Filter 哈希缓存

| Option | Description | Selected |
|--------|-------------|----------|
| ConcurrentHashMap + LRU 限制 | 本地缓存，使用 LRU 策略限制大小 | ✓ |
| Caffeine Cache | 使用专业缓存库，支持 TTL/LRU | |
| Guava Cache | 类似 Caffeine，Google 出品 | |

**User's choice:** ConcurrentHashMap + LRU 限制 (recommended default)
**Notes:** 避免引入新依赖，现有的 ConcurrentHashMap 已能满足需求

---

## PERF-03: inFlight Map 清理机制

| Option | Description | Selected |
|--------|-------------|----------|
| 沿用 ScheduledExecutorService cleanup | Phase 1 已实现，定期清理足够 | ✓ |
| WeakHashMap 替代 | 使用弱引用让 GC 自动清理 | |
| 定时任务清理 | 添加额外的 ScheduledTask | |

**User's choice:** 沿用 ScheduledExecutorService cleanup (recommended default)
**Notes:** Phase 1 已添加 cleanupScheduler，无需额外改动

---

## Claude's Discretion

- StripedReadWriteLock 分片数：建议 16 或 32，根据并发压测调整
- 哈希缓存 LRU 上限：10000 entry/缓存，可配置化
- 哈希缓存 key 设计：使用 cacheName::key 组合避免跨缓存冲突

---

## Deferred Ideas

None — discussion stayed within phase scope
