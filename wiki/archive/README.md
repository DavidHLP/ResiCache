---
title: 归档区
type: meta
tags:
  - meta
  - archive
related: [index, log, archive/adr/INDEX]
status: stable
created: 2026-07-06
updated: 2026-07-06
---

# Wiki 归档区

存放**已完成历史使命、不再纳入常规阅读路径**的 wiki 内容。归档≠废弃——内容保留可查,只是不再主动引导阅读。

## 为什么归档

部分 wiki 内容(尤其高密度的决策记录)在积累到一定体量后,会反向约束后续探索的「发散思维」——后续会话读到它们时,倾向于在既有框架内做保守增量,而非自由重审前提。把这类内容移出主阅读路径,让默认的 agent 会话从**当前架构**出发,而非从**历史决策堆**出发。

## 归档原则

- **只增不删**:归档区内容一经归档不再删除(git history 永久可追)。
- **默认不读**:wiki 主入口([[index]] / [[overview]])不主动指向归档区;需查证历史时由人工 / agent 显式下钻。
- **不断主链**:归档操作必须同步修复所有外部引用,不允许留下断链(wiki 维护铁律)。
- **历史日志保留**:wiki/log.md 中已存在的 append-only 条目是事实快照,归档时不改写(改历史=篡改事实);新归档动作以新条目形式 append。

## 当前归档项

| 归档项 | 位置 | 归档时间 | 说明 |
|---|---|---|---|
| ADR(52 篇) | [[archive/adr/INDEX]] | 2026-07-06 | A 类定位型 0001-0008 + B 类深化型 0009-0052,原 `wiki/adr/` 整体迁入 `wiki/archive/adr/` |

## 如何查证归档内容

需要追溯某条历史架构决策时:

1. 先查 [[archive/adr/INDEX]](A 类 + B 类总索引)或 [[archive/adr/rounds/CHRONICLE]](B 类 round 编年)定位。
2. 阅读具体 ADR 卡片时,把它当作**历史参考**而非**当前硬约束**——决策的 rationale 仍有效,但前提可能已变。

## 相关

[[index]] · [[log]] · [[archive/adr/INDEX]]
