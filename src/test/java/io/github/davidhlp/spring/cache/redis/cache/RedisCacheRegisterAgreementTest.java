package io.github.davidhlp.spring.cache.redis.cache;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.expression.AnnotatedElementKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Guards the invariant that the annotation chain and policy resolver read one LRU entry.
 */
@DisplayName("RedisCacheRegister snapshot reader agreement")
class RedisCacheRegisterAgreementTest {

    @Test
    @DisplayName("multi-cache policy churn cannot make chain and resolver disagree")
    void multiCachePolicyChurn_keepsReadersInAgreement() throws Exception {
        RedisCacheRegister register = new RedisCacheRegister(1, 1);
        Method firstMethod = AgreementService.class.getMethod("first", String.class);
        Method secondMethod = AgreementService.class.getMethod("second", String.class);
        Method thirdMethod = AgreementService.class.getMethod("third", String.class);
        Method fourthMethod = AgreementService.class.getMethod("fourth", String.class);
        RedisCacheableOperation firstOperation = RedisCacheableOperation.builder()
                .name("first")
                .cacheNames("cache-a", "cache-b")
                .ttl(321)
                .build();
        RedisCacheableOperation secondOperation = RedisCacheableOperation.builder()
                .name("second")
                .cacheNames("cache-c")
                .ttl(123)
                .build();

        register.registerSnapshot(firstMethod, AgreementService.class,
                new AnnotationParser.ParsedAnnotations(List.of(firstOperation), List.of(firstOperation)));
        register.registerSnapshot(secondMethod, AgreementService.class,
                new AnnotationParser.ParsedAnnotations(List.of(secondOperation), List.of(secondOperation)));

        MethodMetadataResolver metadataResolver = Mockito.mock(MethodMetadataResolver.class);
        CacheOperationResolver resolver = new CacheOperationResolver(metadataResolver, register);
        AnnotationChainEngine chain = new AnnotationChainEngine(List.of(), register);
        AnnotatedElementKey firstKey = new AnnotatedElementKey(firstMethod, AgreementService.class);
        when(metadataResolver.currentKey()).thenReturn(firstKey);

        assertThat(chain.execute(firstMethod, new AgreementService(), new Object[]{"id"}))
                .singleElement().isSameAs(firstOperation);
        assertThat(resolver.resolve("cache-a", io.github.davidhlp.spring.cache.redis.chain.CacheOperation.GET))
                .isSameAs(firstOperation);

        register.registerSnapshot(thirdMethod, AgreementService.class,
                new AnnotationParser.ParsedAnnotations(List.of(), List.of()));
        register.registerSnapshot(fourthMethod, AgreementService.class,
                new AnnotationParser.ParsedAnnotations(List.of(), List.of()));

        assertThat(chain.execute(firstMethod, new AgreementService(), new Object[]{"id"})).isEmpty();
        assertThat(resolver.resolve("cache-a", io.github.davidhlp.spring.cache.redis.chain.CacheOperation.GET))
                .isNull();
    }

    static class AgreementService {
        public String first(String id) {
            return id;
        }

        public String second(String id) {
            return id;
        }

        public String third(String id) {
            return id;
        }

        public String fourth(String id) {
            return id;
        }
    }
}
