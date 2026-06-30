# ADR-0008: Observation spans 归属 = WS-1.4 后续(企业可观测性延伸),版本 v0.1.0

- **Status**: Proposed
- **Date**: 2026-06-30
- **Deciders**: DavidHLP
- **Related**: ADR-0005 / ADR-0006 / `wiki/log.md` round 35 OPEN-QUESTION / WS-1.4(chain-level `resicache.chain.execute` Timer)

## Context

Round 35 STOPPED-GATED 时遗留 OPEN-QUESTION(原文,`wiki/log.md` round 35 条目):

> (e) Observation spans —— guide 自相矛盾(roadmap v0.0.3 行 [line 278/228] vs v0.1.0 行 [line 291])+ ADR-0005 误归因(实为 kernel-extraction-hedge,非 Observation)。保守 defer 待用户澄清版本归属 + ADR 归因后再启。

本 ADR reconcile 该 OPEN-QUESTION 的**事实基底**,不引入新策略、不改 ADR-0005 文字。

### 事实核验(2026-06-30,基于 working tree)

1. **`MASTER_PLAN.md` 已归档**(commit `0bc6c2b`,2026-06-29)。「guide §223」「guide §223b」「guide §223d」等引用全部失锚——working tree 中**不存在** "guide" 这个文档。这些引用**仅作为历史 commit body 内的回溯标记**保留(读 `git log --grep=guide-§223` 可见),不再约束后续决策。
2. **`wiki/adr/0005-kernel-extraction-hedge.md` 全文 30 行不包含 "Observation" 关键词**。该 ADR 仅讨论 framework-agnostic 内核抽取的 3 个端口接口(`KeyValueStore` / `StatisticsCollector` / `NullValueRepresentation`),与 Micrometer Observation API 无关。**「ADR-0005 误归因」的事实性质是:历史文档曾将 Observation spans 与 ADR-0005 同列,但 ADR-0005 实际从未承诺 Observation**。该误归因随 `MASTER_PLAN.md` 归档**自然消失**——本 ADR 不需要修订 ADR-0005。
3. **版本归属矛盾**(仍存在,需 reconcile):
   - `CHANGELOG.md:33` 写「Observation event lands in v0.2.0」
   - `wiki/modules/observability.md:86` 写「per-cacheName / per-operation tags 与 per-handler Micrometer tags 留后续 release(guide §223,line 291 移至 v0.1.0)」
   - `CHANGELOG.md:420` 写「Observation spans... land in subsequent rounds」(R24 entry,无版本绑定)
   - 矛盾方向:CHANGELOG 偏保守(v0.2.0),observability.md 偏激进(v0.1.0)。CHANGELOG 是 user-facing contract,observability.md 是 developer-facing wiki,两者**应有内部一致性**。

### 已落地的 WS-1.4 子项(round 24-26)

| round | 内容 | SHA(已在 master) | 是否属 Observation spans |
|---|---|---|---|
| 24 | per-handler chain observability 地基:`[chain]` DEBUG + MDC requestId 关联 | `90c09eb` | ❌(log + MDC,**非** Micrometer Observation)|
| 25 | per-handler `resicache.handler.fired` counter(tag = handler simple name) | `df3482e` | ❌(Counter,**非** Span)|
| 26 | `TtlHandler` `resicache.handler.ttl.jittered` 语义 counter | `b93ce43` | ❌(Counter,**非** Span)|

WS-1.4 的 deliverable = **Timer + Counter + DEBUG log + MDC**,**不含** Micrometer Observation spans。Observation spans 是 WS-1.4 的**逻辑续**(chain execution 已经被 Timer 度量,Span 是其 trace 维度),**但属于独立 PR / 独立 ADR 范畴**。

## Decision

### D1:Observation spans **不**属于 ADR-0005

- ADR-0005 是 kernel-extraction-hedge(framework-agnostic 内核抽取的长寿对冲)
- Observation spans 是企业可观测性能力延伸(WS-1.4 的 trace 维度)
- 两者**目标 / 范围 / 触发条件均不同**,不应同列

### D2:Observation spans 真正归属

- **归属项**:企业可观测性(WS-1.4 续 / trace 维度)
- **版本归属**:**v0.1.0**(对齐 Boot 4.0 / Java 21 / Redisson 3.50 milestone;v0.0.x 不引入新机制,仅做硬化 + 治理)
- **实现路径候选**(本 ADR 不实现,仅记录):
  - (a) `Micrometer Observation` + OpenTelemetry / Brave bridge(主流)
  - (b) 内部 thin Span abstraction + 自有 exporter(避免 binding)
  - 选 (a) 还是 (b) 由实现时 ADR 决定,不本 ADR 预先锁定
