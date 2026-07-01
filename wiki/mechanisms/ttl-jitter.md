---
title: TTL 抖动(300)
type: mechanisms
tags:
  - mechanism
  - TTL
  - 抖动
  - 防雪崩
  - TTL_HANDLER
related: [cache-avalanche, early-expiration, context-data-flow]
source-files:
  - src/main/java/io/github/davidhlp/spring/cache/redis/protection/avalanche/TtlHandler.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/protection/avalanche/DefaultTtlPolicy.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/protection/avalanche/TtlPolicy.java
status: stable
created: 2026-06-21
updated: 2026-07-02
---

# TTL 抖动(HandlerOrder 300)

责任链**第四档**。给每个缓存项的 TTL 加一点随机偏移,让原本「同一时刻写入、同一时刻过期」的一批 key **错峰过期**——避免 [[cache-avalanche|缓存雪崩]](大量 key 同时失效 → 同时回源压垮 DB)。

## 机制定位

`TtlHandler` 只对写操作(`PUT` / `PUT_IF_ABSENT`)生效。它**不写 Redis**,只计算最终的 `finalTtl` 写进 `CacheOutput`,交给链尾 [[cache-lifecycle|ActualCacheHandler]] 落盘。

`src/main/java/io/github/davidhlp/spring/cache/redis/protection/avalanche/TtlHandler.java:38`

```java
protected boolean shouldHandle(CacheContext context) {
    return context.getOperation() == CacheOperation.PUT
        || context.getOperation() == CacheOperation.PUT_IF_ABSENT;
}

protected HandlerResult doHandle(CacheContext context) {
    calculateTtl(context);
    return HandlerResult.continueChain();   // 算完就交棒
}
```

## TTL 来源的三级优先

`calculateTtl`(`TtlHandler.java:53`)按优先级确定 baseTtl 并决定是否抖动:

| 优先级 | 条件 | 行为 |
|---|---|---|
| 1 | 注解 `cacheOperation.getTtl() > 0` | 用注解 TTL,可抖动;`ttlFromContext=true` |
| 2 | 参数 `ttl` 有效(`ttlPolicy.shouldApply`) | 用参数 TTL,不抖动;`ttlFromContext=false` |
| 3 | 都无效 | `finalTtl = -1`,永久缓存;`shouldApplyTtl=false` |

兜底:`context.getTtl()` 为 null 时用 `DEFAULT_TTL = 60` 秒(`TtlHandler.java:35`)。

输出写进 `CacheOutput`(见 [[context-data-flow]]):`finalTtl` / `shouldApplyTtl` / `ttlFromContext`。

## 抖动算法:DefaultTtlPolicy

`src/main/java/io/github/davidhlp/spring/cache/redis/protection/avalanche/DefaultTtlPolicy.java:39`

```java
public long calculateFinalTtl(Long baseTtl, boolean randomTtl, float variance) {
    if (baseTtl == null || baseTtl <= 0) return -1;
    if (!randomTtl || variance <= 0) return baseTtl;          // 不开抖动 → 原值
    variance = Math.min(1.0f, Math.max(0.0f, variance));      // clamp [0,1]
    double randomFactor = ThreadLocalRandom.current().nextGaussian();
    // … 在 baseTtl 基础上按 variance 比例施加高斯随机偏移 …
}
```

要点:

- **高斯随机**(`nextGaussian`)而非均匀随机——偏移集中在 0 附近、极端值少,既打散又不至于让 TTL 偏离太多。
- `variance` 先 clamp 到 `[0, 1]`,代表最大抖动比例(默认注解 `0.2` = ±20%)。
- `randomTtl=false` 或 `variance<=0` 时直接返回原值(关闭抖动)。
- ADR-0025 后 `DefaultTtlPolicy` 为无状态纯 TTL 数学,不持有 `Clock`(`Clock` 随 `shouldEarlyExpiration` 迁至 refresh 域 `DefaultEarlyExpirationPolicy`)。

> 精确抖动公式见 `DefaultTtlPolicy.java`。`TtlPolicy` 是策略接口,可替换为自定义抖动逻辑(如均匀分布、固定阶梯)。

## TtlPolicy 接口

`TtlPolicy` 是纯雪崩(抖动)关注的策略 seam:

- `shouldApply(Duration ttl)` —— TTL 是否有效(非 null/零/负);
- `calculateFinalTtl(baseTtl, randomTtl, variance)` —— 抖动计算(本页)。

> ADR-0025 前,本接口还含 `shouldEarlyExpiration`(提前过期判定),其唯一消费者是 refresh 域 `EarlyExpirationHandler`,已迁至 [[early-expiration]] 自有 `EarlyExpirationPolicy` seam;`DefaultTtlPolicy` 的 `Clock` 字段(仅为该方法存在)随之移除。

## 配置

全局默认 TTL(`resi-cache.default-ttl`,见 [[configuration]]):

```yaml
resi-cache:
  default-ttl: 30m
```

注解级(`@RedisCacheable`):

```java
@RedisCacheable(value = "products", key = "#id",
    ttl = 3600,            // 基础 TTL(秒)
    randomTtl = true,      // 开启抖动
    variance = 0.2f)       // ±20% 高斯偏移
```

## 何时用

- **大量同类数据批量预热** → 强烈建议开抖动,否则它们几乎同时过期。
- **强一致/精确过期** → 关抖动(`randomTtl=false`),TTL 说几秒就几秒。
- 抖动只解决「同时过期」型雪崩;「Redis 整体宕机」型雪崩要靠业务层熔断/降级与本地兜底缓存(见 [[cache-avalanche]] 说明,框架未内置独立熔断组件)。

## 相关

- [[cache-avalanche]] —— 雪崩定义与本机制、[[early-expiration]] 的关系
- [[early-expiration]] —— 主动防热 key 过期(自有 `EarlyExpirationPolicy` seam;ADR-0025 前曾寄生于 `TtlPolicy`)
- [[context-data-flow]] —— 抖动结果如何经 `CacheOutput` 传给落盘 handler
- [[configuration]] —— `default-ttl` 等全局项
