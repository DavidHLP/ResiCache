package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCachePut;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.CachePutOperation;
import org.springframework.cache.interceptor.CacheableOperation;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Class-level annotation characterization for the Spring operation source and production
 * annotation policy snapshot.
 *
 * <p>The class target is visible to Spring's operation source, while a method parse snapshot
 * registers the selected class-level policy for the invoked method.
 */
@DisplayName("Class-level annotation policy characterization")
class AnnotationClassLevelPolicyTest {

    private static final class ExposedOperationSource extends RedisCacheOperationSource {

        ExposedOperationSource(RedisCacheRegister register) {
            super(RedisProCacheProperties.NativeAnnotationMode.SELECTIVE, register);
        }

        List<CacheOperation> classOperations(Class<?> target) {
            return List.copyOf(findCacheOperations(target));
        }
    }

    @Test
    @DisplayName("class annotation is shared by Spring and method policy snapshot")
    void classAnnotationIsSpringVisibleButNotMethodPolicySnapshot() throws Exception {
        RedisCacheRegister register = new RedisCacheRegister();
        ExposedOperationSource source = new ExposedOperationSource(register);
        Method method = ClassAnnotatedService.class.getMethod("find", String.class);
        source.getCacheOperations(method, ClassAnnotatedService.class);

        List<CacheOperation> classOperations = source.classOperations(ClassAnnotatedService.class);
        List<CacheOperation> policyOperations =
                register.getSnapshot(method, ClassAnnotatedService.class).policyOperations();

        assertThat(classOperations).extracting(Object::getClass)
                .containsExactly(CacheableOperation.class, CachePutOperation.class);
        assertThat(policyOperations).hasSize(2);
        assertThat(((RedisCacheableOperation) policyOperations.get(0)).getTtl()).isEqualTo(99);
        assertThat(((RedisCachePutOperation) policyOperations.get(1)).getTtl()).isEqualTo(33);
    }

    @Test
    void methodDeclarationOverridesClassPolicy() throws Exception {
        RedisCacheRegister register = new RedisCacheRegister();
        ExposedOperationSource source = new ExposedOperationSource(register);
        Method method = ClassAnnotatedService.class.getMethod("override", String.class);
        source.getCacheOperations(method, ClassAnnotatedService.class);
        List<CacheOperation> policies = register.getSnapshot(method, ClassAnnotatedService.class).policyOperations();
        assertThat(policies).hasSize(1);
        assertThat(((RedisCacheableOperation) policies.get(0)).getTtl()).isEqualTo(7);
    }

    @Test
    void nativeClassPolicyUsesSelectedMethodAndMethodOverride() throws Exception {
        RedisCacheRegister register = new RedisCacheRegister();
        RedisCacheOperationSource source = new RedisCacheOperationSource(
                RedisProCacheProperties.NativeAnnotationMode.FULL, register);
        for (String name : List.of("find", "override")) {
            Method method = NativeClassService.class.getMethod(name, String.class);
            var operations = source.getCacheOperations(method, NativeClassService.class);
            var snapshot = register.getSnapshot(method, NativeClassService.class);
            assertThat(snapshot.policyOperations()).hasSize(1);
            var policy = (RedisCacheableOperation) snapshot.policyOperations().get(0);
            assertThat(policy.isSync()).isEqualTo(name.equals("find"));
            assertThat(policy.getCacheNames()).containsExactly(name.equals("find") ? "native-class" : "native-override");
            assertThat(operations).hasSize(1);
        }
    }

    @Test
    void nativeMethodTargetUsesExplicitConcretePolicyMethod() throws Exception {
        Method declaration = NativeContract.class.getMethod("find", String.class);
        Method selected = NativeClassService.class.getMethod("override", String.class);
        var policy = new AnnotationParser().parse(declaration, selected).policyOperations();
        assertThat(policy).singleElement().satisfies(operation -> {
            assertThat(operation.getName()).isEqualTo("override");
            assertThat(((RedisCacheableOperation) operation).isSync()).isTrue();
        });
    }

