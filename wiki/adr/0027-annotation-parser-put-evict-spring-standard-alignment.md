---
title: "ADR-0027: @RedisCachePut/@RedisCacheEvict AnnotationParser 对齐 Spring 标准类 + 单注解探测修补(纠正 4 轮 ADR 的环境误诊)"
type: adr
status: accepted
date: 2026-07-02
deciders: DavidHLP
related:
  - ADR-0020
  - ADR-0022
tags:
  - annotation
  - correctness-bug
  - spring-cache-integration
  - interface-implementation-gap
  - round-19
---

# ADR-0027: @RedisCachePut/@RedisCacheEvict AnnotationParser 对齐 Spring 标准类 + 单注解探测修补(纠正 4 轮 ADR 的环境误诊)

## 状态

- **Status**: Accepted
- **Date**: 2026-07-02
- **Deciders**: DavidHLP
- **Related**: ADR-0020(parseRedisCacheable 已改用 Spring 标准类,Put/Evict 漏改)/ ADR-0022~0025(四轮误诊"Testcontainers 环境问题")

## 背景

`RedisCacheSemanticsIT` 的 3 个用例(`cachePut_alwaysExecutesAndUpdates` /
`cacheEvict_removesKey` / `cacheEvict_allEntries_removesAll`)自 ADR-0019 起持续失败。
**ADR-0019/0020/0021/0022 四轮独立验证均判定为「pre-existing Testcontainers 环境问题」**
(各轮 `git stash` diff 后 bare master 跑同一 IT 复现 3 失败 → 推论非本 ADR 引入)。

**round 18**(`log.md` 2026-07-02 条目)纠正了「环境问题」定性 —— 实测 Spring Boot 启动成功 +
Redis 连接正常,失败为**真实断言失败**(GET 路径工作,仅 `@RedisCachePut`/`@RedisCacheEvict` 失效),
并收窄根因方向至「PUT/EVICT 走链时 `cacheOperation` 查找」。但 round 18 未通读
`AnnotationParser`,根因方向**误判**(实际 PUT/EVICT 根本未进入链,而非链中短路)。

## 根因(双重,经诊断铁证)

round 19 系统通读 `annotation/AnnotationParser.java` + `annotation/RedisCacheOperationSource.java`
+ 加诊断日志实跑 `RedisCacheSemanticsIT`,dump 每个被拦截方法的 `CacheOperationSource` 输出,
定位到**双重根因**,均在 `AnnotationParser`(注解 → Spring `CacheOperation` 解析层):

### 诊断证据(`DIAG parseCacheOperations` per method)

| TestCacheService 方法 | opCount | opTypes | 实测结果 |
|---|---|---|---|
| `getById` | 1 | `[CacheableOperation]`(Spring 标准) | ✅ 工作 |
| `putById` | **0** | `[]` | ❌ **注解未解析** |
| `evictById` | 1 | `[RedisCacheEvictOperation]`(ResiCache 子类) | ❌ **解析了但不触发** |
| `evictAll` | 1 | `[RedisCacheEvictOperation]`(ResiCache 子类) | ❌ **解析了但不触发** |
| `multiCacheOperation` | 3 | `[CacheableOperation, CacheableOperation, RedisCacheEvictOperation]` | 2 个标准类工作 / 1 个子类失效 |

### 根因 A — 单个 `@RedisCachePut` 探测遗漏(铁证)

`AnnotationParser.parseResiCacheAnnotations`(`AnnotationParser.java:43-80`)依次探测:
单个 `@RedisCacheable` ✅ / 单个 `@RedisCacheEvict` ✅ / `@RedisCaching` 复合(内含
cacheable/evict/put)✅ —— **唯独漏了单个 `@RedisCachePut` 探测**。结果:单独标注
`@RedisCachePut` 的方法(如 `putById`)的 ops 集合为空 → `CacheOperationSource.getCacheOperations`
返回 null → Spring AOP 认为该方法无缓存操作 → **只执行业务方法,不触发 `cache.put`**。

`putById` 诊断 `opCount=0, opTypes=[]` 直接坐实。

