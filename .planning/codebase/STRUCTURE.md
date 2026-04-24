# Codebase Structure

**Analysis Date:** 2026-04-24

## Directory Layout

```
ResiCache/
├── pom.xml                                      # Maven build config (Spring Boot 3.2.4, Java 17)
├── src/
│   ├── main/java/io/github/davidhlp/spring/cache/redis/
│   │   ├── annotation/                           # Cache operation annotations
│   │   ├── config/                              # Spring Boot auto-configuration
│   │   ├── core/                                # Core cache infrastructure
│   │   │   ├── evaluator/                       # SpEL expression evaluation
│   │   │   ├── factory/                        # Operation factories
│   │   │   ├── handler/                        # Annotation handlers
│   │   │   ├── writer/                         # Cache writer implementation
│   │   │   │   ├── chain/                      # Handler chain infrastructure
│   │   │   │   │   └── handler/                # Handler implementations
│   │   │   │   └── support/                    # Handler support classes
│   │   │   │       ├── lock/                   # Distributed lock support
│   │   │   │       ├── protect/                # Protection mechanisms
│   │   │   │       │   ├── bloom/             # Bloom filter support
│   │   │   │       │   ├── nullvalue/         # Null value handling
│   │   │   │       │   └── ttl/                # TTL calculation
│   │   │   │       ├── refresh/                # Pre-refresh support
│   │   │   │       └── type/                   # Type serialization support
│   │   │   ├── RedisCacheInterceptor.java      # AOP interceptor
│   │   │   └── RedisProCache.java              # Custom cache with metrics
│   │   ├── manager/                            # Cache manager
│   │   ├── register/                           # Cache operation registry
│   │   │   └── operation/                     # Operation types
│   │   ├── spi/                               # Service provider interfaces
│   │   └── strategy/                           # Eviction strategies
│   │       └── eviction/
│   │           ├── impl/                       # Strategy implementations
│   │           ├── stats/                      # Eviction statistics
│   │           └── support/                    # Strategy support classes
│   └── test/java/io/github/davidhlp/spring/cache/redis/
│       ├── core/
│       │   ├── handler/                        # Handler unit tests
│       │   └── writer/
│       │       └── chain/                     # Chain tests
│       ├── register/                           # Registry tests
│       └── strategy/
│           └── eviction/                      # Eviction strategy tests
```

## Directory Purposes

**`annotation/`:**
- Purpose: Define declarative cache operations
- Contains: 4 annotation interfaces
- Key files:
  - `RedisCacheable.java` - Main read cache annotation
  - `RedisCacheEvict.java` - Cache invalidation annotation
  - `RedisCachePut.java` - Force cache update annotation
  - `RedisCaching.java` - Composite annotation
  - `RedisCacheOperationSource.java` - Annotation metadata

**`config/`:**
- Purpose: Spring Boot auto-configuration and properties
- Contains: Configuration classes loaded on startup
- Key files:
  - `RedisCacheAutoConfiguration.java` - Main entry point
  - `RedisProCacheConfiguration.java` - Core beans
  - `RedisProCacheProperties.java` - External configuration
  - `RedisConnectionConfiguration.java` - Redis connection setup
  - `RedisCacheRegistryConfiguration.java` - Registry beans
  - `RedisProxyCachingConfiguration.java` - Proxy configuration
  - `CachingEnablementValidation.java` - Validation utilities
  - `JacksonConfig.java` - JSON serialization config
  - `SecureJackson2JsonRedisSerializer.java` - Custom serializer

**`core/`:**
- Purpose: Core cache infrastructure
- Contains: Interceptor, cache implementation, evaluators

**`core/evaluator/`:**
- Purpose: SpEL expression evaluation
- Key files:
  - `SpelConditionEvaluator.java` - Condition/unless evaluation

**`core/factory/`:**
- Purpose: Create cache operation objects from annotations
- Key files:
  - `CacheableOperationFactory.java`
  - `CachePutOperationFactory.java`
  - `EvictOperationFactory.java`
  - `OperationFactory.java`

