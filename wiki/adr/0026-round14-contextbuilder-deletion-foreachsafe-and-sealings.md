---
title: "ADR-0026: Round 14 — CacheContextBuilder 删除 + ObserverRegistry.forEachSafe 异常语义统一 + SpringAnnotationAdapter/typed-key 封口"
type: adr
status: accepted
date: 2026-07-02
deciders: DavidHLP
related:
  - ADR-0009
  - ADR-0016
  - ADR-0020
  - ADR-0021
---

# ADR-0026: Round 14 — CacheContextBuilder 删除 + ObserverRegistry.forEachSafe 异常语义统一 + SpringAnnotationAdapter/typed-key 封口

## 状态

Accepted — 2026-07-02。

## 背景

`/improve-codebase-architecture` round 14 autocratic one-shot 扫描 `chain/` + `cache/` +
`annotation/` + `handler/`,逐文件通读 + deletion test 核实,筛出 4 个未被 ADR-0009~0025
触及的候选(报告 `/tmp/architecture-review-round-14-1782963998.html`,不入仓)。逐条裁决如下。

## 决策

### D1 — 删除 `CacheContextBuilder` pass-through 重复墙 + 清 `getOutput()` 兜底(候选 1,执行)

**问题**:`CacheContextBuilder` 整复制 `CacheInput.Builder` 的 8 字段 + 8 setter + build
(~30 SLOC),仅做 `new CacheContext(new CacheInput(...))` 转发,注释自承"向后兼容旧代码"。
同文件 `getOutput()` 手写方法与字段级 `@Getter` 并存,注释自承"Lombok 可能不生成…若已生成
会报 duplicate" —— 依赖编译器侥幸的不稳定状态。

**deletion test**:
- 删 `CacheContextBuilder` → 7 调用方(main 2 + test 5)迁 `CacheContext.of(CacheInput.builder()…build())`,
  复杂度净减 ~30 SLOC,新加 `CacheInput` 字段从改 2 处变 1 处(locality + leverage)。
- 核实 `getInput()` 在 src/main **0 次直接调用** → 8 个 input 委派方法是唯一入口,在挣价值,
  **保留**(本决策只动 Builder 与 getOutput 兜底)。
- `getOutput()` 19 处调用,删手写后靠字段级 `@Getter` 生成(Lombok 全项目启用已证)。

**落地**:`CacheContext` 删 `CacheContextBuilder` + `builder()` 静态方法 + 手写 `getOutput()` +
尴尬注释 + 2 个死 AttributeKey 常量(见 D3);7 调用方迁 `CacheContext.of(CacheInput.builder()…build())`;
`chain/package-info.java` javadoc 示例同步。

### D2 — `ObserverRegistry.forEachSafe` 统一两 engine 异常语义(候选 2,执行)

**问题**:同一 `ObserverRegistry<O>` seam,两消费者对「observer 抛异常是否中断主链」语义不一致:
`ChainEngine` 裸调 `forEach`(异常冒泡,仅 try/finally 保证 onChainEnd 配对),
`AnnotationChainEngine` 自写 try-catch(吞 + 记 ERROR)。`AnnotationChainEngineTest.observerException_doesNotBlockChain`
注释声称"与 ChainEngine.execute 行为一致"实际为假 —— invariant 隐藏在两份实现,未在 interface 声明。

**裁决**:统一为「吞 + 记 ERROR」(observer 是观测旁路,其失败不阻断主链)。新增
`ObserverRegistry.forEachSafe(Consumer)`,内部 try-catch + log observer 类名 + 异常,主链继续;
保留 `forEach(Consumer)` 给纯遍历与 registry 契约测试。`ChainEngine` 6 处 observer 遍历 +
`AnnotationChainEngine` 2 处全部改用 `forEachSafe`(消除 try-catch 重复)。

