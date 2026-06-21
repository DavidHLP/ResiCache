# Backend Map (Java/Spring Starter)

<!-- Generated: 2026-06-21 | Files scanned: 104 main | Token estimate: ~850 -->

## Annotations (annotation/)

| Annotation | File | Purpose |
|------------|------|---------|
| `@RedisCacheable` | RedisCacheable.java | Cache method result |
| `@RedisCacheEvict` | RedisCacheEvict.java | Evict entries (key / allEntries) |
| `@RedisCachePut` | RedisCachePut.java | Update cache without reading |
| `@RedisCaching` | RedisCaching.java | Combine multiple ops |
| — | RedisCacheOperationSource.java | Resolves ops from method metadata |

## Annotation → Operation Pipeline

```
AnnotationHandler (handler/AnnotationHandler.java, abstract)
  ├─ CacheableAnnotationHandler  → CacheableOperationFactory → RedisCacheableOperation
  ├─ CachePutAnnotationHandler   → CachePutOperationFactory  → RedisCachePutOperation
  ├─ EvictAnnotationHandler      → EvictOperationFactory     → RedisCacheEvictOperation
  └─ CachingAnnotationHandler    → delegates to the above per op

RedisCacheRegister (operation/) registers ops → RedisProCacheManager (cache/)
```

## Chain Contracts (chain/)

| Type | File | Role |
|------|------|------|
| `CacheHandler` | chain/CacheHandler.java | Handler interface |
| `AbstractCacheHandler` | chain/AbstractCacheHandler.java | Template base |
| `CacheHandlerChain` | chain/CacheHandlerChain.java | Ordered execution |
| `CacheHandlerChainFactory` | chain/CacheHandlerChainFactory.java | Builds chain from `HandlerOrder`, honors `disabled-handlers` |
| `PostProcessHandler` | chain/PostProcessHandler.java | afterChainExecution() hook |
| `CacheErrorHandler` | chain/CacheErrorHandler.java | Failure policy |
| `@HandlerPriority` | chain/HandlerPriority.java | Binds handler → HandlerOrder enum |

## Handler Implementations (see [architecture.md](architecture.md))

| Handler | Order | Package | Protection |
|---------|-------|---------|-----------|
| BloomFilterHandler | 100 | protection/bloom | Penetration (BF) |
| SyncLockHandler | 200 | protection/breakdown | Breakdown (Redisson lock) |
| EarlyExpirationHandler | 250 | protection/refresh | Hot key (async refresh) |
| TtlHandler | 300 | protection/avalanche | Avalanche (TTL jitter) |
| NullValueHandler | 400 | protection/nullvalue | Penetration (null cache) |
| ActualCacheHandler | 500 | chain | Redis PUT |

## Bloom Filter SPI (protection/bloom/filter/)

```
BloomIFilter (interface)
  ├─ LocalBloomIFilter         — in-memory (hash cache)
  ├─ RedisBloomIFilter         — Redis bitmap
  └─ HierarchicalBloomIFilter  — local(L1) + Redis(L2) layered
Provider: RedisBloomFilterProvider  |  Hash: MessageDigestBloomHashStrategy : BloomHashStrategy
Config:  BloomFilterConfig { enabled, expectedInsertions(100k), hashCacheSize(10k) }
```

## Lock SPI (protection/breakdown/ + spi/)

```
LockProvider (spi) → RedissonLockProvider       LockManager → DistributedLockManager
LockHandle (spi) — auto-releasing handle (try-with-resources)
```

## Configuration Classes (config/)

| Class | Purpose |
|-------|---------|
| `RedisCacheAutoConfiguration` | Entry (@ConditionalOnClass RedisOperations) |
| `RedisProxyCachingConfiguration` | AOP / proxy registration |
| `RedisProCacheConfiguration` | Core beans (manager, writer, chain factory) |
| `RedisCacheRegistryConfiguration` | Cache registration |
| `RedisConnectionConfiguration` | Redis / Redisson connection |
| `RedisProCacheProperties` | `resi-cache.*` binding |
| `SecureJackson2JsonRedisSerializer` | Safe polymorphic serialization |
| `JacksonConfig` | ObjectMapper setup |
| `MetricsAutoConfiguration` | Observability beans |
| `CachingEnablementValidation` | `@EnableResiCache` validation |
| `VersionEnvelope` | Version metadata |

## Supporting Modules

- `evaluator/SpelConditionEvaluator` — condition/unless via SpEL (`failOnSpelError` toggle)
- `eviction/TwoListLRU` + `TwoListEvictionStrategy` — LRU via 2 Redis lists (`EvictionStrategyFactory`)
- `wrapper/CircuitBreakerCacheWrapper`, `RateLimiterCacheWrapper` — resilience decorators
- `serialization/TypeSupport` + `SecureNullValueDeserializer` — type-safe deser with allow-list
- `observability/CacheMetricsRecorder`, `RedisCacheHealthIndicator`
- `event/CacheEvictedEvent` — Spring application event on evict
