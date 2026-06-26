package io.github.davidhlp.spring.cache.redis.annotation;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties.NativeAnnotationMode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.interceptor.CacheOperation;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

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
}
