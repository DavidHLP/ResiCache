# Phase 2 Research: 安全加固 (Security Hardening)

**Date:** 2026-04-24
**Phase:** SEC-01, SEC-02, SEC-03

---

## 1. SEC-01: SpelConditionEvaluator 反射访问重构

### Current Implementation

```java
// SpelConditionEvaluator.java, lines 94-105
private String getUnlessFromOperation(CacheOperation operation) {
    try {
        java.lang.reflect.Field unlessField = operation.getClass().getDeclaredField("unless");
        unlessField.setAccessible(true);
        Object value = unlessField.get(operation);
        return value != null ? value.toString() : "";
    } catch (Exception e) {
        log.trace("Could not access unless field directly: {}", e.getMessage());
        return "";
    }
}
```

### Logging Level Analysis: warn vs trace

SLF4J logging levels (from most to least severe): ERROR > WARN > INFO > DEBUG > TRACE

| Level | Use Case | Applicable Here? |
|-------|----------|------------------|
| ERROR | Operation failed completely | No - fallback succeeded |
| WARN | Unexpected condition, recovered gracefully | **Yes - recommended** |
| INFO | Normal operational events | No - reflection failure is not "normal" |
| DEBUG | Detailed debugging info | No - too verbose for production |
| TRACE | Very detailed tracing | Current - but too verbose |

**Recommendation:** Change `log.trace()` to `log.warn()` because:
1. Reflection failure is an unexpected condition (not a normal path)
2. The fallback to empty string may cause silent cache behavior changes
3. WARN level provides operational visibility without being alarming
4. TRACE is too verbose and clutters debug logs

### Implementation Approach

```java
} catch (Exception e) {
    log.warn("Failed to access 'unless' field via reflection for {}. Using empty condition. Error: {}",
             operation.getClass().getName(), e.getMessage());
    return "";
}
```

### Security Considerations

- Reflection bypasses type safety - acceptable here as fallback only
- `setAccessible(true)` may fail in strict security managers (rare in Spring apps)
- No sensitive data exposed in log message (only class name and error message)

---

## 2. SEC-02: Bloom Filter Redis 操作超时配置

### Current Implementation

```java
// RedisBloomIFilter.java, lines 40-48
redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
    for (int position : positions) {
        connection.hashCommands().hSet(bloomKey.getBytes(), ...);
    }
    return null;
});
```

### RedisTemplate Timeout Configuration Options

**Option A: Configure via Spring Boot properties (Recommended)**

Spring Boot's `RedisProperties` allows setting timeouts:

```yaml
spring:
  data:
    redis:
      timeout: 2000ms
```

This sets the default timeout for all Redis operations including `executePipelined`.

**Option B: Programmatic configuration on RedisTemplate**

```java
template.setTimeout(Duration.ofMillis(2000));
```

### Spring Data Redis Timeout Behavior

- `RedisTemplate.executePipelined()` uses the template's default timeout
- If no timeout is set, uses Spring Data Redis default (usually 30 seconds)
- The `executePipelined` callback runs on a pooled connection

### Recommended Implementation

Add timeout configuration to `RedisConnectionConfiguration.java`:

```java
// In redisCacheTemplate() method, before afterPropertiesSet()
template.setTimeout(Duration.ofMillis(2000));  // 2 second default
```

Or via `RedisProCacheProperties` for explicit bloom-filter timeout:

```java
// In BloomFilterProperties
private Duration timeout = Duration.ofMillis(2000);
```

### Why 2000ms?

- Bloom filter operations are simple hash HSET/HGET - should be < 100ms normally
- 2 seconds provides headroom for Redis load without risking thread pool exhaustion
- Consistent with Redisson client timeout (set to 3000ms in RedisConnectionConfiguration)

---

## 3. SEC-03: Serializer 包白名单文档完善

### Current Javadoc

