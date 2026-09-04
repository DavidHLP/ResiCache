# Project Instructions

## Tech Stack

| Layer | Technology | Version |
|-------|-----------|---------|
| Language | Java | 21 |
| Framework | Spring Boot | 4.0.0 |
| Cache | Spring Cache + Spring Data Redis | - |
| Distributed Lock | Redisson | 3.50.0 |
| Local Cache | Caffeine | 3.1.8 |
| Build | Maven | 3.x |
| Testing | JUnit 5 + Testcontainers + AssertJ + Awaitility | - |

> Tech Stack 表为单构建口径(Boot 4.0 / Java 21 / Redisson 3.50.0 单构建线)。重构后已无 `wrapper/`/`spi/`/`event/`/`evaluator/`/`CacheMetricsRecorder`,目录树见下方 Project Structure + 已移除 callout。

## Code Style

- **Naming**: Java standard PascalCase for classes, camelCase for methods/fields
- **Checkstyle**: Enforced by `./mvnw checkstyle:check -B`
- **Lombok**: Used throughout - `@Data`, `@Getter`, `@Setter`, `@Builder`
- **Javadoc**: Chinese comments explaining design rationale in key classes

## Testing

- **Run tests**: `./mvnw test`
- **Run with coverage**: `./mvnw verify` (JaCoCo enforced at 70% line / 40% branch coverage)
- **Integration tests**: Use Testcontainers for Redis, extend `AbstractRedisIntegrationTest`
- **Pattern**: Test classes mirror source structure under `src/test/java/`

## Build & Run

- **Dev build**: `./mvnw clean compile`
- **Full verify**: `./mvnw clean verify -B`
- **Checkstyle only**: `./mvnw checkstyle:check -B`
- **Package**: `./mvnw clean package -DskipTests`

## Project Structure

```
ResiCache/
├── resicache-bench/        # Standalone JMH micro-benchmark module (JMH 1.37, shade fat-jar, 5 benchmark suites)
└── src/main/java/io/github/davidhlp/spring/cache/redis/
    ├── annotation/          # @RedisCacheable/Put/Evict/Caching + AnnotationParser/Adapter + OperationSource
    │   └── handler/         #   AnnotationHandler + Abstract + 4 concrete handlers + AnnotationChainEngine
    ├── cache/               # Spring integration core: RedisProCache(Manager/Writer), RedisCacheInterceptor, internal write-failure exception
    │   ├── loader/          #   LoaderOrchestrator, CacheOperationResolver (miss loading orchestration)
    │   ├── metrics/         #   CacheMetrics + RedisProCacheMetricsRegistry + RedisProCacheTimers
    │   └── model/           #   CacheKeys, CachedValue, ResiCacheFeatures
    ├── chain/               # Chain of Responsibility core
    │   ├── (root)           #   CacheHandler/Chain/Factory, AbstractCacheHandler, ChainEngine (推进引擎),
    │   │                    #   HandlerOrder/Priority, CacheOperation/Result, FlowControl, HandlerResult
    │   ├── handler/         #   ActualCacheHandler + CacheErrorHandler (terminal Redis execution & error degradation)
    │   ├── metadata/        #   MethodMetadataResolver(+Default), MethodSnapshot, ScopedActivation, MetadataKeys
    │   ├── model/           #   CacheInput, CacheContext + 4 *Decision (Null/Ttl/Prefetch/EarlyExpiration)
    │   └── observer/        #   ChainObserver + ObserverRegistry + 4 concrete observers (Timer, MDC, Log, Counter)
    ├── config/              # RedisCacheAutoConfiguration + RedisProCacheProperties + 4 sibling @Configurations,
    │                        #   RedisConnectionConfiguration + RedissonConfiguration + TlsConfigurationValidator,
    │                        #   SerializerWhitelistStartupGuard + SerializationPreFlightProbe + JacksonConfig,
    │                        #   CachingEnablementValidation
    ├── health/              # RedisCacheHealthIndicator (actuator health)
    ├── operation/           # RedisCacheable/Put/Evict Operation + RedisCacheAttributes + AttributePopulator +
    │                        #   RedisCacheRegister + OperationKind + SpringCacheableAdapter + RedisCacheAttributesProjector +
    │                        #   TwoListLRU (bounded operation register LRU store)
    ├── protection/          # The 5 resilience mechanisms (each = Handler + Strategy/Support/Config)
    │   ├── avalanche/       #   TtlHandler (300) + TtlPolicy/DefaultTtlPolicy          - TTL jitter, anti-avalanche
    │   ├── bloom/           #   BloomFilterHandler (100) + BloomGate/Support/Rebuilder + Config/HashStrategy
    │   │   └── filter/      #     BloomIFilter + Local/Redis/Hierarchical impls          - anti-penetration
    │   ├── breakdown/       #   SyncLockHandler (200) + SyncSupport/Role + DistributedLockManager + LockManager - anti-breakdown
    │   ├── nullvalue/       #   NullValueHandler (400) + NullValueEncoder + Policy/Default    - null caching
    │   └── refresh/         #   EarlyExpirationHandler (250) + Mode/Policy(+Default) + Scripts + Executor(ThreadPool) + Retry/Metrics - hot key
    └── serialization/       # SecureJacksonRedisSerializer + SecureJacksonSerializerFactory + VersionEnvelope +
        │                    #   WhitelistPolicy + SecureNullValueDeserializer + TypeSupport + SerializationException
        └── migration/       #   SerializationMigrationCli + SerializationMigrationEngine + LegacyValueDecoder + Phase/Report/Properties
```

