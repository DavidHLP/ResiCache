---
title: 操作日志
type: meta
tags:
  - meta
  - log
  - timeline
related: [index, overview, README, archive-2026-q2, milestone-2026-q3]
status: stable
created: 2026-06-21
updated: 2026-07-03
---

# 操作日志

wiki 演化的时间线,倒序排列。**每条一行:`- [YYYY-MM-DD] <op> | <主题>`**,可被 `grep '^- \[' log.md` 解析。

> **维护纪律**:本文件只记「日期 + 主题」摘要,**不 append round-by-round 细节**。
> - 架构决策的完整 rationale → 对应 `adr/NNNN-*.md`(条目里已标 ADR 号)。
> - 单 commit SHA 级细节 / 旧 autonomous-loop(round 1–42)→ [[log/archive-2026-q2]]。
> - 里程碑状态 → [[milestone-2026-q3]]。

---

## 2026-07-04

- [2026-07-04] improve | ADR-0039(round 29)| `CacheResult` 5 字段共享袋 → 2 字段(删 `hit`/`rejectedByBloomFilter`/`exception` 死字段 + 死读法,`failure(e)`→`failure()`)+ `NoOpChainObserver` YAGNI 单例整删(ADR-0038 同构漏网;byte-equivalent;CR 自审斩 3 漏改测试文件;6 测试类绿)→ [[0039-cacheresult-dead-fields-and-noop-removal]]
- [2026-07-04] improve | ADR-0038(round 28)| 3 处零调用死代码清理(`CachedValue.withExpired/withAccessUpdate` 零调用 wither + `HandlerPriority.order()` deprecated 零读取死参数 + `NoOpAnnotationChainObserver` YAGNI 单例整删;byte-equivalent;Explore agent 6 候选核实斩 3 误诊;附带 index.md ADR-0033 错位修正)→ [[0038-cachedvalue-wither-handlerpriority-order-noop-observer-dead-code-removal]]
- [2026-07-04] improve | ADR-0037(round 27)| `TwoListLRU` 锁 wrapper 死代码 + false seam 删除(`readLockForKey` 零调用 + `writeLockForKey` 误导命名 + `promoteNodeSafe` 零语义包装;byte-equivalent,39 eviction 测试全绿;附带 eviction.md ADR-0010 stale 残留清理)→ [[0037-twolistlru-lock-wrapper-dead-code-and-false-seam-removal]]

## 2026-07-03

- [2026-07-03] improve | ADR-0036(round 26)| PrefetchDecision 类型化(attributes 3 业务 key 收编)+ Interceptor activate 归位(消除跨包寄生)+ Lua 外置 EarlyExpirationScripts(守 0029);C4 HierarchicalBloom 撤销(@Primary 默认部署)→ [[0036-prefetch-decision-interceptor-activate-lua-script]]
- [2026-07-03] fix(env) | WSL2 `docker pull` IPv6 timeout → 三层兜底(daocloud mirror + daemon 代理 drop-in + client 代理)→ [[fix-docker-pull-ipv6-timeout]]
- [2026-07-03] improve | ADR-0035(round 25)| async snapshot/restore 跨域寄生归位 MethodMetadataResolver.runWithSnapshot(writer 删 30 行 withMethodMetadataSnapshot + 5 import;MDC 一并内聚;byte-equivalent)→ [[0035-async-snapshot-resolver-attribution]]
- [2026-07-03] improve | ADR-0034(round 25)| `RedisProCacheWriter` context-build 三路分裂 → 单一 9参 buildContext seam + resolveOperation helper(clean `setKeyPattern` 后置 mutate 尾巴清)→ [[0034-writer-context-build-single-seam]]
- [2026-07-03] improve | ADR-0033(round 24)| `CacheOutput` 共享可变袋 → typed per-handler decisions(`TtlDecision`/`NullDecision`),`CacheOutput.java` 97 SLOC 整删 → [[0033-cacheoutput-typed-decisions]]
- [2026-07-03] improve | ADR-0032(round 23)| `MetadataKeys` 收敛 chain 包 reflectField + cast-instanceof seam → [[0032-metadata-keys-extract-seam]]
- [2026-07-03] improve | ADR-0031(round 22)| `RedisProCache` try-finally timing 样板 → `RedisProCacheTimers` 工具 seam → [[0031-redisprocache-timing-helper-seam]]
- [2026-07-03] improve | ADR-0030(round 21)| `RedisProCacheWriter` 死 protected 方法删除(deletion test 通过)→ [[0030-redisprocachewriter-dead-accessors-removal]]
- [2026-07-03] improve | ADR-0028/0029(round 20)| `OperationFactory` seam 收窄 + `applyText` Consumer 化 + hypothetical seam 接受 → [[0028-operationfactory-seam-narrowing-and-applytext]] · [[0029-single-adapter-hypothetical-seams-acceptance]]

## 2026-07-02

- [2026-07-02] improve | ADR-0027(round 19)| `@RedisCachePut`/`@RedisCacheEvict` AnnotationParser 对齐 Spring 标准类 + 单注解探测修补(纠正 4 轮 ADR 的环境误诊)→ [[0027-annotation-parser-put-evict-spring-standard-alignment]]
- [2026-07-02] improve | ADR-0026(round 14)| `CacheContextBuilder` 删除 + `ObserverRegistry.forEachSafe` + 候选 3/4 封口 → [[0026-round14-contextbuilder-deletion-foreachsafe-and-sealings]]
- [2026-07-02] improve | ADR-0025(round 17)| early-expiration 决策 policy seam 迁出 `TtlPolicy`(refresh↔avalanche 跨域寄生方法 + `Clock` 依赖归位)→ [[0025-early-expiration-policy-seam-extraction]]
- [2026-07-02] fix(test)| round 18 | `ChainEngineTest.executeFragment` 同步 ADR-0022 语义 + `RedisCacheSemanticsIT` 真实失败发现