**`core/handler/`:**
- Purpose: Process annotations and build operations
- Key files:
  - `AnnotationHandler.java` - Base class (Chain of Responsibility)
  - `CacheableAnnotationHandler.java` - Handles @RedisCacheable
  - `EvictAnnotationHandler.java` - Handles @RedisCacheEvict
  - `CachePutAnnotationHandler.java` - Handles @RedisCachePut
  - `CachingAnnotationHandler.java` - Handles @RedisCaching

**`core/writer/`:**
- Purpose: Execute cache operations to Redis
- Key files:
  - `RedisProCacheWriter.java` - Main writer with handler chain
  - `CachedValue.java` - Value wrapper with TTL metadata

**`core/writer/chain/`:**
- Purpose: Handler chain infrastructure
- Key files:
  - `CacheHandlerChain.java` - Chain manager
  - `CacheContext.java` - Operation context
  - `CacheResult.java` - Operation result
  - `CacheOperation.java` - Operation type enum

**`core/writer/chain/handler/`:**
- Purpose: Individual handler implementations
- Key files:
  - `CacheHandler.java` - Handler interface
  - `AbstractCacheHandler.java` - Base implementation
  - `ActualCacheHandler.java` - Redis operations
  - `BloomFilterHandler.java` - Penetration prevention
  - `SyncLockHandler.java` - Breakdown prevention
  - `PreRefreshHandler.java` - Hot key protection
  - `TtlHandler.java` - TTL calculation
  - `NullValueHandler.java` - Null value handling
  - `HandlerOrder.java` - Execution order enum
  - `HandlerPriority.java` - Priority annotation
  - `HandlerResult.java` - Decision and result
  - `CacheContext.java` - Context with attributes
  - `LockContext.java` - Lock context
  - `PostProcessHandler.java` - Post-processing interface
  - `ErrorHandler.java` - Error handling interface

**`core/writer/support/`:**
- Purpose: Handler support utilities
- Subdirectories:
  - `lock/` - Distributed lock support
  - `protect/bloom/` - Bloom filter support
  - `protect/nullvalue/` - Null value policies
  - `protect/ttl/` - TTL policies
  - `refresh/` - Pre-refresh support
  - `type/` - Type serialization support

**`manager/`:**
- Purpose: Create and manage cache instances
- Key files:
  - `RedisProCacheManager.java` - Cache manager (extends RedisCacheManager)

**`register/`:**
- Purpose: Register and retrieve cache operations
- Key files:
  - `RedisCacheRegister.java` - Operation registry with eviction

**`register/operation/`:**
- Purpose: Operation type definitions
- Key files:
  - `RedisCacheableOperation.java` - Read operation
  - `RedisCacheEvictOperation.java` - Evict operation
  - `RedisCachePutOperation.java` - Write operation

**`spi/`:**
- Purpose: Pluggable service provider interfaces
- Key files:
  - `BloomFilter.java` - Bloom filter interface
  - `BloomFilterProvider.java` - Bloom filter factory
  - `LockProvider.java` - Lock provider interface
  - `LockManager.java` - Lock management interface
  - `LockHandle.java` - Lock lease handle
  - `RedissonLockProvider.java` - Redisson implementation

**`strategy/eviction/`:**
- Purpose: Memory-bounded operation metadata storage
- Key files:
  - `EvictionStrategy.java` - Strategy interface
  - `EvictionStrategyFactory.java` - Factory
  - `TwoListEvictionStrategy.java` - LRU implementation

## Key File Locations

**Entry Points:**
- `src/main/java/.../config/RedisCacheAutoConfiguration.java` - Auto-config entry
- `src/main/java/.../config/RedisProCacheConfiguration.java` - Bean definitions

**Configuration:**
- `src/main/java/.../config/RedisProCacheProperties.java` - `resi-cache.*` properties

**Core Cache Logic:**
- `src/main/java/.../core/RedisCacheInterceptor.java` - AOP interceptor
- `src/main/java/.../core/RedisProCache.java` - Cache with metrics
- `src/main/java/.../core/writer/RedisProCacheWriter.java` - Writer implementation