### 根因 B — Put/Evict 产出 ResiCache 子类导致 Spring `CacheOperationContexts` 分桶失败

`parseRedisCacheable`(`AnnotationParser.java:89-131`)的 javadoc 自承设计意图:

> 刻意构建 Spring 标准 `CacheableOperation`(而非 ResiCache 的 `RedisCacheableOperation`),
> 确保 `getClass()` 返回 `CacheableOperation.class` —— 这样 `CacheAspectSupport` 的
> `CacheOperationContexts` 能正确按类型索引(可缓存/可放入/可清除三桶)。

ADR-0020 已把 `parseRedisCacheable` 从 ResiCache `RedisCacheableOperation` 改为 Spring 标准
`CacheableOperation`(分桶修正)。**但 `parseRedisCachePut` / `parseRedisCacheEvict` 漏改**,
仍产出 ResiCache 子类(`RedisCachePutOperation` / `RedisCacheEvictOperation`)。

`evictById` 诊断 `opCount=1, opTypes=[RedisCacheEvictOperation]` —— 注解**被解析了**,
ops 有内容,但 Spring AOP 仍不触发 `cache.evict`(全链 DIAG 无 `op=REMOVE`)。对照 `getById`
的 `[CacheableOperation]`(Spring 标准)正常工作,**子类分桶失败铁证**。

**机制**:`RedisCachePutOperation` / `RedisCacheEvictOperation` 经 Lombok `@EqualsAndHashCode(callSuper=true)`
继承 Spring 基类,但 `CacheOperationContexts` 的分桶依赖运行时 `getClass()` 精确匹配
`CachePutOperation.class` / `CacheEvictOperation.class`;ResiCache 子类的 `getClass()` 返回
子类 `.class` ≠ Spring 基类 `.class` → **不进入对应桶 → Spring 不触发 `cache.put`/`cache.evict`**。
`parseRedisCacheable` 的 javadoc 早已记录此陷阱,Put/Evict 是漏改的对称缺口。

## 决策

### D1 — `parseResiCacheAnnotations` 补回单个 `@RedisCachePut` 探测(修根因 A)

在 `@RedisCacheEvict` 探测块后,新增对称的 `@RedisCachePut` 单注解探测
(`AnnotationTargets.findMerged(target, RedisCachePut.class)` + `parseRedisCachePut`)。
形态与 `@RedisCacheable`/`@RedisCacheEvict` 探测块完全对称,`@RedisCaching` 复合分支不变。

### D2 — `parseRedisCacheEvict` 改产 Spring 标准 `CacheEvictOperation`(修根因 B)

- `new org.springframework.cache.interceptor.CacheEvictOperation.Builder()` 替代 ResiCache
  `RedisCacheEvictOperation.builder()`
- 只投影 Spring 字段:`name`/`cacheNames`/`key`/`condition`/`cacheResolver`/`keyGenerator`/
  `cacheManager` + `cacheWide`(`allEntries`)/`beforeInvocation`
- **去掉 ResiCache 增强字段**(`ttl`/`useBloomFilter`/`expectedInsertions`/`falseProbability`/
  `enableEarlyExpiration`/`earlyExpirationThreshold`/`earlyExpirationMode`/`sync`/`syncTimeout`)
- 返回类型从 `RedisCacheEvictOperation` 收窄为 `CacheOperation`

### D3 — `parseRedisCachePut` 改产 Spring 标准 `CachePutOperation`(修根因 B,对齐 `parseRedisCacheable`)

- `new org.springframework.cache.interceptor.CachePutOperation.Builder()` 替代 ResiCache
  `RedisCachePutOperation.builder()`
- 只投影 Spring 字段:`name`/`cacheNames`/`key`/`condition`/`unless`/`keyGenerator`/
  `cacheManager`/`cacheResolver`
- **去掉 ResiCache 增强字段**(`ttl`/`type`/`cacheNullValues`/`useBloomFilter`/
  `expectedInsertions`/`falseProbability`/`sync`/`syncTimeout`/`randomTtl`/`variance`/
  `enableEarlyExpiration`/`earlyExpirationThreshold`/`earlyExpirationMode`)
