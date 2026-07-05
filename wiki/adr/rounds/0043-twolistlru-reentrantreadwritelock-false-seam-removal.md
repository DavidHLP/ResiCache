---
title: "ADR-0043: TwoListLRU ReentrantReadWriteLock false seam 删除(降级 ReentrantLock)"
type: adr
status: accepted
date: 2026-07-04
deciders: DavidHLP
related:
  - ADR-0037
tags:
  - false-seam
  - deletion-test
  - eviction
  - lock
  - high-concurrency
---

# ADR-0043: TwoListLRU ReentrantReadWriteLock false seam 删除(降级 ReentrantLock)

## 状态

Accepted — 2026-07-04。

## 背景

`/improve-codebase-architecture` lock 域深扫(round 32,本轮独裁执行)的延伸发现 —— ADR-0037 删除
`readLockForKey`/`writeLockForKey`/`promoteNodeSafe` 三个 wrapper 后,
`TwoListLRU` 内部锁使用模式变得**完全透明**:

```java
// ADR-0037 后残留的直接调用点(全文件 6 lock + 4 unlock)
globalLock.writeLock().lock();   // put
globalLock.writeLock().unlock();
globalLock.writeLock().lock();   // get
globalLock.writeLock().unlock();
globalLock.writeLock().lock();   // remove
globalLock.writeLock().unlock();
globalLock.writeLock().lock();   // clear
globalLock.writeLock().unlock();
```

8 处调用全部取 `writeLock()`,**0 处取 `readLock()`**。全文件 537 行 grep 验证。

这是教科书级 false seam:Ousterhout 定义的 "interface roughly as complex as implementation" +
"deletion test concentrates complexity"。

## 决策

**将 `ReentrantReadWriteLock globalLock` 降级为 `ReentrantLock globalLock`**,
所有调用点 `globalLock.writeLock().{lock,unlock}()` → `globalLock.{lock,unlock}()`。

### 语义不变性论证

| 维度 | ReentrantReadWriteLock.writeLock() | ReentrantLock |
|---|---|---|
| 排他性 | exclusive(只允许单 writer) | exclusive |
| 可重入 | ✓ (RWLock.writeLock 内部用同一个 holdCount) | ✓ |
| Fairness policy | default unfair | default unfair |
| try/finally 释放语义 | ✓ | ✓ |
| 与 `ConcurrentHashMap` 配合 | 同 | 同 |

`ReentrantReadWriteLock` 在从未取 `readLock()` 的情况下与 `ReentrantLock` **完全等价**——
两者在写路径上调用同一个独占 monitor,只是 RWLock 多维护一个空的 read Sync 队列。

### 性能/内存收益

| 维度 | 削减量 |
|---|---|
| 每实例内存 | `ReentrantReadWriteLock` 内部持 2 个 `Sync`(read Sync + write Sync),每个 `Sync` 含 `state` + 双链表头节点;`ReentrantLock` 仅持 1 个 `Sync`。**~50% 内存削减**。 |
| 单次 acquire 开销 | RWLock.writeLock() 走 CAS + state 修改 + 仍维护 readHoldCount 计数(虽不取 readLock 也会自增/恢复 hold counter 的逻辑分支);ReentrantLock 直接 CAS。**CAS 路径略短**。 |
| 接口诚实度 | 字段类型从 "看似可并发读" 变成 "exclusive-only",与实际行为一致 |

### 为什么 `get()` 不能 lock-free 化

直觉上 `get()` 命中 Active 头部时只需读 `node.value`,看似可以走无锁 fast-path。
**但**晋升路径(`node.isActive && activeHead.next == node` → 直接 return;否则 → `promoteNodeUnsafe`)
需要修改双向链表(`prev.next`、`next.prev`),所以**所有 get() 必须持互斥锁**,与 `put`/`remove`/`clear`
在并发模型上完全等价。**无法进一步降级锁**。

### 为什么 `LocalBloomIFilter` 不动

同项目内 `protection/bloom/filter/LocalBloomIFilter` 也用 `ReentrantReadWriteLock`,
但它**真的取两种锁**:
- `mightContain()` → `readLock().lock()`(无锁读 + RWLock 多读并发优势)
- `add()` → `writeLock().lock()`(单写排他)
是真 RWLock 用例,**保留不动**。

## 后果

### 增益

- **内存**:每个 `TwoListLRU` 实例削减 ~50% 的 `Lock` 相关内存(双 Sync 队列 → 单 Sync 队列)。
- **CPU**:写路径 acquire 开销略减(单 CAS,无需 readHoldCount 维护逻辑)。
- **接口诚实度**:字段类型从"看似可并发读"变成"exclusive-only",与实际行为一致 —— Ousterhout 推崇的 deep module 化(复杂实现 / 简单接口,而不是反过来)。
- **wiki 同步**:`eviction.md` line 59 "ReentrantReadWriteLock + ConcurrentHashMap 保证并发安全" 同步更新。

### 代价

- **零行为变化**。
- **零 API 变化**(public 方法签名、字段可见性、嵌套接口全部不变)。
- **零测试变化**(39 个 eviction 测试 byte-equivalent 通过)。

### 不变

- `TwoListLRU` 的 public 契约(`put`/`get`/`remove`/`contains`/`size`/`clear`/`getActiveSize`/`getInactiveSize`/`getTotalEvictions`/`setEvictionCallback`/`setEvictionPredicate`)完全不变。
- 并发模型实质不变:仍是"单一互斥锁保护所有链表操作;`ConcurrentHashMap` 支持无锁查找"。
- `EvictionStats.of(TwoListLRU,...)` 静态工厂、`RedisCacheRegister.operationLru` 等调用方零感知。

## 相关

- [[0037-twolistlru-lock-wrapper-dead-code-and-false-seam-removal]] —— 上轮删 wrapper(本 ADR 删锁字段,同一清理节奏的二轮)
- [[0042-syncsupport-singleflight-future-and-chain-readlock-removal]] —— 同期 round 31 lock 域清理(SyncSupport 单飞 + CacheHandlerChain 读锁删除)
- [[0029-single-adapter-hypothetical-seams-acceptance]] —— hypothetical seam 接受策略(本 ADR 是 deletion test 通过、seam 不该留的范例)