**Handler Chain:**
- `src/main/java/.../core/writer/chain/CacheHandlerChain.java` - Chain manager
- `src/main/java/.../core/writer/chain/handler/CacheHandler.java` - Handler interface
- `src/main/java/.../core/writer/chain/handler/ActualCacheHandler.java` - Redis ops

**Annotations:**
- `src/main/java/.../annotation/RedisCacheable.java` - @RedisCacheable
- `src/main/java/.../annotation/RedisCacheEvict.java` - @RedisCacheEvict
- `src/main/java/.../annotation/RedisCachePut.java` - @RedisCachePut

**Testing:**
- `src/test/java/.../core/handler/` - Handler tests
- `src/test/java/.../core/writer/chain/` - Chain tests
- `src/test/java/.../register/` - Registry tests
- `src/test/java/.../strategy/eviction/` - Strategy tests

## Naming Conventions

**Files:**
- Java source: `PascalCase.java` (e.g., `RedisCacheInterceptor.java`)
- Test files: `PascalCaseTest.java` (e.g., `CacheableAnnotationHandlerTest.java`)

**Packages:**
- All lowercase with dots: `io.github.davidhlp.spring.cache.redis`
- Feature modules: `core.writer.chain.handler`

**Classes:**
- `PascalCase` for classes: `RedisCacheInterceptor`, `CacheHandlerChain`
- `CamelCase` for methods: `handleCacheAnnotations`, `executeChain`
- `SCREAMING_SNAKE_CASE` for constants: `HANDLER_ORDER`, `CLEAN_SCAN_COUNT`

**Annotations:**
- `PascalCase` with `Redis` prefix: `@RedisCacheable`, `@RedisCacheEvict`

## Where to Add New Code

**New Annotation Handler:**
- Primary code: `src/main/java/.../core/handler/XxxAnnotationHandler.java`
- Extend: `AnnotationHandler` base class
- Tests: `src/test/java/.../core/handler/XxxAnnotationHandlerTest.java`

**New Cache Handler (Chain):**
- Primary code: `src/main/java/.../core/writer/chain/handler/XxxHandler.java`
- Implement: `CacheHandler` interface
- Annotate: `@HandlerPriority(HandlerOrder.XXX)`
- Tests: `src/test/java/.../core/writer/chain/handler/XxxHandlerTest.java`

**New Support Class:**
- Primary code: `src/main/java/.../core/writer/support/feature/XxxSupport.java`
- Tests: Co-located test file

**New SPI Provider:**
- Primary code: `src/main/java/.../spi/XxxProvider.java`
- Implement: Corresponding interface
- Register: `META-INF/services/` file

**New Configuration:**
- Primary code: `src/main/java/.../config/XxxConfiguration.java`
- Import: In `RedisCacheAutoConfiguration`

## Special Directories

**`src/main/java/.../annotation/`:**
- Purpose: Annotation interfaces only
- Generated: No
- Committed: Yes

**`src/main/java/.../core/writer/support/protect/bloom/`:**
- Purpose: Bloom filter implementation variants
- Subdirs: `filter/`, `strategy/`

**`src/main/java/.../strategy/eviction/impl/`:**
- Purpose: Eviction strategy implementations
- Contains: `TwoListEvictionStrategy.java`

**`META-INF/services/` (SPI registration):**
- Purpose: Provider lookup for `BloomFilterProvider`, `LockProvider`
- Not visible in current structure - uses Spring component scanning

## Test Organization

Tests mirror main source structure:
```
src/test/java/io/github/davidhlp/spring/cache/redis/
├── core/
│   ├── handler/           # Tests for annotation handlers
│   └── writer/
│       └── chain/
│           ├── CacheHandlerChainTest.java
│           └── handler/   # Tests for each handler
├── register/
│   └── RedisCacheRegisterTest.java
└── strategy/
    └── eviction/
        └── impl/
            └── TwoListEvictionStrategyTest.java
```

## Package Dependencies

**annotation -> core (via handlers)**
**core/handler -> core/factory -> register/operation**
**core/writer -> core/writer/chain -> core/writer/chain/handler**
**core/writer/chain/handler -> core/writer/support/***
**core/writer/support -> spi**
**config -> all (via component scan)**

---

*Structure analysis: 2026-04-24*
