package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheEvict;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCachePut;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCaching;
import java.lang.reflect.Method;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.interceptor.CacheEvictOperation;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.CachePutOperation;
import org.springframework.cache.interceptor.CacheableOperation;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AnnotationParser seam")
class AnnotationParserTest {

    private final AnnotationParser parser = new AnnotationParser();

    @Test
    @DisplayName("method annotations override inherited class annotations")
    void methodAnnotationsOverrideClassAnnotations() throws Exception {
        Method method = AnnotatedService.class.getDeclaredMethod("read", String.class);

        AnnotationParser.ParsedAnnotations parsed = parser.parse(method);

        assertThat(parsed.operations()).singleElement().isInstanceOf(CacheableOperation.class);
        CacheableOperation operation = (CacheableOperation) parsed.operations().get(0);
        assertThat(operation.getCacheNames()).containsExactly("method-cache");
        assertThat(operation.getKey()).isEqualTo("#id");
        assertThat(parsed.policyOperations()).singleElement().isInstanceOf(RedisCacheableOperation.class);
        RedisCacheableOperation policy = (RedisCacheableOperation) parsed.policyOperations().get(0);
        assertThat(policy.getTtl()).isEqualTo(22L);
        assertThat(policy.isUseBloomFilter()).isTrue();
        assertThat(policy.isSync()).isTrue();
    }

    @Test
    @DisplayName("stable cacheable and put members reach standard and policy operations")
    void stableCacheableAndPutMembersReachOperations() throws Exception {
        Method method = AnnotatedService.class.getDeclaredMethod("write", String.class);

        AnnotationParser.ParsedAnnotations parsed = parser.parse(method);

        assertThat(parsed.operations()).extracting(Object::getClass)
                .containsExactly(CacheableOperation.class, CachePutOperation.class);
        CacheableOperation cacheable = (CacheableOperation) parsed.operations().get(0);
        CachePutOperation put = (CachePutOperation) parsed.operations().get(1);
        assertThat(cacheable.getUnless()).isEqualTo("#result == null");
        assertThat(put.getUnless()).isEqualTo("#result == null");
        assertThat(((RedisCacheableOperation) parsed.policyOperations().get(0)).getType())
                .isEqualTo(String.class);
        assertThat(((RedisCachePutOperation) parsed.policyOperations().get(1)).getType())
                .isEqualTo(Long.class);
    }

    @Test
    @DisplayName("evict unless stays source-compatible while the Spring path remains condition-driven")
    void evictUnlessStaysSourceCompatible() throws Exception {
        Method method = AnnotatedService.class.getDeclaredMethod("evict");
        RedisCacheEvict annotation = method.getAnnotation(RedisCacheEvict.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.unless()).isEqualTo("#result == null");
        assertThat(parser.parse(method).operations()).singleElement()
                .isInstanceOf(CacheEvictOperation.class);
    }

    @Test
    @DisplayName("composite annotation expands directly into Spring and policy operations")
    void compositeAnnotationExpands() throws Exception {
        Method method = AnnotatedService.class.getDeclaredMethod("composite", String.class);

        AnnotationParser.ParsedAnnotations parsed = parser.parse(method);

        assertThat(parsed.operations()).extracting(Object::getClass)
                .containsExactly(CacheableOperation.class, CacheEvictOperation.class, CachePutOperation.class);
        assertThat(parsed.policyOperations()).extracting(Object::getClass)
                .containsExactly(RedisCacheableOperation.class, RedisCacheEvictOperation.class,
                        RedisCachePutOperation.class);
        assertThat(parsed.operations()).extracting(CacheOperation::getCacheNames)
                .containsExactly(Set.of("composite-cache"), Set.of("composite-evict"), Set.of("composite-put"));
    }

    @Test
    @DisplayName("class target returns class operations without method policy snapshots")
    void classTargetReturnsClassOperations() {
        AnnotationParser.ParsedAnnotations parsed = parser.parse(AnnotatedService.class);

        assertThat(parsed.operations()).extracting(Object::getClass)
                .containsExactly(CacheableOperation.class, CachePutOperation.class);
        assertThat(parsed.operations()).extracting(CacheOperation::getCacheNames)
                .containsExactly(Set.of("class-cache"), Set.of("class-put"));
        assertThat(parsed.policyOperations()).isEmpty();
    }

    @Test
    @DisplayName("unannotated method returns no operations")
    void unannotatedMethodReturnsNoOperations() throws Exception {
        Method method = AnnotatedService.class.getDeclaredMethod("plain");

        AnnotationParser.ParsedAnnotations parsed = parser.parse(method);

        assertThat(parsed.operations()).isEmpty();
        assertThat(parsed.policyOperations()).isEmpty();
    }

    @RedisCacheable(value = "class-cache", key = "'class'", ttl = 11)
    @RedisCachePut(value = "class-put", key = "'class-put'")
    static class AnnotatedService {

        @RedisCacheable(value = "method-cache", key = "#id", ttl = 22, useBloomFilter = true, sync = true)
        String read(String id) {
            return id;
        }

        @RedisCaching(
                redisCacheable = @RedisCacheable(value = "composite-cache", key = "#id"),
                redisCacheEvict = @RedisCacheEvict(value = "composite-evict", allEntries = true),
                redisCachePut = @RedisCachePut(value = "composite-put", key = "#id"))
        String composite(String id) {
            return id;
        }

        @RedisCacheable(value = "member-cache", key = "#id", unless = "#result == null", type = String.class)
        @RedisCachePut(value = "member-put", key = "#id", unless = "#result == null", type = Long.class)
        String write(String id) {
            return id;
        }

        @RedisCacheEvict(value = "member-evict", unless = "#result == null")
        void evict() {
        }

        String plain() {
            return "plain";
        }
    }
}