**leverage**:新增第 3 个 observer-bearing engine → 调同一 `forEachSafe` seam,零重复;invariant
浮出到 utility(ADR-0016 收敛列表管理,本 ADR 补齐遍历异常语义)。

### D3 — 删除 `AttributeKey` 2 个死常量(候选 4 部分,执行)

`CacheContext.AttributeKey.CACHE_HIT` / `ASYNC_REFRESH_TASK_ID` 全项目(含 test)0 引用,死代码。
删除,保留在用的 `EARLY_EXPIRATION_SKIPPED` + `PREFETCHED_CACHED_VALUE`。

### D4 — `SpringAnnotationAdapter` 三 build 方法 hasText 守卫墙 — 封口不动(候选 3)

**问题**:`buildRedisCacheable/Put/EvictOperation` 各重复 6 字段(key/condition/unless/
keyGenerator/cacheManager/cacheResolver)× 3 的 `if (StringUtils.hasText(...)) setXxx(...)` 守卫。

**裁决不动**:3 类 builder 异构(Spring 原生 `CacheableOperation.Builder` 用 setXxx 风格 vs
ResiCache `RedisCachePutOperation.Builder` / `RedisCacheEvictOperation.Builder` 用 fluent xxx
风格),统一需 Functional interface 包装 6 字段 × 3 类型 = 18 lambda,可读性反降。**与 ADR-0020
同源裁决**(该 ADR 在 `AnnotationParser` 显式拒绝同款模板化,理由"Builder 类型不同")。本 ADR
把 `SpringAnnotationAdapter` 这处也纳入封口,避免未来 round 重复提议。

**遗留观察**(不入本 ADR 范围):三个方法产出三种不同类型 Operation(Cacheable 走 Spring 原生,
Put/Evict 走 ResiCache 自有)—— 类型不对称根因涉及 Spring Cache 抽象契约,属 1.0 级重设计,
留待未来触发器。

### D5 — typed `AttributeKey<T>` — 封口不动(候选 4 部分)

**问题**:`<T> T getAttribute(String key)` 的 `(T)` 强转无编译时类型安全。

**裁决不动**:`AttributeKey` 常量已缓解 magic string;仅 2 个在用 key,typed-key 重构成本 > 收益
(Java 属性袋固有代价)。本 ADR 封口,避免未来重复提议。

## 落地影响

**文件变更**:
- 修改 5 main:`CacheContext.java`(-~30 SLOC:删 Builder/getOutput/2 死常量)、
  `ObserverRegistry.java`(+`forEachSafe` + `@Slf4j`)、`ChainEngine.java`(6 处 `forEach`→`forEachSafe`)、
  `AnnotationChainEngine.java`(2 处 `forEachSafe` 去 try-catch + javadoc 对齐)、
  `RedisProCacheWriter.java`(2 处构造迁移 + import `CacheInput`)、`chain/package-info.java`(javadoc 示例迁移)
- 修改 5 test:5 处 `CacheContext.builder()` 构造迁移 + import `CacheInput`

**验证**:
- `mvnw verify -B -Dmaven.javadoc.skip=true` —— **BUILD SUCCESS, 782 tests, 0 failures, 0 errors;
  All coverage checks have been met**(38.5s)
- **0 公开 API 变化**:`CacheContext.builder()` / `CacheContextBuilder` 是内部 pass-through,
  无外部调用方;`ObserverRegistry.forEach` 保留,`forEachSafe` 纯增量;`AttributeKey` 死常量删除
  无引用方。

## 相关

- [[0009-chain-engine-extraction]] — ChainEngine + ChainObserver 双 seam
- [[0016-observerregistry-extraction]] — ObserverRegistry 列表管理 seam(本 ADR 补齐其遍历异常语义)
- [[0020-annotation-targets-annotatedelement-seam]] — Builder 模板化封口(本 ADR D4 同源)
- [[0021-redis-cache-attributes-applyto-seam-and-protection-toggle]] — applyTo seam(本 ADR 候选筛选基线)
