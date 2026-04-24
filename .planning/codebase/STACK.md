# Technology Stack

**Analysis Date:** 2026-04-24

## Languages

**Primary:**
- Java 17 - Core language version for the entire project
  - `pom.xml`: `maven.compiler.source` and `maven.compiler.target` set to `${java.version}` which is defined as `17`
  - All source files target Java 17 runtime

**Secondary:**
- XML - Build configuration and Spring Boot metadata files
  - `pom.xml` - Maven project configuration
  - `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` - Spring Boot auto-configuration entry point
  - `META-INF/services/*` - Java SPI service provider configuration files

## Runtime

**Environment:**
- Java 17+ (JVM based)
- No specific application server required (library/framework, not a standalone app)

**Build Tool:**
- Apache Maven 3.x
  - Wrapper not present; standard `mvn` command used
  - Version constraints not explicitly pinned in pom.xml

**Package Manager:**
- Maven Central
  - Dependencies resolved from Maven Central repository
  - `redisson-spring-boot-starter` published to Maven Central
  - `hutool-json` from Maven Central

## Frameworks

**Core:**
- Spring Boot 3.2.4 - Parent POM providing dependency management and auto-configuration base
  - `pom.xml`: `spring-boot-starter-parent` version 3.2.4
  - Provides dependency versions, plugin management, and standard build configuration

**Caching:**
- Spring Cache (`spring-boot-starter-cache`) - Standard Spring caching abstraction
  - `RedisCacheInterceptor` extends `CacheInterceptor` to intercept cache operations
  - Custom annotations: `@RedisCacheable`, `@RedisCacheEvict`, `@RedisCachePut`, `@RedisCaching`

**Redis:**
- Spring Data Redis (`spring-boot-starter-data-redis`) - Redis integration
  - `RedisTemplate<String, Object>` configured in `RedisConnectionConfiguration`
  - `RedisProCacheManager` manages cache regions
  - Serializers: `StringRedisSerializer` for keys, `GenericJackson2JsonRedisSerializer` for values
- Redisson 3.27.0 - Distributed Redis client with advanced features
  - `redisson-spring-boot-starter` dependency
  - Used for distributed locking via `RedissonClient`
  - Connection pool: 64 connections, 10 minimum idle
  - Configured in `RedisConnectionConfiguration.redissonClient()`

**AOP:**
- Spring AOP (`spring-boot-starter-aop`) - Aspect-oriented programming
  - `RedisCacheInterceptor` uses AOPAlliance `MethodInvocation`
  - Annotation handlers process cache operations via interceptors

**Validation:**
- Spring Validation (`spring-boot-starter-validation`) - Bean validation support

**JSON Processing:**
- Hutool JSON 5.8.25 - Chinese open-source utility library
  - `cn.hutool:hutool-json` dependency
  - Lightweight JSON processing
- Jackson JSR310 2.x - Java 8 date/time type support
  - `jackson-datatype-jsr310` for serializing `java.time` types
  - Configured via `JacksonConfig`

**Utilities:**
- Lombok - Compile-time annotation processing
  - `@Slf4j`, `@Getter`, `@Setter`, `@Component` annotations
  - Scope: `provided` (compile-time only)

**Monitoring:**
- Spring Boot Actuator (`spring-boot-starter-actuator`) - Application monitoring
  - `optional=true` in pom.xml

**Serialization:**
- Jackson 2.x (transitive via Spring Boot) - JSON serialization
  - `SecureJackson2JsonRedisSerializer` in `config/` package
  - Custom serializer with security filtering

## Testing

**Framework:**
- Spring Boot Test (`spring-boot-starter-test`) - Testing support
  - JUnit 5 (JUnit Platform) via Spring Boot Test Starter
  - Mockito integration

**Assertions:**
- AssertJ 3.x - Fluent assertion library
  - `org.assertj:assertj-core` test dependency

## Build Plugins

**Source Generation:**
- `maven-source-plugin` 3.3.0 - Generates `-sources.jar` for IDE support

**Javadoc Generation:**
- `maven-javadoc-plugin` 3.6.3 - Generates `-javadoc.jar`
  - `doclint=none` to suppress validation errors
  - UTF-8 encoding configured

**Signing:**
- `maven-gpg-plugin` 3.1.0 - GPG signing for Maven Central release
  - Signs artifacts during `verify` phase

**Central Publishing:**
- `central-publishing-maven-plugin` 0.8.0 - Maven Central publishing configuration

## Configuration

**Application Configuration:**
- YAML-based (`application.yml` / `application-test.yml`)
- Custom prefix: `resi-cache`
- Configuration properties class: `RedisProCacheProperties`

**Environment Variables:**
- Standard Spring Boot Redis properties:
  - `spring.data.redis.host`
  - `spring.data.redis.port`
  - `spring.data.redis.password`
  - `spring.data.redis.database`

**Configuration Files Found:**
- `src/test/resources/application-test.yml` - Test environment configuration
- Spring Boot auto-configuration imports via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

## Key Dependencies Summary

| Dependency | Version | Purpose |
|------------|---------|---------|
| spring-boot-starter-parent | 3.2.4 | Parent POM, dependency management |
| redisson-spring-boot-starter | 3.27.0 | Redis client with distributed locks |
| hutool-json | 5.8.25 | JSON utilities |
| spring-boot-starter-cache | (managed) | Spring caching abstraction |
| spring-boot-starter-data-redis | (managed) | Redis integration |
| spring-boot-starter-aop | (managed) | AOP support |
| spring-boot-starter-validation | (managed) | Bean validation |
| spring-boot-starter-actuator | (managed) | Monitoring |
| jackson-datatype-jsr310 | (managed) | Java 8 date/time serialization |
| lombok | (managed) | Annotation processing |
| spring-boot-starter-test | (managed) | Testing framework |
| assertj-core | (managed) | Fluent assertions |

## Platform Requirements

**Development:**
- JDK 17 or higher
- Maven 3.x
- Redis server instance (for integration testing)

**Production:**
- JRE 17+
- Redis server (standalone or cluster)
- No application server required (can be used as library)

---

*Stack analysis: 2026-04-24*
