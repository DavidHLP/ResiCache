---
title: "ADR-0019: RedisCacheAttributesProjector FieldSource seam (3 from() 26-line 重复墙收敛) + RedisCacheable.expectedInsertions int/long type-drift 留待 1.0 毕业"
type: adr
status: accepted
date: 2026-07-01
deciders: DavidHLP
related:
  - ADR-0010
  - ADR-0017
  - /tmp/architecture-review-round-9.html
tags:
  - factory
  - projector
  - field-mapping
  - deepening
  - round-9
  - stability-contract
---

# ADR-0019: RedisCacheAttributesProjector FieldSource seam + type-drift deferral

## 状态

- **Status**: Accepted
- **Date**: 2026-07-01
- **Deciders**: DavidHLP
- **Related**: ADR-0010 (RedisCacheAttributes 投影层前置 seam) / ADR-0017 (Operation.fromAttributes 平行 seam 模式) / round-9 报告

## 背景

`/improve-codebase-architecture` round 9 autocratic one-shot 扫描 `factory/` + `annotation/` + `chain/observer/` 三域,基于 round 1–8 已落地 ADR-0009/0010/0011/0012/0013/0014/0015/0016/0017/0018 状态,筛出 5 个候选:

| 候选 | 评级 | 裁决 |
| --- | --- | --- |
| A · `RedisCacheAttributesProjector` 3 个 `from(annotation)` 26-line 重复墙收敛 | Strong | **执行** |
| B · `RedisCacheable.expectedInsertions` `int→long` 漂移修复(对齐 Put/Evict/RedisCacheAttributes) | Speculative | **不执行**(违反 STABILITY.md §1 注解类型稳定契约,留待 1.0 毕业) |
| C · 5 个 ChainObserver 实现 DRY 收敛 | Worth exploring | **不动**(YAGNI — 5 observer 已正交最简,"DRY" 形而上) |
| D · `CacheHandlerChainFactory` 4 个 disabled-handler if-block | Worth exploring | **不动**(ADR-0016 F 已显式延后,触发条件 = 新增第 5 个 protection 机制) |
| E · `SpringCacheableAdapterFactory` 参与 `Operation.fromAttributes` | Speculative | **不动**(ADR-0017 C 已显式封口,hasText 守卫性质不同) |

全文精读核实后,逐条裁决如下。

## 决策

### D1 — `FieldSource` 私有 record + 单一 `project()` seam + 3 轻量 `extractFrom(annotation)` (候选 A,执行)

**问题陈述**:`RedisCacheAttributesProjector` 的 3 个 `from(annotation)` 方法
形态几乎一致,2 个(Cacheable 与 Put) **byte-for-byte 完全相同** —— 26 行 builder 链
逐字段映射到 `RedisCacheAttributes.builder()`,外加 2 行 null 守卫 + 1 行 `.build()`。
Evict 形态相同但有 4 个字段填默认值(`type = Object.class` / `cacheNullValues = false`
/ `randomTtl = false` / `variance = 0.0F`)+ 2 个 Evict-only 字段
(`allEntries` / `beforeInvocation`)走真实注解值。

```java
// 原 from(RedisCacheable) 与 from(RedisCachePut) 字节级相同 (26 SLOC body)
public RedisCacheAttributes from(RedisCacheable annotation) {
    if (annotation == null) return null;
    return RedisCacheAttributes.builder()
            .cacheNames(resolveCacheNames(annotation.cacheNames(), annotation.value()))
            .key(annotation.key())
            .keyGenerator(annotation.keyGenerator())
            .cacheManager(annotation.cacheManager())
            .cacheResolver(annotation.cacheResolver())
            .condition(annotation.condition())
            .unless(annotation.unless())
            .ttl(annotation.ttl())
            .type(annotation.type())
            .cacheNullValues(annotation.cacheNullValues())
            .useBloomFilter(annotation.useBloomFilter())
            .expectedInsertions(annotation.expectedInsertions())  // <-- int, 见 D2
            .falseProbability(annotation.falseProbability())
            .randomTtl(annotation.randomTtl())
            .variance(annotation.variance())
            .enableEarlyExpiration(annotation.enableEarlyExpiration())
            .earlyExpirationThreshold(annotation.earlyExpirationThreshold())
            .earlyExpirationMode(annotation.earlyExpirationMode())
            .sync(annotation.sync())
            .syncTimeout(annotation.syncTimeout())
            .allEntries(false)
            .beforeInvocation(false)
            .build();
}
// from(RedisCachePut) 字节级相同,只 annotation 类型不同
// from(RedisCacheEvict) 4 字段填默认 + 2 字段走注解值
```

