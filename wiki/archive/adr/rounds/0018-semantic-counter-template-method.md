---
title: "ADR-0018: AbstractCacheHandler 语义 counter 模板方法 seam (5 个 onAttachMetrics handler 子类样板收敛)"
type: adr
status: accepted
date: 2026-07-01
deciders: DavidHLP
related:
  - ADR-0009
  - ADR-0015
  - ADR-0016
  - ADR-0017
tags:
  - chain
  - handler
  - observability
  - counter
  - template-method
  - deepening
  - round-8
---

# ADR-0018: AbstractCacheHandler 语义 counter 模板方法 seam

## 状态

- **Status**: Accepted
- **Date**: 2026-07-01
- **Deciders**: DavidHLP
- **Related**: ADR-0009 (Chain Engine) / ADR-0015 (registerAll) / ADR-0016 (ObserverRegistry) /

## 背景

`/improve-codebase-architecture` round 7 log 显式延后到 round 8 的候选 G:
5 个 protection handler 子类 (`NullValueHandler` / `TtlHandler` / `SyncLockHandler` /
`BloomFilterHandler` / `EarlyExpirationHandler`) 的 `onAttachMetrics(MeterRegistry)`
样板重复,每 handler 各持一个 `Counter xxxCounter;` 字段 + 5 行
`onAttachMetrics` body 调 `registerCounter`, + 1 处 `safeIncrement(xxxCounter)` 自增站点。

Log 当时 defer 的理由是 "合并易失语"(每 handler counter 名 unique, 合并会丢语义)。
本 ADR 重新评估后,采纳以下辨别:**合并 counter 名会失语;下沉注册样板不会**。
后者正是 ADR-0015 `registerAll` 与 ADR-0017 `Operation.fromAttributes` 的同构
模板方法模式 — 保留 counter 名字的独特性,只让基类接管"取 registry 构 counter 存字段"
的注册样板,以及"null 检查自增"的自增样板。

## 决策

### D1 — 基类 `semanticCounter()` 模板方法 (执行)

**问题陈述**: 5 个 protection handler 各写 5 行 `onAttachMetrics`:

```java
// 1. NullValueHandler (1 处)
this.nullHitCounter = registerCounter(registry,
        "resicache.handler.null.hit",
        "Null value encountered on PUT ...");

// 2. TtlHandler (1 处) — 与上同构, 仅 name/description 字段名不同
// 3. SyncLockHandler (1 处)
// 4. BloomFilterHandler (1 处)
// 5. EarlyExpirationHandler (1 处)
```

外加每 handler 持有 `private Counter xxxCounter;` 字段 (5 处) +
`import io.micrometer.core.instrument.Counter;` + `import ...MeterRegistry;`
(10 处) + `safeIncrement(xxxCounter)` 自增站点 (5 处)。

**形态对比**:
- 当前: 5 × (1 字段 + 1 override body 5 行 + 2 imports + 1 自增) = 5 处样板
- 模板版: 5 × (1 `CounterMetadata` 元数据声明 3 行 + 1 `safeIncrementSemantic()` 调用)
- **interface ≈ implementation** (浅模块病征: 5 个 onAttachMetrics 接口对应 5 套样板)

**deletion test**:
- 删 5 处 `onAttachMetrics` 委派到基类 `semanticCounter()` + 字段上收 → 行为完全一致
  (counter 仍按各自名字注册, 语义零变化)
- 删基类 `semanticCounter()` 与 `semanticCounter` 字段 → 5 处 onAttachMetrics
  重新出现 + 5 个独立 null-prone 字段 → 浅模块病征回归

`semanticCounter()` 在 **用 1 个 4 行模板抵消 5 个 5 行样板 + 5 个 null-prone 字段**。

**决策**: 基类暴露:
- `public record CounterMetadata(String name, String description) {}` (Java 21 record)
- `protected CounterMetadata semanticCounter() { return null; }` — 默认 no-op
- `private Counter semanticCounter;` — 基类唯一持有的语义 counter 字段
- `protected void safeIncrementSemantic()` — 取代原 `safeIncrement(Counter)` 多参版本

`attachMeterRegistry(MeterRegistry)` 改造: registry 非空时调 `semanticCounter()`,
非 null 元数据则用 `registerCounter(...)` 注册到基类字段。子类不再写注册样板。

**关键设计**:
- **Java 21 record + 单 accessor**: Tell-Don't-Ask, 子类一次 declare 名+描述
- **`null` = 不需要语义 counter**: 保留 no-op 语义, ActualCacheHandler / PostProcessHandler
  等不需要 counter 的 handler 零配置
- **`safeIncrementSemantic()` 替代 `safeIncrement(Counter)`**: 字段归基类持有,
  自增无参数, 消除调用方传字段的样板

**落地**:
- `AbstractCacheHandler.java`: 新增 `CounterMetadata` record + `semanticCounter()` 模板 +
  `safeIncrementSemantic()` helper + 改写 `attachMeterRegistry` body (8 SLOC)
- 5 个 handler 子类: 删 5 个 `private Counter xxxCounter;` 字段 + 删 5 个 `onAttachMetrics`
  override + 删 10 个 `Counter`/`MeterRegistry` imports + 5 处 `safeIncrementSemantic()` 委派
