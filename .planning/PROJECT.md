# ResiCache Project

**Project:** ResiCache - Spring Cache Redis Implementation
**Code:** RESI
**Type:** Java Library / Spring Boot Starter

## Core Value

A high-performance Spring Cache implementation with Redis backend, featuring LRU eviction, bloom filter protection, and pre-refresh capabilities.

## Context

ResiCache provides a production-ready Redis caching solution for Spring applications with:
- TwoListLRU eviction strategy (with global lock for thread safety)
- Bloom filter for cache penetration protection
- TTL-aware pre-refresh mechanism
- Distributed locking for cache stampede prevention

## Current Milestone: v1.1 (Planning)

**Status:** v1.0 缺陷修复已完成，下一里程碑规划中

## Validated Requirements (v1.0)

| ID | Requirement | Phase | Completed |
|----|-------------|-------|----------|
| TECH-01 | TwoListLRU 读锁竞争优化 | 1 | v1.0 |
| TECH-02 | ThreadPoolPreRefreshExecutor 清理机制修复 | 1 | v1.0 |
| TECH-03 | PreRefreshHandler TTL 竞态条件修复 | 1 | v1.0 |
| TECH-04 | RedisProCacheWriter.getChain() DCL 简化 | 1 | v1.0 |
| SEC-01 | SpelConditionEvaluator 反射访问重构 | 2 | v1.0 |
| SEC-02 | Bloom Filter Redis 操作添加超时 | 2 | v1.0 |
| SEC-03 | Serializer 包白名单文档完善 | 2 | v1.0 |
| PERF-01 | TwoListLRU 写锁优化 | 3 | v1.0 |
| PERF-02 | Bloom Filter 哈希缓存 | 3 | v1.0 |
| PERF-03 | inFlight Map 自动清理机制 | 3 | v1.0 |
| TEST-01 | TwoListLRU 并发访问测试 | 4 | v1.0 |
| TEST-02 | PreRefreshHandler 竞态条件测试 | 4 | v1.0 |
| TEST-03 | Handler Chain 异常处理测试 | 4 | v1.0 |
| TEST-04 | Bloom Filter 假阳性影响测试 | 4 | v1.0 |
| TEST-05 | SpEL 表达式注入测试 | 4 | v1.0 |

## Key Decisions

| Decision | Rationale | Status |
|----------|-----------|--------|
| TwoListLRU 使用全局锁替代 striped lock | 并发正确性优先于性能 | ✅ Good |
| AtomicInteger 用于无锁大小读取 | 减少读锁竞争 | ✅ Good |
| PreRefreshHandler TTL guard | 防止刷新已过期数据 | ✅ Good |

## Technical Debt

- TwoListLRU 从 striped lock 降级为全局锁，并发性能有所下降

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd-complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---

*Last updated: 2026-04-25 after v1.0 milestone*
