package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.CacheableOperation;
import org.springframework.cache.interceptor.KeyGenerator;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Class-level annotation characterization for the Spring operation source and annotation chain.
 *
 * <p>The class target is visible to Spring's operation source, while the method-side annotation
 * chain only inspects method annotations. This is intentionally pinned against the pre-C3
 * behavior so a future registration change can be evaluated as an explicit compatibility change.
 */
@DisplayName("Class-level annotation policy characterization")
class AnnotationClassLevelPolicyTest {

    private static final class ExposedOperationSource extends RedisCacheOperationSource {

        ExposedOperationSource() {
            super(io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties.NativeAnnotationMode.SELECTIVE);
        }

        List<CacheOperation> classOperations(Class<?> target) {
            return List.copyOf(findCacheOperations(target));
        }
    }

    @Test
    @DisplayName("class annotation is Spring-visible but not chain policy")
    void classAnnotationIsSpringVisibleButNotChainPolicy() throws Exception {
        RedisCacheRegister register = new RedisCacheRegister(8, 4);
        KeyGenerator keyGenerator = Mockito.mock(KeyGenerator.class);
        RedisCacheAttributesProjector projector = new RedisCacheAttributesProjector();
        SpringCacheableAdapter springAdapter = Mockito.mock(SpringCacheableAdapter.class);
        AnnotationChainEngine chain = new AnnotationChainEngine(List.of(
                new CacheableAnnotationHandler(register, keyGenerator, projector, springAdapter),
                new EvictAnnotationHandler(register, keyGenerator, projector),
                new CachePutAnnotationHandler(register, keyGenerator, projector),
                new CachingAnnotationHandler(register, keyGenerator, projector)));
        ExposedOperationSource source = new ExposedOperationSource();
        Method method = ClassAnnotatedService.class.getMethod("find", String.class);

        List<CacheOperation> classOperations = source.classOperations(ClassAnnotatedService.class);
        List<CacheOperation> chainOperations = chain.execute(
                method, new ClassAnnotatedService(), new Object[]{"id"});

        assertThat(classOperations).singleElement().isInstanceOf(CacheableOperation.class);
        assertThat(chainOperations).isEmpty();
    }

    @RedisCacheable(cacheNames = "class-cache", key = "#id", ttl = 99,
            useBloomFilter = true, sync = true, cacheNullValues = true)
    static class ClassAnnotatedService {
        public String find(String id) {
            return id;
        }
    }
}
