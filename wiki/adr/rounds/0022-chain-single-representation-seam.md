---
title: "ADR-0022: Chain single-representation seam (消除 next 指针双轨,统一 List 快照 index 推进)"
type: adr
status: accepted
date: 2026-07-01
deciders: DavidHLP
related:
  - ADR-0009
  - ADR-0012
tags:
  - chain
  - deepening
  - shallow-module-removal
  - round-14
---

# ADR-0022: Chain single-representation seam (消除 next 指针双轨,统一 List 快照 index 推进)

## 状态

- **Status**: Accepted
- **Date**: 2026-07-01
- **Deciders**: DavidHLP
- **Related**: ADR-0009 (Chain Engine extraction) / ADR-0012 (shallow module removal)

## 背景

ADR-0009 抽出 `ChainEngine` 把链推进 + 观测收口到单一 seam,但**保留了** ADR-0009 前的 next 指针链(`AbstractCacheHandler.next` / `getNext()` / `setNext()`)+ 新引入的 `List<CacheHandler>` 快照(`ChainEngine.chainSnapshotRef`)。结果同一责任链有**两套并行表示**,带来 4 处 friction:

1. **混合遍历(dead variable)**:`ChainEngine.driveChain` 取 `snapshot.get(0)` 作起点,之后全程 `current = current.getNext()` 推进 —— 传入的 `List<CacheHandler> snapshot` 参数几乎只用了 `get(0)`,自增的 `int idx` 从不被读取(dead code)。
2. **无意义来回转换**:`executeChainFragment(ctx, from)` 的 `buildFragment(from)` 沿 `getNext()` 链重新展开成 `List`,再交给 `driveChain`,后者又只用 `get(0)` + `getNext()` —— 双重表示来回转换。
3. **并发隔离漏洞**:ADR-0009 引入 List 快照声称"避免 Engine 边遍历边被改链",但 `driveChain` 用 `getNext()` 读 handler 实例字段 —— **不受快照隔离保护**;`CacheHandlerChain.addHandler` 改 `next` 时,正在推进的 `driveChain` 仍会读到新值。
4. **三重链状态维护**:`CacheHandlerChain` 同时维护 `List<CacheHandler> handlers` + `volatile CacheHandler head` + 每个节点的 `next` 指针,三者必须保持一致(不变量负担)。

**deletion test(针对 next 指针链)**:删掉 next 指针链(`AbstractCacheHandler.next` + `CacheHandler` 接口的 `getNext`/`setNext` + `CacheHandlerChain.head` + addHandler 的 O(N) setNext 遍历 + `buildFragment`)→ driveChain 改纯 index 推进、executeChainFragment 改 `subList`、CacheHandlerChain 不再维护 head/next —— **复杂度净减,不在 N 处重现**。next 指针链是 ADR-0009 抽 Engine 时遗留的残骸(同款 ADR-0012 删 `EarlyExpirationSupport` 浅转发层)。

## 决策

### D1 — `CacheHandler` 接口删除 `setNext` / `getNext`(执行)

接口只保留 `handle(CacheContext)`。链结构(节点顺序)归属 `CacheHandlerChain` 的 `List<CacheHandler>`,推进归属 `ChainEngine`。handler 不再"知道自己下一个是谁"。

### D2 — `AbstractCacheHandler` 删除 `next` 字段(执行)

`private CacheHandler next` + `getNext()` / `setNext()` 实现全部删除。子类(5 protection handler + ActualCacheHandler)零改动(均不引用 next)。

### D3 — `CacheHandlerChain` 删除 `head` + O(N) setNext 遍历(执行)

- 删 `private volatile CacheHandler head` 字段
- `addHandler(handler)` 退化为 `handlers.add(handler)` + `engine.setChainSnapshot(List.copyOf(handlers))`(删 O(N) "找链尾 + setNext" 循环)
- `execute(ctx)` 删 `head == null` 前置检查(Engine.execute 内部已判 snapshot empty + WARN),仅留读锁 + 委派
- `clear()` 删 `head = null`

链结构单一真理源 = `List<CacheHandler> handlers`。

### D4 — `ChainEngine` driveChain 改 index 推进 + 删 buildFragment(执行)

