---
title: "ADR-0017: Operation.fromAttributes 静态 seam (Factory materialize 18 行 builder 链退化为 1 行委派)"
type: adr
status: accepted
date: 2026-07-01
deciders: DavidHLP
related:
  - ADR-0010
  - ADR-0016
  - /tmp/architecture-review-round-7.html
tags:
  - factory
  - operation
  - builder
  - deepening
  - round-7
---

# ADR-0017: Operation.fromAttributes 静态 seam (Factory materialize 18 行 builder 链退化为 1 行委派)

## 状态

- **Status**: Accepted
- **Date**: 2026-07-01
- **Deciders**: DavidHLP
- **Related**: ADR-0010 / ADR-0016 / round-7 报告

## 背景

`/improve-codebase-architecture` round 7 autocratic one-shot 扫描 `factory/` + `operation/` 两域,基于 round 1–6 已落地 ADR-0009/0010/0011/0012/0013/0014/0015/0016 状态,筛出 4 个候选:

| 候选 | 评级 | 裁决 |
| --- | --- | --- |
| A · `Operation.fromAttributes(method, key, a)` 静态 seam | Strong | **执行** |
| B · `RedisCacheAttributes` 移到 `operation/` 包(配套 A) | Strong | **执行**(同 commit 合并) |
| C · `SpringCacheableAdapterFactory` 不参与本轮(保留 hasText 守卫) | Worth exploring | **有意保留** |
| D · `AbstractOperationFactory<B extends Builder>` 泛型化 | Speculative | **不动** |

全文精读核实后,逐条裁决如下。

## 决策

### D1 — `Operation.fromAttributes(method, key, attributes)` 静态 seam 抽出(候选 A,执行)

**问题陈述**:三个 ResiCache operation factory(`CacheableOperationFactory` /
`CachePutOperationFactory` / `EvictOperationFactory`)的 `materialize` 方法<strong>99% 同构</strong>,
都是 18 行 builder 链把 `RedisCacheAttributes` 字段翻译到各自 `XxxOperation.Builder`:

```java
// CacheableOperationFactory.materialize (OLD, 18 SLOC body)
RedisCacheableOperation materialize(Method method, String key, RedisCacheAttributes a) {
    return RedisCacheableOperation.builder()
            .name(method.getName())
            .key(key)
            .cacheNames(a.getCacheNames())
            .keyGenerator(a.getKeyGenerator())
            .cacheManager(a.getCacheManager())
            .cacheResolver(a.getCacheResolver())
            .condition(a.getCondition())
            .unless(a.getUnless())
            .ttl(a.getTtl())
            .type(a.getType())
            .cacheNullValues(a.isCacheNullValues())
            .useBloomFilter(a.isUseBloomFilter())
            .expectedInsertions((int) Math.min(Integer.MAX_VALUE, Math.max(0L, a.getExpectedInsertions())))
            .falseProbability(a.getFalseProbability())
            .randomTtl(a.isRandomTtl())
            .variance(a.getVariance())
            .enableEarlyExpiration(a.isEnableEarlyExpiration())
            .earlyExpirationThreshold(a.getEarlyExpirationThreshold())
            .earlyExpirationMode(a.getEarlyExpirationMode())
            .sync(a.isSync())
            .syncTimeout(a.getSyncTimeout())
            .build();
}
```

Put 与 Evict 形态几乎相同(差异见 D1.B 子决策)。

**deletion test**:
- 删 3 个 `materialize` → factory 失去从 attributes 构造 operation 的能力 → 失败。**3 处共同在挣存在代价**(各自带 18 行样板)。
- 替换为泛型 `AbstractOperationFactory<B extends Builder>.fillBuilder(B, attrs)` 模板 → Builder 类型不兼容
  (继承自不同 Spring 基类 `CacheableOperation.Builder` / `CachePutOperation.Builder` /
  `CacheEvictOperation.Builder`),单一泛型无法覆盖。

**形态对比**:
- 重复墙:3 factory × ~18 SLOC builder 链 = ~54 SLOC 重复 + 3 × javadoc
- 模板版:每 Operation 类 1 个静态 `fromAttributes(method, key, attrs)` 静态方法 + 3 个 factory 退化为 1 行委派

**决策**:抽出 `fromAttributes(method, key, attributes)` 静态 seam 到三个 Operation 类自身。