- 0 个 public API 删除, 0 个 protected API 删除 (旧 `onAttachMetrics(MeterRegistry)` 与
  `safeIncrement(Counter)` 是基类 protected hook 而非 public API)

### D2 — `safeIncrement(Counter)` 旧 helper 删除 (执行)

**问题陈述**: 旧 `protected void safeIncrement(Counter counter)` 多参版本, 5 个
handler 中每个用一次 (`safeIncrement(xxxCounter)`)。Counter 字段上收后, 唯一
调用方消失, helper 自身被 `safeIncrementSemantic()` 替代。

**决策**: 删除 `safeIncrement(Counter)` 多参版本。理由:
- 唯一调用方被新 API 替代 (D1)
- 保留会成为"用 0 次"的死代码 (JaCoCo 0% 覆盖)
- 与 `RedisProCache` 的同名 private helper 隔离 (类内 private, 不影响)

**落地**: 基类少 1 个 protected method (5 SLOC body)。

### D3 — `registerCounter(MeterRegistry, String, String)` 保留 (有意)

**未执行原因**:
- 当前唯一调用方是基类 `attachMeterRegistry` (D1 改造后内部使用)
- 但 protected 暴露为"未来子类需要自定义 counter 注册逻辑"的扩展点
- 0 SLOC 减少 (1 行的 helper 仍被基类自身用)
- 与 ADR-0016 "seam 仅在 ≥2 adapter 时引入" 原则一致: 这里是 1 个 adapter
  (基类), 但 `registerCounter` 是"工具方法"而非"seam", 不可类比

**封口**: 本 ADR 不删 `registerCounter`, 留作未来扩展点。

### D4 — Counter 名字仍 unique per handler (有意, 反驳 round 7 defer)

**问题陈述**: round 7 log 显式说 "5 个 onAttachMetrics handler 子类 single-counter
pattern 微 DRY (目前每 handler unique counter 名, 合并易失语)"。

**决策**: counter 名字仍 unique, 语义零合并。`CounterMetadata` record 的 `name` 字段
按 handler 各自声明, 基类按名注册到 `MeterRegistry`, 各自 Micrometer exposition
行独立存在 (`resicache.handler.null.hit` ≠ `resicache.handler.ttl.jittered` ≠ ...)—
Prometheus 抓取端看到的是 5 行不同 metric, 与 round 7 前的 exposition 行为**完全一致**。

**反 defer 理由**: round 7 担心的"合并"是"共用 1 个 counter 名", 会让 5 个
不同事件失去区分。本 ADR 不是"合并", 是"下沉注册样板 + 字段托管" — 名字字段仍
由 handler 各自 declare, 基类只接管"取 registry 调 registerCounter 存字段"
+ "null-safe 自增" 这 2 段机械样板。语义零变化, 但每 handler SLOC 减少
(5 个 `onAttachMetrics` body 5 行 × 5 = 25 行 → 5 个 `semanticCounter` body 3 行 × 5
= 15 行, 净减 10 行; 加上 5 个字段删除 + 10 个 import 删除, 总 -25 SLOC body
+ +18 SLOC Javadoc)。

## 后果

**增益**:
- 5 个 handler 退化为"declare 元数据 3 行 + safeIncrementSemantic() 1 行",
  模板样板从基类消失
- Counter 字段上收基类, 5 个 null-prone 字段消失 (基类唯一字段)
- 新增第 6 个带语义 counter 的 handler → 只需 declare 3 行 + 1 自增, 无样板
- 测试: 新增 `AbstractCacheHandlerSemanticCounterTest` 8 个 contract 测试
  (null registry / no-override / with-override / idempotent / increment / no-op 三态 /
  record accessor)

**代价**:
- **breaking change for user-extended subclasses**: 自定义 `AbstractCacheHandler` 子类
  若 override 旧 `onAttachMetrics(MeterRegistry)`, 该 override 会被静默忽略
  (基类不再调它), 自身 counter 不再注册。mitigation: 基类 Javadoc 显式说明
  "已弃用 onAttachMetrics, 改用 semanticCounter() 声明元数据"
- 0 个 public API 删除 (`onAttachMetrics` / `safeIncrement(Counter)` 是 protected
  hook, 不属 public surface; 0 个 public method 改变签名)

**不变**:
- 5 个 counter 的 Micrometer exposition 行完全一致 (name / description 各自独立)
- 测试: TtlHandlerTest / NullValueHandlerTest / BloomFilterHandlerTest /
  SyncLockHandlerTest / EarlyExpirationHandlerTest / CacheHandlerChainFactoryTest
  全部 0 修改通过 (测试在 public seam `registry.get("...")` 验证, 不触内部字段)
- ADR-0009 (ChainEngine) / ADR-0016 (ObserverRegistry) / ADR-0017 (fromAttributes)
  既有 seam 与暴露 API

## 参考

- ADR-0009: ChainEngine 抽出 (基类链推进外包)
- ADR-0015: AnnotationHandler.registerAll 模板方法下沉 (本 ADR 的同构 seam)
- ADR-0016: ObserverRegistry 跨 engine observer 列表去重
- ADR-0017: Operation.fromAttributes 静态 seam
- round 7 log 候选 G: 5 个 onAttachMetrics single-counter pattern 微 DRY
