package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.cache.interceptor.SimpleKeyGenerator;
import org.springframework.context.expression.AnnotatedElementKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RedisCacheInterceptor method normalization")
class RedisCacheInterceptorNormalizationTest {

    @Test
    @DisplayName("JDK proxy invocation uses the implementation method for metadata and resolver lookup")
    void jdkProxyInvocation_normalizesInterfaceMethod() throws Throwable {
        RedisCacheRegister register = new RedisCacheRegister();
        RedisCacheOperationSource operationSource = new RedisCacheOperationSource(
                RedisProCacheProperties.NativeAnnotationMode.SELECTIVE,
                register);
        Method implementationMethod = JdkServiceImpl.class.getMethod("load", String.class);
        operationSource.getCacheOperations(implementationMethod, JdkServiceImpl.class);
        RedisCacheableOperation declaredPolicy = register.get(
                "jdk-cache",
                new AnnotatedElementKey(implementationMethod, JdkServiceImpl.class),
                OperationKind.CACHEABLE);
        DefaultMethodMetadataResolver metadataResolver = spy(new DefaultMethodMetadataResolver());
        CacheOperationResolver resolver = new CacheOperationResolver(metadataResolver, register);
        CacheManager cacheManager = new ConcurrentMapCacheManager("jdk-cache");
        KeyGenerator keyGenerator = new SimpleKeyGenerator();
        RedisCacheInterceptor interceptor = new RedisCacheInterceptor(
                operationSource, cacheManager, keyGenerator, metadataResolver);

        ProxyFactory proxyFactory = new ProxyFactory(new JdkServiceImpl());
        proxyFactory.setProxyTargetClass(false);
        proxyFactory.addAdvice(interceptor);
        JdkService proxy = (JdkService) proxyFactory.getProxy();

        assertThat(Proxy.isProxyClass(proxy.getClass())).isTrue();
        assertThat(proxy.load("id")).isEqualTo("value:id");
        when(metadataResolver.currentKey()).thenReturn(
                new AnnotatedElementKey(implementationMethod, proxy.getClass()));
        assertThat(resolver.resolve("jdk-cache", io.github.davidhlp.spring.cache.redis.chain.CacheOperation.GET))
                .isSameAs(declaredPolicy);
        verify(metadataResolver).activate(implementationMethod, JdkServiceImpl.class);
    }

    @Test
    @DisplayName("JDK proxy invocation preserves an interface-declared policy")
    void jdkProxyInvocation_preservesInterfaceDeclaredPolicy() throws Throwable {
        RedisCacheRegister register = new RedisCacheRegister();
        RedisCacheOperationSource operationSource = new RedisCacheOperationSource(
                RedisProCacheProperties.NativeAnnotationMode.SELECTIVE,
                register);
        Method interfaceMethod = InterfaceAnnotatedService.class.getMethod("load", String.class);
        AnnotationParser.ParsedAnnotations parsed = new AnnotationParser().parse(interfaceMethod);
        RedisCacheableOperation declaredPolicy =
                (RedisCacheableOperation) parsed.policyOperations().get(0);
        register.registerSnapshot(interfaceMethod, InterfaceAnnotatedService.class, parsed);
        DefaultMethodMetadataResolver metadataResolver = spy(new DefaultMethodMetadataResolver());
        CacheOperationResolver resolver = new CacheOperationResolver(metadataResolver, register);
        RedisCacheInterceptor interceptor = new RedisCacheInterceptor(
                operationSource,
                new ConcurrentMapCacheManager("interface-cache"),
                new SimpleKeyGenerator(),
                metadataResolver);

        ProxyFactory proxyFactory = new ProxyFactory(new InterfaceAnnotatedServiceImpl());
        proxyFactory.setProxyTargetClass(false);
        proxyFactory.addAdvice(interceptor);
        InterfaceAnnotatedService proxy = (InterfaceAnnotatedService) proxyFactory.getProxy();

        assertThat(Proxy.isProxyClass(proxy.getClass())).isTrue();
        assertThat(proxy.load("id")).isEqualTo("interface:id");
        Method implementationMethod = InterfaceAnnotatedServiceImpl.class.getMethod("load", String.class);
        when(metadataResolver.currentKey()).thenReturn(
                new AnnotatedElementKey(implementationMethod, proxy.getClass()));
        assertThat(resolver.resolve(
                "interface-cache", io.github.davidhlp.spring.cache.redis.chain.CacheOperation.GET))
                .isSameAs(declaredPolicy);
        verify(metadataResolver).activate(implementationMethod, InterfaceAnnotatedServiceImpl.class);
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
}
