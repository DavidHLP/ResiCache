---
title: 操作日志 · 2026 Q3 归档
type: meta
tags:
  - meta
  - 日志
  - 归档
related: [log, milestone-2026-q3, archive-2026-q2]
status: archived
created: 2026-07-04
updated: 2026-07-04
---

# 操作日志 · 2026 Q3 归档

> **归档窗口**:2026-06-30(Q3 启动)~ 2026-07-04(round 31 / ADR-0041)。
> **前置**:autonomous-loop v1/v2(round 1–42)已归档至 [[archive-2026-q2]]。
> **季中归档**:Q3 季度尚在进行,本页为 07-04 时点的**阶段性归档**(Q3 季末再追加)。承诺出处:[[milestone-2026-q3]]「季末归档」纪律。
> **摘要 vs 细节**:[[log]] 保留「日期 + 主题」摘要(append-only);本页沉淀 commit SHA 级脉络 + 阶段总结。ADR rationale 不在本页复制——见对应 `adr/NNNN-*.md`。

Q3 共 **44 commits** / **33 ADR**(ADR-0009 ~ ADR-0041),主线 = **责任链 seam 抽出 + 样板收敛 + 死代码扫尾**。

## 阶段 0:Q3 启动与文档归零

| SHA | 内容 |
|---|---|
| `c39f198` | round 42 wait-state(ADR-0008 pending user review)|
| `7b120db` | close autonomous-loop-v2 + start Q3 optimization milestone |
| `1acb9f9` | remove Q3 forward-planning content + closed v2 plans |
| `b1f7f39` | docs: remove future-planning + resolve cross-doc conflicts |
| `cb61032` | wiki sync cleanup — drop forward-planning + repair cross-doc |

**主题**:从 autonomous-loop v2(过程驱动)切换到 Q3 milestone(目标驱动);清文档中的 forward-planning(未来规划)与跨文档冲突,确立「源码变了→wiki→log」单向流。

## 阶段 1:Chain Engine 抽出与 seam 化(ADR-0009 ~ 0013)

| SHA | ADR | 内容 |
|---|---|---|
| `855d95d` / `736d588` | ADR-0009 | ChainEngine + ChainObserver 抽出(责任链推进 + 观测收口到单一 seam)|
| `da0d108` | — | log round 43 — ADR-0009 提交记录 |
| `c00db15` | ADR-0011 | Bloom 键漂移修复 + CacheKeys 键派生 seam(sync+bloom 静默 null)|
| `1a0735f` | ADR-0010 | RedisCacheAttributes 投影层 + TwoListEvictionStrategy 删除 |
| `647ce11` | ADR-0012 | Path C interceptor 残骸收敛 + EarlyExpirationSupport 浅模块删除 |
| `6809272` | ADR-0013 | AnnotationChainEngine + AnnotationChainObserver 抽出(平行 0009 seam)|

**主题**:把责任链推进从 handler 内联抽到 ChainEngine 单一 seam(ADR-0022 的前序);消除 Path C 残骸 + 浅模块。

## 阶段 2:构造/注册/工厂 seam 收敛(ADR-0014 ~ 0017)

| SHA | ADR | 内容 |
|---|---|---|
| `32fbcf4` | ADR-0014 | collapse constructor telescoping on RedisProCache + Manager |
| `ca5404d` | ADR-0015 | AbstractAnnotationHandler.registerAll 批量注册模板下沉 |
| `a88b67b` | ADR-0016 | ObserverRegistry 抽出 + RedisProCacheManager instantiate seam |
| `067f8ed` | ADR-0017 | XxxOperation.fromAttributes 静态 seam(Factory materialize 1-liner)|

**主题**:构造器重载墙收敛 + 批量注册样板下沉 + factory→operation 1-liner 委派。

## 阶段 3:样板收敛 + 反射多态 seam(ADR-0018 ~ 0020)

