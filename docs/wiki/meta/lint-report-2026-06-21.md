---
title: "Lint Report 2026-06-21"
type: meta
tags: [meta, lint, 健康检查]
related: [index, log, README]
status: stable
created: 2026-06-21
updated: 2026-06-21
---

# Lint Report: 2026-06-21

> 首次 lint。Transport: filesystem(`Grep`/`Bash`,非 Obsidian vault,无 `.vault-meta/`/`obsidian-cli`)。Address Validation / Semantic Tiling 为 opt-in 且脚本不存在,已跳过。

## Summary

- Pages scanned: **28**
- Issues found: **2**(1 MEDIUM + 1 LOW)
- Auto-fixed: **0**
- Needs review: **1**(frontmatter 对齐决策)
- 结构健康度:**优秀**(0 断链 / 0 孤儿 / index 完整 / 无真空节)

## ✅ Passed

| 检查项 | 结果 |
|---|---|
| Dead links(断链) | **0** —— 真实 wikilink 全部命中(README/log 反引号内的 `[[slug]]`/`[[page-name]]` 是格式教学示例,Obsidian 不解析,不计) |
| Orphan pages(孤儿) | **0** —— 每页都有入链,graph 无游离节点 |
| Index 完整性 | **OK** —— `index.md` 所有条目都有对应文件,无指向已删/改名页 |
| Empty sections(真空节) | **0 真空** —— 脚本初报 8 处均为误报:这些 `##` 节标题的内容写在 `###` 子节里(如「## 三大操作」→「### Ingest/Query/Lint」),属合法分层结构 |

## 🟡 MEDIUM — Frontmatter 字段与 skill 标准的差异

`claude-obsidian` 的 wiki-lint 期望 frontmatter 含 `type` / `status` / `created` / `updated` / `tags`。本 wiki 采用自定义约定(见 [[README]] schema):

| 字段 | 覆盖 | 说明 |
|---|---|---|
| `title` / `tags` / `updated` / `related` | 28/28 ✓ | 符合 |
| `category`(≈ `type`) | 28/28 ✓ | 用 `category` 代替 `type`,语义等价 |
| `source-files` | 28/28 ✓ | 本 wiki 特有(指向源码),skill 无此字段 |
| **`type`** | 0/28 | 用 `category` 替代 |
| **`status`** | 0/28 | 缺(seed/developing/stable 等成熟度标记) |
| **`created`** | 0/28 | 缺(仅 `updated`) |

**影响**:若只用 markdown / GitHub 浏览,**无影响**(约定自洽)。若要用 Obsidian **Dataview** 插件(其 dashboard 查询依赖 `type`/`status`/`created`,见 skill 文档),则需补齐这三字段。

**建议**:见文末决策项。

## 🟢 LOW — 命名约定

- skill 默认:`Title Case with spaces`(`Machine Learning.md`)
- 本 wiki:`kebab-case`(`bloom-filter.md`),与 wikilink slug 一致

代码项目知识库用 kebab-case 更自然(URL 友好、与代码/URL 惯例一致),且 [[README]] schema 已明确规定。**自洽,无需改**——仅记录与 Obsidian 笔记 vault 惯例的差异。

## ℹ️ Stale Claims(外部,非 wiki 自身)

wiki 在 init 阶段已主动发现并记录:项目 `CLAUDE.md`/`README.md` 描述了 `a5ab55b` 重构后已删除的模块(`wrapper/`、`spi/`、`event/`、`evaluator/`、`CacheMetricsRecorder`)。**wiki 全程以代码为准**,详见 [[log]] 的 lint 条目。这是源文档过期,非 wiki 问题——后续 ingest 可顺手修正 `CLAUDE.md`/`README.md`。

## 未生成(可选增强)

以下 skill 提及的产物本次未生成,因当前为代码项目 wiki(非 Obsidian vault),Dataview/Canvas 价值有限:

- `meta/dashboard.md`(Dataview 查询:近期活动/seed 页/待补源)
- `meta/overview.canvas`(Obsidian canvas 可视化域地图)

若计划用 Obsidian 打开此 wiki 作 vault 并启用 Dataview,可补这两项 + 对齐 frontmatter。

## 建议的后续动作

1. **(可选,MEDIUM)** 决定 frontmatter 是否对齐 skill 标准(加 `type`/`status`/`created`)。若用 Dataview → 对齐;否则保持现状。
2. **(可选)** 修正 `CLAUDE.md`/`README.md` 的过时结构描述(stale claims 根源)。
3. **(常规)** 维持节奏:每 10–15 次 ingest 或每周跑一次 lint。
