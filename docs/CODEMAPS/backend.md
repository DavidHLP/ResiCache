# Backend Map (Java/Spring Starter)

<!-- Generated: 2026-06-21 | Files scanned: 90 main | Token estimate: ~750 -->

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

## Bloom Filter (protection/bloom/filter/)

```
BloomIFilter (interface)
  ├─ LocalBloomIFilter         — in-memory (hash cache)        @Component("localBloomFilter")
  ├─ RedisBloomIFilter         — Redis bitmap                   @Component("redisBloomFilter")
  └─ HierarchicalBloomIFilter  — local(L1) + Redis(L2) layered  @Primary, composes the two above via @Qualifier
BloomSupport (@Component) → injected with HierarchicalBloomIFilter (selected by @Primary)
Hash:  MessageDigestBloomHashStrategy : BloomHashStrategy
Config: BloomFilterConfig (@Value: prefix, bit-size, hash-functions, hash-cache-size)
```

## Distributed Lock (protection/breakdown/)

```
LockManager (interface) → DistributedLockManager (@Component, @ConditionalOnClass RedissonClient)
  └─ tryAcquire → Redisson RLock → LockManager.LockHandle (nested interface, AutoCloseable)
SyncLockHandler → SyncSupport (JVM single-flight + distributed lock) → LockManager
```

## Configuration Classes (config/)

| Class | Purpose |
|-------|---------|
| `RedisCacheAutoConfiguration` | Entry (@ConditionalOnClass RedisOperations, @Import 5 sub-configs) |
| `RedisProxyCachingConfiguration` | AOP / proxy registration |
| `RedisProCacheConfiguration` | Core beans (manager, writer, chain factory) + @ComponentScan |
| `RedisCacheRegistryConfiguration` | Cache registration |
| `RedisConnectionConfiguration` | Redis / Redisson connection |
| `RedisProCacheProperties` | `resi-cache.*` binding |
| `SecureJackson2JsonRedisSerializer` | Safe polymorphic serialization |
| `JacksonConfig` | ObjectMapper setup |
| `MetricsAutoConfiguration` | `RedisCacheHealthIndicator` bean (metrics are inline in RedisProCache) |
| `CachingEnablementValidation` | `@EnableCaching` presence check at startup |
| `VersionEnvelope` | Serialization version envelope |

## Supporting Modules

- `eviction/TwoListLRU` + `TwoListEvictionStrategy` — LRU via 2 Redis lists (used by `RedisCacheRegister`)
- `serialization/TypeSupport` + `SecureNullValueDeserializer` — type-safe deser with allow-list
- `observability/RedisCacheHealthIndicator` — actuator health endpoint
- SpEL `condition`/`unless` on annotations handled natively by Spring's `CacheAspectSupport` (no custom evaluator)