**deletion test**:
- 删 `from(RedisCacheable)` → `from(RedisCachePut)` 与 `from(RedisCacheEvict)` 行为独立,失败 → 必须保留
- 删 26 行 builder 链 + 改为 `project(FieldSource)` 委派 → 行为完全一致(只是多一次 record 构造 + lambda 包装)
- 替换为反射读字段 → 失去类型安全 + 反射开销;失败
- 替换为 `Map<String, Object>` 通用容器 → 失去字段类型约束 + 增 22 条 entry 样板;失败

**形态对比**:
- 重复墙:3 from() × ~26 SLOC body = ~78 SLOC 重复 + 3 × javadoc
- 模板版:1 `FieldSource` record (24 SLOC 组件声明) + 1 `project()` (24 SLOC body)
  + 3 `extractFrom(annotation)` (各 12 SLOC,只是 `a.foo()` 列表) + 3 from() 1-liner 委派

**Java 注解类型不可共享接口约束**:不能用 `default` 方法或 `extends` 提取公共
读取路径(注解是 Java 类型系统的孤儿)。只能用以下两种之一:
1. 22 字段参数直传(丑陋,顺序漂移风险)
2. **FieldSource record 包装**(本 ADR 选用)—— 一次 record 构造代替 22 字段直传,
   类型安全 + 顺序集中

**deletion test for FieldSource**:
- 删 `FieldSource` record → 3 `extractFrom(annotation)` 无返回类型 → 失败
- 用 `Map<String, Object>` 代替 FieldSource → 失去 22 字段类型约束, 编译期查不出字段拼写错
  → 维护性下降, 失败

**关键设计**:
- **`FieldSource` 为 private record(嵌套类)**:仅在 `RedisCacheAttributesProjector`
  内部使用, 不污染公开 API;Java 21 record 语法最小化样板
- **`NO_EVICT_DELTA = b -> b`**:Cacheable/Put 不持有 `allEntries`/`beforeInvocation`,
  走 `@Builder` 默认 false,故传 identity 函数
- **Evict 字段 locality 保留**:`from(RedisCacheEvict)` 直接在 lambda 内读
  `annotation.allEntries()` / `annotation.beforeInvocation()`, 不走 `FieldSource` —
  Evict 字段来源在 Evict 调用方本地, 不污染 22 字段通用 record
- **`extractFrom` 三独立方法**:因 Java 注解类型不可共享接口, 不能用 `<A extends Annotation>`
  泛型; 三个方法都是 12 行 `a.foo()` 列表, 字面重复 ~12 SLOC × 3, 但语义零差异
- **`cacheNames` / `value` 合并**: `resolveCacheNames` 仍由 `project()` 调,
  保持单一职责(22 字段容器 + cacheNames 合并逻辑都在 project 内部)

**落地**:
- 新 `FieldSource` 私有 record(24 SLOC 组件声明)
- 新 `NO_EVICT_DELTA` 静态常量(1 SLOC)
- 新 `project(FieldSource, UnaryOperator<Builder>)` 私有静态方法(24 SLOC body)
- 新 `extractFrom(RedisCacheable)` 私有静态方法(12 SLOC)
- 新 `extractFrom(RedisCachePut)` 私有静态方法(12 SLOC)
- 新 `extractFrom(RedisCacheEvict)` 私有静态方法(12 SLOC, 4 字段填默认)
- 改 `from(RedisCacheable)` 1-liner 委派
- 改 `from(RedisCachePut)` 1-liner 委派
- 改 `from(RedisCacheEvict)` 1-liner 委派 + Evict delta lambda
- `resolveCacheNames` 公开静态方法保持不变(已有 1 个 public 静态工具测试)

