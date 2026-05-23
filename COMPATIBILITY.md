# Compatibility Matrix

ResiCache version compatibility with underlying infrastructure.

## Supported Versions

| Component | Minimum | Recommended | Tested |
|-----------|---------|-------------|--------|
| Java | 17 | 21 | 17, 21 |
| Spring Boot | 3.4.x | 3.4.13 | 3.4.13 |
| Spring Cache | 6.2.x | 6.2.x | 6.2.x |
| Spring Data Redis | 3.5.x | 3.5.x | 3.5.x |
| Redis Server | 6.2 | 7.x | 6.2, 7.x |
| Redisson | 3.27.0 | 3.27.x | 3.27.0 |
| Caffeine | 3.1.8 | 3.1.x | 3.1.8 |

## Spring Boot Version Policy

- **Primary support**: Latest Spring Boot 3.4.x release
- **Baseline**: Spring Boot 3.4.0+ (Java 17 baseline)
- **No support**: Spring Boot 2.x or 3.0.x/3.1.x/3.2.x/3.3.x (may work but not tested)

## Known Limitations

- **Reactive types**: `Mono<T>` and `Flux<T>` return types are not fully supported. ResiCache will log a warning and fall back to Spring's native cache behavior.
- **Async methods**: `@Async` cached methods are not supported for sync-lock and Bloom-filter enhancements.
- **Transaction-aware caching**: Supported but requires explicit `resi-cache.transaction-aware=true`.
