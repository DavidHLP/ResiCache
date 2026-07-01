---
title: "ADR-0014: RedisProCache + RedisProCacheManager 构造重载墙收敛(单一 seam)"
type: adr
status: accepted
date: 2026-07-01
deciders: DavidHLP
related:
  - ADR-0009
  - ADR-0010
  - ADR-0012
  - ADR-0013
  - /tmp/architecture-review-1782872112.html
tags:
  - cache
  - constructor
  - deepening
  - round-5
---

# ADR-0014: RedisProCache + RedisProCacheManager 构造重载墙收敛(单一 seam)

## 状态

- **Status**: Accepted
- **Date**: 2026-07-01
- **Deciders**: DavidHLP
- **Related**: ADR-0009 / ADR-0010 / ADR-0012 / ADR-0013 /
  `/tmp/architecture-review-1782872112.html` (round 5,候选 A)

## 背景

`/improve-codebase-architecture` round 5 autocratic one-shot 报告基于 round 1–4
已落地 ADR-0009/0010/0011/0012/0013 后,扫描 `cache/` + `handler/` + `factory/`
三个域,筛出 3 个候选:

| 候选 | 评级 | 裁决 |
| --- | --- | --- |
| A · `RedisProCache` + `RedisProCacheManager` 构造重载墙收敛 | Strong | **执行** |
| B · `AnnotationHandler` 子类 for-loop 模板下沉到基类 | Strong | **留 round 6** |
| C · Factory `materialize` builder 填充统一 | Worth exploring | **留 round 6+**(YAGNI) |

全文精读核实后,逐条裁决如下。

## 决策

### D1 — `RedisProCache` 构造重载墙收敛(候选 A 第 1 部分,执行)

**问题陈述**:`RedisProCache` 4 个 public 构造重载(4 → 6 → 7 → 8 参),
每个都用 `this(...)` 委派到下一个,中间插入 `null` 占位:

```java
// 4 参 — 委派到 8 参
public RedisProCache(String, Writer, Cfg, MR) {
    this(name, writer, cfg, mr, null, null, null, null);   // 3 个 null 占位
}
// 6 参 — 委派到 8 参
public RedisProCache(String, Writer, Cfg, MR, Bloom, Reg) {
    this(name, writer, cfg, mr, bloom, reg, null, null);  // 2 个 null 占位
}
// 7 参 — 委派到 8 参
public RedisProCache(String, Writer, Cfg, MR, Bloom, Reg, Sync) {
    this(name, writer, cfg, mr, bloom, reg, sync, null);  // 1 个 null 占位
}
// 8 参 — 终态
public RedisProCache(String, Writer, Cfg, MR, Bloom, Reg, Sync, Resolver) { ... }
```

**deletion test**:删前 3 个重载 → 测试改用 8 参 + 显式 `null`,无行为变化。
删 8 参 → 无法构造 → 失败。前 3 个是 **纯传递性 / pass-through 浅模块**。

**形态对比**:
- 重载墙:interface 4 个签名(50 SLOC,5 行参数表 × 4 + 4 行委派体)≈
  implementation 1 个签名(15 SLOC,8 行参数表 + 7 行 field 赋值)
- **interface ≈ implementation**(浅模块病征)

**决策**:**单一 public 构造,8 参全显式**。调用方需 `null` 表示"特性禁用"
(对应运行时 null-safe 路径,与原 4-重载 null 委派行为一致)。

**落地**:
- `RedisProCache.java` 4 构造 → 1 构造(`-30 SLOC` + `+30 SLOC` Javadoc = 净 +0)
- 删 `java.util.Collections` import(原先仅 5-参重载用过 `Collections.emptyMap()`)
- 调用方 2 处(`RedisProCacheManager.createRedisCache` + `getMissingCache`)已用 8 参全构造,
  **零修改**

### D2 — `RedisProCacheManager` 构造重载墙收敛(候选 A 第 2 部分,执行)

**问题陈述**:`RedisProCacheManager` 5 个 public 构造重载(3 → 5 → 6 → 8 → 9 参),
委派结构与 `RedisProCache` 镜像:`5 重载 = 5 套参数子集 = 调用方必须记住"哪个用哪个"`。

更糟的是 `Collections.emptyMap()` / `false` 隐式默认值散在 3 个重载里——
"我用的是哪个默认值"是 implicit knowledge,删任一重载都要找全传播点。

**deletion test**:删前 4 个重载 → 测试改用 9 参 + 显式 `Collections.emptyMap()` +
`false`,无行为变化。

**决策**:**单一 public 构造,9 参全显式**。

**落地**:
- `RedisProCacheManager.java` 5 构造 → 1 构造(`-30 SLOC` + `+30 SLOC` Javadoc = 净 +0)
- 删 `java.util.Collections` import(原 3-参/5-参重载用过,9-参全构造后唯一)
- 调用方 1 处(`RedisProCacheConfiguration.cacheManager()`)已用 9 参全构造,**零修改**

### D3 — `AnnotationHandler` 子类 for-loop 模板下沉(候选 B,延后)

**问题陈述**:round 5 报告筛出的候选 B 描述了 4 个 `AnnotationHandler` 子类
(`Cacheable` / `CachePut` / `Evict` / `Caching`)的 for-loop + `registerOne`
样板重复(子类 60–95 SLOC,80% 同构)。

**未执行原因**:
- 本轮聚焦于"接口 ≈ 实现"型浅模块(候选 A),候选 B 是"per-subclass 模板重复",
  性质不同(后者是 GoF Template Method 的合理形态,前者是构造重载墙)
