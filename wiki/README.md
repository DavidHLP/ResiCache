---
title: ResiCache Wiki 维护规范
type: meta
tags:
  - meta
  - wiki
  - schema
  - conventions
related: [index, overview]
status: stable
created: 2026-06-21
updated: 2026-07-09
---

# ResiCache LLM Wiki

ResiCache 项目的 **LLM 维护知识库**。本文件是 schema——告诉后续 LLM 会话:wiki 是什么、如何组织、如何维护。

## 这是什么

ResiCache 架构散落在 ~90 个 Java 文件里。本 wiki 把这些知识**编译一次、持续保鲜**:架构 / 机制 / 数据流整理成结构化、可交叉引用的页面。

> 知识不是每次查询时 RAG 重算,而是增量积累成持久产物。**LLM 写并维护全部 wiki;人类提问、审核、定方向。**

**铁律**:源码变了 → 更新对应 wiki 页 → 更新 [[index]];源码没变 → wiki 视为可信,直接引用,不重新推导。

> wiki 目录结构与源码 Project Structure 见 `CLAUDE.md`(单一真理源)。

## 页面规范(每个 wiki 页必须遵守)

### 1. Frontmatter(YAML)

```yaml
---
title: 页面标题
type: architecture | mechanisms | modules | concepts | how-to | meta
tags:                                  # 首个为 type 锚,其余自由
  - architecture
related: [chain-of-responsibility, cache-avalanche]   # wikilink slug 列表
source-files:                          # 引用的源码(相对仓库根)
  - src/main/java/.../HandlerOrder.java
status: stable
created: 2026-06-21
updated: 2026-06-21
---
```

### 2. 正文结构

1. **一句话定位** —— 讲什么、对应哪个源码包。
2. **职责 / 要点** —— 解决什么问题、核心机制。
3. **核心源码引用** —— **符号引用(`Class#method`)优先**;`file:line` 易漂移慎用。配 1–2 段精选代码,不贴整文件。
4. **关键设计 / 数据流** —— 为什么这么做、数据怎么流动。
5. **配置项 / 注解属性** —— 若涉及 `resi-cache.*` 或注解,列出。
6. **交叉引用** —— 用 `[[slug]]` 链向相关页。

### 3. 交叉引用

- Obsidian wikilink:`[[page-name]]`(无 `.md`,slug = 文件名)。
- liberal 链接:提到另一页覆盖的概念即加链接。

### 4. 源码引用

- **优先符号引用** `Class#method` —— 行号交给 codebase-memory 即时解析,规避 stale `file:line`。
- 代码块只贴关键片段(10–30 行),不复制整类。

### 5. 命名

- 文件名 **kebab-case**,与 wikilink slug 完全一致。
- 目录名复数小写。

## 三大操作

### Ingest(源码变更后更新)

源码改动 → 读源码理解变化 → 更新受影响 wiki 页(常 2–5 页)→ 必要时建新页 → 更新 [[index]]。

### Query(回答问题)

被问架构 / 机制 / 流程:**先读 [[index]] 定位** → 下钻细读(**不直接 grep 源码**)→ 综合 wiki 作答带 `[[slug]]` → 答案有沉淀价值就写回 wiki。

### Lint(健康检查)

定期查:断链、孤儿页、过期声明(wiki 说 A 源码已改 B)、缺失页、缺失交叉引用。

## [[index]]

- **[[index]]** —— 内容导向。全量页面按类别分组,每条一句话定位。
- 变更历史由 `git log` 承载(commit body 是 SOURCE OF TRUTH),不再维护独立 log 文档。

## 给后续会话的速查

- **入口**:从 [[overview]] 或 [[index]] 开始。
- **源码地图**:`CLAUDE.md` 的「Project Structure」与「Where to Look」表。
- **结构化查询**:优先用 codebase-memory 工具(`search_graph` / `trace_path` / `get_code_snippet`)查符号关系,再用本 wiki 理解「为什么」。
- **改 wiki 前**:确认源码未变(变了先 ingest);**所有源码引用必须真实存在**——符号优先,行号次之。

## 许可与来源

本 wiki 衍生自 ResiCache 源码(git 仓库一部分)。源码以项目根 `LICENSE` 为准。
