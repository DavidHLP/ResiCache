---
title: Round 39 — RedissonConfiguration 跨模式 timeout/retry setter 2-site 样板收敛 → applyTimeoutAndRetrySettings 私有 helper
type: adr
tags:
  - adr
  - seam-deepening
  - round-39
  - config-package
related: [0029-single-adapter-hypothetical-seams-acceptance, 0031-redisprocache-timing-helper-seam, 0016-observer-registry-seam-and-manager-instantiate-seam, 0051-round37-f2-f3-f4-rejection-and-stale-javadoc-fix, 0052-actualcachehandler-storeintent-deep-module]
status: stable
created: 2026-07-06
updated: 2026-07-06
---

# ADR-0053 — Round 39:RedissonConfiguration 跨模式 timeout/retry setter 2-site 样板收敛 → `applyTimeoutAndRetrySettings` 私有 helper

> ADR-0051(Round 37)遗嘱 + ADR-0052(Round 38)首兑现「扫新域」(扫 chain 写路径)。
> 本轮继续扫**全域剩余未触及域**:`cache/` 接合部、`config/` 装配域、`annotation/operation/factory/handler/` 注解处理域、`eviction/`、`serialization/`、`observability/`。

## 上下文 (Context)

完整通读 6 个域后,**3 个候选**浮现,经红蓝博弈裁决 **2 驳 1 立**。

### 候选全景

| # | 候选 | 域 | 裁决 | 理由 |
|---|------|----|------|------|
| 1 | `RedisProCacheProperties` "属性袋膨胀"(13 顶层字段 + 7 嵌套静态类)→ 抽 `ConfigResolver` Strategy | config | **驳回** | 嵌套静态类是 Spring Boot `@ConfigurationProperties` 官方惯用模式(ServerProperties / RedisProperties 同款);"属性袋"在深模块词汇指 stringly-typed Map(如 `CacheContext.attributes`),非强类型 POJO。deletion test 反向:摊平嵌套类才制造真属性袋。抽 Strategy = false seam,违反 ADR-0029 |
| 2 | `CacheableAnnotationHandler` 双路径(@RedisCacheable vs @Cacheable)→ 抽 `AnnotationProcessingContext` | handler | **驳回** | doHandle 两路径已收敛到 `registerOne` 单一模板(ADR-0015),差异仅注解类型 / factory / logTag;`@Cacheable` 兼容回退是 ADR-0001 产品特性,非样板。agent "2 site = 跳转文件数" 偷换 ADR-0029 概念(指代码样板重复 2+ 处)。再抽 Context = interface≈implementation 浅模块 |
| 3 | `RedissonConfiguration` 跨模式 timeout/retry setter 2-site 样板 → 私有 helper | config | **采用** | 见下 |

### 候选 3:真实 2-site 样板

`configureSingle`(single 模式)与 `applyCommonSettings`(cluster / sentinel 模式)各自对 Redisson 服务器配置应用一批 timeout/retry setter。javap Redisson `BaseConfig` 确认 **5 个 setter** 定义在基类 `BaseConfig<T>` 上(被 `SingleServerConfig` 与 `BaseMasterSlaveServersConfig` 共同继承):

```
setIdleConnectionTimeout / setConnectTimeout / setTimeout / setRetryAttempts / setRetryInterval
```

这 5 个 setter 在两处逐字重复:

```java
// configureSingle(:156-160) + applyCommonSettings(:188-192):
.setIdleConnectionTimeout(pool.getIdleConnectionTimeout())
.setConnectTimeout(pool.getConnectTimeout())
.setTimeout(pool.getTimeout())
.setRetryAttempts(pool.getRetryAttempts())
.setRetryInterval(pool.getRetryInterval())   // 5 行 × 2 site = 10 行样板
```

## 删除测试 (Deletion Test)

```
不抽 helper:
└─ 5 setter × 2 site = 10 行重复;任一 setter 名/参数变更须同步改两处(漂移风险)
   (Redisson 版本升级若调整 setter 签名,两处必漏改一边)

抽 applyTimeoutAndRetrySettings 后:
└─ 5 setter 收口单点;2 site 各塌缩为 1 行调用
```