- **tag 白名单**:本 ADR 不规定 tag 白名单(由 §10.5 P1 #5 "MeterFilter 拒绝未知 tag" 候选处理)

### D3:`CHANGELOG.md:33` 修正

- 当前文案:`Observation event lands in v0.2.0`
- 修正文案:`Observation spans 归属 v0.1.0(per ADR-0008;pre-1.0 may-change)`
- 修正时机:用户批准本 ADR → 由用户手动改 CHANGELOG(loop 不动 CHANGELOG 版本号绑定段,§v2-2 纪律)
- loop **不自主**改 CHANGELOG,只在本 ADR 中标注"待用户批准后人工修正"

### D4:`wiki/modules/observability.md:86` 文案保留

- 当前文案:`... 留后续 release(guide §223,line 291 移至 v0.1.0)`
- 本 ADR 状态升为 Proposed 后,该文案**自然成立**(`移至 v0.1.0` 与本 ADR 一致)
- 但「guide §223」失锚 → 改为 `wiki/adr/0008-observation-spans-attribution`(本 ADR)
- 修正时机:用户批准本 ADR → 由用户手动改 wiki(loop 不动 wiki 既有内容,§v2-5 append-only 纪律)

## Consequences

### 正面

- **OPEN-QUESTION 闭合**:round 35 遗留的「guide 矛盾 + ADR-0005 误归因」有正式 reconciliation,后续轮次可以照此推进 #4 #5 候选。
- **ADR-0005 不污染**:不动 ADR-0005 文字,只通过本 ADR 划清边界(ADR-0005 = kernel-extraction-hedge;Observation spans = 企业可观测性延伸)。
- **CHANGELOG 与 wiki 一致**:用户批准后,CHANGELOG.md:33 + observability.md:86 同向收敛到 v0.1.0。
- **版本归属清晰**:pre-1.0 不引入新机制(v0.0.x 只硬化 + 治理),Observation spans 是 v0.1.0 的 feature,符合 `STABILITY.md §1+§3` 公开 API 稳定性纪律(不提前承诺 pre-1.0 引入的能力)。

### 负面 / 风险

- **本 ADR 是 Proposed 不是 Accepted**:用户未批准前,CHANGELOG / observability.md / 实际实现均**按旧文案**(v0.2.0 vs v0.1.0 矛盾仍在)。
- **不改 ADR-0005 文字的风险**:未来 LLM session 读 ADR-0005 时,仍可能"自创"Observation spans 与 kernel-extraction 的关联(因为 ADR-0005 没显式说"我不包含 Observation")。**缓释**:本 ADR 第 D1 段已划清边界,未来 LLM 读 ADR-0005 时应同时读 ADR-0008(通过 Related 链接)。
- **版本被锁 v0.1.0**:若 v0.1.0 release 前 Micrometer Observation API 发生 breaking(可能性低,但不是 0),本 ADR 需要修订。这是 OpenFeature 的常规 trade-off。

### 不变

- ADR-0001 ~ ADR-0007 文字**不动**(§v2-4 纪律 + 本 ADR 不污染既有 ADR 纪律)。
- `MASTER_PLAN.md` 不复活(归档决策在 commit `0bc6c2b` 已 finalize)。
- WS-1.4 落地(round 24-26)不算入本 ADR,各自 commit body 自带 rationale。

## 触发重评估条件

- 若 Micrometer Observation API 出现 major breaking change → 重新评估 §D2 实现路径(a) vs (b)。
- 若 v0.1.0 提前至 v0.0.x 窗口(用户战略决策变化)→ 重新评估 §D2 版本归属。
- 若 ADR-0005 真要包含 Observation(用户认为两者应同列)→ 重新评估 §D1。
- 若 Spring Boot 5 / Java 25 churn 让 kernel-extraction 优先级上升 → ADR-0005 可能升级,本 ADR 不受影响。

## 取代 / 被取代关系

- **不取代**任何既有 ADR。本 ADR 是**新增**(gap-filling),不是修订。
- **被本 ADR 引用**:ADR-0005(划清边界)+ ADR-0006(企业可观测性是 ADR-0006 §1.4 信任算术的延伸)。
- **本 ADR 不引用**:CHANGELOG.md(由用户批准后人工同步)+ wiki/modules/observability.md(同)。

## 后续轮次引用

本 ADR 一旦被用户批准(`Status: Accepted`),允许 loop 在以下候选推进时引用:

- `AUTONOMOUS_ITERATION_LOOP.md` §10.5 P0 #1(本 ADR 自身)
- §10.5 P1 #4 chain-level `resicache.cache.operation` Observation 升级(WS-1.4 续)
- §10.5 P1 #5 Micrometer tag 白名单枚举护栏(MeterFilter)
- `COMPETITIVENESS_GUIDE.md` §5.1 「Observation 升级目标」(本 ADR 锁版本 v0.1.0 后,该段无需改)

## 当前 OPEN-QUESTION 处理决定

round 35 OPEN-QUESTION 已收敛:

| 子项 | 处理 |
|---|---|
| guide 自相矛盾(roadmap v0.0.3 vs v0.1.0) | 收敛到 v0.1.0(本 ADR §D2 + §D3 + §D4)|
| ADR-0005 误归因 | ADR-0005 文字不动,通过本 ADR §D1 划清边界 |
| 「guide §223」失锚 | 改为本 ADR 锚(`wiki/adr/0008-...`)|

round 35 OPEN-QUESTION 在本 ADR 用户批准后**正式闭合**。loop 后续推进 Observation 相关候选时**不再 defer**。

---

**最后更新**:2026-06-30
**下次维护触发**:用户批准 / 拒绝 / 修订本 ADR;或 Micrometer Observation API major breaking;或 v0.1.0 窗口变更。
**维护者**:@DavidHLP