> 已移除(不在源码中):`wrapper/`(熔断/限流)、`spi/`(ServiceLoader)、`event/`、独立 `evaluator/`、`CacheMetricsRecorder`、`holder/`、`operation/eviction/` 子包(已收敛至 `operation/TwoListLRU.java`)。文档始终以实际源码为准。

### Test Structure

```
src/test/java/io/github/davidhlp/spring/cache/redis/
├── (mirror packages: annotation/{,handler/}, cache/{,loader/,metrics/,model/}, chain/{,handler/,observer/}, config/,
│   operation/, protection/{avalanche,bloom{,filter},breakdown,nullvalue,refresh},
│   serialization/{,migration/}) # unit tests mirroring src/main/java structure
├── integration/                 # shared Testcontainers scaffolding + many integration suites
│   ├── AbstractRedisIntegrationTest  # base class — Redis container + @DynamicPropertySource
│   ├── AbstractRedisClusterIntegrationTest # base class — 3-master real Redis Cluster
│   ├── TestApplication / TestRedisConfiguration   # Spring Boot test entry + test-only @Primary fixtures
│   ├── TestCacheService          # @Service stub used by integration tests
│   └── *IntegrationTest.java     # integration suites in this package
├── cache/, chain/, config/       # additional integration tests mirroring production packages
└── com/example/domain/           # test fixture for whitelisted custom domain types (serializer interop)
```
> Redis integration tests may live in the mirrored production package or
> `integration/`; all use the `*IntegrationTest.java` suffix. The naming guard
> rejects `*IT.java` and is enforced in local/CI flows.
## Key Architecture: Chain of Responsibility

Cache operations that use ResiCache go through a chain of handlers (in order):

1. **BloomFilterHandler** (100) - Checks if key exists in bloom filter, blocks cache penetration
2. **SyncLockHandler** (200) - Acquires distributed lock, prevents cache breakdown
3. **EarlyExpirationHandler** (250) - Triggers async early refresh for hot keys
4. **TtlHandler** (300) - Applies TTL variation to prevent cache avalanche
5. **NullValueHandler** (400) - Caches null values to prevent cache penetration
6. **ActualCacheHandler** (500) - Executes actual Redis PUT

Each handler implements `CacheHandler` interface with `handle()` method.

## Conventions

- **Handler ordering**: Defined by `@HandlerPriority(HandlerOrder)` enum in `chain/HandlerOrder.java` (gap=100, single source of truth)
- **Configuration properties**: Use `@ConfigurationProperties(prefix = "resi-cache")` with nested properties classes
- **Checkstyle**: Runs through the explicit `checkstyle:check` command, not as part of `verify`.
- **Strategy replacement**: `TtlPolicy`, `NullValuePolicy`, `EarlyExpirationPolicy`,
  `BloomHashStrategy`, `BloomIFilter`, and `MethodMetadataResolver` defaults are
  explicit typed Beans with `@ConditionalOnMissingBean`. `CacheHandler` remains
  a component class registered by the explicit auto-configuration import list;
  package scanning is not used.

## Where to Look

| I want to... | Look at... |
|--------------|-----------|
| Understand the chain / a mechanism | `chain/` package + `protection/<mechanism>/` (each handler carries design rationale in Javadoc) |
| Understand a module | the package itself under `src/main/java/.../`; module layout is in Project Structure above |
| Add a new cache protection handler | `protection/<mechanism>/` + implement `CacheHandler`, annotate `@HandlerPriority(HandlerOrder.X)` |
| Modify annotation processing | `annotation/handler/` + `AnnotationHandler` interface |
| Change Redis connection config | `config/RedisConnectionConfiguration.java` |
| Configure behavior | `config/RedisProCacheProperties.java` (`resi-cache.*` prefix) |
| Add integration tests | `AbstractRedisIntegrationTest.java` + Testcontainers |