**判据**:删 helper 内联回去 → 10 行样板重复回归,复杂度上升。本 seam **浓缩**复杂度(非搬家),过 ADR-0029「2-site = real seam」门槛。与 ADR-0031(timing helper)/ ADR-0016(instantiateRedisProCache)同款「N-site 样板 → 私有 helper」深化模式。

## 范围限定 — 不合并项(红蓝博弈)

| 项 | 为何不合并 |
|----|-----------|
| **pool size** | SingleServer:`setConnectionPoolSize` / `setConnectionMinimumIdleSize`;MasterSlave:`setMasterConnectionPoolSize` / `setSlaveConnectionPoolSize` / `setMasterConnectionMinimumIdleSize` / `setSlaveConnectionMinimumIdleSize`。**setter 名不同**(Redisson API 在子类各自声明,不在 `BaseConfig` 基类),无法跨基类收敛;硬合并需反射或 adapter,过度设计 |
| **password / username** | `configureSingle` 有 ResiCache → Spring `RedisProperties` fallback 链(`redis.getPassword() != null ? ... : redisProperties.getPassword()`);`applyCommonSettings` 直取无 fallback。**语义不同**,合并破坏 single 模式的 fallback 契约 |
| **database / address** | SingleServer 独有(`setDatabase` / `setAddress` 在 `SingleServerConfig` 子类),cluster / sentinel 无此概念 |

## 备选路径与驳回 (Alternatives Rejected)

| 路径 | 方案 | 裁决 |
|------|------|------|
| **W** | 抽 `ConnectionPoolConfig.applyTo(serverConfig)` 收敛全部 pool + timeout setter | **驳回**:pool size setter 在 Single / MasterSlave 两套 API 上不同名(子类各自声明),无法用单一基类参数收敛;强行用反射 / visitor 过度设计,违反 ADR-0029「反对制造 hypothetical seam」 |
| **Y** | 把 timeout/retry 应用推到 `RedissonProperties` record 上(自应用) | **驳回**:`RedissonProperties` 是 `@ConfigurationProperties` 嵌套 POJO(被动数据),让它认知 Redisson `BaseConfig` API = 配置数据反向依赖 Redisson 实现细节,污染 Spring Boot 属性类惯用定位 |
| **Z** | 不动(宣告零候选,仅修 stale Javadoc,同 round 37) | **驳回**:候选 3 是真实 2-site 样板,工程价值高于 stale Javadoc 修复(ADR-0051),有 ADR-0031 / 0016 先例;不动 = 放弃可落地的真实深化 |
| **X**(采用) | `RedissonConfiguration` 私有静态 `applyTimeoutAndRetrySettings(BaseConfig<?>, RedissonProperties)` | 见下「决策」 |

## 决策 (Decision)

### 私有静态 helper `applyTimeoutAndRetrySettings(BaseConfig<?> config, RedissonProperties pool)`

```java
private static void applyTimeoutAndRetrySettings(
        BaseConfig<?> config, RedisProCacheProperties.RedissonProperties pool) {
    config.setIdleConnectionTimeout(pool.getIdleConnectionTimeout());
    config.setConnectTimeout(pool.getConnectTimeout());
    config.setTimeout(pool.getTimeout());
    config.setRetryAttempts(pool.getRetryAttempts());
    config.setRetryInterval(pool.getRetryInterval());
}
```

`configureSingle` 与 `applyCommonSettings` 的 5-setter 链各塌缩为 1 行调用,pool size / password / username 留在原位(前述范围限定)。

`BaseConfig<?>` 通配类型同时接受 `SingleServerConfig`(single 路径)与 `BaseMasterSlaveServersConfig<?>`(cluster / sentinel 路径)—— Redisson javap 确认 5 setter 在 `BaseConfig` 基类(public,返回协变 `T`)。

## 影响面 / SLOC 对比