```java
/**
 * Secure Jackson2 JSON Redis serializer that uses a whitelist-based PolymorphicTypeValidator.
 *
 * <p>This serializer addresses the deserialization vulnerability in the default
 * GenericJackson2JsonRedisSerializer by restricting type information to only allow
 * classes from configured package hierarchies.
 *
 * <p>Usage:
 * <pre>{@code
 * RedisTemplate<String, Object> template = new RedisTemplate<>();
 * template.setValueSerializer(new SecureJackson2JsonRedisSerializer(objectMapper));
 * }</pre>
 *
 * <p>Custom package prefixes can be configured via:
 * <pre>{@code
 * resi-cache:
 *   serializer:
 *     allowed-package-prefixes:
 *       - io.github.davidhlp
 *       - com.example.business
 *       - com.acme.domain
 * }</pre>
 */
public class SecureJackson2JsonRedisSerializer implements RedisSerializer<Object> {
```

### Javadoc Enhancement Recommendations

1. **Add `@author` tag** (optional but common)
2. **Add `@since` tag** for version tracking
3. **Explain default behavior** more explicitly
4. **Add "Why Whitelist?" section** for security awareness
5. **Document consequences of misconfiguration** (deserialization failures)

### Enhanced Javadoc Example

```java
/**
 * Secure Jackson2 JSON Redis serializer that uses a whitelist-based PolymorphicTypeValidator.
 *
 * <p>This serializer addresses the deserialization vulnerability in the default
 * GenericJackson2JsonRedisSerializer by restricting type information to only allow
 * classes from configured package hierarchies.
 *
 * <h2>Why Package Whitelist?</h2>
 * <p>Without whitelist validation, Jackson's default typing can deserialize arbitrary classes
 * from the classpath, enabling remote code execution (RCE) attacks if Redis is compromised.
 * This serializer restricts deserialization to explicitly allowed package prefixes only.
 *
 * <h2>Configuration</h2>
 * <p>Default allowed package: {@code io.github.davidhlp}
 * <p>To add custom packages, configure in application.yml:
 * <pre>{@code
 * resi-cache:
 *   serializer:
 *     allowed-package-prefixes:
 *       - io.github.davidhlp        # default, required
 *       - com.example.business      # your domain objects
 *       - com.acme.domain          # another package
 * }</pre>
 *
 * <h2>Common Pitfalls</h2>
 * <ul>
 *   <li>If your cached objects are in a package <strong>not</strong> in the whitelist,
 *       deserialization will fail silently and return {@code null}.</li>
 *   <li>Ensure all cached domain object packages are listed before first deployment.</li>
 * </ul>
 *
 * @see PolymorphicTypeValidator
 * @see BasicPolymorphicTypeValidator
 */
```

### Additional Documentation: Configuration Properties Javadoc

Enhance `RedisProCacheProperties` to document the serializer config:

```java
/**
 * Serializer configuration properties.
 */
@Getter
@Setter
public static class SerializerProperties {
    /**
     * Allowed package prefixes for deserialization.
     * Only classes from these packages can be deserialized from Redis.
     *
     * <p>Default: {@code ["io.github.davidhlp"]}
     *
     * <p>Example to add your domain packages:
     * <pre>{@code
     * resi-cache:
     *   serializer:
     *     allowed-package-prefixes:
     *       - io.github.davidhlp
     *       - com.example.myapp
     * }</pre>
     */
    private List<String> allowedPackagePrefixes = List.of("io.github.davidhlp");
}
```

---

## Summary of Implementation Approaches

| Task | Approach | File Changes |
|------|----------|--------------|
| SEC-01 | Change `log.trace()` to `log.warn()` with enhanced message | `SpelConditionEvaluator.java` |
| SEC-02 | Set timeout on RedisTemplate in configuration | `RedisConnectionConfiguration.java` |
| SEC-03 | Enhance class Javadoc with security context and examples | `SecureJackson2JsonRedisSerializer.java` |

---

## References

- [Spring Data Redis Documentation](https://docs.spring.io/spring-data/data-redis/docs/current/reference/html/)
- [Jackson Default Typing Security](https://cowtowncoder.medium.com/on-jackson-cves-dont-panic-f2a34570269a)
- [SLF4J Manual](https://www.slf4j.org/manual.html)
- [Spring Boot Redis Properties](https://docs.spring.io/spring-boot/docs/current/reference/html/data.html#data.redis)