- `driveChain(snapshot, ctx)` 改纯 `for (int idx = 0; idx < snapshot.size(); idx++)` 推进:删 dead `idx` + `getNext()`,链尾 CONTINUE 由 `idx == snapshot.size() - 1` 判定(等价于原 `getNext() == null`)。**并发隔离修复**:index 推进完全在不可变快照内读取,`addHandler` 改链仅影响下次 `setChainSnapshot`,当前 `execute` 持有的快照引用完全隔离。
- `executeChainFragment(ctx, from)` 改 `snapshot.indexOf(from) + 1` 的 `subList` 定位起点(不再 `buildFragment` 沿 next 重建 List)。语义从"含 from"调整为"from 之后"(配合 D5 调用方传 `this`)。
- 删 `private static buildFragment(CacheHandler from)`。

### D5 — `SyncLockHandler` `getNext()` → `this`(执行)

`engine.executeChainFragment(context, getNext())` → `engine.executeChainFragment(context, this)`。Engine 按 `indexOf(this) + 1` 定位后继,语义等价(推进"自己之后"的剩余链)。handler 不再依赖自身在链中的 next 引用。

### D6 — test mock 清理(执行)

6 个 test 文件的 mock `CacheHandler` 实现删除强加的 `getNext` / `setNext` / `next` 字段样板(`ChainEngineTest` / `CacheHandlerChainExceptionTest` / `CacheHandlerChainTest` / `CacheHandlerChainFactoryTest` / `ChainObserverTest` / `NullValueHandlerTest`)。其中 `CacheHandlerChainExceptionTest` 的 4 处 mock `handle()` 内 `getNext().handle()` 旧自推进改为 `return continueChain()` —— 这修正了 ADR-0009 后 Engine 推进下"双重推进"的潜在混乱,符合"handler 不自推进,Engine 推进"的真实语义。`ChainEngineTest.installChain` 的 `setNext` 串联循环删除(链结构现为单一 List 快照)。

## 后果

**增益(locality + leverage)**:

1. **单一链表示**:`List<CacheHandler>` 是链结构唯一真理源,消除 head × next × list 三重不变量维护
2. **并发隔离修复**:`driveChain` index 推进完全在不可变快照内,`addHandler`/`clear` 改链不再能穿透到正在执行的 `execute`(原 `getNext()` 读实例字段的漏洞关闭)
3. **dead code 清除**:`driveChain` 的 dead `idx`、`buildFragment` 的 next 链重建、`CacheHandlerChain.head` 冗余引用全部消失
4. **接口收窄**:`CacheHandler` 从 3 方法(handle/setNext/getNext)收敛为 1 方法(handle),handler 不再承担"链接管理"职责
5. **新增 handler 零链接成本**:加 handler 只需 `chain.addHandler(h)`(List.add),无需维护 next 指针

**代价**:

- `executeChainFragment(ctx, from)` 语义从"含 from"改为"from 之后"(唯一调用方 SyncLockHandler 同步改 `this`,等价)
- test mock 样板调整(机械,无行为变化)

**不变**:

- `CacheHandler.handle(CacheContext)` 契约零变化
- 责任链推进协议(CONTINUE / SKIP_ALL / TERMINATE 三态决策)零变化
- `ChainEngine.execute` / `executeChainFragment` 公开签名零变化
- observer 编排(aroundChain / perNode)零变化
- post-process 遍历零变化
- ADR-0009 的 Engine/Observer seam 架构(本 ADR 是其残骸收口,非推翻)

## ADR-0009 关系澄清

ADR-0009 line 62/146 描述 AbstractCacheHandler 退化后"仅保留 next / getNext / setNext"是**当时形态陈述**,非"next 指针必须永久保留"的封口;line 119"子类读 getNext 需开新 ADR"暗示 next 是过渡机制。本 ADR 是 ADR-0009 的自然延伸:把抽 Engine 时遗留的 next 指针双轨收口,兑现"Engine 单一推进 seam"的完整设计。

## 参考

- ADR-0009:ChainEngine + ChainObserver 抽出(本 ADR 收口其残骸双轨)
- ADR-0012:shallow module removal(同款"删残骸转发层"模式)
- Ousterhout《A Philosophy of Software Design》deep/shallow module + deletion test
