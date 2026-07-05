---
title: 角色 · 扩展开发
type: meta
tags:
  - meta
  - moc
  - implementer
  - 角色
related: [add-protection-handler, handler-result-control, context-data-flow, chain-of-responsibility, cache-core, observability]
status: stable
created: 2026-07-05
updated: 2026-07-05
---

# 角色:扩展开发

> 目标:给责任链加一个新 handler,或修改现有 handler 行为。读完能正确落点、不破坏链不变量。

## 加新 handler —— 4 步

详见 [[add-protection-handler]]。骨架:

1. **选档位** → [[chain-of-responsibility]] `HandlerOrder` 枚举(间隔 100,单一真理源)。找语义相邻的空档。
2. **实现 `CacheHandler`** → `handle(CacheInput, CacheContext, CacheOutput)`,用 `CacheOutput.signalContinue/terminate/skipAll()` 控制流([[handler-result-control]])。
3. **数据传递** → 只读 `CacheInput`,读写 `CacheContext`,产出到 `CacheOutput`([[context-data-flow]])。
4. **注册** → `@Component` + `@HandlerPriority(HandlerOrder.YOUR_SLOT)`,自动进链。

## 控制流三态

| 信号 | 含义 |
|---|---|
| CONTINUE | 放行,下一档继续 |
| TERMINATE | 终止整条链(如布隆拒绝) |
| SKIP_ALL | 跳过剩余 handler(如已命中) |

→ [[handler-result-control]]

## 不变量(别破坏)

- handler **不**直接写 Redis;产出决策由 `ActualCacheHandler(500)` 执行。
- 跨 handler 通信只用 `CacheContext`,不改 `CacheInput`(immutable)。
- 顺序由 `HandlerOrder` 单一真理源,不要在代码里写魔术数字。

## 下钻

- 想加观测 → [[observability]](`ChainObserver` 钩子,handler 0 修改即可加观测)
- 想懂装配 → [[auto-configuration]]
- 决策有沉淀价值 → 走 ADR 流程(见 [[adr/INDEX]])