    @Test
    void selectiveMixedClassRetainsNativeSyncPolicy() throws Exception {
        RedisCacheRegister register = new RedisCacheRegister();
        var source = new RedisCacheOperationSource(RedisProCacheProperties.NativeAnnotationMode.SELECTIVE, register);
        Method method = MixedNativeClass.class.getMethod("find", String.class);
        assertThat(source.getCacheOperations(method, MixedNativeClass.class)).hasSize(2);
        assertThat(register.getSnapshot(method, MixedNativeClass.class).policyOperations())
                .singleElement().satisfies(operation ->
                        assertThat(((RedisCacheableOperation) operation).isSync()).isTrue());
    }

    @io.github.davidhlp.spring.cache.redis.annotation.RedisCacheEvict(cacheNames = "mixed-native", beforeInvocation = true)
    @Cacheable(cacheNames = "mixed-native", sync = true)
    static class MixedNativeClass {
        public String find(String id) { return id; }
    }

    @Test
    void nativeInheritedAndInterfaceDeclarationsKeepSelectedPolicy() throws Exception {
        RedisCacheRegister register = new RedisCacheRegister();
        RedisCacheOperationSource source = new RedisCacheOperationSource(
                RedisProCacheProperties.NativeAnnotationMode.FULL, register);
        Method inherited = NativeChildService.class.getMethod("find", String.class);
        source.getCacheOperations(inherited, NativeChildService.class);
        assertThat(register.getSnapshot(inherited, NativeChildService.class).policyOperations())
                .singleElement().satisfies(operation ->
                        assertThat(((RedisCacheableOperation) operation).isSync()).isTrue());
        Method declared = NativeContract.class.getMethod("find", String.class);
        source.getCacheOperations(declared, NativeImplementation.class);
        assertThat(register.getSnapshot(declared, NativeImplementation.class).policyOperations())
                .singleElement().satisfies(operation -> {
                    assertThat(((RedisCacheableOperation) operation).isSync()).isTrue();
                    assertThat(operation.getCacheNames()).containsExactly("native-interface");
                });
    }

    static class NativeChildService extends NativeClassService { }

    interface NativeContract {
        @Cacheable(cacheNames = "native-interface", sync = true)
        String find(String id);
    }

    static class NativeImplementation implements NativeContract {
        @Override
        public String find(String id) { return id; }
    }

    @Test
    void pureNativeClassRemainsIgnoredOutsideFullMode() throws Exception {
        for (var mode : List.of(
                RedisProCacheProperties.NativeAnnotationMode.NONE,
                RedisProCacheProperties.NativeAnnotationMode.SELECTIVE)) {
            RedisCacheRegister register = new RedisCacheRegister();
            RedisCacheOperationSource source = new RedisCacheOperationSource(mode, register);
            Method method = NativeClassService.class.getMethod("find", String.class);
            assertThat(source.getCacheOperations(method, NativeClassService.class)).isNull();
            assertThat(register.getSnapshot(method, NativeClassService.class)).isNull();
        }
    }

    @CacheConfig(cacheNames = "unused-default")
    @Cacheable(value = "native-class", sync = true)
    static class NativeClassService {
        public String find(String id) { return id; }

        @Cacheable(cacheNames = "native-override")
        public String override(String id) { return id; }
    }

    @RedisCacheable(cacheNames = "class-cache", key = "#id", ttl = 99,
            useBloomFilter = true, sync = true, cacheNullValues = true)
    @RedisCachePut(cacheNames = "class-put", key = "#id", ttl = 33,
            useBloomFilter = true, sync = true, cacheNullValues = true)
    static class ClassAnnotatedService {
        @RedisCacheable(cacheNames = "override-cache", ttl = 7)
        public String override(String id) {
            return id;
        }

        public String find(String id) {
            return id;
        }
    }
}
