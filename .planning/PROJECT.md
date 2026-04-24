# ResiCache Project

**Project:** ResiCache - Spring Cache Redis Implementation
**Code:** RESI
**Type:** Java Library / Spring Boot Starter

## Core Value

A high-performance Spring Cache implementation with Redis backend, featuring LRU eviction, bloom filter protection, and pre-refresh capabilities.

## Context

ResiCache provides a production-ready Redis caching solution for Spring applications with:
- TwoListLRU eviction strategy
- Bloom filter for cache penetration protection
- TTL-aware pre-refresh mechanism
- Distributed locking for cache stampede prevention

## Current Milestone: v1.0 缺陷修复

**Goal:** 修复 ResiCache 项目中已识别的所有技术债务、安全考虑、性能瓶颈和测试覆盖空白

**目标缺陷分类:**
- 🐛 技术债务修复 (4项)
- 🔒 安全考虑 (3项)
- ⚡ 性能优化 (4项)
- 🧪 测试覆盖增强 (5项)

## Active Requirements

| ID | Requirement | Phase | Status |
|----|-------------|-------|--------|
| TECH-01 | TwoListLRU 读锁竞争优化 | 1 | pending |
| TECH-02 | ThreadPoolPreRefreshExecutor 清理机制修复 | 1 | pending |
| TECH-03 | PreRefreshHandler TTL 竞态条件修复 | 1 | pending |
| SEC-01 | SpelConditionEvaluator 反射访问重构 | 2 | pending |
| SEC-02 | Bloom Filter Redis 操作添加超时 | 2 | pending |
| SEC-03 | Serializer 包白名单文档完善 | 2 | pending |
| PERF-01 | TwoListLRU 写锁优化 | 3 | pending |
| PERF-02 | Bloom Filter 哈希缓存 | 3 | pending |
| PERF-03 | inFlight Map 自动清理机制 | 3 | pending |
| TEST-01 | TwoListLRU 并发测试 | 4 | pending |
| TEST-02 | PreRefreshHandler 竞态测试 | 4 | pending |
| TEST-03 | Handler Chain 异常处理测试 | 4 | pending |
| TEST-04 | Bloom Filter 假阳性影响测试 | 4 | pending |
| TEST-05 | SpEL 表达式注入测试 | 4 | pending |

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

*Last updated: 2026-04-24*