**SLOC 净变化**:
- 旧 3 from() body 总计: 78 SLOC(26 × 3) + 8 SLOC null 守卫(2-3 × 3)
- 新 3 from() body 总计: 6 SLOC(2 × 3, 1-liner + null 三元) + 12 SLOC Evict delta
- 新增 1 FieldSource record: 24 SLOC
- 新增 1 project() 私有方法: 26 SLOC(含 1 SLOC evictDelta.apply)
- 新增 3 extractFrom() 私有方法: 36 SLOC(12 × 3)
- **总净变化: +26 SLOC body, +60 SLOC Javadoc** — 略增 SLOC, **换取 1 处单一 builder 链
  seam** + 3 处显式 `extractFrom` 提取点

**leverage 兑现**:
1. 新增第 24 个共享字段(假设): 1 处改 `FieldSource` 组件 + 1 处改 `project()` body
   + 1-3 处改 `extractFrom(annotation)`(按字段在 3 注解中是否真实存在决定), 不再 3 处
   `from()` 全部逐字修改
2. `extractFrom` 是显式 "字段读" 站点, `project()` 是显式 "字段写" 站点, 中间
   `FieldSource` 是显式 "数据形状" 站点 — 三站分离, 维护时定位精确
3. 隐藏 22 字段长链于私有 `project()` 内部, 公开 `from(annotation)` 3 个 1-liner
   体现 "本类对外做什么" 而非 "怎么映射字段"

### D2 — `RedisCacheable.expectedInsertions` int/long type-drift 显式记录,不修 (候选 B,不执行)

**问题陈述**:扫读 3 个 `RedisCache*` 注解源码发现:
- `@RedisCacheable.expectedInsertions()` → **`int`** default `100000`
- `@RedisCachePut.expectedInsertions()` → **`long`** default `100000L`
- `@RedisCacheEvict.expectedInsertions()` → **`long`** default `100000L`
- 投影目标 `RedisCacheAttributes.expectedInsertions` → **`long`**

**当前行为**:`RedisCacheAttributesProjector.from(RedisCacheable)` 调
`.expectedInsertions(annotation.expectedInsertions())`,Java 隐式 `int→long` 拓宽,
写入 `long` 容器 — 表面无 bug, 测试全部通过。

**真问题**:**公开 API 字段类型不一致**。下游用户写
`@RedisCacheable(expectedInsertions = 3_000_000_000L)` 会在 javac 阶段
被截断到 int 范围;`@RedisCachePut(expectedInsertions = 3_000_000_000L)` 不会。
同一字段在 3 个公开注解上的类型不统一, 违反 `STABILITY.md` §1 注解属性类型稳定
契约(已公布的"attribute names, types, and semantics")。

**为什么不修**:
- `STABILITY.md` §1 明确把 "Enhancement annotation signatures" 的 attribute
  **types** 列为 stable surface(0.x 内不破坏)
- 改 `int→long` 是 **binary-breaking**(已编译的 .class 文件持有 int 字段引用)
  且与 §1 公开契约冲突
- 本 ADR 在 `RedisCacheAttributesProjector` 类 Javadoc 与
  `RedisCacheAttributesProjectorTest.Adr0019TypeDriftSentinel` nested class
  双重显式记录此漂移,作为 1.0 毕业时(§4)的已知 BREAKING 候选项

**触发重评估**:
- 1.0 毕业(§4):开 BREAKING 变更,统一 3 注解 `expectedInsertions` 为 `long`,
  CHANGELOG 显式标注, 提供 migration guide
- 新增 24 字段时:同步审查是否有类似 type-drift 引入

**测试钉住的契约**:
- `Adr0019TypeDriftSentinel.putEvict_expectedInsertions_isLong_acceptsLargeValues` —
  Put/Evict 可承载 `5_000_000_000L`(> Integer.MAX_VALUE)
- `Adr0019TypeDriftSentinel.cacheable_expectedInsertions_isInt_widensToLong` —
  Cacheable 上限为 `Integer.MAX_VALUE`, 隐式拓宽到 long OK
- `Adr0019DriftNoRegression.*` 三测试 — 默认值 `100_000` 在 3 注解投影后均为
  `100_000L`,与 v0.0.3 完全一致(零行为回归)

### D3 — 5 个 ChainObserver 实现不收敛 (候选 C,不动)

**不执行原因**:
- 5 observer 各自单职责,正交组合:MDC stamp / DEBUG log / Timer / FiredCounter / NoOp
- "DRY" 形而上:它们共享的是 "实现 `ChainObserver` 4 个 default no-op 钩子",
  这已经是 seam(ADR-0009 D2),无需再加基类