| 项 | Round 38(前) | Round 39(本 ADR) | 净变化 |
|----|------|------|------|
| `RedissonConfiguration.java` 总行数 | 202 | 232 | **+30**(Javadoc + helper) |
| inline timeout/retry 5-setter | 5 行 × 2 = 10 | 0(收口 helper) | **-10** |
| `applyTimeoutAndRetrySettings` helper(含 Javadoc) | 0 | ~30 | +30 |
| 调用点塌缩 | 5 行 × 2 | 1 行 × 2 | **-8** |
| 新增测试(填补覆盖盲区) | 0 | 2 | +2 |
| 公开 API / Bean 行为 / Redisson 配置字节 | 不变 | 不变 | **0** |

## 字节等价 / 测试矩阵

新增 2 个测试填补**既有覆盖盲区**(原 `RedissonConfigurationTest` 无任何 timeout/retry 断言):

| 测试 | 路径 | 验证 |
|------|------|------|
| `singleMode_appliesTimeoutSettings` | `SingleServerConfig`(`BaseConfig` 直接子类) | 5 setter 经 helper 正确应用:idle=15000 / connect=20000 / timeout=30000 / retryAttempts=5 / retryInterval=2500 |
| `clusterMode_appliesTimeoutSettings` | `ClusterServersConfig`(`BaseMasterSlaveServersConfig` 子类) | helper 对 MasterSlave 路径也生效:timeout=30000 / retryAttempts=5 |

5 setter 调用顺序逐字保留(`idle → connect → timeout → retryAttempts → retryInterval`),pool size 顺序不变,single / cluster 两条路径均行为等价。

## 验证状态

- ✅ `RedissonConfigurationTest`:16 tests / 0 fail / 0 err(原 14 + 新增 2)
- ✅ **746 单测全绿(0 fail / 0 err / 17 skipped Docker integration)** — 较 round 38 的 744 增 2(新增 timeout 测试)
- ✅ checkstyle 0 violation
- ✅ JaCoCo 覆盖率强制通过
- ✅ Redisson 配置字节等价:5 setter 顺序 + pool size / password / username / database / address 全保持
- ⚠️ `./mvnw verify` 的 `javadoc:jar` 阶段失败(`RedisCacheAttributesProjector.java:125` 引用 Lombok `@Builder` 生成的 `RedisCacheAttributesBuilder`)—— **pre-existing** Lombok↔javadoc 兼容问题,与本轮改动无关(改动范围仅 `RedissonConfiguration.java` + 其测试,git diff 可证);round 38 跑 `mvn test`(不含 javadoc 阶段)未暴露

## 设计纪律

- **私有静态而非顶层**:helper 当前仅 2 消费者(`configureSingle` / `applyCommonSettings`),未达顶层必要性(YAGNI)。
- **不污染 `RedissonProperties`**:配置 POJO 维持被动数据定位,不添自应用方法(驳回路径 Y)。
- **不强行合并 pool size**:Redisson API 在子类各自声明不同名 setter 是真实约束,尊重之(驳回路径 W)。
- **范围最小**:只收敛真正 2-site 共享的 5 setter,password / username / database / address 因 fallback 链或 API 差异不动。

## 相关 ADR

- **前置**:
  - ADR-0029(single-adapter hypothetical seam 接受策略 — 本轮 2-site 过 real seam 门槛,路径 W 驳回依据)
  - ADR-0031(`RedisProCacheTimers` timing helper — 同款「N-site 样板 → 私有 helper」深化先例)
  - ADR-0016(`instantiateRedisProCache` — Manager 装配样板收敛先例)
  - ADR-0051 / 0052(「扫新域」遗嘱 — 本轮兑现,扫 config 装配域)
- **后续**:无新候选挂账。cache / config / 注解 / eviction / serialization / observability 全域扫描完成,3 候选 2 驳 1 立;后续 `/improve-codebase-architecture` 应等新功能需求揭示新摩擦,或重扫 protection / chain 域看是否有漂移。
