# Architecture Map

<!-- Generated: 2026-06-21 | Files scanned: 104 main + 64 test | Token estimate: ~700 -->

## System Type

Spring Boot Starter library — Redis cache with resilience protections. No web layer;
consumed as a dependency. Parent: `spring-boot-starter-parent:3.4.13`, Java 17+.

## High-Level Data Flow

```
User App → @RedisCacheable / @RedisCacheEvict / @RedisCachePut / @RedisCaching
                ↓
    RedisCacheInterceptor (AOP)                         cache/RedisCacheInterceptor.java
                ↓
    AnnotationHandler (handler/)  Cacheable | CachePut | Evict | Caching
                ↓
    OperationFactory (factory/) → RedisCacheable/Put/Evict Operation
                ↓
    RedisCacheRegister (operation/) → RedisProCacheManager (cache/)
                ↓
    RedisProCacheWriter (cache/) → CacheHandlerChain (chain/)
                ↓
    6 resilience handlers (protection/* + chain/ActualCacheHandler)
```

## Cache Handler Chain (chain/HandlerOrder.java — single source of truth, gap=100)

```
BloomFilterHandler       (100) protection/bloom      — 防穿透: BF 校验先于 Redis
SyncLockHandler          (200) protection/breakdown  — 防击穿: Redisson 分布式锁
EarlyExpirationHandler   (250) protection/refresh    — 热 key: 异步提前刷新
TtlHandler               (300) protection/avalanche  — 防雪崩: TTL 随机抖动
NullValueHandler         (400) protection/nullvalue  — 防穿透: 空值短 TTL 缓存
ActualCacheHandler       (500) chain                 — 执行 Redis PUT
```

Handlers bound via `@HandlerPriority(HandlerOrder.XXX)`. Chain assembled by
`CacheHandlerChainFactory` (honors `resi-cache.disabled-handlers`).
Any handler may set `output.skipRemaining=true` to short-circuit.

## Package Map (organized by business capability)

```
annotation/    @RedisCacheable/@Evict/@Put/@Caching + RedisCacheOperationSource
cache/         RedisProCache, RedisProCacheManager, RedisProCacheWriter, RedisCacheInterceptor, CachedValue
chain/         CacheHandler, AbstractCacheHandler, CacheHandlerChain, CacheHandlerChainFactory,
               ActualCacheHandler, PostProcessHandler, CacheErrorHandler, HandlerOrder, HandlerPriority,
               ChainDecision, CacheOperation, CacheResult, HandlerResult  +  model/{CacheInput,CacheOutput,CacheContext,...}
config/        RedisCacheAutoConfiguration (entry) + config classes + Properties + SecureJackson + Validation
protection/    avalanche/ bloom/ breakdown/ nullvalue/ refresh/   ← the 5 resilience mechanisms
operation/     RedisCacheable/Put/Evict Operation + RedisCacheRegister
factory/       OperationFactory + 3 concrete factories
handler/       AnnotationHandler + 4 concrete annotation handlers
evaluator/     SpelConditionEvaluator (condition/unless)
eviction/      TwoListLRU + TwoListEvictionStrategy (LRU via 2 Redis lists)
serialization/ SecureNullValueDeserializer, TypeSupport, SerializationException (safe Jackson)
observability/ CacheMetricsRecorder, RedisCacheHealthIndicator
wrapper/       CircuitBreakerCacheWrapper, RateLimiterCacheWrapper
spi/           BloomFilter, BloomFilterProvider, LockManager, LockProvider, LockHandle, RedissonLockProvider
event/         CacheEvictedEvent
holder/        CacheOperationMetadataHolder
```

## Context Passing (chain/model/)

| Model | Mutability | Holds |
|-------|-----------|-------|
| `CacheInput` | immutable | operation, cacheName, redisKey, actualKey, valueBytes, deserializedValue, ttl, cacheOperation |
| `CacheOutput` | mutable | finalTtl, storeValue, lockContext, earlyExpirationDecision, skipRemaining, keyPattern, finalResult |
| `CacheContext` | wrapper | input + output + `attributes` (ConcurrentHashMap — cross-handler data) |

## SPI (optional extension — META-INF/services/ files empty by default)

```
io.github.davidhlp.spring.cache.redis.spi.BloomFilterProvider  → user registers impl
io.github.davidhlp.spring.cache.redis.spi.LockProvider         → user registers impl
```

Default impls injected as Spring beans, not via ServiceLoader.

## Configuration Entry

- Prefix `resi-cache.*` → `config/RedisProCacheProperties.java`
- Auto-config: `RedisCacheAutoConfiguration` (@ConditionalOnClass RedisOperations)
- See [data.md](data.md) for key/TTL model · [dependencies.md](dependencies.md) for libs
