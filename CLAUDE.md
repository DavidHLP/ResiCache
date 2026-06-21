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
├── evaluator/           # SpEL condition evaluator (SpelConditionEvaluator)
├── eviction/            # TwoListLRU + TwoListEvictionStrategy
├── serialization/       # SecureNullValueDeserializer, TypeSupport (safe Jackson)
├── observability/       # CacheMetricsRecorder, RedisCacheHealthIndicator
├── wrapper/             # CircuitBreakerCacheWrapper, RateLimiterCacheWrapper
├── spi/                 # Service Provider Interfaces (BloomFilterProvider, LockProvider)
├── event/               # Cache events (CacheEvictedEvent)
└── holder/              # CacheOperationMetadataHolder
```

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
- **SPI discovery**: Components use Java ServiceLoader pattern via `META-INF/services/`

## Where to Look

| I want to... | Look at... |
|--------------|-----------|
| Add a new cache protection handler | `protection/<mechanism>/` + implement `CacheHandler`, annotate `@HandlerPriority(HandlerOrder.X)` |
| Modify annotation processing | `handler/` + `AnnotationHandler` interface |
| Change Redis connection config | `config/RedisConnectionConfiguration.java` |
| Add a new SPI provider | `spi/` + `META-INF/services/` |
| Configure behavior | `config/RedisProCacheProperties.java` |
| Add integration tests | `AbstractRedisIntegrationTest.java` + Testcontainers |
