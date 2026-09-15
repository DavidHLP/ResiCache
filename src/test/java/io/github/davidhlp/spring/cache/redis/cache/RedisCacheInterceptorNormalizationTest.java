package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.cache.interceptor.SimpleKeyGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

@DisplayName("RedisCacheInterceptor method normalization")
class RedisCacheInterceptorNormalizationTest {

    @Test
    @DisplayName("JDK proxy invocation uses the implementation method for chain and metadata")
    void jdkProxyInvocation_normalizesInterfaceMethod() throws Throwable {
        RedisCacheRegister register = new RedisCacheRegister();
        RedisCacheOperationSource operationSource = new RedisCacheOperationSource(
                io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties.NativeAnnotationMode.SELECTIVE,
                register);
        Method implementationMethod = JdkServiceImpl.class.getMethod("load", String.class);
        RecordingAnnotationChainEngine chain = new RecordingAnnotationChainEngine(register);
        operationSource.getCacheOperations(implementationMethod, JdkServiceImpl.class);
        RedisCacheableOperation declaredPolicy = register.get(
                "jdk-cache",
                new org.springframework.context.expression.AnnotatedElementKey(
                        implementationMethod, JdkServiceImpl.class),
                OperationKind.CACHEABLE);
        DefaultMethodMetadataResolver metadataResolver = spy(new DefaultMethodMetadataResolver());
        CacheManager cacheManager = new ConcurrentMapCacheManager("jdk-cache");
        KeyGenerator keyGenerator = new SimpleKeyGenerator();
        RedisCacheInterceptor interceptor = new RedisCacheInterceptor(
                operationSource, cacheManager, keyGenerator, chain, metadataResolver);

        ProxyFactory proxyFactory = new ProxyFactory(new JdkServiceImpl());
        proxyFactory.setProxyTargetClass(false);
        proxyFactory.addAdvice(interceptor);
        JdkService proxy = (JdkService) proxyFactory.getProxy();

        assertThat(Proxy.isProxyClass(proxy.getClass())).isTrue();
        assertThat(proxy.load("id")).isEqualTo("value:id");
        assertThat(chain.observedOperations).singleElement().isSameAs(declaredPolicy);
        assertThat(((RedisCacheableOperation) chain.observedOperations.get(0)).getTtl())
                .isEqualTo(321L);
        assertThat(((RedisCacheableOperation) chain.observedOperations.get(0)).isUseBloomFilter())
                .isTrue();
        verify(metadataResolver).activate(implementationMethod, JdkServiceImpl.class);
    }

    @Test
    @DisplayName("JDK proxy invocation preserves an interface-declared policy")
    void jdkProxyInvocation_preservesInterfaceDeclaredPolicy() throws Throwable {
        RedisCacheRegister register = new RedisCacheRegister();
        RedisCacheOperationSource operationSource = new RedisCacheOperationSource(
                io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties.NativeAnnotationMode.SELECTIVE,
                register);
        Method interfaceMethod = InterfaceAnnotatedService.class.getMethod("load", String.class);
        AnnotationParser.ParsedAnnotations parsed = new AnnotationParser().parse(interfaceMethod);
        RedisCacheableOperation declaredPolicy =
                (RedisCacheableOperation) parsed.policyOperations().get(0);
        register.registerSnapshot(interfaceMethod, InterfaceAnnotatedService.class, parsed);
        RecordingAnnotationChainEngine chain = new RecordingAnnotationChainEngine(register);
        DefaultMethodMetadataResolver metadataResolver = spy(new DefaultMethodMetadataResolver());
        RedisCacheInterceptor interceptor = new RedisCacheInterceptor(
                operationSource,
                new ConcurrentMapCacheManager("interface-cache"),
                new SimpleKeyGenerator(),
                chain,
                metadataResolver);

        ProxyFactory proxyFactory = new ProxyFactory(new InterfaceAnnotatedServiceImpl());
        proxyFactory.setProxyTargetClass(false);
        proxyFactory.addAdvice(interceptor);
        InterfaceAnnotatedService proxy = (InterfaceAnnotatedService) proxyFactory.getProxy();

        assertThat(Proxy.isProxyClass(proxy.getClass())).isTrue();
        assertThat(proxy.load("id")).isEqualTo("interface:id");
        assertThat(chain.observedOperations).singleElement().isSameAs(declaredPolicy);
        assertThat(((RedisCacheableOperation) chain.observedOperations.get(0)).getTtl())
                .isEqualTo(654L);
        assertThat(((RedisCacheableOperation) chain.observedOperations.get(0)).isUseBloomFilter())
                .isTrue();
        verify(metadataResolver).activate(
                InterfaceAnnotatedServiceImpl.class.getMethod("load", String.class),
                InterfaceAnnotatedServiceImpl.class);
    }

    private interface InterfaceAnnotatedService {
        @RedisCacheable(cacheNames = "interface-cache", ttl = 654, useBloomFilter = true)
        String load(String id);
    }

    private static final class InterfaceAnnotatedServiceImpl implements InterfaceAnnotatedService {
        @Override
        public String load(String id) {
            return "interface:" + id;
        }
    }

    private interface JdkService {
        String load(String id);
    }

    private static final class JdkServiceImpl implements JdkService {
        @Override
        @RedisCacheable(cacheNames = "jdk-cache", ttl = 321, useBloomFilter = true)
        public String load(String id) {
            return "value:" + id;
        }
    }

    private static final class RecordingAnnotationChainEngine extends AnnotationChainEngine {
        private List<CacheOperation> observedOperations = List.of();

        private RecordingAnnotationChainEngine(RedisCacheRegister register) {
            super(List.of(), register);
        }

        @Override
        public List<CacheOperation> execute(Method method, Object target, Object[] args) {
            observedOperations = super.execute(method, target, args);
            return observedOperations;
        }
    }
}
