---
title: ResiCache Wiki 维护规范
category: meta
tags: [wiki, schema, conventions]
related: [index, overview, log]
updated: 2026-06-21
---

# ResiCache LLM Wiki

ResiCache 项目的 **LLM 维护知识库**。本文件是 schema——告诉任何后续 LLM 会话:这个 wiki 是什么、如何组织、如何维护。

## 这是什么

ResiCache 的架构散落在 90 个 Java 文件里。每次回答「责任链怎么跑」「布隆过滤器如何防穿透」都要从源码重新推导。本 wiki 把这些知识**编译一次、持续保鲜**:把分散的架构、机制、数据流整理成结构化、可交叉引用的 markdown 页面。

核心理念(来自 [LLM Wiki 模式](https://github.com/)):知识不是每次查询时 RAG 重算,而是增量积累成一个持久的、复利的产物。**LLM 写并维护全部 wiki;人类负责提问、审核、决定方向。**

## 三层架构

| 层 | 位置 | 谁拥有 | 说明 |
|---|---|---|---|
| **Raw sources** | `src/main/java/**`、`README.md`、`CLAUDE.md` | 项目 / 只读 | 权威真相。改 wiki 不改它们,除非源码本身变了 |
| **The Wiki** | `docs/wiki/**/*.md` | **LLM 完全拥有** | 你写的页面,人类阅读、你维护 |
| **The Schema** | `docs/wiki/README.md` + `index.md` + `log.md` | 人机共演 | 本文件 + 导航 + 日志 |

**铁律:源码变了 → 更新对应 wiki 页 → 记一条 log。源码没变 → wiki 视为可信,直接引用,不重新推导。**

## 目录结构

```
docs/wiki/
├── README.md            ← 你在这里(schema)
├── index.md             内容索引(按类别,每页一句摘要)
├── log.md               操作日志(append-only)
├── overview.md          项目概览(从这里开始读)
├── architecture/        架构概念:责任链、生命周期、数据流、链路控制、自动装配
├── mechanisms/          5 大防护机制:布隆/锁/提前过期/TTL抖动/空值
├── modules/             模块实体页:cache-core、annotations、serialization 等
├── concepts/            缓存领域概念:穿透/击穿/雪崩/热key
└── how-to/              操作指南:加 handler、配置行为
```

共 30 页。完整清单见 [[index]]。

## 页面规范(每个 wiki 页必须遵守)

### 1. Frontmatter(YAML)

```yaml
---
title: 页面标题
category: architecture | mechanisms | modules | concepts | how-to | meta
tags: [责任链, handler, 缓存雪崩]   # 自由标签,便于检索
related: [chain-of-responsibility, cache-avalanche]   # wikilink slug 列表
source-files:                          # 引用的源码(相对仓库根)
  - src/main/java/.../HandlerOrder.java
updated: 2026-06-21
---
```

### 2. 正文结构

1. **一句话定位** —— 这页讲什么、对应哪个源码包。
2. **职责 / 要点** —— 它解决什么问题、核心机制。
3. **核心源码引用** —— `file_path:line` 格式(可点击),配 1–2 段精选代码,**不要贴整文件**。
4. **关键设计 / 数据流** —— 为什么这么做、数据怎么流动。
5. **配置项 / 注解属性** —— 若涉及 `resi-cache.*` 或注解,列出。
6. **交叉引用** —— 用 `[[slug]]` 链向相关机制 / 概念 / 模块页。

### 3. 交叉引用

- 用 Obsidian wikilink:`[[page-name]]`(无 `.md` 扩展名,slug = 文件名)。
- **liberal 链接**:凡是提到另一个 wiki 页覆盖的概念,都加链接。
- 链向概念页(如 [[cache-avalanche]])和机制页(如 [[ttl-jitter]])是最常见的。

### 4. 源码引用

- 格式 `src/main/java/io/github/davidhlp/spring/cache/redis/xxx/Foo.java:行号`。
- 行号标注关键方法/常量,便于点击直达。
- 代码块只贴关键片段(10–30 行),突出逻辑,不复制整类。

### 5. 命名

- 文件名 **kebab-case**,与 wikilink slug 完全一致(如 `bloom-filter.md` ↔ `[[bloom-filter]]`)。
- 目录名复数小写(`architecture/`、`mechanisms/`)。

## 三大操作

### Ingest(源码变更后更新)

当源码改动(新 handler、配置项变化、重构):

1. 读改动涉及的源码,理解变化。
2. 更新受影响的 wiki 页(可能不止一个——一次改动常触及 2–5 页)。
3. 必要时新建页面(新机制 / 新模块)。
4. 在 [[log]] 追加一条:`## [YYYY-MM-DD] ingest | 简述`。
5. 更新 [[index]] 若有新页。

### Query(回答问题)

当被问到架构 / 机制 / 流程问题:

1. **先读 [[index]]** 定位相关页,再下钻细读,**不要直接 grep 源码**(wiki 已编译过)。
2. 综合 wiki 页作答,带 `[[slug]]` 引用。
3. 若答案有沉淀价值(一次对比 / 分析 / 连接),**把它写回 wiki 成新页**,不要让它消失在聊天里。

### Lint(健康检查)

定期或被要求时,检查 wiki 健康:

- **断链**:`[[slug]]` 指向不存在的页。
- **孤儿页**:无任何入链的页(graph view 里游离)。
- **过期声明**:wiki 说 A,但源码已改成 B(源码 refactor 后未同步)。
- **缺失页**:某概念被多次提及却无独立页面。
- **缺失交叉引用**:两个强相关页未互链。

修复后记一条 `## [YYYY-MM-DD] lint | 简述` 到 [[log]]。

## index.md 与 log.md

- **[[index]]** —— 内容导向。全部 30 页按类别分组,每条 `- [标题](相对路径) — 一句话摘要`。回答问题前先读它定位。每次 ingest 有新页就更新。
- **[[log]]** —— 时间导向。append-only 的事件流(ingest / query / lint / init)。条目格式 `## [YYYY-MM-DD] <op> | <subject>`,可被 `grep "^## \[" log.md` 解析。给你(和人类)一个 wiki 演化的时间线。

## 给后续会话的速查

- **入口**:从 [[overview]] 或 [[index]] 开始。
- **源码地图**:见 `CLAUDE.md` 的「Project Structure」与「Where to Look」表。
- **结构化查询**:优先用 CodeGraph(`codegraph_*` 工具)查符号关系,再用本 wiki 理解「为什么」。
- **改 wiki 前**:确认源码未变(变了就先 ingest);**不要**为了凑规范而臆造源码细节——所有 `file:line` 必须真实存在。

## 许可与来源

本 wiki 衍生自 ResiCache 源码(git 仓库的一部分)。源码以项目根 `LICENSE` 为准。
