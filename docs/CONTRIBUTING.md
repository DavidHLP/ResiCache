# Contributing to ResiCache

<!-- AUTO-GENERATED: Build Commands -->
## Build Commands

| Command | Description |
|---------|-------------|
| `./mvnw clean compile` | Compile source code |
| `./mvnw test` | Run unit tests |
| `./mvnw verify` | Run tests with JaCoCo coverage (70% line / 40% branch minimum) |
| `./mvnw checkstyle:check` | Run Checkstyle linting |
| `./mvnw clean package -DskipTests` | Build JAR without tests |
| `./mvnw clean verify -B` | Full CI build (no IDE integration) |

<!-- AUTO-GENERATED: Test Commands -->
## Testing

```bash
# Run all tests
./mvnw test

# Run with coverage report
./mvnw verify

# Integration tests require Docker (Testcontainers)
# They extend AbstractRedisIntegrationTest and use docker-redis container
```

**Test Pattern**: Tests mirror source structure under `src/test/java/`

```java
// Integration test example
@SpringBootTest
@ExtendWith(TestcontainersExtension.class)
class MyServiceIntegrationTest extends AbstractRedisIntegrationTest {
    // Uses singleton Redis container
}
```

<!-- AUTO-GENERATED: Code Style -->
## Code Style

- **Checkstyle**: Enforced via `src/main/resources/checkstyle-custom.xml`
- **Lombok**: Used throughout (`@Data`, `@Getter`, `@Setter`, `@Builder`)
- **Javadoc**: Chinese comments explaining design rationale
- Run checkstyle: `./mvnw checkstyle:check`

```
Checkstyle Rules:
- No tabs (spaces only)
- No trailing whitespace
- Javadoc required for public classes/methods
- Chinese comments for design decisions
```

<!-- AUTO-GENERATED: Project Structure -->
## Project Structure

```
src/main/java/io/github/davidhlp/spring/cache/redis/
├── annotation/          # @RedisCacheable, @RedisCacheEvict, @RedisCachePut, @RedisCaching
├── cache/               # RedisProCache, RedisProCacheManager, RedisProCacheWriter, RedisCacheInterceptor
├── chain/               # 责任链: CacheHandler/Chain/Factory, ActualCacheHandler, HandlerOrder
│   └── model/           #   CacheInput / CacheOutput / CacheContext
├── config/              # 自动配置 + RedisProCacheProperties + SecureJackson
├── protection/          # 五大防护机制 (avalanche/bloom/breakdown/nullvalue/refresh)
│   ├── avalanche/       #   TtlHandler (300)              — 防雪崩
│   ├── bloom/           #   BloomFilterHandler (100)      — 防穿透
│   ├── breakdown/       #   SyncLockHandler (200)         — 防击穿
│   ├── nullvalue/       #   NullValueHandler (400)        — 防穿透
│   └── refresh/         #   EarlyExpirationHandler (250)  — 热 key 保护
├── operation/           # RedisCacheable/Put/Evict Operation + RedisCacheRegister
├── factory/             # OperationFactory + 3 个具体工厂
├── handler/             # AnnotationHandler + 4 个注解处理器
├── evaluator/           # SpelConditionEvaluator (condition/unless)
├── eviction/            # TwoListLRU + TwoListEvictionStrategy
├── serialization/       # SecureNullValueDeserializer, TypeSupport
├── observability/       # CacheMetricsRecorder, RedisCacheHealthIndicator
├── wrapper/             # CircuitBreakerCacheWrapper, RateLimiterCacheWrapper
├── spi/                 # BloomFilterProvider, LockProvider
├── event/               # CacheEvictedEvent
└── holder/              # CacheOperationMetadataHolder
```

<!-- AUTO-GENERATED: Dependencies -->
## Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| Spring Boot (parent) | 3.4.13 | Framework baseline |
| spring-boot-starter-data-redis | 3.4.13 | Redis client (Lettuce) |
| Redisson | 3.27.0 | Distributed lock |
| Caffeine | 3.1.8 | Local cache / Bloom L1 |
| jackson-datatype-jsr310 | managed | Java 8 time serde |
| Testcontainers | 1.20.6 | Integration testing |
| Awaitility | 4.3.0 | Async test assertions |

<!-- AUTO-GENERATED: PR Checklist -->
## Pull Request Checklist

- [ ] Code follows checkstyle (`./mvnw checkstyle:check` passes)
- [ ] Tests added/updated for new features
- [ ] JaCoCo coverage ≥ 70% line / 40% branch (`./mvnw verify`)
- [ ] Javadoc added for public APIs
- [ ] Git commit message follows conventional format
- [ ] No sensitive data in commits (tokens, secrets)
