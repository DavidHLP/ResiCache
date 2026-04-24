# Testing Patterns

**Analysis Date:** 2026-04-24

## Test Framework

**Primary Framework:** JUnit 5 (Jupiter)
**Assertion Library:** AssertJ
**Mocking Framework:** Mockito (with MockitoExtension)
**Build Tool:** Maven

## Test Dependencies

From `pom.xml`:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>

<dependency>
    <groupId>org.assertj</groupId>
    <artifactId>assertj-core</artifactId>
    <scope>test</scope>
</dependency>
```

## Test File Organization

**Location Pattern:** Tests mirror `src/main/java` structure in `src/test/java`

```
src/test/java/io/github/davidhlp/spring/cache/redis/
├── register/
│   └── RedisCacheRegisterTest.java
├── core/
│   ├── handler/
│   │   ├── AnnotationHandlerTest.java
│   │   ├── CacheableAnnotationHandlerTest.java
│   │   ├── CachingAnnotationHandlerTest.java
│   │   └── EvictAnnotationHandlerTest.java
│   └── writer/
│       └── chain/
│           ├── CacheHandlerChainTest.java
│           └── handler/
│               ├── ActualCacheHandlerTest.java
│               ├── BloomFilterHandlerTest.java
│               ├── CacheErrorHandlerTest.java
│               ├── NullValueHandlerTest.java
│               ├── PreRefreshHandlerTest.java
│               ├── SyncLockHandlerTest.java
│               └── TtlHandlerTest.java
└── strategy/
    └── eviction/
        └── impl/
            └── TwoListEvictionStrategyTest.java
```

## Test Class Structure

### Basic Pattern with @ExtendWith

```java
@ExtendWith(MockitoExtension.class)
@DisplayName("CacheableAnnotationHandler Tests")
class CacheableAnnotationHandlerTest {

    @Mock
    private RedisCacheRegister redisCacheRegister;

    @Mock
    private KeyGenerator keyGenerator;

    @Mock
    private CacheableOperationFactory cacheableOperationFactory;

    private CacheableAnnotationHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CacheableAnnotationHandler(redisCacheRegister, keyGenerator, cacheableOperationFactory);
    }
    // ... tests
}
```

### @Nested Inner Classes for Grouping

Tests use nested classes to organize related test cases:

```java
@Nested
@DisplayName("canHandle() Tests")
class CanHandleTests {

    @Test
    @DisplayName("canHandle returns true when method has @RedisCacheable annotation")
    void canHandle_withRedisCacheable_returnsTrue() throws NoSuchMethodException {
        // Test implementation
    }
}

@Nested
@DisplayName("doHandle() Tests")
class DoHandleTests {
    // Related tests grouped here
}

@Nested
@DisplayName("Error Handling Tests")
class ErrorHandlingTests {
    // Error case tests grouped here
}
```

## Test Naming

**Method Names:** Descriptive with underscores separating scenario parts
**@DisplayName:** Human-readable descriptions in Chinese/English

```java
@Test
@DisplayName("canHandle returns true when method has @RedisCacheable annotation")
void canHandle_withRedisCacheable_returnsTrue() throws NoSuchMethodException {
    // ...
}
```

## Assertion Patterns

### AssertJ Usage

```java
// Basic assertions
assertThat(result).isTrue();
assertThat(result).isFalse();
assertThat(result).isEqualTo(expected);
assertThat(result).isNull();
assertThat(result).isNotNull();

// Collection assertions
assertThat(stats.totalEntries()).isEqualTo(2);
assertThat(strategy.size()).isEqualTo(3);
assertThat(processor1.afterChainExecutionCalled).isTrue();

// Exception assertions
assertThatThrownBy(() -> orderService.findById(99L))
    .isInstanceOf(OrderNotFoundException.class)
    .hasMessageContaining("99");
```

## Mocking Patterns

### @Mock Fields

```java
@Mock
private RedisTemplate<String, Object> redisTemplate;

@Mock
private ValueOperations<String, Object> valueOperations;

@Mock
private CacheStatisticsCollector statistics;
```

### Mockito.when().thenReturn()

```java
when(keyGenerator.generate(target, method, args)).thenReturn(generatedKey);
when(cacheableOperationFactory.create(any(), any(), any(), any(), any()))
    .thenReturn(RedisCacheableOperation.builder().build());
