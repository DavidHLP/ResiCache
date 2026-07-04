---
title: "ADR-0045: PostProcessHandler 折回 CacheHandler + 寄生属性键 handler-local 归位"
type: adr
status: accepted
date: 2026-07-04
deciders: DavidHLP
related:
  - ADR-0009
  - ADR-0033
  - ADR-0036
tags:
  - interface-merge
  - locality
  - stringly-typed
  - deletion-test
---

# ADR-0045: PostProcessHandler 折回 CacheHandler + 寄生属性键归位(round 33)

## 状态

Accepted — 2026-07-04。

## 背景

`/improve-codebase-architecture` round 33 评审识别 5 个候选。**C3(Worth exploring)** 与 **C4(Worth exploring)**
合并落地:两条同源 friction 都涉及 handler 内部状态管理,合并落地降低 commit 噪声。

通读 `chain/PostProcessHandler.java`(53 SLOC)+ `chain/CacheHandler.java`(27 SLOC)+
`chain/ChainEngine.java:265-283`(executePostProcess 含 instanceof 分支)+
`protection/bloom/BloomFilterHandler.java`(POST_PROCESS_KEY)+ `protection/breakdown/SyncLockHandler.java`(LOCK_ACQUIRED_KEY)
后裁决。

## 决策

### C4 落地:PostProcessHandler 折回 CacheHandler

- **删除** `chain/PostProcessHandler.java` 整文件(53 SLOC)
- **编辑** `chain/CacheHandler.java`:在 `handle(ctx)` 后追加两个 default no-op 方法
  ```java
  default boolean requiresPostProcess(CacheContext context) { return false; }
  default void afterChainExecution(CacheContext context, CacheResult result) {}
  ```
  `requiresPostProcess` 默认 `false` —— 与原 `implements PostProcessHandler` 隐式 opt-in 语义等价
  (handler 必须显式 override 才参与 post-process)
- **编辑** `chain/ChainEngine.executePostProcess`:删除 `if (handler instanceof PostProcessHandler postHandler)`
  分支,改走 `if (handler.requiresPostProcess(context))` —— 消灭 seam 边界 type check
- **编辑** `protection/bloom/BloomFilterHandler.java`:
  - 删除 `implements PostProcessHandler`
  - 删除 POST_PROCESS_KEY 常量
  - 删除 handlePut/handlePutIfAbsent/handleClean 中的 `context.setAttribute(POST_PROCESS_KEY, true)` 调用
  - 修改 `requiresPostProcess` 内部为直接派生于 `context.getOperation()`(PUT / PUT_IF_ABSENT / CLEAN → true;其他 → false)

### C3 落地:SyncLockHandler LOCK_ACQUIRED_KEY 死 seam 删除

- **删除** `LOCK_ACQUIRED_KEY = "sync.lock.acquired"` 常量
- **删除** `shouldHandle(ctx)` 内的 re-entry guard:
  ```java
  if (context.getAttribute(LOCK_ACQUIRED_KEY, false)) {
      return false;
  }
  ```
- **删除** `doHandle(ctx)` 内的 `context.setAttribute(LOCK_ACQUIRED_KEY, true)` 调用

### deletion test 论证

- **PostProcessHandler**:删它 → handler 用 `requiresPostProcess` 方法代替接口实现,Engine 抹 `instanceof`,
  抽象不增反减(default 方法让 `CacheHandler` 承载 2 个钩子,语义清晰)
- **BloomFilterHandler POST_PROCESS_KEY**:原 handler self-set / self-get,跨越 `CacheContext.attributes` 全局桶,
  违反 locality。改派生于 operation enum 后,handler 不写任何 stringly-typed 属性
- **SyncLockHandler LOCK_ACQUIRED_KEY**:实际生产路径下,fragment 推进(ADR-0022 `indexOf(this)+1`)
  不会再回到 SyncLockHandler 自身,该标记属于 dead seam。删除零行为变化

## 收益

| 维度 | 削减量 |
|---|---|
| PostProcessHandler.java | 整文件 -53 SLOC |
| CacheHandler.java | +30 SLOC(default 方法) |
| BloomFilterHandler.java | -12 SLOC(常量 + 3 处 setAttribute + requiresPostProcess 实现简化) |
| SyncLockHandler.java | -10 SLOC(常量 + 2 处 attribute 操作) |
| ChainEngine.executePostProcess | -4 SLOC(seam 边界 type check 消灭) |
| 测试影响 | BloomFilterHandlerTest/SyncLockHandlerTest 删 7 处 attribute 操作 + 1 个 re-entry guard 测试 |
| **净 SLOC** | **-49** |
| type safety | boolean / Operation enum vs `Object` 属性袋值 |
| locality | handler 内部态不再通过 context 桶泄漏 |
| 测试适配 | 3 个测试类适配(原"setAttribute 后断言可读"模式失效,改为派生语义测试) |

## 不变量保留

- post-process 整体语义不变(handler 仍通过 `requiresPostProcess=true` opt-in;Engine 仍按 main chain 完成后顺序回调)
- 失败隔离(try/catch 不污染主链)保留
- SyncLockHandler 锁推进路径零变化(没有 re-entry guard 也不需要 — fragment 自身跳过)
- `CacheContext.attributes` 桶不删(observer thread-local 计时、MDC 回滚键仍合法使用)

## 后续

C5 见 ADR-0046。

## 相关

- [[0009-chain-engine-extraction]] — Engine 抽出 + post-process 协议
- [[0033-cacheoutput-typed-decisions]] — typed decision locality-first 模型
- [[0036-prefetch-decision-interceptor-activate-lua-script]] — typed decision 续篇