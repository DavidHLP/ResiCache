# Project Instructions

## Tech Stack

| Layer | Technology | Version |
|-------|-----------|---------|
| Language | Java | 17+ |
| Framework | Spring Boot | 3.2.4 |
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
- **Run with coverage**: `./mvnw verify` (JaCoCo enforced at 60% line coverage)
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
├── annotation/          # Custom cache annotations (@RedisCacheable, @RedisCacheEvict, etc.)
├── config/              # Spring configuration classes
├── core/
│   ├── factory/         # Cache component factories
│   ├── handler/         # Annotation handlers for @Cacheable/@CacheEvict/@CachePut
│   ├── writer/
│   │   └── chain/
│   │       └── handler/ # Chain of Responsibility handlers (main logic)
│   │           ├── BloomFilterHandler.java      # Cache penetration protection
│   │           ├── SyncLockHandler.java         # Cache breakdown protection
│   │           ├── PreRefreshHandler.java       # Hot key protection
│   │           ├── TtlHandler.java              # TTL variation for cache avalanche
│   │           ├── NullValueHandler.java        # Null value caching
│   │           └── ActualCacheHandler.java      # Actual Redis put operation
│   ├── evaluator/       # SpEL condition evaluator
│   ├── metrics/         # Metrics collection
│   └── wrapper/        # Cache wrappers (CircuitBreaker, RateLimiter)
├── register/           # Cache registration with Redis
├── manager/            # Cache managers
├── ratelimit/          # Rate limiting support
├── spi/                # Service Provider Interfaces (BloomFilterProvider, LockProvider)
├── strategy/           # Eviction strategies
└── event/              # Cache events
```

## Key Architecture: Chain of Responsibility

Cache writes go through a chain of handlers (in order):

1. **BloomFilterHandler** (100) - Checks if key exists in bloom filter, blocks cache penetration
2. **SyncLockHandler** (200) - Acquires distributed lock, prevents cache breakdown
3. **PreRefreshHandler** (250) - Triggers async pre-refresh for hot keys
4. **TtlHandler** (300) - Applies TTL variation to prevent cache avalanche
5. **NullValueHandler** (400) - Caches null values to prevent cache penetration
6. **ActualCacheHandler** (500) - Executes actual Redis PUT

Each handler implements `CacheHandler` interface with `handle()` method.

## Conventions

- **Handler ordering**: Defined by `@Order` annotation or explicit order numbers
- **Configuration properties**: Use `@ConfigurationProperties(prefix = "resi-cache")` with nested properties classes
- **Context passing**: Use `CacheContext` to pass data between handlers (input is immutable, output is mutable)
- **SPI discovery**: Components use Java ServiceLoader pattern via `META-INF/services/`

## Where to Look

| I want to... | Look at... |
|--------------|-----------|
| Add a new cache protection handler | `core/writer/chain/handler/` + implement `CacheHandler` |
| Modify annotation processing | `core/handler/` + `AnnotationHandler` interface |
| Change Redis connection config | `config/RedisConnectionConfiguration.java` |
| Add a new SPI provider | `spi/` + `META-INF/services/` |
| Configure behavior | `config/RedisProCacheProperties.java` |
| Add integration tests | `AbstractRedisIntegrationTest.java` + Testcontainers |