**关键设计 — Tell, Don't Ask**:
- <strong>归属反转</strong>:Builder 字段映射的逻辑从 factory.materialize 迁到
  `XxxOperation.fromAttributes`,Operation 类最了解自己的 Builder 字段定义(谁拥有谁映射)。
- <strong>Factory 退化</strong>:三个 `materialize` 方法从 18 SLOC body 退化为 1 行委派:
  ```java
  RedisCacheableOperation materialize(Method method, String key, RedisCacheAttributes a) {
      return RedisCacheableOperation.fromAttributes(method, key, a);
  }
  ```
- <strong>接口不变</strong>:三个 factory 仍保留 `package-private` materialize 方法
  (供 test 直接调 + 与 Spring `AnnotationChainEngine` 集成时反射访问),
  行为完全等价于 `Operation.fromAttributes`,新加的 `OperationFromAttributesTest` 钉住这层契约。

**D1.A — Cacheable/Put 全量字段(21 字段)**:字段集相同,差异仅
`expectedInsertions` 类型(int vs long)与 int cast 归属。Cacheable.int cast 留在
`fromAttributes` body(Builder 内部窄化)。

**D1.B — Evict 字段子集(17 字段)+ Evict-only**:
- <strong>缺失</strong>(无 Builder 槽位):`unless` / `type` / `cacheNullValues` /
  `randomTtl` / `variance` — Evict 不持有这些语义,fromAttributes 显式忽略
- <strong>独有</strong>(仅 Evict):`allEntries` / `beforeInvocation` — 直接映射

### D2 — `RedisCacheAttributes` 移到 `operation/` 包(候选 B,执行)

**问题陈述**:D1 的 `Operation.fromAttributes` 静态方法必须 import `RedisCacheAttributes`。
该类原在 `factory/` 包 → 若 Operation 引用它,会形成 `operation → factory` 反向依赖
(原方向是 `factory → operation`)。

**deletion test**:
- 保留 `RedisCacheAttributes` 在 `factory/` → Operation 引用会形成反向依赖,
  Spring 装配时虽不爆但 package 方向污染,**不优雅**。
- 移到 `operation/` → Operation 引用同包(无 import),Factory 引用 `operation` 方向
  维持原样(`factory → operation` 单向)。

**决策**:把 `RedisCacheAttributes` 从 `factory/` 移到 `operation/` 包。

**包归属重新论证**:
- 类是"ResiCache operation 数据形状"的统一描述(21 字段对应 3 个 ResiCache
  Builder + 1 个 Spring 适配 Builder)
- Factory 是该数据描述的<strong>消费者</strong>(annotation → attributes → operation 链上)
- Operation 是该数据描述的<strong>归宿</strong>(attributes POJO 描述的就是 operation 应该长啥样)

**落地**:
- 删 `src/main/java/.../factory/RedisCacheAttributes.java`
- 新建 `src/main/java/.../operation/RedisCacheAttributes.java`(同内容 + 新 Javadoc 段)
- 6 个 import 改动:`RedisCacheAttributesProjector` / `AbstractOperationFactory` /
  `CacheableOperationFactory` / `CachePutOperationFactory` / `EvictOperationFactory` /
  `SpringCacheableAdapterFactory` 全部加 `import ...operation.RedisCacheAttributes;`
- 4 个 test 文件 import 同步

### D3 — `SpringCacheableAdapterFactory` 不参与(候选 C,有意保留)

**不合并原因**:
- Spring `Cacheable` 注解字段集<strong>完全不同</strong>(无 TTL/布隆/早过期/sync.lock 等
  增强字段)
- Spring `CacheableOperation.Builder.setX(...)` 对 null/空串敏感(会抛 IAE)→
  必须走 hasText 守卫路径(`if (hasText(a.getKeyGenerator())) builder.keyGenerator(...);`)
- 强行并入 `Operation.fromAttributes` 需为 Spring 单独写 if-guard 分支,反增复杂度
  且破坏"通用 seam"的简洁性

**封口**:`SpringCacheableAdapterFactory.materialize` 保持独立,继续走 hasText 守卫。

### D4 — `AbstractOperationFactory<B extends Builder>` 泛型化(候选 D,不动)

