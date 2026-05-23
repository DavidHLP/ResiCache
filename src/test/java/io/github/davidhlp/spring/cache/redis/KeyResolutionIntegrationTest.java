package io.github.davidhlp.spring.cache.redis;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.register.RedisCacheRegister;
import io.github.davidhlp.spring.cache.redis.register.operation.RedisCacheableOperation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.expression.AnnotatedElementKey;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Key-Resolution Compatibility Integration Test.
 *
 * <p>Asserts that ResiCache's metadata lookup key matches Spring's actual cache key
 * for common annotation scenarios.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Import({TestRedisConfiguration.class, KeyResolutionIntegrationTest.TestConfig.class})
@DisplayName("Key Resolution Integration Tests")
class KeyResolutionIntegrationTest extends AbstractRedisIntegrationTest {

    @Autowired
    private TestService testService;

    @Autowired
    private ClassLevelService classLevelService;

    @Autowired
    private RedisCacheRegister redisCacheRegister;

    @Autowired
    private RedisTemplate<String, Object> redisCacheTemplate;

    @BeforeEach
    void setUp() {
        redisCacheTemplate.getConnectionFactory().getConnection().flushDb();
    }

    @Nested
    @DisplayName("SpEL Key Resolution")
    class SpElKeyResolutionTests {

        @Test
        @DisplayName("@RedisCacheable(key = \"#id\") registers metadata correctly")
        void simpleSpelKey_registersMetadata() throws NoSuchMethodException {
            Long id = 42L;
            testService.getById(id);

            Method method = TestService.class.getMethod("getById", Long.class);
            AnnotatedElementKey elementKey = new AnnotatedElementKey(method, TestService.class);
            RedisCacheableOperation operation = redisCacheRegister.getCacheableOperation("users", elementKey);

            assertThat(operation).isNotNull();
            assertThat(operation.getKey()).isEqualTo("#id");
        }

        @Test
        @DisplayName("@RedisCacheable(key = \"#root.methodName + ':' + #id\") registers metadata correctly")
        void compositeSpelKey_registersMetadata() throws NoSuchMethodException {
            Long id = 99L;
            testService.getByIdComposite(id);

            Method method = TestService.class.getMethod("getByIdComposite", Long.class);
            AnnotatedElementKey elementKey = new AnnotatedElementKey(method, TestService.class);
            RedisCacheableOperation operation = redisCacheRegister.getCacheableOperation("items", elementKey);

            assertThat(operation).isNotNull();
            assertThat(operation.getKey()).isEqualTo("#root.methodName + ':' + #id");
        }
    }

    @Nested
    @DisplayName("KeyGenerator Resolution")
    class KeyGeneratorResolutionTests {

        @Test
        @DisplayName("@RedisCacheable(keyGenerator = \"customKeyGenerator\") registers metadata correctly")
        void customKeyGenerator_registersMetadata() throws NoSuchMethodException {
            testService.getWithCustomKey("arg1", "arg2");

            Method method = TestService.class.getMethod("getWithCustomKey", String.class, String.class);
            AnnotatedElementKey elementKey = new AnnotatedElementKey(method, TestService.class);
            RedisCacheableOperation operation = redisCacheRegister.getCacheableOperation("custom", elementKey);

            assertThat(operation).isNotNull();
            assertThat(operation.getKeyGenerator()).isEqualTo("customKeyGenerator");
        }
    }

    @Nested
    @DisplayName("Class-Level and Method-Level Override")
    class ClassLevelMethodLevelTests {

        @Test
        @DisplayName("method-level annotation overrides class-level defaults")
        void methodLevelOverride_registersMetadata() throws NoSuchMethodException {
            classLevelService.classLevelMethod("key1");

            Method method = ClassLevelService.class.getMethod("classLevelMethod", String.class);
            AnnotatedElementKey elementKey = new AnnotatedElementKey(method, ClassLevelService.class);
            RedisCacheableOperation operation = redisCacheRegister.getCacheableOperation("class-cache", elementKey);

            assertThat(operation).isNotNull();
            assertThat(operation.getKey()).isEqualTo("#key");
        }
    }

    @Nested
    @DisplayName("Multiple Cache Names")
    class MultipleCacheNamesTests {

