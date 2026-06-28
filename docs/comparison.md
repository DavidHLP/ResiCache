# ResiCache vs JetCache vs Caffeine vs raw Redisson

> 诚实是 solo 库的通货。本页**明确 owns ResiCache 输在哪、赢在哪**。
> 定位参见 [ADR-0006](../wiki/adr/0006-redisson-companion-positioning.md):**ResiCache for Redisson —— Redisson 忘了做的那条可声明缓存防护链**。

## 一句话各自定位

| 库 | 一句话 | 你已选它说明你要的是 |
|---|---|---|
| **JetCache** | 多级缓存(local+remote)+ 广播失效 + 自有 `@Cached` AOP 的成熟框架 | 多级缓存、跨实例失效、阿里背书的成熟度 |
| **Caffeine** | JVM 内高性能本地缓存(W-TinyLFU) | 纯本地、极致本地命中率 |
| **raw Redisson** | Redis Java 客户端 + 分布式原语(`RLock`/`RBloomFilter`/`RMap`…) | 直接用原语、自己组装一切 |
| **ResiCache** | Redisson 之上那条**可编排、可关断、可观测**的声明式缓存防护链 | 把散落的 RLock/RBloomFilter/TTL 收敛成一个 `@RedisCacheable`,且防护可插拔 |

## 能力矩阵

| 能力 | JetCache | Caffeine | raw Redisson | **ResiCache** |
|---|:---:|:---:|:---:|:---:|
| 多级缓存(local+remote) | ✅ 内置 | 仅 local | 需自组 | ❌ **明确不做** |
| 跨实例广播失效 | ✅ | N/A | 需自组(PubSub) | ❌ |
| API 级 `Cache.get/put`(脱离注解) | ✅ | ✅ | ✅ | ❌(走 Spring Cache 抽象) |
| 防**击穿**(分布式锁串行回源) | ✅ `@CachePenetrationProtect` | ❌ | `RLock` 手写 | ✅ `sync=true`(Redisson) |
| 防**穿透**(null 缓存) | ✅ | 手写 | `RBucket` 手写 | ✅ `cacheNullValues` |
| 防**雪崩**(TTL 抖动) | ✅ | 手写 | 手写 | ✅ `randomTtl` |
| **防真正穿透(Bloom 过滤器)** | ❌ **无** | ❌ | `RBloomFilter` 手写 | ✅ `useBloomFilter` |
| 热 key 异步提前刷新 | ✅ `@CacheRefresh`+refreshLock | ❌ | 手写 | ✅ `enableEarlyExpiration` |
| **可编排/可插拔防护链 SPI** | ❌(注解固化) | N/A | ❌ | ✅ **`HandlerOrder` + `@HandlerPriority`** |
| 基于 Spring Cache 抽象(`@Cacheable` 兼容) | ❌(自有 `@Cached`) | ✅(作 CacheManager) | N/A | ✅ |

## ResiCache 赢在哪(真实的、窄的)

1. **Bloom 过滤器**:JetCache **没有**。对"真正不存在的 key"的穿透,只有 Bloom 是正经防线(null 缓存挡不住恶意/海量不存在 key)。
2. **可编排防护链 SPI**:`HandlerOrder`(间隔 100,单一真理源)+ `@HandlerPriority` + `@ConditionalOnMissingBean` 策略替换。**插一个自定义 handler(如限流/熔断/配额)是一个 `@Component` 类**;JetCache 的机制 per-annotation 固化,没有插入点——这是它结构上抄不走的。
3. **`@Cacheable` 契约内的纵深防护**:JetCache 的 `jetcache-core` 零 Spring 依赖、走自有 `@Cached` AOP——**任何已标准化在 `@Cacheable`+`@EnableCaching` 的团队,结构上用不了 JetCache 而不重写**。

## ResiCache 输在哪(大声承认)

- **多级缓存、广播失效、API 级访问**:全让给 JetCache。**不要用 ResiCache 做这些。**
- **成熟度/背书/生产验证**:JetCache(阿里,大规模生产)碾压 solo v0.0.x ResiCache。**生产核心基建选 JetCache 更稳。**
- **Reactive(WebFlux/Mono/Flux)**:ResiCache 当前不支持(阻塞式 interceptor)。

## 先发制人:为什么不直接调 `redissonClient.getBloomFilter()` / `getLock()`?

- **1 个缓存**:直接用 Redisson 原语更好——ResiCache 没价值。
- **~30 个缓存、要一套一致防护策略**:手写 = 30 处 `RLock`+`RBloomFilter`+TTL 散落、行为漂移、无统一观测、改一处策略要改 30 处。ResiCache 收敛成 30 个 `@RedisCacheable` + 一条可编排链 + 统一指标。**编排价值只在规模与一致性需求出现时才显现**——这正是 ResiCache 的领地。

## 该选谁?

- 要**多级缓存 / 跨实例失效 / 成熟生产背书** → **JetCache**。
- 要**纯本地极致命中率** → **Caffeine**。
- 要**完全自己组装** → **raw Redisson**。
- 已在 `@Cacheable`+Redisson、要把**穿透/击穿/雪崩/热 key 做成可插拔可观测的一致策略**、不想推翻缓存层 → **ResiCache**。

> ResiCache 不追求"全面",追求"防护纵深这一件事,做得可编排、可关断、可观测、诚实"。
