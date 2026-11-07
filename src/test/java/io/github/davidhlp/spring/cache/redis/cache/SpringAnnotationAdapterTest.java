package io.github.davidhlp.spring.cache.redis.cache;






import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties.NativeAnnotationMode;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.SpringCacheAnnotationParser;
import org.springframework.cache.interceptor.CacheableOperation;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.interceptor.CacheOperation;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SpringAnnotationAdapter 单元测试
 *
 * <p>覆盖 NativeAnnotationMode 三模式(FULL/SELECTIVE/NONE)对 Method/Class 目标的转换行为。
 * 纯注解解析逻辑,无 testcontainers/Spring context 依赖。这是 C05 抽出 SpringAnnotationAdapter
 * 的直接单测收益(原本埋在 565 行 god class 内)。
 */
@DisplayName("SpringAnnotationAdapter Tests")
class SpringAnnotationAdapterTest {

    /** fixture:带 Spring 原生注解的方法 */
    static class SpringFixture {
        @Cacheable("cacheable-c")
        public void cached() { }

        @CacheEvict("evict-c")
        public void evicted() { }

        @CachePut("put-c")
        public void putted() { }

        @Cacheable("both-c")
        @RedisCacheable("both-c")
        public void bothCacheable() { }
    }

    @Cacheable("class-cacheable")
    static class ClassLevelFixture { }

    private Method cached;
    private Method both;

    @BeforeEach
    void setUp() throws Exception {
        cached = SpringFixture.class.getMethod("cached");
        both = SpringFixture.class.getMethod("bothCacheable");
    }

    @Test
    @DisplayName("FULL mode converts Spring @Cacheable on method")
    void fullMode_convertsSpringCacheableOnMethod() {
        SpringAnnotationAdapter adapter = new SpringAnnotationAdapter(NativeAnnotationMode.FULL);
        List<CacheOperation> ops = new ArrayList<>();

        adapter.addSpringNativeOperations(cached, ops);

        assertThat(ops).hasSize(1);
    }

    @Test
    @DisplayName("FULL mode converts Spring @Cacheable on class-level target")
    void fullMode_convertsSpringCacheableOnClass() {
        SpringAnnotationAdapter adapter = new SpringAnnotationAdapter(NativeAnnotationMode.FULL);
        List<CacheOperation> ops = new ArrayList<>();

        adapter.addSpringNativeOperations(ClassLevelFixture.class, ops);

        assertThat(ops).hasSize(1);
    }

    @Test
    @DisplayName("NONE mode converts nothing")
    void noneMode_convertsNothing() {
        SpringAnnotationAdapter adapter = new SpringAnnotationAdapter(NativeAnnotationMode.NONE);
        List<CacheOperation> ops = new ArrayList<>();

        adapter.addSpringNativeOperations(cached, ops);

        assertThat(ops).isEmpty();
    }

    @Test
    @DisplayName("SELECTIVE mode skips when target has no ResiCache annotation at all")
    void selectiveMode_skipsWhenNoResiCacheAnnotation() {
        // cached 方法无任何 @RedisCache* 注解 → SELECTIVE 直接 return,不转换
        SpringAnnotationAdapter adapter = new SpringAnnotationAdapter(NativeAnnotationMode.SELECTIVE);
        List<CacheOperation> ops = new ArrayList<>();

        adapter.addSpringNativeOperations(cached, ops);

        assertThat(ops).isEmpty();
    }

    @Test
    @DisplayName("SELECTIVE mode skips Spring @Cacheable when @RedisCacheable counterpart present")
    void selectiveMode_skipsSpringCounterpartWhenResiCachePresent() {
        // bothCacheable 同时有 @Cacheable + @RedisCacheable → SELECTIVE 下 @Cacheable 被跳过
        SpringAnnotationAdapter adapter = new SpringAnnotationAdapter(NativeAnnotationMode.SELECTIVE);
        List<CacheOperation> ops = new ArrayList<>();

        adapter.addSpringNativeOperations(both, ops);

        assertThat(ops).isEmpty();
    }
    @CacheConfig(cacheNames = "users", keyGenerator = "defaultKey", cacheManager = "defaultManager")
    @Cacheable(sync = true)
    static class ConfigDefaults {
        public String classDefault(String id) { return id; }
        @Cacheable(sync = true)
        public String methodDefault(String id) { return id; }
        @Cacheable(value = "explicit", key = "#p0", cacheResolver = "explicitResolver")
        public String explicit(String id) { return id; }
        @CachePut
        public String put(String id) { return id; }
        @CacheEvict
        public void evict(String id) { }
    }

    @CacheConfig(cacheNames = "users", keyGenerator = "defaultKey",
            cacheManager = "ignoredManager", cacheResolver = "defaultResolver")
    static class ResolverDefaults {
        @Cacheable(sync = true)
        public String load(String id) { return id; }
        @Cacheable(cacheNames = "explicit", keyGenerator = "explicitKey", cacheManager = "explicitManager")
        public String explicit(String id) { return id; }
        @CachePut
        public String put(String id) { return id; }
        @CacheEvict
        public void evict(String id) { }
    }

    @Test
    void cacheConfigDefaultsMatchSpringForOperationsAndPolicies() throws Exception {
        var spring = new SpringCacheAnnotationParser();
        for (Class<?> owner : List.of(ConfigDefaults.class, ResolverDefaults.class)) {
            var register = new RedisCacheRegister();
            var source = new RedisCacheOperationSource(NativeAnnotationMode.FULL, register);
            for (Method method : owner.getDeclaredMethods()) {
                if (method.isSynthetic()) {
                    continue;
                }
                var expected = spring.parseCacheAnnotations(method);
                if (expected == null) {
                    expected = spring.parseCacheAnnotations(owner);
                }
                var operations = source.getCacheOperations(method, owner);
                assertThat(operations).as(method.toString()).hasSize(1);
                CacheOperation operation = operations.iterator().next();
                assertDefaults(operation, expected.iterator().next());
                if (operation instanceof CacheableOperation) {
                    var policies = register.getSnapshot(method, owner).policyOperations();
                    assertThat(policies).hasSize(1);
                    assertDefaults(policies.get(0), operation);
                }
            }
        }
    }

    private static void assertDefaults(CacheOperation actual, CacheOperation expected) {
        assertThat(actual.getCacheNames()).isEqualTo(expected.getCacheNames());
        assertThat(actual.getKey()).isEqualTo(expected.getKey());
        assertThat(actual.getKeyGenerator()).isEqualTo(expected.getKeyGenerator());
        assertThat(actual.getCacheManager()).isEqualTo(expected.getCacheManager());
        assertThat(actual.getCacheResolver()).isEqualTo(expected.getCacheResolver());
    }

}