        @Test
        @DisplayName("multiple cache names register metadata for each name")
        void multipleCacheNames_allRegistered() throws NoSuchMethodException {
            testService.getMultiCache("key1");

            Method method = TestService.class.getMethod("getMultiCache", String.class);
            AnnotatedElementKey elementKey = new AnnotatedElementKey(method, TestService.class);

            RedisCacheableOperation op1 = redisCacheRegister.getCacheableOperation("cache-a", elementKey);
            RedisCacheableOperation op2 = redisCacheRegister.getCacheableOperation("cache-b", elementKey);

            assertThat(op1).isNotNull();
            assertThat(op2).isNotNull();
            assertThat(op1.getKey()).isEqualTo("#key");
            assertThat(op2.getKey()).isEqualTo("#key");
        }
    }

    @Nested
    @DisplayName("Composed Annotation")
    class ComposedAnnotationTests {

        @Test
        @DisplayName("meta-annotated @MyCaching registers underlying @RedisCacheable metadata")
        void composedAnnotation_registersMetadata() throws NoSuchMethodException {
            testService.getWithComposedAnnotation("composed");

            Method method = TestService.class.getMethod("getWithComposedAnnotation", String.class);
            AnnotatedElementKey elementKey = new AnnotatedElementKey(method, TestService.class);
            RedisCacheableOperation operation = redisCacheRegister.getCacheableOperation("composed-cache", elementKey);

            assertThat(operation).isNotNull();
            assertThat(operation.getKey()).isEqualTo("#name");
            assertThat(operation.getTtl()).isEqualTo(120);
        }
    }

    @Nested
    @DisplayName("Spring Native Annotation Conversion")
    class SpringNativeAnnotationTests {

        @Test
        @DisplayName("Spring @Cacheable is converted and metadata is registered")
        void springCacheable_convertedAndRegistered() throws NoSuchMethodException {
            testService.getWithSpringCacheable("native");

            Method method = TestService.class.getMethod("getWithSpringCacheable", String.class);
            AnnotatedElementKey elementKey = new AnnotatedElementKey(method, TestService.class);
            RedisCacheableOperation operation = redisCacheRegister.getCacheableOperation("spring-cache", elementKey);

            assertThat(operation).isNotNull();
            assertThat(operation.getKey()).isEqualTo("#name");
        }
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        public TestService testService() {
            return new TestService();
        }

        @Bean
        public ClassLevelService classLevelService() {
            return new ClassLevelService();
        }

        @Bean("customKeyGenerator")
        public KeyGenerator customKeyGenerator() {
            return (target, method, params) -> {
                StringBuilder sb = new StringBuilder("custom");
                for (Object param : params) {
                    sb.append(":").append(param);
                }
                return sb.toString();
            };
        }
    }

    static class TestService {

        @RedisCacheable(cacheNames = "users", key = "#id", ttl = 60)
        public String getById(Long id) {
            return "user-" + id;
        }

        @RedisCacheable(cacheNames = "items", key = "#root.methodName + ':' + #id", ttl = 60)
        public String getByIdComposite(Long id) {
            return "item-" + id;
        }

        @RedisCacheable(cacheNames = "custom", keyGenerator = "customKeyGenerator", ttl = 60)
        public String getWithCustomKey(String arg1, String arg2) {
            return arg1 + "-" + arg2;
        }

        @RedisCacheable(cacheNames = {"cache-a", "cache-b"}, key = "#key", ttl = 60)
        public String getMultiCache(String key) {
            return "multi-" + key;
        }

        @MyCaching
        public String getWithComposedAnnotation(String name) {
            return "composed-" + name;
        }

        @Cacheable(value = "spring-cache", key = "#name")
        public String getWithSpringCacheable(String name) {
            return "spring-" + name;
        }
    }

    @RedisCacheable(cacheNames = "class-cache", ttl = 60)
    static class ClassLevelService {

        @RedisCacheable(cacheNames = "class-cache", key = "#key", ttl = 60)
        public String classLevelMethod(String key) {
            return "class-" + key;
        }
    }

    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD)
    @io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable(cacheNames = "composed-cache", key = "#name", ttl = 120)
    @interface MyCaching {
    }
}
