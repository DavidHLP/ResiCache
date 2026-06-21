---
title: Dashboard
type: meta
tags:
  - meta
  - dashboard
  - dataview
related: [index, overview, lint-report-2026-06-21]
status: stable
created: 2026-06-21
updated: 2026-06-21
---

# Wiki Dashboard

> 需 **Obsidian Dataview 插件**才渲染以下查询;纯 markdown 浏览器会显示原始代码块(不影响阅读)。
> 若 vault 根不是 `wiki/`,把 `FROM "..."` 的路径调整为相对 vault 根的实际路径。

## 近期更新(最新 15 页)

```dataview
TABLE type, status, updated FROM "" SORT updated DESC LIMIT 15
```

## 按类别统计

```dataview
TABLE length(rows) AS 页数 FROM "" WHERE type GROUP BY type SORT 页数 DESC
```

## 待完善(status ≠ stable)

```dataview
LIST FROM "" WHERE status != "stable" SORT updated ASC
```

## 架构与机制核心页

```dataview
LIST FROM "" WHERE type = "architecture" OR type = "mechanisms" SORT type, updated
```

## 最近 lint

见 [[lint-report-2026-06-21]]。维护历史见 [[log]]。