## 2026-07-01

- [2026-07-01] improve | ADR-0024(round 16)| early-expiration 线程池配置接入 seam(兑现 dead config + `EarlyExpirationSupport` stale wiki 清理)→ [[0024-early-expiration-pool-config-seam]]
- [2026-07-01] improve | ADR-0023(round 15)| Executor graceful-shutdown seam(`ThreadPoolEarlyExpirationExecutor.shutdown` 两段样板收敛)→ [[0023-executor-graceful-shutdown-seam]]
- [2026-07-01] improve | ADR-0022(round 14)| Chain single-representation seam(消除 next 指针双轨,统一 List 快照 index 推进,修并发隔离漏洞)→ [[0022-chain-single-representation-seam]]
- [2026-07-01] improve | ADR-0021(round 13)| `RedisCacheAttributes.applyTo(B)` seam + `ProtectionToggle` Function 化 → [[0021-redis-cache-attributes-applyto-seam-and-protection-toggle]]
- [2026-07-01] improve | ADR-0020(round 10)| `AnnotationTargets` 反射多态 seam(23 处 `instanceof Method/Class` 收敛为 `AnnotatedElement`)→ [[0020-annotation-targets-annotatedelement-seam]]
- [2026-07-01] improve | ADR-0019(round 9)| `RedisCacheAttributesProjector.FieldSource` seam(3 处 `from()` 26-line 重复墙收敛)+ int/long type-drift 留待 1.0 → [[0019-projector-fieldsource-seam-and-type-drift-deferral]]
- [2026-07-01] improve | ADR-0018(round 8)| `AbstractCacheHandler` 语义 counter 模板方法 seam(5 个 `onAttachMetrics` 样板收敛)→ [[0018-semantic-counter-template-method]]
- [2026-07-01] improve | ADR-0017(round 7)| `Operation.fromAttributes` 静态 seam(Factory materialize 1-liner 委派)→ [[0017-operation-fromattributes-seam]]
- [2026-07-01] improve | ADR-0016(round 6)| `ObserverRegistry` 抽出 + `RedisProCacheManager` instantiate seam 收敛 → [[0016-observer-registry-seam-and-manager-instantiate-seam]]
- [2026-07-01] improve | ADR-0015(round 6)| `AnnotationHandler.registerAll` 批量注册模板下沉 → [[0015-annotation-handler-registerall-deepening]]
- [2026-07-01] improve | ADR-0013 | `AnnotationChainEngine` + `AnnotationChainObserver` 抽出(平行 ADR-0009 seam)→ [[0013-annotation-chain-engine-extraction]]
- [2026-07-01] improve | ADR-0012(round 3)| interceptor 残骸收敛 + `EarlyExpirationSupport` 浅模块删除 → [[0012-interceptor-consolidation-and-shallow-module-removal]]
- [2026-07-01] improve | ADR-0009 | `ChainEngine` + `ChainObserver` 抽出(3 切片一次落地)→ [[0009-chain-engine-extraction]]

## 2026-06-30

- [2026-06-30] improve | ADR-0011 | Bloom 键漂移修复 + `CacheKeys` 键派生 seam(sync+bloom 静默 null)→ [[0011-bloom-key-drift-fix-and-cachekeys-seam]]
- [2026-06-30] improve | ADR-0010 | Attributes 投影层 + `TwoListEvictionStrategy` 删除(A+B+C 三候选合并落地)→ [[0010-attributes-projection-and-strategy-deletion]]
- [2026-06-30] improve | TTL/NullValue Policy 升为真 seam + 评审候选核验
- [2026-06-30] improve | per-handler 语义 counter 装配单轨化(metrics deepening)
- [2026-06-30] init | Q3 里程碑启动 + 旧 plan 归档 + log 精简(101.8KB→28KB)→ [[milestone-2026-q3]]

## 2026-06-29

- [2026-06-29] improve | Path C 收官 + WS-1.4/1.5 + 工作集文档归档 + wiki 同步
- [2026-06-29] FIRE | WS-1.1 FIRE M0–M4 闭环 + Path C 7 步序列收官
- [2026-06-29] review | 架构评审 6 候选 C1–C6:C2/C3 落地(删 4 单实现接口 + Writer executeChain 收敛,净 -187 行),C1/C4/C5/C6 诊断有误跳过

## 2026-06-28

- [2026-06-28] update | WS-1.2 硬化(fail-fast + Cluster hash-tag + 布隆 rebuilding 窗口)

## 2026-06-27

- [2026-06-27] improve | 多 AI CR 修复轮(可维护性 / 合规 / 安全)
- [2026-06-27] improve | v0.0.3 文档诚实化 + 代码护栏 + 4 份 ADR(移除 `wrapper/`/`spi/`/`event/`/`evaluator/` stale facts)

## 2026-06-21

- [2026-06-21] colorize | `graph.json` 按目录着色
- [2026-06-21] improve | 完善 obsidian 设计
- [2026-06-21] lint | 发现并修复 CLAUDE.md / README 的 stale facts
- [2026-06-21] init | 创建 ResiCache LLM Wiki 知识库
- [2026-06-21] ingest | 将 `docs/wiki/` 提升为顶层 `wiki/`

---

**归档**:2026-06-21 ~ 06-30 的 round-by-round 细节 + autonomous-loop v1/v2(round 1–42)见 [[log/archive-2026-q2]]。
