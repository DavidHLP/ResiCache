# Dependencies Map

<!-- Generated: 2026-06-21 | Token estimate: ~500 -->

Parent: `spring-boot-starter-parent:3.4.13`, Java 17+, packaging=jar (Starter library).

## Runtime

| Dep | Version | Role |
|-----|---------|------|
| spring-boot-starter-cache | 3.4.13 | Spring Cache abstraction |
| spring-boot-starter-data-redis | 3.4.13 | Redis client (Lettuce) |
| spring-boot-starter-aop | 3.4.13 | AOP for `@RedisCache*` interception |
| spring-boot-starter-validation | 3.4.13 | Bean validation |
| spring-boot-starter-actuator | 3.4.13 | HealthIndicator / metrics exposure |
| redisson-spring-boot-starter | ${redisson.version} (=3.27.0) | Distributed lock (SyncLockHandler) |
| caffeine | 3.1.8 | Local cache / hash cache (Bloom L1) |
| jackson-datatype-jsr310 | (managed) | Java 8 time serde |

## Compile-only / Provided

| Dep | Role |
|-----|------|
| lombok (provided) | Boilerplate (`@Data` / `@Builder`) |

## Test

| Dep | Version | Role |
|-----|---------|------|
| spring-boot-starter-test | managed | JUnit 5, Spring Test |
| assertj-core | managed | Fluent assertions |
| awaitility | 4.3.0 | Async condition waits |
| testcontainers | 1.20.6 | Real Redis in tests |
| testcontainers junit-jupiter | 1.20.6 | JUnit 5 integration |

## External Systems

- **Redis** (single / cluster / sentinel) — sole data store: cache values, locks, bloom bitmaps, LRU lists
- No RDBMS · No message broker · No external HTTP APIs

## Extension Points

- Bloom filter layering via `BloomIFilter` impls (Local / Redis / Hierarchical) — Spring beans,
  `@Primary` selects `HierarchicalBloomIFilter` (L1 local + L2 Redis)
- Distributed lock via `LockManager` (`protection/breakdown/`) — `DistributedLockManager` is the
  default bean; replace by registering another `LockManager`
