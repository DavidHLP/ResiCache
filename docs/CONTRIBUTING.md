# Contributing to ResiCache

<!-- AUTO-GENERATED: Build Commands -->
## Build Commands

| Command | Description |
|---------|-------------|
| `./mvnw clean compile` | Compile source code |
| `./mvnw test` | Run unit tests |
| `./mvnw verify` | Run tests with JaCoCo coverage (60% minimum) |
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
├── annotation/          # @RedisCacheable, @RedisCacheEvict, @RedisCachePut
├── config/              # Spring Boot auto-configuration
├── core/
│   ├── handler/         # Annotation handler chain
│   ├── writer/
│   │   └── chain/
│   │       └── handler/ # 6 cache protection handlers (order 100-500)
│   │           ├── BloomFilterHandler (100)
│   │           ├── SyncLockHandler (200)
│   │           ├── PreRefreshHandler (250)
│   │           ├── TtlHandler (300)
│   │           ├── NullValueHandler (400)
│   │           └── ActualCacheHandler (500)
│   ├── evaluator/       # SpEL condition evaluator
│   └── metrics/         # Cache metrics
├── register/            # Cache operation registration
├── spi/                # BloomFilterProvider, LockProvider (SPI)
├── strategy/           # Eviction strategies
└── event/              # Cache events
```

<!-- AUTO-GENERATED: Dependencies -->
## Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| Spring Boot | 3.2.4 | Framework |
| Redisson | 3.27.0 | Distributed lock |
| Caffeine | 3.1.8 | Local cache |
| Hutool | 5.8.25 | Utility |
| Testcontainers | 1.19.7 | Integration testing |

<!-- AUTO-GENERATED: PR Checklist -->
## Pull Request Checklist

- [ ] Code follows checkstyle (`./mvnw checkstyle:check` passes)
- [ ] Tests added/updated for new features
- [ ] JaCoCo coverage ≥ 60% (`./mvnw verify`)
- [ ] Javadoc added for public APIs
- [ ] Git commit message follows conventional format
- [ ] No sensitive data in commits (tokens, secrets)