```

### Verifying Interactions

```java
verify(redisCacheRegister).registerCacheableOperation(operation);
verify(keyGenerator).generate(target, method, args);
verify(cacheableOperationFactory).create(eq(method), any(RedisCacheable.class), eq(target), eq(args), eq(generatedKey));
```

### Argument Matchers

```java
// any() - matches any argument
when(cacheableOperationFactory.create(any(), any(), any(), any(), any()))

// eq() - matches specific value
when(cacheableOperationFactory.create(eq(method), any(), eq(target), eq(args), eq(generatedKey)))

// anyString(), anyLong(), anyBoolean() - typed matchers
verify(valueOperations).set(eq("test:key"), any(CachedValue.class), eq(Duration.ofSeconds(120)));
```

### Mock Verification Modes

```java
// never() - verify method was NOT called
verify(redisCacheRegister, never()).registerCacheableOperation(any());

// times() - verify exact number of calls
verify(mock, times(3)).someMethod();

// atLeastOnce() - verify called at least once
verify(mock, atLeastOnce()).someMethod();
```

## Setup/Teardown

### @BeforeEach for Setup

```java
@BeforeEach
void setUp() {
    strategy = new TwoListEvictionStrategy<>(10, 5);
}
```

## Test Data Fixtures

### Inner Test Classes

Used to provide annotated methods for testing handlers:

```java
// Test class with annotated methods
private static class TestClass {
    @RedisCacheable(cacheNames = "testCache", ttl = 60)
    public void cachedMethod() {
    }

    @RedisCacheable(cacheNames = "anotherCache", ttl = 120)
    public void anotherCachedMethod() {
    }

    public void noAnnotation() {
    }
}
```

### Inline Test Helpers

For testing chains, inner classes implement the handler interface:

```java
static class TestPostProcessor implements CacheHandler, PostProcessHandler {
    private CacheHandler next;
    boolean afterChainExecutionCalled = false;
    boolean required = true;
    boolean shouldThrowException = false;

    @Override
    public CacheHandler getNext() { return next; }

    @Override
    public void setNext(CacheHandler next) { this.next = next; }

    @Override
    public HandlerResult handle(CacheContext context) {
        if (getNext() != null) {
            return getNext().handle(context);
        }
        return HandlerResult.continueWith(CacheResult.success());
    }

    @Override
    public void afterChainExecution(CacheContext context, CacheResult result) {
        afterChainExecutionCalled = true;
        if (shouldThrowException) {
            throw new RuntimeException("Test exception in post processor");
        }
    }
}
```

### CacheContext Builder

Test utilities create context objects:

```java
private CacheContext createTestContext() {
    return CacheContext.builder()
            .operation(CacheOperation.PUT)
            .cacheName("test-cache")
            .redisKey("test:key")
            .actualKey("test:key")
            .build();
}
```

## Test Coverage Scope

### Unit Tests

- Individual handler classes (CacheableAnnotationHandler, EvictAnnotationHandler, etc.)
- Factory classes (CacheableOperationFactory)
- Strategy implementations (TwoListEvictionStrategy)
- Chain components (CacheHandlerChain)

### What is Tested

**Handler Tests:**
- `canHandle()` returns true/false correctly
- `doHandle()` registers operations correctly
- Error handling when dependencies throw

**Strategy Tests:**
- put/get/remove operations
- size tracking
- clear operation
- eviction behavior when capacity exceeded
- statistics tracking

**Chain Tests:**
- Post-processor execution
- Exception handling in post-processors
- Multiple post-processors

## Test Execution

**Run All Tests:**
```bash
./mvnw test
```

**Run Specific Test Class:**
```bash
./mvnw test -Dtest=TwoListEvictionStrategyTest
```

**Run with Coverage:**
```bash
./mvnw test jacoco:report
```

## Test Patterns Summary

| Aspect | Pattern Used |
|--------|-------------|
| Test Runner | JUnit 5 with `@ExtendWith(MockitoExtension.class)` |
| Assertions | AssertJ with fluent API |
| Grouping | `@Nested` inner classes |
| Naming | `@DisplayName` annotations |
| Fixtures | Inner classes + Builder pattern |
| Mocks | `@Mock` + Mockito |
| Setup | `@BeforeEach` |

---

*Testing analysis: 2026-04-24*
