# Coding Conventions

**Analysis Date:** 2026-04-24

## Language and Framework

**Java Version:** 17
**Spring Boot Version:** 3.2.4
**Build Tool:** Maven

## Package Structure

**Base Package:** `io.github.davidhlp.spring.cache.redis`

```
src/main/java/io/github/davidhlp/spring/cache/redis/
├── annotation/          # Custom cache annotations (@RedisCacheable, @RedisCacheEvict, etc.)
├── config/             # Spring configuration classes
├── core/               # Core caching logic (interceptor, handlers, cache implementation)
│   ├── factory/         # Operation factories (CacheableOperationFactory, etc.)
│   ├── handler/        # Annotation handlers (Chain of Responsibility pattern)
│   └── writer/         # Cache writer implementation
├── manager/            # Cache manager
├── register/           # Cache operation registry
│   └── operation/      # Operation types (RedisCacheableOperation, etc.)
├── spi/                # Service Provider Interface (LockProvider, BloomFilterProvider)
└── strategy/           # Eviction strategies
    └── eviction/       # Eviction strategy implementations
```

## Naming Conventions

**Classes:** PascalCase
- Example: `RedisCacheable`, `CacheableAnnotationHandler`, `TwoListEvictionStrategy`
- Factory classes: `XxxOperationFactory` pattern
- Handler classes: `XxxAnnotationHandler` pattern

**Methods:** camelCase
- Example: `registerCacheableOperation()`, `doHandle()`, `generateKey()`

**Fields/Variables:** camelCase
- Example: `redisCacheRegister`, `cacheableOperationFactory`, `operationStrategy`

**Constants (static final):** SCREAMING_SNAKE_CASE
- Example: Not heavily used, but when present follows this pattern

**Packages:** all lowercase
- Example: `io.github.davidhlp.spring.cache.redis.core.handler`

## Annotation Conventions

**Custom Annotations:**
- Use `@Target({ElementType.TYPE, ElementType.METHOD})` for method-level cache annotations
- Use `@Inherited` for annotation inheritance
- Use `@Documented` for Javadoc exposure
- Builder pattern for annotation attributes

Example from `RedisCacheable.java`:
```java
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface RedisCacheable {
    String[] value() default {};
    String[] cacheNames() default {};
    String key() default "";
    long ttl() default 60;
    // ...
}
```

## Class Design Patterns

### Builder Pattern

Used extensively for complex objects with many optional parameters:

```java
// From RedisCacheableOperation
public static Builder builder() {
    return new Builder();
}

public static class Builder extends CacheOperation.Builder {
    private String unless;
    private boolean sync;
    private long syncTimeout = 10;
    private long ttl;
    // ... fluent setters returning `this`
    
    public Builder ttl(long ttl) {
        this.ttl = ttl;
        return this;
    }
    
    @Override
    @NonNull
    public RedisCacheableOperation build() {
        return new RedisCacheableOperation(this);
    }
}
```

### Chain of Responsibility Pattern

Used for annotation handlers:

```java
// From RedisCacheInterceptor
public RedisCacheInterceptor(
        CacheableAnnotationHandler cacheableHandler,
        EvictAnnotationHandler evictHandler,
        CachingAnnotationHandler cachingHandler,
        CachePutAnnotationHandler cachePutHandler) {
    // Build chain: Cacheable -> Evict -> Caching -> CachePut
    cacheableHandler.setNext(evictHandler).setNext(cachingHandler).setNext(cachePutHandler);
    this.handlerChain = cacheableHandler;
}

// Base class structure
public abstract class AnnotationHandler {
    protected AnnotationHandler next;
    
    public AnnotationHandler setNext(AnnotationHandler next) {
        this.next = next;
        return next;
    }
    
    public List<CacheOperation> handle(Method method, Object target, Object[] args) {
        // Delegates to next if canHandle returns true
    }
}
```

### Factory Pattern

Operation factories create specific operation instances:

```java
public interface OperationFactory<A extends Annotation, O extends CacheOperation> {
    O create(Method method, A annotation, Object target, Object[] args, String key);
    boolean supports(Annotation annotation);
}
```

## Dependency Injection

**Constructor Injection** - Always used, no field injection:

```java
// From CacheableAnnotationHandler
public CacheableAnnotationHandler(
        RedisCacheRegister redisCacheRegister,
        KeyGenerator keyGenerator,
        CacheableOperationFactory cacheableOperationFactory) {
    this.redisCacheRegister = redisCacheRegister;
    this.keyGenerator = keyGenerator;
    this.cacheableOperationFactory = cacheableOperationFactory;
}
```

## Lombok Usage

Used extensively to reduce boilerplate:

```java
@Slf4j                          // Logger injection
@Getter                        // Getters for all fields
@EqualsAndHashCode(callSuper = true)  // equals/hashCode including parent
```

## Logging

Uses Lombok's `@Slf4j` with debug/info levels:

```java
log.debug("Registered cacheable operation: {} with key: {} for caches: {}",
        method.getName(), key, String.join(",", operation.getCacheNames()));
```

## Error Handling

- Return `null` on failures in register methods (logged but not thrown)
- Wrap exceptions with context in cache operations
- Use `RuntimeException` for unexpected failures

```java
// From CacheableAnnotationHandler
private RedisCacheableOperation registerCacheableOperation(...) {
    try {
        // ... registration logic
    } catch (Exception e) {
        log.error("Failed to register cacheable operation", e);
        return null;
    }
}
```

## SpEL Expressions

Condition and unless attributes support SpEL expressions:

```java
// condition: evaluated before method execution
// unless: evaluated after method execution (result available)
String condition() default "";
String unless() default "";
```

## Spring Stereotypes

- `@Component` - General Spring beans
- `@AutoConfiguration` - Spring Boot auto-configuration classes

```java
@AutoConfiguration(after = RedisAutoConfiguration.class)
@Import({
    JacksonConfig.class,
    RedisConnectionConfiguration.class,
    // ...
})
public class RedisCacheAutoConfiguration { }
```

## Documentation

Javadoc in Chinese with:
- Class/purpose descriptions
- Parameter descriptions (`@param`)
- Return value descriptions (`@return`)
- Usage examples

```java
/**
 * 缓存清除注解
 *
 * <p>用于标注方法，在方法执行前或执行后清除缓存。
 * 支持同步清除和异步清除两种模式。
 */
public @interface RedisCacheEvict { }
```

---

*Convention analysis: 2026-04-24*
