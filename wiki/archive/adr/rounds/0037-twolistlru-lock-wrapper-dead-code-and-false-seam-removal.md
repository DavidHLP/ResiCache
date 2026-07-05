---
title: "ADR-0037: TwoListLRU 锁 wrapper 死代码 + false seam 删除"
type: adr
status: accepted
date: 2026-07-04
deciders: DavidHLP
related:
  - ADR-0030
  - ADR-0010
tags:
  - dead-code-removal
  - false-seam
  - deletion-test
  - eviction
  - round-27
---

# ADR-0037: TwoListLRU 锁 wrapper 死代码 + false seam 删除

## 状态

Accepted — 2026-07-04。

## 背景

`/improve-codebase-architecture` round 27 autocratic one-shot 深扫 `eviction/` 域 ——
这是 ADR-0010 删 `TwoListEvictionStrategy` 之后**再未被任何 ADR 触及**的盲区(27 轮无人扫)。
Explore agent 首扫漏报(只读 excerpts),独裁者亲自通读 `TwoListLRU.java`(564 行全文)后揪出
3 个遗留 wrapper:

```java
// 1. 零调用死代码 —— grep 全项目(main + test)无任何调用点
private ReentrantReadWriteLock.ReadLock readLockForKey() {
    return globalLock.readLock();
}

// 2. 误导命名 false seam —— 方法名暗示 "per-key 锁分段",无参却返回全局锁
private ReentrantReadWriteLock.WriteLock writeLockForKey() {
    return globalLock.writeLock();
}

// 3. 零语义包装 —— 一行转调 + "已持锁" 注释,无额外行为
private void promoteNodeSafe(Node<K, V> node) {
    // Caller holds global lock, no additional locking needed
    promoteNodeUnsafe(node);
}
```

**核实(2026-07-04,基于 working tree)**:

1. **`readLockForKey` 全项目零调用** —— `grep -rn 'readLockForKey' src/` 仅返回 line 102
   定义本身,零调用点。**纯死代码**。
2. **`writeLockForKey` 8 处调用**(put/get/remove/clear 的 lock+unlock 配对),实现仅
   `return globalLock.writeLock();` 一行;方法名 `ForKey` 暗示 per-key 分段锁语义,实际
   无参返回全局锁。**接口(命名)比实现更复杂/更误导** —— Ousterhout 定义的 shallow
   module 反面教材。line 41-46 注释已坦白「全局写锁...保护所有链表操作」,wrapper 与该
   事实矛盾,是早期"打算做 per-key 锁分段"的设计化石(方法名暴露原意),后证实全局锁
   即够却未清理 wrapper。
3. **`promoteNodeSafe` 1 处调用**(get 内),实现仅 `promoteNodeUnsafe(node)` + 注释
   "Caller holds global lock"。"Safe" 命名暗示做了额外加锁保护,实际零额外行为 ——
   消除 "Safe vs Unsafe" 的虚假二分(读者会问:区别是什么?答:无区别)。
4. **未被任何前轮 ADR 触及** —— eviction 域 27 轮盲区,本轮新发现。

## 决策

**删除 3 个 private wrapper + 内联调用点**(byte-equivalent):

- 删 `readLockForKey()`(零调用,纯删)
- 删 `writeLockForKey()`,8 处 `writeLockForKey().{lock,unlock}()` →
  `globalLock.writeLock().{lock,unlock}()`
- 删 `promoteNodeSafe()`,1 处 `promoteNodeSafe(node)` → `promoteNodeUnsafe(node)`

理由:deletion test 干净通过 ——

- **删 `readLockForKey`**:零调用,复杂度直接消失(死代码清理,与
  [[0030-redisprocachewriter-dead-accessors-removal]] 同款)。
- **删 `writeLockForKey`**:消除 false seam。原 wrapper 的接口(命名 `ForKey`)比实现
  (全局锁)更复杂,是「接口谎言」;删后 `globalLock.writeLock().lock()` 让"全局锁"语义
  **诚实可见**,读者不会被 per-key 暗示误导。把误导性 shallow interface 删除,让真实
  语义直接呈现。
- **删 `promoteNodeSafe`**:消除 "Safe vs Unsafe" 虚假二分,直接用 `promoteNodeUnsafe`
  + javadoc 标注「持写锁时调用」。

## 后果

**增益**:

- `TwoListLRU` 接口更诚实:消除 1 个零调用死方法 + 2 个误导命名 wrapper,降低未来维护者
  被 `ForKey`/`Safe` 命名误导的风险(误以为有 per-key 锁分段或额外加锁保护)。
- 文件 564 → 543 行(净 -21 行,含 javadoc)。
- 编译期保证 + 39 个 eviction 单元测试全绿(含 3 个并发测试)的铁证。

**代价**:零(零行为变化、零 API 变化、零测试调整)。

**不变**:

- `TwoListLRU` 的 public 契约(put / get / remove / contains / size / clear /
  getActiveSize / getInactiveSize / getTotalEvictions / setEvictionCallback /
  setEvictionPredicate)完全不变。
- 并发模型完全不变(仍是 `globalLock` 写锁保护所有链表操作;`ReentrantReadWriteLock`
  import 保留,`globalLock` 字段 line 47 在用)。
- `EvictionStats.of(TwoListLRU,...)` 静态工厂、`RedisCacheRegister` 持有
  `operationLru` 等调用方零感知。

## 相关

- [[0030-redisprocachewriter-dead-accessors-removal]] —— 死 protected accessor 删除
  (本 ADR 同款 dead-code + deletion-test 模式)
- [[0010-attributes-projection-and-strategy-deletion]] —— 删 `TwoListEvictionStrategy`
  (本 ADR 附带清理其遗留 stale wiki)
- [[0029-single-adapter-hypothetical-seams-acceptance]] —— hypothetical seam 接受策略
  (本 ADR 不涉及,但同属「是否删接口」的 deletion test 谱系)
