package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.context.expression.AnnotatedElementKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Structural evidence for one parser invocation and one policy snapshot per element.
 */
@DisplayName("Annotation policy snapshot structure")
class AnnotationPolicySnapshotTest {

    @Test
    @DisplayName("operation source parses an element once and both readers reuse its snapshot")
    void operationSourceParsesElementOnceAndReadersReuseSnapshot() throws Exception {
        RedisCacheRegister register = new RedisCacheRegister();
        CountingAnnotationParser parser = new CountingAnnotationParser();
        RedisCacheOperationSource source = new RedisCacheOperationSource(
                RedisProCacheProperties.NativeAnnotationMode.SELECTIVE, parser, register);
        Method method = SnapshotService.class.getMethod("read", String.class);
        MethodMetadataResolver metadataResolver = Mockito.mock(MethodMetadataResolver.class);
        when(metadataResolver.currentKey()).thenReturn(
                new AnnotatedElementKey(method, SnapshotService.class));
        CacheOperationResolver resolver = new CacheOperationResolver(metadataResolver, register);
        AnnotationChainEngine chain = new AnnotationChainEngine(List.of(), register);

        source.getCacheOperations(method, SnapshotService.class);
        AnnotationParser.ParsedAnnotations first = register.getSnapshot(method, SnapshotService.class);
        source.getCacheOperations(method, SnapshotService.class);
        AnnotationParser.ParsedAnnotations second = register.getSnapshot(method, SnapshotService.class);

        assertThat(parser.invocations()).isEqualTo(1);
        assertThat(second).isSameAs(first);
        assertThat(chain.execute(method, new SnapshotService(), new Object[]{"id"}))
                .singleElement().isSameAs(first.policyOperations().get(0));
        assertThat(resolver.resolve("snapshot-cache", io.github.davidhlp.spring.cache.redis.chain.CacheOperation.GET))
                .isSameAs(first.policyOperations().get(0));
    }

    @Test
    @DisplayName("operation source registration is consumed by chain without manual registration")
    void operationSourceRegistrationIsConsumedByChainWithoutManualRegistration() throws Exception {
        RedisCacheRegister register = new RedisCacheRegister();
        RedisCacheOperationSource source = new RedisCacheOperationSource(
                RedisProCacheProperties.NativeAnnotationMode.SELECTIVE, register);
        Method method = SnapshotService.class.getMethod("read", String.class);
        AnnotationChainEngine chain = new AnnotationChainEngine(List.of(), register);

        java.util.Collection<CacheOperation> sourceOperations =
                source.getCacheOperations(method, SnapshotService.class);
        AnnotationParser.ParsedAnnotations snapshot =
                register.getSnapshot(method, SnapshotService.class);
        List<CacheOperation> chainOperations =
                chain.execute(method, new SnapshotService(), new Object[]{"id"});

        assertThat(sourceOperations).singleElement().isSameAs(snapshot.operations().get(0));
        assertThat(chainOperations).singleElement().isSameAs(snapshot.policyOperations().get(0));
    }


    private static final class CountingAnnotationParser extends AnnotationParser {
        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        ParsedAnnotations parse(Object target) {
            invocations.incrementAndGet();
            return super.parse(target);
        }

        int invocations() {
            return invocations.get();
        }
    }

    static class SnapshotService {
        @RedisCacheable(cacheNames = "snapshot-cache", key = "#id", ttl = 77,
                useBloomFilter = true, sync = true, cacheNullValues = true)
        public String read(String id) {
            return id;
        }
    }
}