**不执行原因**:
- 三个 Spring Builder 继承自三个不同的基类(`CacheableOperation.Builder` /
  `CachePutOperation.Builder` / `CacheEvictOperation.Builder`),泛型 `<B extends
  CacheOperation.Builder>` 只能约束公共父类,无法调用子类特有 setter(`.useBloomFilter` /
  `.expectedInsertions` 等)
- 反射或 `BiConsumer<B, X>` 回调能强行通用,但要么失去类型安全,要么把样板挪到调用方
- 实际收益:0 SLOC 减少(子类仍需 18 行 setter 链)+ 抽象复杂度上升
- 本轮 D1 已通过"Operation 类各自持 fromAttributes"达成更深层的 seam,无需再加
  factory 层泛型

## 后果

**增益**:
- 3 个 factory `materialize` 从 18 SLOC body 退化为 1 行委派(-51 SLOC factory body,
  净代码精简显著;+45 SLOC Operation static method body,总 SLOC 略增)
- Builder 字段映射的归属(Tell, Don't Ask)从 factory 迁到 operation,locality 提升:
  谁拥有字段谁填字段,新增字段只动 Operation.fromAttributes 1 处
- 测试:新增 `OperationFromAttributesTest` 11 个契约测试,覆盖 Cacheable/Put/Evict
  全字段映射 + 边界裁剪 + 3 个 factory 委派契约

**代价**:
- `RedisCacheAttributes` 包路径变更(`factory/` → `operation/`),6 个 main + 4 个 test
  import 改动 — 纯内部包,无外部 API 影响
- D1.A 的 Cacheable.int cast 留在 Operation 静态方法(而非 factory 层) — 归属正确,
  不算"泄漏"

**不变**:
- 3 个 factory 公开 API(`create(Method, A, Object, Object[], String)`)零变化
- 3 个 factory `package-private materialize(Method, String, RedisCacheAttributes)` 签名零变化
- 21 字段 `RedisCacheAttributes` POJO 字段集与默认值零变化
- `RedisCacheAttributesProjector` 3 个 `from(annotation)` 重载零变化
- `SpringCacheableAdapterFactory` 形态零变化(hasText 守卫独立)
- ADR-0010 的投影层架构 + 3 处默认值漂移修复
- ADR-0016 的 ObserverRegistry + Manager instantiate seam

## 实施

### 修改(7 main + 4 test 文件)

**main**:
- `factory/AbstractOperationFactory.java` — Javadoc 更新("Builder 字段填充不下沉" → "已下沉")
- `factory/CacheableOperationFactory.java` — materialize 18 行 → 1 行委派
- `factory/CachePutOperationFactory.java` — materialize 18 行 → 1 行委派
- `factory/EvictOperationFactory.java` — materialize 18 行 → 1 行委派
- `factory/RedisCacheAttributesProjector.java` — 新增 import (`operation.RedisCacheAttributes`)
- `factory/SpringCacheableAdapterFactory.java` — 新增 import + 零行为变化
- `operation/RedisCacheableOperation.java` — 新增 static `fromAttributes(Method, String, RedisCacheAttributes)`
- `operation/RedisCachePutOperation.java` — 同上
- `operation/RedisCacheEvictOperation.java` — 同上

**新增 main**:
- `operation/RedisCacheAttributes.java` (从 `factory/` 搬迁,Javadoc 更新包归属说明)

**test**:
- 4 个 test 文件 import 同步(`operation.RedisCacheAttributes`)
- 新增 `operation/OperationFromAttributesTest.java` (11 契约测试)

### 验证

- `mvnw checkstyle:check` — 0 violations
- `mvnw test` — **BUILD SUCCESS, 757 testcases, 0 failures, 0 errors**
  (原 746 + 11 新增 fromAttributes 契约测试)
- 已知:1 个 flaky race condition test (`EarlyExpirationHandlerRaceConditionTest`)
  单独运行 3/3 通过,与本 ADR 改动无关(未触 EarlyExpirationHandler 相关代码)

## 参考

- ADR-0010:RedisCacheAttributes 投影层(本 ADR 的前置 seam)
- ADR-0016:ObserverRegistry + Manager instantiate(本轮延后候选 C 的前序 ADR)
- Tell, Don't Ask 原则(Martin Fowler):字段归属应在拥有字段的类
- Spring Boot 4.0 `CacheOperation.Builder`:`setX` 字段对 null 敏感(Assert.notNull 守卫)
