# Phase 2 Plan: 安全加固 (Security Hardening)

**Phase:** SEC-01, SEC-02, SEC-03
**Wave:** 1 (parallel, independent documentation/code changes)

---

## Task SEC-01: SpelConditionEvaluator 反射访问日志级别

**Requirement:** SEC-01
**Priority:** high
**Status:** pending

### Changes

**File:** `src/main/java/io/github/davidhlp/spring/cache/redis/core/evaluator/SpelConditionEvaluator.java`
**Line:** 101-103

```java
// BEFORE
} catch (Exception e) {
    log.trace("Could not access unless field directly: {}", e.getMessage());
    return "";
}

// AFTER
} catch (Exception e) {
    log.warn("Failed to access 'unless' field via reflection for {}. Using empty condition. Error: {}",
             operation.getClass().getName(), e.getMessage());
    return "";
}
```

### read_first
`src/main/java/io/github/davidhlp/spring/cache/redis/core/evaluator/SpelConditionEvaluator.java`

### action
Change line 101-103 from:
```java
        } catch (Exception e) {
            log.trace("Could not access unless field directly: {}", e.getMessage());
            return "";
        }
```
To:
```java
        } catch (Exception e) {
            log.warn("Failed to access 'unless' field via reflection for {}. Using empty condition. Error: {}",
                     operation.getClass().getName(), e.getMessage());
            return "";
        }
```

### acceptance_criteria
- [ ] `grep -n "log.warn.*Failed to access 'unless' field" src/main/java/io/github/davidhlp/spring/cache/redis/core/evaluator/SpelConditionEvaluator.java` returns 1 match
- [ ] No `log.trace.*unless field` remains in the file

---

## Task SEC-02: Bloom Filter Redis 操作超时配置

**Requirement:** SEC-02
**Priority:** high
**Status:** pending

### Changes

**File:** `src/main/java/io/github/davidhlp/spring/cache/redis/config/RedisConnectionConfiguration.java`
**Location:** `redisCacheTemplate()` method, before `afterPropertiesSet()`

Add import for Duration:
```java
import java.time.Duration;
```

Add timeout configuration before `afterPropertiesSet()`:
```java
template.setTimeout(Duration.ofMillis(2000));
```

### read_first
`src/main/java/io/github/davidhlp/spring/cache/redis/config/RedisConnectionConfiguration.java`

### action
1. Add import after line 10:
```java
import java.time.Duration;
```

2. In `redisCacheTemplate()` method, add before `template.afterPropertiesSet()` (line 45):
```java
template.setTimeout(Duration.ofMillis(2000));
```

### acceptance_criteria
- [ ] `grep -n "import java.time.Duration" src/main/java/io/github/davidhlp/spring/cache/redis/config/RedisConnectionConfiguration.java` returns 1 match
- [ ] `grep -n "setTimeout.*Duration" src/main/java/io/github/davidhlp/spring/cache/redis/config/RedisConnectionConfiguration.java` returns 1 match
- [ ] `setTimeout` appears before `afterPropertiesSet` in the `redisCacheTemplate()` method

---

## Task SEC-03: Serializer 包白名单文档完善

**Requirement:** SEC-03
**Priority:** medium
**Status:** pending

### Changes

**File:** `src/main/java/io/github/davidhlp/spring/cache/redis/config/SecureJackson2JsonRedisSerializer.java`
**Location:** Class-level Javadoc (lines 13-38)

Replace existing Javadoc with enhanced version including:
- "Why Package Whitelist?" section explaining security rationale
- Common pitfalls section
- More explicit configuration examples

### read_first
`src/main/java/io/github/davidhlp/spring/cache/redis/config/SecureJackson2JsonRedisSerializer.java`

### action
Replace lines 13-38 with:

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

### acceptance_criteria
- [ ] `grep -n "Why Package Whitelist" src/main/java/io/github/davidhlp/spring/cache/redis/config/SecureJackson2JsonRedisSerializer.java` returns 1 match
- [ ] `grep -n "Common Pitfalls" src/main/java/io/github/davidhlp/spring/cache/redis/config/SecureJackson2JsonRedisSerializer.java` returns 1 match
- [ ] `grep -n "RCE" src/main/java/io/github/davidhlp/spring/cache/redis/config/SecureJackson2JsonRedisSerializer.java` returns 1 match

---

## Verification

After all changes:
```bash
# SEC-01
grep -n "log.warn.*Failed to access 'unless' field" src/main/java/io/github/davidhlp/spring/cache/redis/core/evaluator/SpelConditionEvaluator.java

# SEC-02
grep -n "setTimeout.*Duration" src/main/java/io/github/davidhlp/spring/cache/redis/config/RedisConnectionConfiguration.java

# SEC-03
grep -n "Why Package Whitelist" src/main/java/io/github/davidhlp/spring/cache/redis/config/SecureJackson2JsonRedisSerializer.java
grep -n "Common Pitfalls" src/main/java/io/github/davidhlp/spring/cache/redis/config/SecureJackson2JsonRedisSerializer.java

# Compile check
./mvnw compile -q
```

---

## Summary

| Task | Requirement | File | Change |
|------|-------------|------|--------|
| SEC-01 | SEC-01 | SpelConditionEvaluator.java | `log.trace` -> `log.warn` with enhanced message |
| SEC-02 | SEC-02 | RedisConnectionConfiguration.java | Add `setTimeout(Duration.ofMillis(2000))` before `afterPropertiesSet()` |
| SEC-03 | SEC-03 | SecureJackson2JsonRedisSerializer.java | Enhanced Javadoc with security context and examples |

---

*Plan version: 1.0*
*Planned: 2026-04-24*