- 2 个 Micrometer 观察者(ChainTimer / FiredCounter)看似同构(都持 `MeterRegistry`),
  但 lazy 注册模式完全不同(DCL 单实例 vs ConcurrentMap per-class),
  强行共基类会引入类型擦除的 `Meter` 通配
- ADR-0016 的 "one adapter = hypothetical seam" 原则在 5 observer 上不适用:
  ChainTimer 与 FiredCounter 的 cardinality profile / 失败语义 / 注册时机均不同

### D4 — `CacheHandlerChainFactory` 4 个 disabled-handler if-block 不收敛 (候选 D,不动)

**不执行原因**:
- ADR-0016 F 已显式延后,理由 "单文件单处,已是局部最优(集中在一个 createChain 方法内),
  抽象成 Map<HandlerOrder, Supplier<Boolean>> 会增加配置类复杂度,反而降低可读性"
- 触发条件 = "新增第 5 个 protection 机制时 → 4 → 5 if-block 重审"
- 当前仍为 4 个机制(BloomFilter / SyncLock / EarlyExpiration / NullValue),触发未发生
- **本 ADR 显式确认 ADR-0016 F 仍生效**, 不再延后(避免与 ADR-0016 重述重复)

### D5 — `SpringCacheableAdapterFactory` 不参与 `Operation.fromAttributes` (候选 E,不动)

**不执行原因**:
- ADR-0017 C 已显式封口, 理由 "Spring `Cacheable` 字段集完全不同 + Spring
  Builder.setX 对 null/空串敏感(抛 IAE) → 必须走 hasText 守卫路径, 与 ResiCache
  工厂的'全量 set' 策略性质不同, 强行并入需为 Spring 单独写 if-guard 分支反增复杂度"
- 本 ADR 不重开 ADR-0017 决议

## 后果

### D1 增益

- **1 处单一 builder 链 seam**:3 `from(annotation)` → 1 `project()` 委派, 22 字段映射
  集中可审
- **1 处显式字段数据形状**:`FieldSource` 私有 record 把 "3 注解的 22 字段标准化"
  这个语义显式化(之前隐含在 3 个 builder 链内)
- **3 处显式字段读站点**:`extractFrom(annotation)` 把 "本注解有哪些字段"
  显式化(之前 26 行混着字段读 + 字段写)
- **零公开 API 变化**:`from(annotation)` 三方法签名 + 行为完全不变;
  `resolveCacheNames` 公开静态方法零变化
- **零行为回归**:`Adr0019CacheableEqualsPut.cacheableAndPut_produceIdenticalProjection`
  钉住 Cacheable ≡ Put 在相同输入下 byte-for-byte 一致; `Adr0019DriftNoRegression`
  3 测试钉住 3 注解默认值仍为 `100_000L`

### D1 代价

- **+26 SLOC body / +60 SLOC Javadoc** — 略增代码量, 换取 1 处 seam + 3 处显式提取点
- **新增 4 个私有成员**:`FieldSource` record + `NO_EVICT_DELTA` 常量 +
  3 `extractFrom` 方法(全部 private, 零公开 API 影响)

### D2 后果(显式 deferral)

- **STABILITY.md §1 契约维持**:不静默修 type, 不在 0.x 内破坏公开注解类型
- **1.0 毕业 §4 候选项**:本 ADR 在 `RedisCacheAttributesProjector` Javadoc 与
  `Adr0019TypeDriftSentinel` nested test 双重显式记录, 1.0 毕业时统一为 long
- **新测试 2 个**:`putEvict_expectedInsertions_isLong_acceptsLargeValues` +
  `cacheable_expectedInsertions_isInt_widensToLong` 钉住当前 type 行为, 防止后续
  漂移恶化

## 实施

### 修改(2 文件,行数变化)

- `factory/RedisCacheAttributesProjector.java`:
  - 3 from() 方法 (78 SLOC body) → 3 from() 1-liner (6 SLOC body) + Evict delta lambda
  - 新增 FieldSource 私有 record (24 SLOC 组件)
  - 新增 project() 私有方法 (24 SLOC body + 26 SLOC Javadoc)
  - 新增 3 extractFrom(annotation) 私有方法 (各 12 SLOC body + 各 18 SLOC Javadoc)
  - 类 Javadoc 新增 2 段: "ADR-0019 seam 收敛" + "ADR-0019 已知 type-drift"