- 4 个子类的 for-loop 数据源不同:
  - `Cacheable`:`AnnotatedElementUtils.findMergedAnnotation` × 2(Spring @Cacheable 路径)
  - `CachePut / Evict`:`method.getAnnotationsByType`
  - `Caching`:组合注解的 `caching.redisCacheXxx()` 字段
- 数据源差异使"完全统一"需要 factory pattern(template 接受 annotation 数组),
  改动面比 A 大一倍以上,适合独立 round 单独决策

**封口**:**显式延后到 round 6**。届时一并审视 4 个子类的 annotation-array
source 差异,要么下沉到基类的 `for (A a : annotations) registerOne(...)`,
要么提供 `AnnotationArraySource` 函数式接口,要么在 `AbstractAnnotationHandler`
暴露 `doHandle(Method, target, args, annotationArray, factory, register)`。
本 ADR 不预先决定形态。

### D4 — Factory `materialize` builder 填充统一(候选 C,延后)

**问题陈述**:round 5 报告筛出的候选 C 描述 3 个 factory (`Cacheable` /
`CachePut` / `Evict`)的 `materialize(method, key, attrs)` 方法 18 行 builder
填充同构。

**未执行原因**:
- Spring 的 `CacheableOperation.Builder` / `CachePutOperation.Builder` /
  `CacheEvictOperation.Builder` 继承自不同基类,无法在 compile-time 用 common
  interface 统一
- 解决方案需要 `@Delegate` 模式(运行时反射开销)或 `CommonOpBuilder<B>` 委托
  wrapper(类数量翻倍),两者都不符合 YAGNI
- 当前 3 factory 18 行 builder 填充的"同构"是文字层面的,字段语义部分不同
  (e.g. Evict 没有 `unless` / `type` / `cacheNullValues` / `randomTtl` /
  `variance` 槽位,直接不调用;Cacheable 有 `type` 字段而 CachePut 也有)

**封口**:**YAGNI**。**等下一次需要新增字段触发再次同构时**才重审。
本 ADR 显式记录"无重大变动不重审",避免未来 round 重新建议。

## 后果

### D1 + D2 增益

- 2 个生产类各 4 / 5 构造 → 1 构造,接口与实现比例从 ~1:1 改善为 ~1:10(深度)
- 0 个生产调用方需要修改(装配路径已用全构造)
- 2 个测试调用方修改(显式 `null` 替代重载 magic),意图更清晰
- 删 2 个无用的 import(`Collections` × 2)
- 测试覆盖零变化(`RedisProCache` + `RedisProCacheManager` 测试 21 项全过)
- 调用方评审"该 cache 接哪些特性"一目了然(8 参 / 9 参 named 列表,无 null 委派陷阱)

### D1 + D2 代价

- `RedisProCache` / `RedisProCacheManager` 构造 API 表面变更(4 重载 / 5 重载 → 1)
- 属于有意破坏性变更,需 release note 标注:
  - 用户直接 `new RedisProCache(name, writer, cfg, mr)` 的代码需改为 8 参全构造
  - 用户直接 `new RedisProCacheManager(writer, cfg, mr)` 的代码需改为 9 参全构造
- Spring 装配路径用户**零影响**(本来就用全构造)

### D3 + D4 延后(有意)

- 候选 B / C 不在本 ADR 范围内,显式封口到 round 6 / YAGNI 重审
- 避免 round 1–5 autocratic one-shot 一次性吞下过多改动,降低 review 风险

## 实施

### 修改(4 文件,行数变化)

- `cache/RedisProCache.java`:4 构造 → 1 构造(`-30 SLOC` body / `+30 SLOC` Javadoc)
- `cache/RedisProCacheManager.java`:5 构造 → 1 构造(`-30 SLOC` body / `+30 SLOC` Javadoc)
- `test/cache/RedisProCacheTest.java`:1 行 `new` → 8 参全构造 with `null` 注释
- `test/cache/RedisProCacheManagerTest.java`:1 行 `new` → 9 参全构造 with `null` 注释
  + `import java.util.Collections`

### 验证

- `mvnw compile` —— **PASS**
- `mvnw test-compile` —— **PASS**
- `mvnw test`(全量 unit + integration with Testcontainers)—— **BUILD SUCCESS,727 tests,0 failures,0 errors**
- `mvnw checkstyle:check` —— **0 violations**
- JaCoCo 覆盖率无回归(全仓覆盖率维持)

## 平行问题映射(本 ADR 不处理)

| 问题 | 状态 | 计划 |
| --- | --- | --- |
| `AnnotationHandler` 子类 for-loop 模板重复 | 候选 B | round 6 单独处理 |
| Factory `materialize` builder 填充同构 | 候选 C | YAGNI,下次新增字段触发重审 |
| `CacheHandlerChain` thin facade 是否需进一步收敛 | 不属本轮 | ADR-0009 已完成收敛,封口 |
| 6 个具体 `*Handler` (`Bloom/Sync/Ttl/...`) `doHandle` 局部重复 | 不属本轮 | 各自机制独特,无明显可收敛重复 |

## 参考

- `/tmp/architecture-review-1782872112.html`(round 5)
- ADR-0009:Chain Engine 抽出(thin facade 模式先例)
- ADR-0012:Path C interceptor 残骸收敛(浅模块删除先例)
- ADR-0013:AnnotationChainEngine 抽出(平行 seam 模式先例)
