# Project Instructions

## Tech Stack

| Layer | Technology | Version |
|-------|-----------|---------|
| Language | Java | 17+ |
| Framework | Spring Boot | 3.4.13 |
| Cache | Spring Cache + Spring Data Redis | - |
| Distributed Lock | Redisson | 3.27.0 |
| Local Cache | Caffeine | 3.1.8 |
| Build | Maven | 3.x |
| Testing | JUnit 5 + Testcontainers + AssertJ + Awaitility | - |

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

## 项目知识库(LLM Wiki)

本项目有一个由 LLM 持续维护的知识库,位于 **`wiki/`**。**任何 LLM 会话开始时优先读它**,避免从源码重新推导已沉淀过的内容:

- **入口**:`wiki/overview.md` —— 一句话概览 + 技术栈 + 阅读路线
- **目录**:`wiki/index.md` —— 全部页面(架构 / 机制 / 模块 / 概念 / 指南)
- **规范**:`wiki/README.md` —— 如何维护(ingest / query / lint 三大操作)
- **历史**:`wiki/log.md`

**源码 → wiki 是单向关系**:源码变了 → 更新对应 wiki 页 → 记 `log.md`;源码没变 → 直接引用 wiki,不要重新推导。

回答架构 / 机制 / 模块问题先读 `index.md` 定位,再下钻;不直接 grep 源码(wiki 已编译过)。

## Project Structure

```
src/main/java/io/github/davidhlp/spring/cache/redis/
├── annotation/          # Custom cache annotations (@RedisCacheable, @RedisCacheEvict, @RedisCachePut, @RedisCaching)
├── cache/               # RedisProCache, RedisProCacheManager, RedisProCacheWriter, RedisCacheInterceptor
├── chain/               # Chain of Responsibility: CacheHandler/Chain/Factory, AbstractCacheHandler,
│   └── model/           #   ActualCacheHandler, HandlerOrder/Priority  +  CacheInput/Output/Context
├── config/              # Auto-configuration + RedisProCacheProperties + SecureJackson
├── protection/          # The 5 resilience mechanisms
│   ├── avalanche/       #   TtlHandler (300)              - TTL jitter, avalanche protection
│   ├── bloom/           #   BloomFilterHandler (100) + filter/{Local,Redis,Hierarchical} - penetration
│   ├── breakdown/       #   SyncLockHandler (200) + DistributedLockManager - breakdown protection
│   ├── nullvalue/       #   NullValueHandler (400)        - null caching, penetration protection
│   └── refresh/         #   EarlyExpirationHandler (250)  - hot key early refresh
├── operation/           # RedisCacheable/Put/Evict Operation + RedisCacheRegister
├── factory/             # OperationFactory + 3 concrete factories
├── handler/             # AnnotationHandler + 4 concrete annotation handlers
├── eviction/            # TwoListLRU + TwoListEvictionStrategy
├── serialization/       # SecureNullValueDeserializer, TypeSupport (safe Jackson)
├── observability/       # RedisCacheHealthIndicator (actuator health)
└── holder/              # CacheOperationMetadataHolder (method → operation cache)
```

> 已移除(不在源码中):`wrapper/`(熔断/限流)、`spi/`(ServiceLoader)、`event/`、独立 `evaluator/`、`CacheMetricsRecorder` —— 见 `a5ab55b` 重构与 `wiki/log.md` 的 lint 记录。wiki 始终以实际源码为准。

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
| Understand the chain / a mechanism | `wiki/architecture/` and `wiki/mechanisms/` |
| Understand a module | `wiki/modules/<name>.md` |
| Add a new cache protection handler | `protection/<mechanism>/` + implement `CacheHandler`, annotate `@HandlerPriority(HandlerOrder.X)` |
| Modify annotation processing | `handler/` + `AnnotationHandler` interface |
| Change Redis connection config | `config/RedisConnectionConfiguration.java` |
| Configure behavior | `wiki/modules/configuration.md` + `config/RedisProCacheProperties.java` |
| Add integration tests | `AbstractRedisIntegrationTest.java` + Testcontainers |
