# ResiCache

ResiCache is a Spring Boot 3 cache extension that hardens Redis-backed caches against penetration, breakdown, and avalanche scenarios. It replaces the stock `RedisCacheManager` with an interceptor-driven pipeline that understands custom [`@RedisCacheable`](src/main/java/io/github/davidhlp/spring/cache/redis/annotation/RedisCacheable.java), [`@RedisCacheEvict`](src/main/java/io/github/davidhlp/spring/cache/redis/annotation/RedisCacheEvict.java), and [`@RedisCaching`](src/main/java/io/github/davidhlp/spring/cache/redis/annotation/RedisCaching.java) annotations.

## Key Features
- Bloom filter guardrails and null-value controls to block cache penetration.
- Sync locking with timeout, second-level caching hooks, and optional null caching to mitigate breakdown.
- TTL randomization, variance control, and async/sync pre-refresh to soften cache avalanches.
- Two-list eviction registry that tracks cache operations for fast lookups and warm reloads.
- Drop-in auto-configuration (`RedisCacheAutoConfiguration`) that wires enhanced writer/manager beans, statistics, and key generation.

## Installation
```xml
<!-- pom.xml -->
<dependency>
  <groupId>io.github.davidhlp</groupId>
  <artifactId>ResiCache</artifactId>
  <version>3.2.4</version>
</dependency>
```
With Gradle (Kotlin DSL):
```kotlin
dependencies {
    implementation("io.github.davidhlp:ResiCache:3.2.4")
}
```
Requires Java 17+, Spring Boot 3.2.x, and a reachable Redis instance.

## Quick Start
```java
@SpringBootApplication
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
```
Add minimal configuration:
```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
  cache:
    type: redis
```
The auto-configuration imports enhanced writer/manager beans and registers the Redis-aware cache interceptor automatically.

## Using @RedisCacheable
```java
@Service
public class ProductService {

    @RedisCacheable(
        cacheNames = "product",
        key = "#id",
        ttl = 300,
        sync = true,
        syncTimeout = 5,
        useBloomFilter = true,
        randomTtl = true,
        variance = 0.25F,
        enablePreRefresh = true,
        preRefreshThreshold = 0.2,
        preRefreshMode = PreRefreshMode.ASYNC)
    public ProductDto findById(Long id) {
        return repository.fetch(id);
    }
}
```
Behaviour highlights:
- `sync`/`syncTimeout` guard regeneration with fine-grained locks (`SyncLockHandler`).
- `useBloomFilter` adds keys to the Bloom filter handler on writes and checks for misses on reads.
- `randomTtl`+`variance` route TTL decisions through `TtlPolicy` to stagger expiries.
- `enablePreRefresh` triggers the `PreRefreshSupport` to refresh hot keys before they expire.
- `cacheNullValues` and `useSecondLevelCache` toggle null caching and L2 integration when required.

## Eviction & Composite Operations
Use `@RedisCacheEvict` for targeted invalidation:
```java
@RedisCacheEvict(cacheNames = "product", key = "#id", beforeInvocation = true)
public void delete(Long id) {
    repository.remove(id);
}
```
Bundle multiple cache actions with `@RedisCaching`:
```java
@RedisCaching(
    redisCacheable = {
        @RedisCacheable(cacheNames = "product", key = "#request.id")
    },
    redisCacheEvict = {
        @RedisCacheEvict(cacheNames = "product:list", allEntries = true)
    }
)
public ProductDto refresh(ProductRequest request) {
    return repository.reload(request.id());
}
```
The interceptor chain registers operations in `RedisCacheRegister`, allowing the writer to resolve TTL, locking, and bloom parameters per cache/key pair.

## Development & Testing
- Build + test: `mvn clean verify`
- Quick test loop: `mvn test`
- Run sample app: `mvn spring-boot:run`
- Inspect dependency graph: `mvn dependency:tree`
Static analysis is configured via `qodana.yaml`; run `jetbrains/qodana-jvm-community` Docker image locally to mirror CI.

## Troubleshooting
- Ensure Redis credentials match `spring.data.redis` settings before enabling bloom filters or pre-refresh.
- If sync locks appear stuck, verify the configured lock key pattern and increase `syncTimeout`.
- Bloom filter false positives are expected; tune capacity/error rate within `BloomSupport` settings when extending the module.
