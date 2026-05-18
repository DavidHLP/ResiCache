# Architecture Map

<!-- Generated: 2026-05-18 | Files scanned: 102 Java files | Token estimate: ~600 -->

## High-Level Architecture

```
User App → @RedisCacheable/@RedisCacheEvict/@RedisCachePut
                ↓
    RedisCacheInterceptor (AOP)
                ↓
    AnnotationHandler chain (Cacheable/Evict/Put)
                ↓
    RedisCacheRegister → RedisProCacheManager
                ↓
    RedisProCacheWriter
                ↓
    CacheHandlerChain (6 handlers)
```

## Cache Handler Chain (Order: 100→500)

```
BloomFilterHandler (100)     → Block cache penetration (check BF before Redis)
       ↓
SyncLockHandler (200)        → Prevent cache breakdown (distributed lock)
       ↓
PreRefreshHandler (250)      → Hot key protection (async pre-refresh)
       ↓
TtlHandler (300)             → Prevent cache avalanche (TTL variation)
       ↓
NullValueHandler (400)       → Cache null values (prevent penetration)
       ↓
ActualCacheHandler (500)     → Execute Redis PUT operation
```

## Key Components

| Component | File | Responsibility |
|-----------|------|----------------|
| `RedisCacheAutoConfiguration` | `config/RedisCacheAutoConfiguration.java` | Spring Boot auto-config entry |
| `RedisCacheInterceptor` | `core/RedisCacheInterceptor.java` | AOP interceptor for cache ops |
| `CacheHandlerChain` | `writer/chain/CacheHandlerChain.java` | Chain execution manager |
| `CacheContext` | `writer/chain/handler/CacheContext.java` | Data passing between handlers |
| `BloomFilterHandler` | `writer/chain/handler/BloomFilterHandler.java` | Bloom filter protection |
| `SyncLockHandler` | `writer/chain/handler/SyncLockHandler.java` | Distributed lock |
| `PreRefreshHandler` | `writer/chain/handler/PreRefreshHandler.java` | Async refresh executor |

## Data Flow

```
1. Method annotated @RedisCacheable is called
2. RedisCacheInterceptor intercepts (AOP)
3. SpElConditionEvaluator evaluates condition/unless
4. CacheHandlerChain.execute(context) is called
5. Each handler processes in order, may set output.skipRemaining=true
6. PostProcessHandler.afterChainExecution() called after chain
7. Result returned to caller
```

## SPI Providers

```
META-INF/services/
├── io.github.davidhlp.spring.cache.redis.spi.BloomFilterProvider
└── io.github.davidhlp.spring.cache.redis.spi.LockProvider
```

## Configuration Properties

`resi-cache.*` prefix in `RedisProCacheProperties.java`:
- `default-ttl` - Default cache TTL
- `bloom-filter.*` - Bloom filter settings
- `pre-refresh.*` - Pre-refresh thread pool
- `sync-lock.*` - Distributed lock settings
- `redisson.*` - Redisson connection pool
