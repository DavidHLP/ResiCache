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
    ├── annotation/          # @RedisCacheable/Put/Evict/Caching stable annotations
    ├── cache/               # package-private runtime module: AOP, chain, operations, protections, serialization, assembly
    ├── chain/               # stable CacheHandler/Operation/Result contracts and typed decision views
    ├── config/              # RedisCacheAutoConfiguration + RedisProCacheProperties + metrics/enablement entries
    ├── protection/          # stable BloomIFilter, LockManager, EarlyExpirationMode contracts
    └── serialization/       # public SerializationException + operator migration contracts and wire envelope
```

> 已移除(不在源码中):`wrapper/`(熔断/限流)、`spi/`(ServiceLoader)、`event/`、独立 `evaluator/`、`CacheMetricsRecorder`、`BloomRebuilder`，以及已收拢至 package-private `cache/` 的旧实现子包。文档始终以实际源码为准。

### Test Structure

```
src/test/java/io/github/davidhlp/spring/cache/redis/
├── cache/                       # internal implementation tests + shared Testcontainers scaffolding
├── (stable contract packages: annotation/, chain/, config/, serialization/) # API/contract tests
├── cache/                       # unit/integration suites plus Testcontainers fixtures
└── com/example/domain/           # test fixture for whitelisted custom domain types (serializer interop)
```
> Redis integration tests now live beside the internal cache module; all use the
> `*IntegrationTest.java` suffix. The naming guard
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
- **Strategy replacement**: only documented stable seams (`BloomIFilter` and `LockManager`)
  are replaceable. Other policies, handlers, metadata, and serializers are package-private
  implementation details assembled by the internal `cache` module. The public auto-configuration
  scans that internal package with a test-class exclusion; no root-package scan is used.

## Where to Look

| I want to... | Look at... |
|--------------|-----------|
| Understand the chain / a mechanism | `chain/` package + `protection/<mechanism>/` (each handler carries design rationale in Javadoc) |
| Understand a module | the package itself under `src/main/java/.../`; module layout is in Project Structure above |
| Add a new cache protection handler | internal `cache/` runtime + implement `CacheHandler`, annotate `@HandlerPriority(HandlerOrder.X)` |
| Modify annotation processing | internal `cache/` annotation pipeline |
| Change Redis connection config | internal `cache/RedisConnectionConfiguration.java` |
| Configure behavior | `config/RedisProCacheProperties.java` (`resi-cache.*` prefix) |
| Add integration tests | internal `cache/` test fixtures + Testcontainers |
