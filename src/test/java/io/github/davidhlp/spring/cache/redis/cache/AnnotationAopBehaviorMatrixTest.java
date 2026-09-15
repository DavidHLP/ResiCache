package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheEvict;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCachePut;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCaching;
import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import io.github.davidhlp.spring.cache.redis.chain.model.CachePolicyView;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.CacheableOperation;
import org.springframework.cache.interceptor.CacheEvictOperation;
import org.springframework.cache.interceptor.CachePutOperation;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.expression.AnnotatedElementKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * AOP 注解管线行为矩阵 —— 在策略快照合并前锁定可观察契约。
 *
 * <p>同一组方法同时经过 Spring operation source 与 annotation chain，验证两侧的
 * operation 类型、key/condition、命名空间选择及增强策略字段。
 */
@DisplayName("AOP annotation behavior matrix")
class AnnotationAopBehaviorMatrixTest {

    private RedisCacheRegister register;
    private AnnotationChainEngine annotationChainEngine;
    private CacheOperationResolver resolver;
    private RedisCacheOperationSource operationSource;
    private MethodMetadataResolver metadataResolver;

    @BeforeEach
    void setUp() {
        register = new RedisCacheRegister(32, 8);
        KeyGenerator keyGenerator = Mockito.mock(KeyGenerator.class);
        when(keyGenerator.generate(any(), any(), any())).thenReturn("generated-key");
        RedisCacheAttributesProjector projector = new RedisCacheAttributesProjector();
        SpringCacheableAdapter springAdapter = Mockito.mock(SpringCacheableAdapter.class);
        annotationChainEngine = new AnnotationChainEngine(List.of(
                new CacheableAnnotationHandler(register, keyGenerator, projector, springAdapter),
                new EvictAnnotationHandler(register, keyGenerator, projector),
                new CachePutAnnotationHandler(register, keyGenerator, projector),
                new CachingAnnotationHandler(register, keyGenerator, projector)));
        metadataResolver = Mockito.mock(MethodMetadataResolver.class);
        resolver = new CacheOperationResolver(metadataResolver, register);
        operationSource = new RedisCacheOperationSource(
                RedisProCacheProperties.NativeAnnotationMode.SELECTIVE);
    }

    @Test
    @DisplayName("read policy produces matching Spring and chain operations")
    void readPolicyProducesMatchingOperations() throws Exception {
        Method method = method("read");

        List<CacheOperation> chainOperations = execute(method);
        Collection<CacheOperation> springOperations =
                operationSource.getCacheOperations(method, Matrix.class);

        assertThat(chainOperations).hasSize(1);
        RedisCacheableOperation chainOperation = (RedisCacheableOperation) chainOperations.get(0);
        assertThat(chainOperation.getKey()).isEqualTo("#id");
        assertThat(chainOperation.getCondition()).isEqualTo("#id != null");
        assertThat(chainOperation.getTtl()).isEqualTo(321L);
        assertThat(chainOperation.isUseBloomFilter()).isTrue();
        assertThat(chainOperation.isSync()).isTrue();
        assertThat(chainOperation.isCacheNullValues()).isTrue();
        assertThat(chainOperation.isEnableEarlyExpiration()).isTrue();
        assertThat(chainOperation.getEarlyExpirationThreshold()).isEqualTo(0.8D);

        assertThat(springOperations).singleElement().isInstanceOf(CacheableOperation.class);
        CacheableOperation springOperation = (CacheableOperation) springOperations.iterator().next();
        assertThat(springOperation.getKey()).isEqualTo("#id");
        assertThat(springOperation.getCondition()).isEqualTo("#id != null");
        assertThat(resolve("read-cache", io.github.davidhlp.spring.cache.redis.chain.CacheOperation.GET))
                .isSameAs(chainOperation);
    }

    @Test
    @DisplayName("write policy resolves its own namespace and preserves policy fields")
    void writePolicyResolvesPutNamespace() throws Exception {
        Method method = method("write");

        List<CacheOperation> chainOperations = execute(method);
        Collection<CacheOperation> springOperations =
                operationSource.getCacheOperations(method, Matrix.class);

        assertThat(chainOperations).singleElement().isInstanceOf(RedisCachePutOperation.class);
        RedisCachePutOperation chainOperation = (RedisCachePutOperation) chainOperations.get(0);
        assertThat(chainOperation.getKey()).isEqualTo("#id");
        assertThat(chainOperation.getCondition()).isEqualTo("#id != null");
        assertThat(chainOperation.getTtl()).isEqualTo(123L);
        assertThat(chainOperation.isUseBloomFilter()).isTrue();
        assertThat(chainOperation.isSync()).isTrue();
        assertThat(chainOperation.isCacheNullValues()).isTrue();
        assertThat(chainOperation.isEnableEarlyExpiration()).isTrue();
        assertThat(resolve("write-cache", io.github.davidhlp.spring.cache.redis.chain.CacheOperation.PUT))
                .isSameAs(chainOperation);

        assertThat(springOperations).singleElement().isInstanceOf(CachePutOperation.class);
        CachePutOperation springOperation = (CachePutOperation) springOperations.iterator().next();
        assertThat(springOperation.getKey()).isEqualTo("#id");
        assertThat(springOperation.getCondition()).isEqualTo("#id != null");
    }