| SHA | ADR | 内容 |
|---|---|---|
| `cfa552c` | ADR-0018 | AbstractCacheHandler 语义 counter 模板方法(5 处 onAttachMetrics 样板)|
| `3d923fc` | ADR-0019 | RedisCacheAttributesProjector FieldSource seam(3×26-line 重复墙)+ int/long type-drift defer |
| `553879a` | ADR-0020 | AnnotationTargets 反射多态 seam(23 处 instanceof → AnnotatedElement)|

**主题**:per-handler 样板用模板方法收敛;投影层重复墙用 FieldSource 私有 record 收敛;反射 instanceof 用多态收敛。

## 阶段 4:applyTo + Chain 单一表示 + 配置接入(ADR-0021 ~ 0025)

| SHA | ADR | 内容 |
|---|---|---|
| `ea5f4b2` | ADR-0021 | RedisCacheAttributes.applyTo(B) seam + ProtectionToggle Function 化 |
| `f5f883f` | ADR-0022 | chain single-representation seam(删 next 指针双轨,统一 List index 推进,修并发隔离漏洞)|
| `f51324e` | ADR-0023 | executor graceful-shutdown seam(两段逐字重复样板收敛)|
| `560da32` | ADR-0024 | early-expiration 线程池配置接入(兑现 dead config)|
| `5dd7eba` | ADR-0025 | early-expiration 决策 policy seam 迁出 TtlPolicy(refresh↔avalanche 跨域寄生归位)|

**主题**:字段映射归属字段拥有者;链表示从「next 指针 × List 快照」双轨收敛为单一 List index 推进(并发安全升级);executor 样板 + dead config 兑现;跨域寄生方法归位。

## 阶段 5:Round 14 封口 + Annotation 对齐 + Factory 收窄(ADR-0026 ~ 0029)

| SHA | ADR | 内容 |
|---|---|---|
| `ad0ff24` | — | test: sync executeFragment_skipsAroundChain to ADR-0022 |
| `f5cf1f6` | ADR-0026 | 删 CacheContextBuilder + ObserverRegistry.forEachSafe 异常语义统一 + 候选封口 |
| `bbcb3a0` | ADR-0027 | @RedisCachePut/@RedisCacheEvict AnnotationParser 对齐 Spring 标准类(纠正 4 轮环境误诊)|
| `4732fd7` | ADR-0028 | OperationFactory seam 收窄(删 supports 死链 + create 5参→3参 + 删 AbstractOperationFactory)+ applyText Consumer 化 |
| (合并于 0028) | ADR-0029 | 单-adapter hypothetical seam 接受(MethodMetadataResolver + BloomHashStrategy 锁定不删)|

**主题**:Round 14 遗留封口;纠正 4 轮 ADR 的环境误诊;factory 死链清除;明确「可逆性对冲」seam 接受策略(不删)。

## 阶段 6:Writer / Metadata / Typed decisions(ADR-0030 ~ 0033)

| SHA | ADR | 内容 |
|---|---|---|
| `ab82079` | ADR-0030 | 删 RedisProCacheWriter.getTtl/getExpiration 死 protected 方法 |
| `bfc6e26` | ADR-0031 | RedisProCache 6 处 try-finally timing 样板 → RedisProCacheTimers seam |
| `30490ee` | ADR-0032 | MetadataKeys seam 收敛 chain 包 reflectField + cast-instanceof |
| `a8f085f` | ADR-0033 | **CacheOutput 共享可变袋 → typed per-handler decisions**(TtlDecision/NullDecision records)|

**主题**:死方法删除;timing/metadata 样板收敛;**关键深化**——9 字段共享袋类型化为 per-handler owned decisions(locality-first 转折点)。

## 阶段 7:Writer context 寄生归位 + Prefetch/Lua(ADR-0034 ~ 0036)

| SHA | ADR | 内容 |
|---|---|---|
| `8954bd1` | ADR-0034 | Writer context-build 三路分裂 → 单一 9参 buildContext seam |
| `9953a02` | ADR-0035 | async snapshot/restore 跨域寄生归位 MethodMetadataResolver.runWithSnapshot |
| `8a37dbd` | ADR-0036 | PrefetchDecision 类型化 + interceptor activate 归位 + Lua 外置 EarlyExpirationScripts;C4 HierarchicalBloom 撤销(@Primary)|

