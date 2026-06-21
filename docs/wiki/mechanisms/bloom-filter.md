---
title: 布隆过滤器(100)
type: mechanisms
tags:
  - mechanism
  - 布隆过滤器
  - 防穿透
  - BLOOM_FILTER
  - PostProcessHandler
related: [cache-penetration, handler-result-control, chain-of-responsibility]
source-files:
  - src/main/java/io/github/davidhlp/spring/cache/redis/protection/bloom/BloomFilterHandler.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/protection/bloom/BloomSupport.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/protection/bloom/filter/LocalBloomIFilter.java
status: stable
created: 2026-06-21
updated: 2026-06-21
---

# 布隆过滤器(HandlerOrder 100)

责任链**第一档**。在查 Redis 之前,先问一句「这个 key 可能存在吗?」——若布隆判定**不可能存在**,直接返回 miss,根本不打 Redis、不回源。这是防 [[cache-penetration|缓存穿透]] 的第一道、也是最省的一道防线。

## 机制定位

布隆过滤器是一种空间高效的概率结构:判断「不在」是 100% 准确的,判断「在」可能有假阳性。缓存场景正好利用这一点——「不在」就拦截,「在」就放行去查 Redis。

`src/main/java/io/github/davidhlp/spring/cache/redis/protection/bloom/BloomFilterHandler.java:35`

```java
@Component
@HandlerPriority(HandlerOrder.BLOOM_FILTER)
public class BloomFilterHandler extends AbstractCacheHandler implements PostProcessHandler {
```

## 操作分流

`BloomFilterHandler` 同时实现 `PostProcessHandler`(见 [[handler-result-control]]),按操作不同对待:

| 操作 | 行为 |
|---|---|
| `GET` | 查布隆:不存在 → `terminate(CacheResult.miss())` 短路;可能存在 → `continueChain()` |
| `PUT` / `PUT_IF_ABSENT` | 设 `bloom.postProcess=true`,继续链;**链成功后**才把 key 加进过滤器 |
| `CLEAN` | 设 `bloom.postProcess=true`;链结束后清空对应过滤器 |

### GET 短路

`src/main/java/io/github/davidhlp/spring/cache/redis/protection/bloom/BloomFilterHandler.java:68`

```java
private HandlerResult handleGet(CacheContext context) {
    boolean mightContain = bloomSupport.mightContain(context.getCacheName(), context.getActualKey());
    if (!mightContain) {
        statistics.incMisses(context.getCacheName());
        return HandlerResult.terminate(CacheResult.miss());   // ← 短路,不查 Redis
    }
    return HandlerResult.continueChain();
}
```

> 此外,`RedisProCache.get(...)` 在调用业务 loader **前**会再做一次布隆拦截——防止 sync 模式下未命中仍触发数据源查询。两层拦截。

### PostProcess:确认成功才写入

关键设计——**只在数据确实写进 Redis 后才更新布隆**。PUT 时只打个标记,真正的 `bloomSupport.add(...)` 在 `afterChainExecution` 里执行:

```java
public void afterChainExecution(CacheContext context, CacheResult result) {
    if (!result.isSuccess() || context.isSkipRemaining()) return;   // 失败/跳过则不加
    switch (context.getOperation()) {
        case PUT, PUT_IF_ABSENT -> addToBloomFilter(context);
        case CLEAN -> clearBloomFilter(context);
        default -> { }
    }
}
```

这样 GET miss(空值)不会污染过滤器;CLEAN 清空时会留一条 warning(布隆不支持精确删除,清空后短期有穿透风险,直到新 PUT 重新填充)。

## 三种 BloomIFilter 实现

策略接口 `BloomIFilter`,通过 `BloomSupport` 门面按 cacheName 路由。三种实现可按部署形态选择:

### LocalBloomIFilter —— JVM 内存

`src/main/java/io/github/davidhlp/spring/cache/redis/protection/bloom/filter/LocalBloomIFilter.java:30`

- 每个 cacheName 一个独立 `java.util.BitSet` + `ReadWriteLock`(读锁并发查、写锁独占写)。
- `clear()` 清空 BitSet 而**不移除**条目,避免其他线程竞态。
- 速度最快,但不跨实例共享——适合单实例或可接受各节点布隆独立的小规模场景。

### RedisBloomIFilter —— Redis 分布式

- 基于 Redisson 的位图(`RBitSet`),所有实例共享同一份过滤器。
- 跨节点一致,但每次检查多一次 Redis 往返。

### HierarchicalBloomIFilter —— 两级

- 本地布隆(快、可能假阳性)→ 未命中再查 Redis 布隆(权威)。
- 兼顾速度与一致性:绝大多数请求命中本地,只有本地判「不在」时才回退 Redis 确认。

## 哈希策略:BloomHashStrategy

`BloomHashStrategy.positionsFor(key, config)` 把 key 映射到位数组的若干下标。`MessageDigestBloomHashStrategy` 是默认实现(消息摘要派生多个哈希位)。`BloomFilterConfig` 决定 `bitSize` / 哈希函数数(由 `expectedInsertions` + `falseProbability` 推导)。

## 自定义布隆后端

`BloomIFilter` 是策略接口,三种实现都是 Spring `@Component`,`BloomSupport` 注入它们。要换自定义后端(如 RedisBloom 模块、TairBloom),实现 `BloomIFilter` 并声明为 `@Bean` / `@Component`,配合 `@ConditionalOnMissingBean` 即可顶替默认实现。

## 配置

全局(`resi-cache.bloom-filter.*`,见 [[configuration]]):

```yaml
resi-cache:
  bloom-filter:
    enabled: true
    expected-insertions: 100000
    false-probability: 0.01
```

注解级(`@RedisCacheable`,见 [[annotations]]):

```java
@RedisCacheable(value = "users", key = "#id",
    useBloomFilter = true,
    expectedInsertions = 10000,
    falseProbability = 0.03)
```

## 相关

- [[cache-penetration]] —— 布隆 + 空值双防线的另一道
- [[null-value]] —— 空值缓存,与布隆互补
- [[handler-result-control]] —— `PostProcessHandler` 机制