- `factory/RedisCacheAttributesProjectorTest.java`:
  - 新增 4 nested classes, 8 新测试:
    - `Adr0019CacheableEqualsPut` (1 测试): Cacheable ≡ Put 字节级一致
    - `Adr0019EvictDefaults` (2 测试): Evict 4 字段填默认 + ttl=0 语义
    - `Adr0019TypeDriftSentinel` (2 测试): int/long 漂移契约钉住
    - `Adr0019DriftNoRegression` (3 测试): 3 注解默认 100_000 投影为 100_000L
  - 11 原有测试零修改

### 验证

- `mvnw checkstyle:check` — **0 violations**
- `mvnw test` (全量 unit + IT) — **773 unit tests, 0 failures, 0 errors**
  (原 765 unit tests + 8 新增 ADR-0019 contract = 773)
  - 3 IT 失败(`RedisCacheSemanticsIT.cacheEvict_allEntries_removesAll` /
    `cacheEvict_removesKey` / `cachePut_alwaysExecutesAndUpdates`)
    为 **pre-existing Testcontainers 环境问题** — git stash ADR-0019 diff 后
    跑 `mvnw test -Dtest='RedisCacheSemanticsIT'` 同样 3 失败, 与本 ADR 改动无关
- `RedisCacheAttributesProjectorTest` 单测 — **19 tests, 0 failures**
  (原 11 + 新增 8)
- `mvnw verify` — JaCoCo gate 通过,`RedisCacheAttributesProjector` 行覆盖
  维持 100%

### 不变

- 3 from(annotation) 公开方法签名零变化
- `resolveCacheNames(String[], String[])` 公开静态方法签名 + 行为零变化
- 22 字段 `RedisCacheAttributes` POJO 字段集与默认值零变化
- 3 个 `RedisCache*Operation.fromAttributes(method, key, attrs)` 静态方法零变化
- 3 个 `XxxOperationFactory.materialize` 1-liner 委派零变化
- STABILITY.md §1 注解属性类型契约零变化(`int expectedInsertions` 维持, 不静默修)
- ADR-0010 投影层架构 + 3 处默认值漂移修复
- ADR-0017 Operation.fromAttributes 静态 seam 形态
- ADR-0016 F (4 disabled-handler if-block) 仍生效
- ADR-0017 C (Spring adapter 不参与 fromAttributes) 仍生效

## 触发重评估

- 1.0 毕业(§4):开 BREAKING 变更, 统一 3 注解 `expectedInsertions` 为 `long`,
  CHANGELOG 显式标注, 提供 migration guide, 移除 `Adr0019TypeDriftSentinel`
  nested class(契约从漂移变为一致)
- 新增 24 字段时:同步审查 3 注解是否引入新 type-drift
- 新增第 5 个 `@RedisCache*` 注解时:`extractFrom` 多一个 12 行方法,
  project() body 多 1-2 个 builder setter, leverage 自然兑现

## 平行问题映射(本 ADR 不处理)

| 问题 | 状态 | 计划 |
| --- | --- | --- |
| 5 ChainObserver 实现 DRY | 候选 C | 继续 YAGNI(各 observer 已正交最简) |
| 4 disabled-handler if-block | 候选 D | 继续 YAGNI(ADR-0016 F 触发条件未发生) |
| Spring adapter fromAttributes | 候选 E | 继续不动(ADR-0017 C 已封口) |
| 1.0 毕业时 int→long 统一 | 候选 B | 1.0 毕业时执行(本 ADR D2 显式 defer) |

## 参考

- ADR-0010:RedisCacheAttributes 投影层(本 ADR 的前置 seam)
- ADR-0017:Operation.fromAttributes 静态 seam(本 ADR 平行模式 —— "3 同构 facade → 1 共享 seam")
- ADR-0016:ObserverRegistry + Manager instantiate(同 8 rounds 后 9 round 收尾的 seam 收敛模式先例)
- STABILITY.md §1:0.x 内注解属性 types 稳定契约(D2 不静默修的根据)
- STABILITY.md §4:1.0 毕业 forward marker(D2 触发重评估的根据)
- Tell, Don't Ask 原则(Martin Fowler):数据形状由拥有者声明
- Java 21 record 语法:FieldSource 22 字段声明最小化