    @Test
    @DisplayName("read declaration wins write-back policy when read and put coexist")
    void readDeclarationWinsWriteBackPolicy() throws Exception {
        Method method = method("readThrough");

        List<CacheOperation> chainOperations = execute(method);
        assertThat(chainOperations).extracting(Object::getClass)
                .containsExactly(RedisCacheableOperation.class, RedisCachePutOperation.class);
        RedisCacheableOperation readOperation = (RedisCacheableOperation) chainOperations.get(0);
        assertThat(resolve("mixed-cache", io.github.davidhlp.spring.cache.redis.chain.CacheOperation.GET))
                .isSameAs(readOperation);
        assertThat(resolve("mixed-cache", io.github.davidhlp.spring.cache.redis.chain.CacheOperation.PUT))
                .isSameAs(readOperation);
        assertThat(resolve("mixed-cache", io.github.davidhlp.spring.cache.redis.chain.CacheOperation.PUT).getTtl())
                .isEqualTo(900L);
    }

    @Test
    @DisplayName("eviction produces a Spring operation but no chain policy namespace")
    void evictionHasNoPolicyNamespace() throws Exception {
        Method method = method("evict");

        List<CacheOperation> chainOperations = execute(method);
        Collection<CacheOperation> springOperations =
                operationSource.getCacheOperations(method, Matrix.class);

        assertThat(chainOperations).singleElement().isInstanceOf(RedisCacheEvictOperation.class);
        assertThat(springOperations).singleElement().isInstanceOf(CacheEvictOperation.class);
        CacheEvictOperation springOperation = (CacheEvictOperation) springOperations.iterator().next();
        assertThat(springOperation.getKey()).isEqualTo("#id");
        assertThat(springOperation.getCondition()).isEqualTo("#id != null");
        assertThat(resolve("evict-cache", io.github.davidhlp.spring.cache.redis.chain.CacheOperation.REMOVE)).isNull();
        assertThat(resolve("evict-cache", io.github.davidhlp.spring.cache.redis.chain.CacheOperation.CLEAN)).isNull();
    }

    @Test
    @DisplayName("composite annotation expands into each Spring and chain operation")
    void compositeAnnotationExpands() throws Exception {
        Method method = method("composite");

        List<CacheOperation> chainOperations = execute(method);
        Collection<CacheOperation> springOperations =
                operationSource.getCacheOperations(method, Matrix.class);

        assertThat(chainOperations).extracting(Object::getClass)
                .containsExactly(RedisCacheableOperation.class, RedisCacheEvictOperation.class,
                        RedisCachePutOperation.class);
        assertThat(springOperations).extracting(Object::getClass)
                .containsExactly(CacheableOperation.class, CacheEvictOperation.class, CachePutOperation.class);
    }

    private List<CacheOperation> execute(Method method) {
        when(metadataResolver.currentKey()).thenReturn(new AnnotatedElementKey(method, Matrix.class));
        return annotationChainEngine.execute(method, new Matrix(), new Object[]{"id"});
    }

    private CachePolicyView.Source resolve(
            String cacheName, io.github.davidhlp.spring.cache.redis.chain.CacheOperation operation) {
        return resolver.resolve(cacheName, operation);
    }

    private Method method(String name) throws NoSuchMethodException {
        return Matrix.class.getMethod(name, String.class);
    }

    static class Matrix {
        @RedisCacheable(
                cacheNames = "read-cache",
                key = "#id",
                condition = "#id != null",
                ttl = 321,
                useBloomFilter = true,
                sync = true,
                cacheNullValues = true,
                enableEarlyExpiration = true,
                earlyExpirationThreshold = 0.8)
        public String read(String id) {
            return id;
        }

        @RedisCachePut(
                cacheNames = "write-cache",
                key = "#id",
                condition = "#id != null",
                ttl = 123,
                useBloomFilter = true,
                sync = true,
                cacheNullValues = true,
                enableEarlyExpiration = true)
        public String write(String id) {
            return id;
        }

        @RedisCacheable(cacheNames = "mixed-cache", key = "#id", ttl = 900)
        @RedisCachePut(cacheNames = "mixed-cache", key = "#id", ttl = 10)
        public String readThrough(String id) {
            return id;
        }

        @RedisCacheEvict(cacheNames = "evict-cache", key = "#id", condition = "#id != null")
        public void evict(String id) {
        }

        @RedisCaching(
                redisCacheable = @RedisCacheable(cacheNames = "composite-read", key = "#id"),
                redisCacheEvict = @RedisCacheEvict(cacheNames = "composite-evict", key = "#id"),
                redisCachePut = @RedisCachePut(cacheNames = "composite-write", key = "#id"))
        public String composite(String id) {
            return id;
        }
    }
}
