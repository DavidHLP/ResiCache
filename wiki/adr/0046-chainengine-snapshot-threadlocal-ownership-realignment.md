---
title: "ADR-0046: ChainEngine chainSnapshotRef 删除 + ThreadLocal 快照 + CacheHandlerChain 全收口"
type: adr
status: accepted
date: 2026-07-04
deciders: DavidHLP
related:
  - ADR-0022
  - ADR-0042
tags:
  - single-source-of-truth
  - threadlocal
  - facade-ownership
  - deletion-test
---

# ADR-0046: ChainEngine chainSnapshotRef 删除 + ThreadLocal 快照 + CacheHandlerChain 全收口(round 33)

## 状态

Accepted — 2026-07-04。

## 背景

`/improve-codebase-architecture` round 33 评审识别 5 个候选。**C5(Speculative)**:
快照委派 seam 在两个变体间自洽度不一致 ——
- 变体 A:`CacheHandlerChain` 模式(外部 seam 拍快照,Engine 持 `AtomicReference`)
- 变体 B:`AnnotationChainEngine` 模式(handler list 运行期不变,完全自治)

通读 `chain/ChainEngine.java`(284 行)+ `chain/CacheHandlerChain.java`(150 行)+
`chain/CacheHandlerChainFactory.java`(301 行)+ `ChainEngineTest.java`(385 行)+ `CacheHandlerChainTest.java`(386 行)
后裁决。

### 路径分叉

- **路径 Y**:`ChainEngine` 完全自治,下沉 chain list 所有权 → 改 Engine 构造语义、改 ChainObserver 接口、
  改所有装配测试 → 触面太广,失去 facade 分层语义
- **路径 X**:`CacheHandlerChain` 全收口,删 `setChainSnapshot` / `chainSnapshotRef`,
  Engine 改构造注入 List + ThreadLocal 隐式快照 → 触面小,与 ADR-0022「链单一真理源」同向

**彻底扼杀路径 Y,采用路径 X**。

## 决策

### 落地

- **删除** `chain/ChainEngine.chainSnapshotRef` 字段(`AtomicReference<List<CacheHandler>>`)
- **删除** `chain/ChainEngine.setChainSnapshot(...)` 方法
- **新增** `chain/ChainEngine.CURRENT_SNAPSHOT` 静态 `ThreadLocal<List<CacheHandler>>` 字段
- **改签名** `ChainEngine.execute(CacheContext)` → `execute(List<CacheHandler> snapshot, CacheContext)`:
  entry 处 `CURRENT_SNAPSHOT.set(snapshot)`,finally 块 `CURRENT_SNAPSHOT.remove()`
- **改实现** `ChainEngine.executeChainFragment(...)`:
  `chainSnapshotRef.get()` → `CURRENT_SNAPSHOT.get()`(ThreadLocal 隐式读)
- **新增测试 helper**(package-private):
  - `setCurrentSnapshotForTest(List<CacheHandler>)` —— 测试直接设入 ThreadLocal
  - `clearCurrentSnapshotForTest()` —— 测试清空
- **编辑** `chain/CacheHandlerChain`:
  - 删除 `addHandler` / `clear` 中的 `engine.setChainSnapshot(...)` 调用
  - `execute(CacheContext)` 内部:`synchronized(chainGuard) { snapshot = List.copyOf(handlers); }` →
    `engine.execute(snapshot, context)`
- **编辑** `chain/CacheHandlerChainFactory`:不动(未引用 `setChainSnapshot`,grep 验证 0 hit)

### ThreadLocal vs AtomicReference 收益

| 维度 | AtomicReference(原) | ThreadLocal(新) |
|---|---|---|
| 并发隔离 | 全局唯一快照,addHandler 修改全局 ref,execute 读全局 ref | per-thread 独立快照,execute 之间互不污染 |
| 写入者 | CacheHandlerChain.addHandler/clear(跨线程) | Engine.execute entry(单线程) |
| 锁需求 | CacheHandlerChain 自身已 synchronized;Engine 无锁 | 完全无锁(ThreadLocal 自带 per-thread 隔离) |
| SyncLockHandler.executeFragment 读快照 | OK(同全局 ref) | OK(同线程 CURRENT_SNAPSHOT) |
| 删除测试 | 删 AtomicReference → Engine 无法获得快照,execute 直接 NPE | Engine 仅在 execute(snapshot, ctx) entry 处 set,语义更清晰 |

### 测试影响

- **ChainEngineTest.java**:`installChain(...)` helper 从 `engine.setChainSnapshot(...)` 改为
  `this.snapshot = List.of(handlers)`;所有 `engine.execute(ctx)` 改为 `engine.execute(snapshot, ctx)`;
  新增 `private List<CacheHandler> snapshot` 字段;`executeFragment_skipsAroundChain` 测试改用
  `setCurrentSnapshotForTest`/`clearCurrentSnapshotForTest` helper 绕开 execute 的 aroundChain 钩子
- **CacheHandlerChainTest.java**:零变化(`chain.execute(ctx)` facade 签名不变)
- **CacheHandlerChainExceptionTest.java**:零变化

## 收益

| 维度 | 削减量 |
|---|---|
| ChainEngine SLOC | `chainSnapshotRef` 字段 -1,`setChainSnapshot` 方法 -6(签名+Javadoc) |
| CacheHandlerChain SLOC | `engine.setChainSnapshot(...)` 调用 -2(addHandler/clear 各 1) |
| **净 SLOC** | **-7 + execute 改签名 +2 行 ThreadLocal set/remove** |
| ownership | 链 list 单一真理源完全收敛到 `CacheHandlerChain`,Engine 退化为"接收 snapshot + 推进 + 观测" |
| 锁开销 | `addHandler`/`clear` 不再触发 `setChainSnapshot`(即使原本也是低开销) |
| 内存 | 删除 1 个 `AtomicReference` 字段(每实例) |

## 不变量保留

- `RedisProCacheWriter` → `CacheHandlerChain.execute(ctx)` 签名不变,调用方零感知
- Engine `aroundChain` / `perNode` / post-process 顺序不变
- `executeChainFragment` 签名不变(SyncLockHandler 零修改)
- 失败隔离(observer 异常 try/catch,handler 异常冒泡)不变

## 后续

- 5 个候选全部落地完毕,本轮 round 33 收口
- 下一轮 round 34 等用户从剩余未消化的 friction 中选新目标

## 相关

- [[0022-chain-single-representation-seam]] — 链 list 单一真理源
- [[0042-syncsupport-singleflight-future-and-chain-readlock-removal]] — 上轮 lock 域清理(同 round 思路)