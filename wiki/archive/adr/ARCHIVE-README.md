---
title: ADR archive 收口说明
type: meta
tags:
  - meta
  - archive
  - adr
related: [log, index, ../README]
status: stable
created: 2026-07-08
updated: 2026-07-08
---

# ADR archive —— 收口说明

> 本目录原保留 **49 篇 ADR 卡片**(8 篇定位型 ADR-0001~0008 + 41 篇深化型 ADR-0009~0057)+ `INDEX.md` + `rounds/CHRONICLE.md`,共约 600KB 决策散文。**2026-07-08 一次性收缩为本文档**:卡片本身不再以 prose 形式存在于 wiki,完整 rationale 由 `git log` + [[log]] 承载(commit body + log 条目已是 wiki 卡片的双胞胎信息源)。

## 为什么收口

- **认知负载**:49 篇卡片让后续 LLM session 在进入时被迫先吸收整段决策史再动手,挤压发散空间。
- **重复源**:`log.md` 已逐 round 记录决策摘要 + 链接到对应 commit;`git log` commit body 又是同一决策的原文。**三处同步 → wiki 卡片沦为 git 的冗余镜像**。
- **决策是流水,非资产**:架构决策随代码变更而失效,留有 prose 的卡片反而成为「上下文陷阱」(后读 LLM 倾向在既有框架内做保守增量,不再自由重审前提)。

## 如何追溯历史决策

| 想要追溯 | 去看 |
|---|---|
| 某条决策的 rationale + 备选路径 | `git log --all --grep="<关键字>"` 或 `git log -- <相关源码文件>` |
| 某条决策的 round 摘要 + 测试通过数 | [[log]] 的 `- [YYYY-MM-DD] improve \| ADR-XXXX \| ...` 行 |
| 某个文件的修改历史 | `git log --follow -- <path>` |
| 定位型决策(定位 / 信封 / 单构建) | [[../README]] 引用表 + [[STABILITY]] §1+§3(原文已并入主文档) |

## 不再保留

- ~~49 篇 ADR 卡片~~(0001-0057)
- ~~`INDEX.md`(二分类索引)~~
- ~~`rounds/CHRONICLE.md`(阶段编年)~~

git history 永久可追;`git show <commit>` 即可恢复任意卡片原文。

## 新增 ADR 的去处

**不新增独立 prose 卡片**。架构决策的承载方式:

1. **commit message body** — 写入决策 rationale + 备选路径驳回理由 + 测试统计。这是 SOURCE OF TRUTH。
2. **[[log]] 单行 append** — `- [YYYY-MM-DD] <op> | ADR-XXXX | <一句话主题>`。
3. **wiki 主文档引用**(可选) — 若决策对当前架构认知有持续价值,在对应 `architecture/` 或 `modules/` 页用 1-2 句直接陈述,不引申至独立卡片。

## 相关

[[log]] · [[../README]] · [[index]]
