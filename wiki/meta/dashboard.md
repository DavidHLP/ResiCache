---
title: Wiki Dashboard
type: meta
tags:
  - meta
  - dashboard
  - dataview
  - 导航
related: [index, overview, README, lint-report-2026-06-21, log]
status: stable
created: 2026-06-21
updated: 2026-06-21
---

# Wiki Dashboard

> [!info] 启用 Dataview 才生效
> 以下查询块依赖 **Obsidian Dataview 插件**(已加入 `community-plugins.json`)。
> 启用步骤:`Settings → Community Plugins → Community plugins → Browse → 搜 "Dataview" → Install → Enable`。
> 没装的话,这些块在 Obsidian 里会显示原始代码;**纯 markdown 浏览器** 看到的也是代码块(但不影响阅读其他章节)。

## 🧭 入口(三个起点)

- 速读入口:[[overview]] — 一句话 + 五大机制 + 阅读路线
- 目录入口:[[index]] — 全部 30 页按类别分组,每页一句摘要
- 维护入口:[[README]] — schema、frontmatter 规范、三大操作

## 📊 全局统计

> [!note] 类型分布
> ```dataview
> TABLE length(rows) AS "页数"
> FROM ""
> WHERE type
> GROUP BY type
> SORT length(rows) DESC
> ```

> [!note] 状态分布
> ```dataview
> TABLE length(rows) AS "页数"
> FROM ""
> WHERE status
> GROUP BY status
> SORT length(rows) DESC
> ```

## 🆕 近期更新(最新 10 页)

> [!example] 按 `updated` 倒序
> ```dataview
> TABLE type AS "类型", status AS "状态", updated AS "更新"
> FROM ""
> SORT updated DESC
> LIMIT 10
> ```

## 🏗️ 核心骨架:架构 + 机制(10 页)

> [!important] 责任链 + 五大防护机制
> ```dataview
> TABLE tags, status
> FROM ""
> WHERE type = "architecture" OR type = "mechanisms"
> SORT type ASC, file.name ASC
> ```

## 📦 模块速查(8 个 module)

> [!example]
> ```dataview
> TABLE tags, status
> FROM "modules"
> SORT file.name ASC
> ```

## 📚 概念与操作指南(6 页)

> [!tip] 概念(4) + 操作指南(2)
> ```dataview
> TABLE type AS "类型", status
> FROM ""
> WHERE type = "concepts" OR type = "how-to"
> SORT type ASC, file.name ASC
> ```

## ⚠️ 待完善(非 stable)

> [!warning] 状态非 `stable` 的页
> ```dataview
> LIST
> FROM ""
> WHERE status AND status != "stable"
> SORT updated ASC
> ```

> 当前所有内容页 `status: stable`(2026-06-21 一次性建库)。该查询在事实稳定后是空列表,作为信号灯保留:以后任何 ingest 引入新页,这里会浮现待办。

## 🗺️ 视觉地图

### 画布

- 总览:![[meta/overview.canvas]] — 架构 / 机制 / 概念 三栏布局
- 机制拓扑:![[meta/mechanisms-canvas.canvas]] — 5 机制在责任链上的交互关系
- 模块依赖:![[meta/modules-canvas.canvas]] — 8 模块在数据流上的依赖关系

### MOC(Map of Content)

- [[mechanisms-moc|机制拓扑 MOC]] — 责任链档位 + 4 问题 ↔ 防御组合
- [[modules-moc|模块依赖 MOC]] — 三层模型 + 8 模块分组 + 关键调用链

## 🔧 最近维护

- 最近 lint:[[lint-report-2026-06-21]] — 28 页扫描,0 断链 / 0 孤儿
- 维护历史:[[log]] — append-only 时间线
- 路线图:见 `log.md` 末尾待办