**主题**:Writer 三路 context 分裂收敛;MDC + async snapshot 寄生归位;attributes 业务 key 类型化;守 ADR-0029 不删 Lua adapter。

## 阶段 8:死代码扫尾系列(ADR-0037 ~ 0041)

| SHA | ADR | 内容 |
|---|---|---|
| `172f752` | ADR-0037 | TwoListLRU 锁 wrapper 死代码 + false seam 删除 |
| `c17fd44` | ADR-0038 | CachedValue wither + HandlerPriority.order + NoOpAnnotationChainObserver 死代码 |
| `00e3958` | ADR-0039 | CacheResult 5 字段共享袋 → 2 字段 + NoOpChainObserver 整删 |
| `b27d4a7` | ADR-0040 | LockContext.noLock + NullDecision.passthrough 死工厂删除 |
| `f0c7381` | ADR-0041 | RedisProCacheConfiguration.cacheManager + buildInitialCacheConfigurations ObjectMapper 死参数删除 |

**主题**:Typed decisions(ADR-0033)落地后的**同构扫尾**——所有「共享袋 / 死工厂 / 死参数 / false seam / NoOp YAGNI」逐个过 deletion test 删除。byte-equivalent 系列续篇。

## 阶段 9:文档诚实化与测试基建

| SHA | 内容 |
|---|---|
| `e01b7c3` | WSL2 Docker pull IPv6 timeout 故障排查 + 修复指引 |
| `980a9fc` | test(it):WSL2 native docker 下 testcontainers redis 集成测试全跑通 |
| `e29b3b4` | docs:WSL2 native docker + testcontainers 集成测试修复指南 |
| `756d16d` | 瘦身 log.md 至一行摘要规范 + 清除 meta 冗余 |

**主题**:WSL2 + 大陆网络环境下的 Docker / Testcontainers 集成测试修复(实战指南入库);log.md 规范化为「日期 + 主题」单行摘要。

## 经验总结(从 Q3 31 轮沉淀)

1. **typed decisions > 共享可变袋**:ADR-0033 的 CacheOutput 类型化是 Q3 最高 leverage 转折——后续 ADR-0037~0041 全是它的同构扫尾。**把共享袋按 owner 拆成 per-handler records,locality 自动浮现**。
2. **seam 的两种命运**:真扩展点(BloomIFilter / LockManager / MethodMetadataResolver / BloomHashStrategy)→ 文档化 + `@ConditionalOnMissingBean` 保留;false seam(单实现 + 非扩展点 + 删除集中复杂度)→ 删。ADR-0029 / ADR-0037 分别是两种裁决的样本。
3. **byte-equivalent 是深化期的安全网**:Q3 后半段(0037~0041)全是 byte-equivalent 死代码删除,745 测试兜底,零行为风险。**收益递减期,byte-equivalent cleanup 是唯一负责任的深化方式**。
4. **跨域寄生是隐性 friction 主源**:ADR-0025(决策寄生 TtlPolicy)/ ADR-0035(snapshot 寄生 writer)/ ADR-0036(activate 寄生跨包)——方法定义在错误 owner 是 30 轮深化后仍能找到的真实 friction。
5. **文档诚实化是硬纪律**:阶段 0 的 forward-planning 清理 + 阶段 9 的 log 规范化,保证 wiki 不撒谎。CI `docs-link-check` 护栏(黑名单防移除特性复发 + 白名单校验关键类)是纪律的技术兜底。
6. **环境韧性**:WSL2 + 大陆网络的 Docker/Testcontainers 修复(阶段 9)是集成测试能跑的前提——文档化这些 fix 让后续会话不重蹈覆辙。

## 归档维护

- 本页为 **Q3 季中归档**(2026-07-04);Q3 季末(09-30)再追加阶段 10+。
- 追加方式:在「阶段 N」续表 + 更新本 frontmatter `updated` + [[log]] 加一条 `archive | Q3 季中归档`。
- ADR rationale 永远在 `adr/NNNN-*.md`,本页只存 SHA 脉络 + 阶段总结,不复制决策细节。
