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
- **Checkstyle**: Enforced via `src/main/resources/checkstyle-custom.xml` (runs on every build)
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
- **Checkstyle only**: `./mvnw checkstyle:check`
- **Package**: `./mvnw clean package -DskipTests`

## Project Structure

```
src/main/java/io/github/davidhlp/spring/cache/redis/
├── annotation/          # @RedisCacheable/Put/Evict/Caching + AnnotationParser/Adapter + OperationSource
│   └── handler/         #   AnnotationHandler + Abstract + 4 concrete handlers + AnnotationChainEngine
├── cache/               # Spring integration core: RedisProCache(Manager/Writer), RedisCacheInterceptor,
│                        #   LoaderOrchestrator, CacheOperationResolver, CacheKeys, CachedValue,
│                        #   ResiCacheFeatures
│   └── metrics/         #   CacheMetrics + RedisProCacheMetricsRegistry + RedisProCacheTimers
├── chain/               # Chain of Responsibility core (root + handler/ + metadata/ + model/ + observer/)
│   ├── (root)           #   CacheHandler/Chain/Factory, AbstractCacheHandler, ChainEngine (推进引擎),
│   │                    #   HandlerOrder/Priority, CacheOperation/Result, ChainProtectionToggleResolver
│   ├── handler/         #   ActualCacheHandler + CacheErrorHandler (terminal Redis execution)
│   ├── metadata/        #   MethodMetadataResolver(+Default), CacheInvocationContext,
│   │                    #   ScopedActivation, MetadataKeys
│   ├── model/           #   CacheInput, CacheContext + 4 *Decision (Null/Ttl/Prefetch/EarlyExpiration)
│   └── observer/        #   ChainObserver/Registry/Registration + 4 concrete observers
├── config/              # RedisCacheAutoConfiguration + 4 sibling @Configurations + RedisProCacheProperties (311 LOC),
│                        #   RedisConnectionConfiguration + RedissonConfiguration + TlsConfigurationValidator,
│                        #   SerializerWhitelistStartupGuard + SerializationPreFlightProbe + JacksonConfig,
│                        #   CachingEnablementValidation
├── protection/          # The 5 resilience mechanisms (each = Handler + Strategy/Support/Config)
│   ├── avalanche/       #   TtlHandler (300) + TtlPolicy/DefaultTtlPolicy          - TTL jitter, anti-avalanche
│   ├── bloom/           #   BloomFilterHandler (100) + BloomGate/Support/Rebuilder + Config/HashStrategy
│   │   └── filter/      #     BloomIFilter + Local/Redis/Hierarchical impls          - anti-penetration
│   ├── breakdown/       #   SyncLockHandler (200) + SyncSupport/Role + DistributedLockManager + LockManager - anti-breakdown
│   ├── nullvalue/       #   NullValueHandler (400) + NullValueEncoder + Policy/Default    - null caching
│   └── refresh/         #   EarlyExpirationHandler (250) + Mode/Policy(+Default) + Scripts + Executor(ThreadPool) + Retry/Metrics - hot key
├── operation/           # RedisCacheable/Put/Evict Operation + RedisCacheAttributes + AttributePopulator + RedisCacheRegister +
│                        #   OperationKind + OperationFactory + SpringCacheableAdapterFactory + RedisCacheAttributesProjector
│   └── eviction/        #   TwoListLRU + EvictionStats (RedisCacheRegister's bounded metadata store)
├── serialization/       # SecureJacksonRedisSerializer + SecureJacksonSerializerFactory + VersionEnvelope +
│                        #   WhitelistPolicy + SecureNullValueDeserializer + TypeSupport + SerializationException
└── health/              # RedisCacheHealthIndicator (actuator health)
```

> 已移除(不在源码中):`wrapper/`(熔断/限流)、`spi/`(ServiceLoader)、`event/`、独立 `evaluator/`、`CacheMetricsRecorder`、`holder/`、`factory/`(已并入 `operation/`)。文档始终以实际源码为准。

### Test Structure

```
src/test/java/io/github/davidhlp/spring/cache/redis/
├── (mirror packages: annotation/{,handler/}, cache/{,metrics/}, chain/{,observer/}, config/,
│   operation/{,eviction/}, protection/{avalanche,bloom{,filter},breakdown,nullvalue,refresh},
│   serialization/)              # unit tests mirroring src/main/java structure
├── integration/                 # ALL Testcontainers-based integration tests + shared scaffolding:
│   ├── AbstractRedisIntegrationTest  # base class — Redis container + socat forward + @DynamicPropertySource
│   ├── TestApplication / TestRedisConfiguration   # Spring Boot test entry + @Primary bean mirror
│   ├── TestCacheService          # @Service stub used by PathCAop* / RedisCacheSemantics ITs
│   ├── *IntegrationTest.java (6) # BloomFilter / CacheOperations / DistributedLock / KeyResolution /
│   │                             #   SelectiveMode / SyncSingleFlight — full end-to-end protection scenarios
│   └── *IT.java (3)              # PathCAopContract / PathCAopAsync / RedisCacheSemantics — AOP contract ITs
└── com/example/round5/          # test fixture for whitelisted custom domain types (serializer interop)
```

> 集成测试统一位于 `integration/`。新增集成测试:继承 `AbstractRedisIntegrationTest`,放在同一包内,无需显式 import scaffolding。

## Key Architecture: Chain of Responsibility

Cache writes go through a chain of handlers (in order):

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
- **Context passing**: Use `CacheContext` to pass data between handlers (input is immutable, output is mutable)
- **Strategy replacement**: 策略接口(`BloomIFilter`、`LockManager`)的默认实现均为 Spring `@Component`。自定义实现时声明 `@Bean` 配合 `@ConditionalOnMissingBean` 顶替默认即可。框架核心不依赖 Java ServiceLoader。

## Where to Look

| I want to... | Look at... |
|--------------|-----------|
| Understand the chain / a mechanism | `chain/` package + `protection/<mechanism>/` (each handler carries design rationale in Javadoc) |
| Understand a module | the package itself under `src/main/java/.../`; module layout is in Project Structure above |
| Add a new cache protection handler | `protection/<mechanism>/` + implement `CacheHandler`, annotate `@HandlerPriority(HandlerOrder.X)` |
| Modify annotation processing | `annotation/handler/` + `AnnotationHandler` interface |
| Change Redis connection config | `config/RedisConnectionConfiguration.java` |
| Configure behavior | `config/RedisProCacheProperties.java` (311 LOC, `resi-cache.*` prefix) |
| Add integration tests | `AbstractRedisIntegrationTest.java` + Testcontainers |