- 返回类型保持 `CacheOperation`

### D4 — 移除 ResiCache Operation 子类的 import

`AnnotationParser` 不再引用 `RedisCacheEvictOperation` / `RedisCachePutOperation`
(两个 import 删除)。这两个 ResiCache 子类**仍由 `handler/` 的
`CachePutAnnotationHandler`/`EvictAnnotationHandler` 产出并注册到 `RedisCacheRegister`**
(供链路 `buildContext` 查询增强配置),两类并存各司其职:
**AnnotationParser → Spring 标准类(触发 AOP);handler/ → ResiCache 子类(注册 register)**。

### D5 — 修 `RedisCacheSemanticsIT.cachePut_alwaysExecutesAndUpdates` 的 pre-existing `ClassCastException`

修复 D1~D3 后 `@RedisCachePut` 触发 `cache.put`,暴露出测试本身的 pre-existing bug:
测试 `(String) valueOps.get("testCache::1")` 强转裸 `String`,但 ResiCache `ActualCacheHandler`
按核心设计把值包装成 `CachedValue`(携带 `ttl`/`createdTime` 供 early-expiration),所以 Redis
里存的是 `CachedValue` 信封,强转失败。改用 `cacheService.getById(1L)` 经 `RedisProCache`
抽象层正确解包,断言**更强**(`isEqualTo("second")` + `callCount` 命中验证 替代 `contains`)。
非为通过而弱化,而是修正错误假设 + 加强。

### D6 — 歧路否决

- **D6a** ❌ 在 `RedisProCacheWriter.buildContext` 按 operation type 查 `getCachePutOperation`/
  `getCacheEvictOperation`(让 PUT/EVICT 也能拿到增强配置)—— **不在本轮 scope**。当前
  `CacheContext.cacheOperation` 字段类型硬编码为 `RedisCacheableOperation`,无法装载 Put/Evict
  子类;修此需引入 `ResiCacheOperation` 共同接口并改 `CacheContext` 字段类型,属独立深化,
  留待后续 ADR。本轮聚焦核心失效(写入/删除基本语义),增强配置(ttl/bloom 对 PUT/EVICT)缺失
  不影响基本写入/删除(`ActualCacheHandler` 总是处理)。
- **D6b** ❌ 让 ResiCache 子类 `implements` 标记接口骗过 Spring 分桶 —— 治标不治本,Spring
  分桶按 `getClass()` 精确类型,标记接口不改变 `getClass()`;且违背 ADR-0020 已确立的
  「AnnotationParser 产 Spring 标准类」纪律。
- **D6c** ❌ 把根因归为 round 18 的「`cacheOperation==null` 短路」假说并修链中 handler ——
  误诊。DIAG 铁证 PUT/EVICT 根本未进入链(opCount=0 / 无 op=REMOVE),非链中短路。

## 验证

- `mvnw checkstyle:check` —— **0 violations**
- `mvnw clean verify -Dmaven.javadoc.skip=true` —— **BUILD SUCCESS, 782 tests, 0 failures,
  0 errors, All coverage checks have been met**(JaCoCo gate 通过)
- `mvnw test -Dtest=RedisCacheSemanticsIT` —— **8 tests, 0 failures**(此前 3 失败全绿)
- 诊断日志(6 处 `DIAG` 临时 log)已全部移除,源码零诊断残留

## 后续

- **D6a 候选**(独立 ADR):`buildContext` 按 operation type 查对应 getter,让 PUT/EVICT 也能
  拿到注解增强配置(ttl/bloom/nullValue/early-expiration)。需先引入 `ResiCacheOperation`
  共同接口统一三类 Operation 的增强字段访问,`CacheContext.cacheOperation` 改为该接口类型。
- 「环境问题」误诊教训:4 轮 ADR 的「`git stash` + bare master 复现失败」推论在失败是
  **pre-existing 且非环境**时同样成立(bare master 也坏),无法区分「环境问题」与「长期潜伏
  代码 bug」。后续遇 IT 失败应优先加诊断日志定位真实断点,而非急于甩锅环境。

详见 round 19 `log.md` 条目。
