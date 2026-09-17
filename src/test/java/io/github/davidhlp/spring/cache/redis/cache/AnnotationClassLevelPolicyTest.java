package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCachePut;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.CachePutOperation;
import org.springframework.cache.interceptor.CacheableOperation;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Class-level annotation characterization for the Spring operation source and production
 * annotation policy snapshot.
 *
 * <p>The class target is visible to Spring's operation source, while a method parse snapshot
 * intentionally does not register class-level policy operations.
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
    @DisplayName("class annotation is Spring-visible but not method policy snapshot")
    void classAnnotationIsSpringVisibleButNotMethodPolicySnapshot() throws Exception {
        RedisCacheRegister register = new RedisCacheRegister();
        ExposedOperationSource source = new ExposedOperationSource();
        Method method = ClassAnnotatedService.class.getMethod("find", String.class);
        AnnotationParser.ParsedAnnotations parsed = new AnnotationParser().parse(method);
        register.registerSnapshot(method, ClassAnnotatedService.class, parsed);

        List<CacheOperation> classOperations = source.classOperations(ClassAnnotatedService.class);
        List<CacheOperation> policyOperations =
                register.getSnapshot(method, ClassAnnotatedService.class).policyOperations();

        assertThat(classOperations).extracting(Object::getClass)
                .containsExactly(CacheableOperation.class, CachePutOperation.class);
        assertThat(policyOperations).isEmpty();
    }

    @RedisCacheable(cacheNames = "class-cache", key = "#id", ttl = 99,
            useBloomFilter = true, sync = true, cacheNullValues = true)
    @RedisCachePut(cacheNames = "class-put", key = "#id", ttl = 33,
            useBloomFilter = true, sync = true, cacheNullValues = true)
    static class ClassAnnotatedService {
        public String find(String id) {
            return id;
        }
    }
}
