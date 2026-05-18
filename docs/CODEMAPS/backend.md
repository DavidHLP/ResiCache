# Backend Map (Java/Spring)

<!-- Generated: 2026-05-18 | Files scanned: 102 Java files | Token estimate: ~800 -->

## Annotations

| Annotation | File | Purpose |
|------------|------|---------|
| `@RedisCacheable` | `annotation/RedisCacheable.java` | Cache method results |
| `@RedisCacheEvict` | `annotation/RedisCacheEvict.java` | Evict cache entries |
| `@RedisCachePut` | `annotation/RedisCachePut.java` | Update cache |
| `@RedisCaching` | `annotation/RedisCaching.java` | Combined annotations |

## Annotation Handler Chain

```
AnnotationHandler (abstract)
    ├── CacheableAnnotationHandler → @RedisCacheable
    ├── CachePutAnnotationHandler  → @RedisCachePut
    ├── EvictAnnotationHandler    → @RedisCacheEvict/@RedisCacheEvict
    └── CachingAnnotationHandler  → @RedisCaching
```

## Handler Interfaces

| Interface | File | Purpose |
|-----------|------|---------|
| `CacheHandler` | `writer/chain/handler/CacheHandler.java` | Chain handler contract |
| `PostProcessHandler` | `writer/chain/handler/PostProcessHandler.java` | Post-chain callbacks |
| `AnnotationHandler` | `core/handler/AnnotationHandler.java` | Annotation processor |
| `BloomFilterProvider` | `spi/BloomFilterProvider.java` | Bloom filter SPI |
| `LockProvider` | `spi/LockProvider.java` | Lock SPI |

## Cache Handler Implementations

| Handler | Order | Protection | Key Files |
|---------|-------|------------|-----------|
| `BloomFilterHandler` | 100 | Cache penetration | `support/protect/bloom/*` |
| `SyncLockHandler` | 200 | Cache breakdown | `support/lock/*` |
| `PreRefreshHandler` | 250 | Hot keys | `support/refresh/*` |
| `TtlHandler` | 300 | Cache avalanche | `support/protect/ttl/*` |
| `NullValueHandler` | 400 | Cache penetration | `support/protect/nullvalue/*` |
| `ActualCacheHandler` | 500 | Redis PUT | `RedisProCacheWriter.java` |

## Configuration Classes

| Class | File | Purpose |
|-------|------|---------|
| `RedisCacheAutoConfiguration` | `config/RedisCacheAutoConfiguration.java` | Main auto-config |
| `RedisProCacheConfiguration` | `config/RedisProCacheConfiguration.java` | Core config |
| `RedisConnectionConfiguration` | `config/RedisConnectionConfiguration.java` | Redis connection |
| `RedisCacheRegistryConfiguration` | `config/RedisCacheRegistryConfiguration.java` | Cache registry |
| `RedisProCacheProperties` | `config/RedisProCacheProperties.java` | External props |

## SPI Implementation

```
BloomFilterProvider implementations:
├── LocalBloomIFilter      → In-memory bloom filter
├── RedisBloomIFilter      → Redis-backed bloom filter
└── HierarchicalBloomIFilter → Layered (local+Redis)

LockProvider:
└── RedissonLockProvider   → Redisson-based distributed lock
```

## Key Supporting Classes

| Class | File | Purpose |
|-------|------|---------|
| `CacheContext` | `writer/chain/handler/CacheContext.java` | Context passing |
| `CacheInput` | `writer/chain/handler/CacheInput.java` | Immutable input |
| `CacheOutput` | `writer/chain/handler/CacheOutput.java` | Mutable output |
| `HandlerResult` | `writer/chain/handler/HandlerResult.java` | Handler decision |
| `SpelConditionEvaluator` | `core/evaluator/SpelConditionEvaluator.java` | SpEL evaluation |
