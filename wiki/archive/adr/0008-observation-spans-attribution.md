# ADR-0008: Observation spans 归属 = 企业可观测性延伸,与 ADR-0005 划清边界

- **Status**: Proposed
- **Date**: 2026-06-30
- **Deciders**: DavidHLP
- **Related**: ADR-0005 / ADR-0006 / `wiki/log.md` round 35 OPEN-QUESTION / WS-1.4

## Context

round 35 遗留 OPEN-QUESTION:Observation spans 应当归 ADR-0005 (kernel-extraction-hedge)
还是独立线?版本归属应是 v0.0.3 / v0.1.0 / v0.2.0 哪一个?

事实核验(2026-06-30,基于 working tree):

1. `MASTER_PLAN.md` 已归档(commit `0bc6c2b`,2026-06-29);「guide §223」等引用全部失锚——
   仅作为历史 commit body 内的回溯标记保留,不再约束后续决策。
2. ADR-0005 全文不包含 "Observation" 关键词,该 ADR 仅讨论 framework-agnostic 内核抽取。
   「ADR-0005 误归因」随 `MASTER_PLAN.md` 归档自然消失,本 ADR 不修订 ADR-0005。
3. 版本归属矛盾:`CHANGELOG.md` 旧条写 v0.2.0;`wiki/modules/observability.md` 旧条写 v0.1.0。

WS-1.4 现状(round 24-26,已落 master):MDC requestId + chain Timer + handler fired
counter + TtlHandler jittered counter — **不含** Micrometer Observation spans。Observation
spans 是 WS-1.4 的 trace 维度延续,属独立 PR / ADR 范畴。

## Decision

### D1:Observation spans **不**属 ADR-0005

ADR-0005 = kernel-extraction-hedge(framework-agnostic 内核抽取的长寿对冲)。Observation
spans = 企业可观测性能力延伸(WS-1.4 的 trace 维度)。两者目标 / 范围 / 触发条件均不同,
不应同列。

### D2:Observation spans 真正归属

- 归属项:企业可观测性(WS-1.4 续 / trace 维度)。
- 版本归属:**v0.1.0**(对齐 Boot 4.0 / Java 21 / Redisson 3.50 milestone;v0.0.x 不引入新机制,
  仅做硬化 + 治理)。
- 实现路径候选:`(a)` Micrometer Observation + OTel / Brave bridge(主流);`(b)` 内部
  thin Span + 自有 exporter(避免 binding)。选择由实现时 ADR 决定,本 ADR 不预先锁定。
- tag 白名单:本 ADR 不规定(由 §10.5 P1 #5 "MeterFilter 拒绝未知 tag" 候选处理)。

### D3:`CHANGELOG.md` v0.2.0 旧文案修正

当前文案:`Observation event lands in v0.2.0` → 修正文案:`Observation spans 归属 v0.1.0
(per ADR-0008;pre-1.0 may-change)`。

### D4:`wiki/modules/observability.md` 失锚引用修正

「guide §223」失锚 → 改为 `wiki/adr/0008-observation-spans-attribution`(本 ADR)。

D3 + D4 修正时机由用户批准本 ADR → 人工同步;loop 不自动改 CHANGELOG / wiki 既有
内容。

## Consequences

### 正面

- **OPEN-QUESTION 闭合**:round 35 遗留的「guide 矛盾 + ADR-0005 误归因」有正式 reconciliation。
- **ADR-0005 不污染**:不动 ADR-0005 文字,只通过本 ADR 划清边界。
- **CHANGELOG 与 wiki 一致**:用户批准后,`CHANGELOG.md` 与 `wiki/modules/observability.md`
  同向收敛到 v0.1.0。
- **版本归属清晰**:pre-1.0 不引入新机制,Observation spans 是 v0.1.0 的 feature。

### 负面

- **Proposed 状态**:用户未批准前,CHANGELOG / observability.md / 实际实现均按旧文案。
- **不改 ADR-0005 的风险**:未来 LLM session 仍可能"自创"Observation 与 kernel-extraction
  关联。缓释:本 ADR §D1 已划清边界,通过 Related 链接可达成本 ADR。

### 不变

- ADR-0001 ~ ADR-0007 文字不动。
- `MASTER_PLAN.md` 不复活(归档决策 finalize 于 commit `0bc6c2b`)。
- WS-1.4 落地(round 24-26)不算入本 ADR,各自 commit body 自带 rationale。

## 触发重评估

- Micrometer Observation API 出现 major breaking → 重新评估 §D2 实现路径 `(a)` vs `(b)`。
- v0.1.0 窗口变更 → 重新评估 §D2 版本归属。
- ADR-0005 真要包含 Observation(用户认为两者应同列)→ 重新评估 §D1。

---

**最后更新**:2026-06-30
**下次维护触发**:用户批准 / 拒绝 / 修订本 ADR;或 Micrometer Observation API major
breaking;或 v0.1.0 窗口变更。
**维护者**:@DavidHLP
