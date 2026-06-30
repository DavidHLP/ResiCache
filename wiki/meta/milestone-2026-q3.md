---
title: 项目优化里程碑 · 2026 Q3
type: meta
tags:
  - meta
  - 里程碑
  - 优化
related: [log, archive-2026-q2, README]
status: active
created: 2026-06-30
updated: 2026-06-30
---

# 项目优化里程碑 · 2026 Q3

> **起点**: 2026-06-30
> **前置**: autonomous-loop v1/v2(round 1–42)已归档至 [[log/archive-2026-q2]],v2 runbook 已 CLOSED。
> **目的**: 在「v0.0.6 发布待执行」+「v0.1.0 路线图」+「ADR-0008 待 review」三重未决议之间,启动新一轮**项目优化**工作。

---

## 1. 背景与起点

2026 Q2 的工作重心是**文档诚实化 + 异步可观测性 + 前门 reconcile**(见 [[log/archive-2026-q2]])。round 35 已确认 Q2 自主可推进项耗尽,剩余项全为:

1. **outward GATE**: v0.0.6 发布命令链(pom 签名 phase 修复 + tag 删建 + push + GitHub Release 验证)— **需用户执行**
2. **ADR-0008 待 review**: Observation spans attribution(版本归属 + ADR 归因)— **需用户决策**
3. **v0.1.0 路线图**: WS-1.x 收官 + JMH + Cluster IT + 迁移工具 — **需新里程碑接管**

Q3 里程碑承接 (3) 并启动**项目优化**维度的工作 — 超越功能增量,聚焦「使项目本身更易维护/更易接纳/更易发布」。

---

## 2. Q3 候选轴(三大优化方向)

### 轴 A — **可观测性深化**(续 WS-1.4)

- ADR-0008 落地后 chain-level Observation spans(`ObservationRegistry` 接入)
- per-handler Micrometer tags(handler / decision,bound cardinality)
- MeterFilter 白名单护栏(防未知 tag 进 registry)

> 状态: 等 ADR-0008 Accepted 后启动。

### 轴 B — **测试质量与覆盖**

- 并发安全测试矩阵(`AbstractCacheHandler.execute()` 多线程回归)
- Cluster 模式 Testcontainers IT(目前 12 个 IT 全部 single-node,Cluster 拓扑仅静态验证)
- Property contract tests(`resi-cache.*` keys 行为契约,锁住 STABILITY §1)

> 状态: 可独立启动,不需要等任何 ADR。

### 轴 C — **发布工程与项目运营**

- v0.0.6 发布命令链文档化(把 release.yml 经验沉淀为 RUNBOOK)
- ADOPTERS.md 模板(STABILITY §4.5 1.0 毕业条件 #5)
- BUS-FACTOR.md 雏形(STABILITY §4.6 1.0 毕业条件 #6)
- sample module 骨架(README 提到但未交付)
- benchmark module 骨架(JMH,roadmap v0.3.0)

> 状态: 部分可独立启动,部分需用户协调(samples / labels)。

---

## 3. 当前排程(Q3 头 4 周)

| 周 | 主题 | 来源 | 预计产出 |
|---|---|---|---|
| W1 | 用户 gate 收尾(ADR-0008 review + v0.0.6 发布命令) | 用户 | (外部动作) |
| W1 | 轴 B: 并发安全测试矩阵 启动 | 独立 | 1 个并发测试 class |
| W2 | 轴 B: 并发测试矩阵 续 | 续 | 3+ 个并发场景 |
| W2 | 轴 A: ADR-0008 Accepted 后立即启动 chain Observation | gated by W1 | 1 个 Observation 接入 |
| W3 | 轴 C: ADOPTERS.md + BUS-FACTOR.md 模板 | 独立 | 2 个治理文档 |
| W3 | 轴 C: sample module 骨架 | 独立 | 1 个 sample 目录 + README |
| W4 | Q3 评审 + 决定 Q4 主轴 | 总结 | wiki/log + Q4 候选 |

---

## 4. 工作纪律(从 Q2 沉淀)

| 原则 | 落地形式 |
|---|---|
| 每 commit 1 原子项 | commit body 显式 scope 声明 |
| truth 源恒为 pom.xml + 源码 | docs reconcile 永远是 docs → source |
| verify 必跑(纯 docs 例外) | commit 前 `./mvnw clean verify -B`(test + JaCoCo 70%/40% + checkstyle 0) |
| outward 不可自主推进 | publish / tag / label / gh description 全归 user gate |
| ADR 状态机 | Proposed → user review → Accepted/Rejected;loop 不基于 Proposed 推论 |
| wiki append-only | 主 log.md 简短摘要 + commit-SHA 级细节归档 |

---

## 5. 范围外(Q3 不做)

- ❌ v0.0.6 发布命令本身(归用户 gate)
- ❌ ADR-0008 内容修订(等用户决策)
- ❌ 任何与 Q3 三轴无关的 scope creep(纵有 token 余量也不做)

---

## 6. 决策记录入口

- ADR: `wiki/adr/0009-*` 起的 Q3 新决策
- wiki/log: 走主 log.md「日期 + 主题」摘要风格
- 归档: 单 commit SHA 级细节 → `wiki/log/archive-2026-q3.md`(Q3 季末归档)

---

## 7. 状态

- **status**: active(2026-06-30 启动)
- **当前 active 项**: 等待用户 gate(ADR-0008 review + v0.0.6 命令链)
- **可独立启动项**: 轴 B 并发安全测试矩阵
- **下次评审**: W4(2026-07-27 左右)

---

> 维护纪律:本文档更新在 `Q3 节点评审 + 决策变更` 时,append-only。日常 commit 进度走 [[log]]。