---
title: ADR-0039 CacheResult 共享袋死字段 + NoOpChainObserver 删除
type: adr
status: accepted
created: 2026-07-04
related: [0033, 0038, 0009, 0016]
---

# ADR-0039:CacheResult 共享袋死字段 + NoOpChainObserver 删除

## Context

ADR-0033 删除了 `CacheOutput` **写入侧**共享袋,但**结果侧**的 `CacheResult`(5 字段:`success` / `hit` / `resultBytes` / `rejectedByBloomFilter` / `exception`)仍是同胞残骸。Round 29 HTML architecture review 定位三个零生产读者死字段:

- `hit` —— 与 `resultBytes != null` 在 success 前提下同构,冗余;
- `rejectedByBloomFilter` —— bloom 域私有语义寄生通用结果层,且 `BloomFilterHandler` 自身用 `CacheResult.miss()` 表达拒绝(`BloomFilterHandler:88`),该字段 + 工厂 + 读法三连**零生产引用**(grep 核实);
- `exception` —— `failure(e)` 的附属信息,`CacheErrorHandler` 已在 `log.error` 记录异常,`getException()` 零生产读者。

同时 `chain/observer/NoOpChainObserver`(22 SLOC 单例)是 ADR-0038 删除的 `handler/NoOpAnnotationChainObserver` 的**同胞复刻漏网**:`INSTANCE` 生产零引用,`ChainObserver` 接口全 default no-op,`ObserverRegistry`(ADR-0016)已处理空列表,占位单例多余。

## Decision

**C1 + C2 合并落地**(round 29 HTML review Top recommendation):

1. **CacheResult 收敛为 2 字段**(`success` / `resultBytes`):
   - 删 `hit` / `rejectedByBloomFilter` / `exception` 字段;
   - 删 `rejectedByBloomFilter()` 工厂 + `isRejectedByBloomFilter()` / `isFailure()` / `hasResult()` 读法(零生产读者);
   - `failure(Exception e)` → `failure()`(无参,`e` 已在 `CacheErrorHandler.log.error` 记录);
   - 保留 `miss()` 作为 `success()` 的"未命中"语义别名(字节同构但保留调用方可读性,deletion test 通过)。

2. **删 `NoOpChainObserver.java`**(整类):测试改用空 `ObserverRegistry` / inline 匿名;`ChainEngineTest` 删 unused import,`ChainObserverTest` 删 `NoOpTests` + javadoc。

## 路径裁决(The Only Path)

存在 X(`CacheResult` 改 record 激进深化)与 Y(删死字段保守深化)歧路。**彻底扼杀 X**:record 迁移牵动 5 工厂 + `CacheResultTest` builder + 链出口公共契约,且 `CacheResult` 是"链出口聚合结果",与 ADR-0033 per-handler typed decisions(`TtlDecision`/`NullDecision`)职责不同,无需 typed 化。**直接采用 Y**:死字段零生产读者,byte-equivalent 零风险。

## 内部红蓝博弈(CR & Fix)

Plan 阶段 grep 字段读者只扫 `src/main`,CR 阶段自我审计捕获漏洞:`src/test/` 下 `CacheErrorHandlerTest`(~20 处 `isFailure`/`isHit`/`getException`)、`ActualCacheHandlerTest`(`isHit` ×4 + `failure(exception)` ×3)、`BloomFilterHandlerTest`(`failure(..)` ×1)三文件漏改。直接 Fix:机械等价改造(`isFailure`).isTrue → `isSuccess`).isFalse;`isHit` → `getResultBytes`;`failure(exception)` → `failure()`),6 测试类全绿(EXIT=0)。

## Consequences

- **正面 locality**:bloom 决策内聚回 bloom handler,不再寄生通用 Result;
- **正面 leverage**:Result 与 `HandlerResult.decision` 控制流单点,消除 `success`/`miss` 歧义;
- **正面 一致性**:对齐 ADR-0038 裁决,消除 chain / handler 两包 NoOp 不对称;
- **规模**:净 −22 SLOC(NoOp)+ CacheResult 死字段/死读法(5 字段→2);
- **负面**:无(`failure` 不再存 exception,但零生产读者;若未来需读,可恢复)。

## deletion test

| 对象 | 删除效果 | 裁决 |
|---|---|---|
| `hit` / `rejectedByBloomFilter` / `exception` | 浓缩(死代码消除,零行为变更) | 删 |
| `miss()` | 移动(调用方失语义表达) | 留(语义别名) |
| `NoOpChainObserver` | 浓缩(YAGNI,ObserverRegistry 已处理空列表) | 删 |
