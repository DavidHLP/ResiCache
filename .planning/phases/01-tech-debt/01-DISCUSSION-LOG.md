# Phase 1: 技术债务修复 - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-24
**Phase:** 1-tech-debt
**Areas discussed:** 4 (all auto-selected via --auto mode)

---

## TECH-01: TwoListLRU 读锁优化

| Option | Description | Selected |
|--------|-------------|----------|
| AtomicInteger 计数器 | 无锁读取，避免读锁竞争 | ✓ (recommended) |
| StripedReadWriteLock | 减少锁粒度但仍有开销 | |
| 保留读锁 | 简单但高并发下性能差 | |

**User's choice:** [auto] AtomicInteger 计数器 (recommended default)
**Notes:** 使用推荐方案，JDK AtomicInteger CAS 模式

---

## TECH-02: cleanFinished() 清理机制

| Option | Description | Selected |
|--------|-------------|----------|
| 独立定期清理 | ScheduledExecutorService 定期调用 | ✓ (recommended) |
| getActiveCount() 触发 | 依赖调用时机，可能不及时 | |
| 主动式清理 | 每次 put 时触发清理检查 | |

**User's choice:** [auto] 独立定期清理 (recommended default)
**Notes:** 清理间隔可配置，默认 30 秒

---

## TECH-03: PreRefreshHandler TTL 竞态条件

| Option | Description | Selected |
|--------|-------------|----------|
| 填充前检查 TTL | 执行前验证剩余 TTL | ✓ (recommended) |
| 延长 TTL | 预刷新数据使用更长 TTL | |
| 锁保护 | 使用分布式锁保证一致性 | |

**User's choice:** [auto] 填充前检查 TTL (recommended default)
**Notes:** TTL 即将过期时跳过填充

---

## TECH-04: getChain() DCL 简化

| Option | Description | Selected |
|--------|-------------|----------|
| synchronized 简化 | 替代 volatile + 双 synchronized | ✓ (recommended) |
| holder 模式 | 静态内部类延迟初始化 | |
| Supplier/懒加载 | Java 8+ 懒加载模式 | |

**User's choice:** [auto] synchronized 简化 (recommended default)
**Notes:** 简化易理解，保留线程安全

---

## Claude's Discretion

- AtomicInteger CAS 失败重试策略：无限循环直到成功
- cleanFinished() 线程安全：ConcurrentHashMap 安全迭代

## Deferred Ideas

None
