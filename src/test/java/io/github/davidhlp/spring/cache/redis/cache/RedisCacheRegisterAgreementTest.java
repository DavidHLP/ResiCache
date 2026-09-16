package io.github.davidhlp.spring.cache.redis.cache;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.cache.interceptor.CacheableOperation;
import org.springframework.context.expression.AnnotatedElementKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.when;

/**
 * Guards the invariant that the annotation chain and policy resolver read one snapshot.
 */
@DisplayName("RedisCacheRegister snapshot reader agreement")
class RedisCacheRegisterAgreementTest {

    @Test
    @DisplayName("multi-cache policy churn cannot make chain and resolver disagree")
    void multiCachePolicyChurn_keepsReadersInAgreement() throws Exception {
        RedisCacheRegister register = new RedisCacheRegister();
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

        assertThat(chain.execute(firstMethod, new AgreementService(), new Object[]{"id"}))
                .singleElement().isSameAs(firstOperation);
        assertThat(resolver.resolve("cache-a", io.github.davidhlp.spring.cache.redis.chain.CacheOperation.GET))
                .isSameAs(firstOperation);
    }

    @Test
    @DisplayName("snapshots remain resolvable after further registrations")
    void snapshotRemainsResolvableAfterFurtherRegistrations() throws Exception {
        RedisCacheRegister register = new RedisCacheRegister();
        Method anchorMethod = AgreementService.class.getMethod("first", String.class);
        Method secondMethod = AgreementService.class.getMethod("second", String.class);
        Method thirdMethod = AgreementService.class.getMethod("third", String.class);
        RedisCacheableOperation anchorOperation = RedisCacheableOperation.builder()
                .name("anchor")
                .cacheNames("anchor-cache")
                .ttl(321)
                .build();
        register.registerSnapshot(anchorMethod, AgreementService.class,
                new AnnotationParser.ParsedAnnotations(List.of(anchorOperation), List.of(anchorOperation)));

        for (int i = 0; i < 100; i++) {
            Method method = i % 2 == 0 ? secondMethod : thirdMethod;
            RedisCacheableOperation operation = RedisCacheableOperation.builder()
                    .name("churn-" + i)
                    .cacheNames("churn-cache-" + i)
                    .build();
            register.registerSnapshot(method, AgreementService.class,
                    new AnnotationParser.ParsedAnnotations(List.of(operation), List.of(operation)));
        }

        AnnotatedElementKey anchorKey = new AnnotatedElementKey(anchorMethod, AgreementService.class);
        assertThat(register.getSnapshot(anchorMethod, AgreementService.class))
                .isNotNull();
        RedisCacheableOperation resolved = register.get(
                "anchor-cache", anchorKey, OperationKind.CACHEABLE);
        assertThat(resolved).isSameAs(anchorOperation);
    }

    @Test
    @DisplayName("interface-derived snapshots are memoized by requested element")
    void interfaceSnapshotLookup_isMemoizedByRequestedElement() throws Exception {
        CountingRedisCacheRegister register = new CountingRedisCacheRegister();
        Method interfaceMethod = AnnotatedService.class.getMethod("load", String.class);
        Method implementationMethod = AnnotatedServiceImpl.class.getMethod("load", String.class);
        RedisCacheableOperation operation = RedisCacheableOperation.builder()
                .name("interface")
                .cacheNames("interface-cache")
                .ttl(654)
                .build();
        AnnotationParser.ParsedAnnotations snapshot =
                new AnnotationParser.ParsedAnnotations(List.of(operation), List.of(operation));
        register.registerSnapshot(interfaceMethod, AnnotatedService.class, snapshot);
        AnnotatedElementKey requestKey = new AnnotatedElementKey(
                implementationMethod, AnnotatedServiceImpl.class);

        assertThat(register.get(
                "interface-cache", requestKey, OperationKind.CACHEABLE))
                .isSameAs(operation);
        assertThat(register.interfaceSnapshotLookups).isEqualTo(1);
        assertThat(register.get(
                "interface-cache", requestKey, OperationKind.CACHEABLE))
                .isSameAs(operation);
        assertThat(register.interfaceSnapshotLookups).isEqualTo(1);
    }

    @Test
    @DisplayName("registration invalidates interface-derived aliases")
    void registration_invalidatesInterfaceAlias() throws Exception {
        CountingRedisCacheRegister register = new CountingRedisCacheRegister();
        Method interfaceMethod = AnnotatedService.class.getMethod("load", String.class);
        Method implementationMethod = AnnotatedServiceImpl.class.getMethod("load", String.class);
        RedisCacheableOperation oldOperation = RedisCacheableOperation.builder()
                .name("old")
                .cacheNames("interface-cache")
                .build();
        RedisCacheableOperation newOperation = RedisCacheableOperation.builder()
                .name("new")
                .cacheNames("interface-cache")
                .build();
        AnnotationParser.ParsedAnnotations oldSnapshot =
                new AnnotationParser.ParsedAnnotations(List.of(oldOperation), List.of(oldOperation));
        AnnotationParser.ParsedAnnotations newSnapshot =
                new AnnotationParser.ParsedAnnotations(List.of(newOperation), List.of(newOperation));
        register.registerSnapshot(interfaceMethod, AnnotatedService.class, oldSnapshot);
        assertThat(register.getSnapshot(implementationMethod, AnnotatedServiceImpl.class))
                .isSameAs(oldSnapshot);

        register.registerSnapshot(interfaceMethod, AnnotatedService.class, newSnapshot);

        assertThat(register.getSnapshot(implementationMethod, AnnotatedServiceImpl.class))
                .isSameAs(newSnapshot);
        assertThat(register.interfaceSnapshotLookups).isEqualTo(2);
    }

    @Test
    @DisplayName("an alias resolved before registration cannot be written afterward")
    void aliasWrite_afterRegistration_doesNotRestoreOldSnapshot() throws Exception {
        CountingRedisCacheRegister register = new CountingRedisCacheRegister();
        Method interfaceMethod = AnnotatedService.class.getMethod("load", String.class);
        Method implementationMethod = AnnotatedServiceImpl.class.getMethod("load", String.class);
        RedisCacheableOperation oldOperation = RedisCacheableOperation.builder()
                .name("old")
                .cacheNames("interface-cache")
                .build();
        RedisCacheableOperation newOperation = RedisCacheableOperation.builder()
                .name("new")
                .cacheNames("interface-cache")
                .build();
        AnnotationParser.ParsedAnnotations oldSnapshot =
                new AnnotationParser.ParsedAnnotations(List.of(oldOperation), List.of(oldOperation));
        AnnotationParser.ParsedAnnotations newSnapshot =
                new AnnotationParser.ParsedAnnotations(List.of(newOperation), List.of(newOperation));
        register.registerSnapshot(interfaceMethod, AnnotatedService.class, oldSnapshot);
        AnnotatedElementKey requestKey = new AnnotatedElementKey(
                implementationMethod, AnnotatedServiceImpl.class);
        assertThat(register.getSnapshot(implementationMethod, AnnotatedServiceImpl.class))
                .isSameAs(oldSnapshot);
        long oldGeneration = register.currentRegistrationGeneration();

        register.registerSnapshot(interfaceMethod, AnnotatedService.class, newSnapshot);
        register.cacheAliasIfCurrent(requestKey, oldSnapshot, oldGeneration);

        assertThat(register.getSnapshot(implementationMethod, AnnotatedServiceImpl.class))
                .isSameAs(newSnapshot);
        assertThat(register.interfaceSnapshotLookups).isEqualTo(2);
    }

    private static final class CountingRedisCacheRegister extends RedisCacheRegister {
        private int interfaceSnapshotLookups;

        @Override
        AnnotationParser.ParsedAnnotations findInterfaceSnapshot(
                Method method, Class<?> targetClass, Set<Class<?>> visited) {
            interfaceSnapshotLookups++;
            return super.findInterfaceSnapshot(method, targetClass, visited);
        }
    }

    private interface AnnotatedService {
        String load(String id);
    }

    private static final class AnnotatedServiceImpl implements AnnotatedService {
        @Override
        public String load(String id) {
            return id;
        }
    }


    @Test
    @DisplayName("direct fallback registration merges snapshots containing native operations")
    void directRegistration_mergesForeignOperationTypes() throws Exception {
        RedisCacheRegister register = new RedisCacheRegister();
        Method method = AgreementService.class.getMethod("first", String.class);
        CacheableOperation.Builder springBuilder = new CacheableOperation.Builder();
        springBuilder.setName("native");
        springBuilder.setCacheNames("cache-a");
        CacheableOperation springOperation = springBuilder.build();
        RedisCacheableOperation previousOperation = RedisCacheableOperation.builder()
                .name("previous")
                .cacheNames("cache-a")
                .ttl(1)
                .build();
        RedisCacheableOperation newestOperation = RedisCacheableOperation.builder()
                .name("newest")
                .cacheNames("cache-a")
                .ttl(2)
                .build();
        register.registerSnapshot(method, AgreementService.class,
                new AnnotationParser.ParsedAnnotations(
                        List.of(springOperation, previousOperation),
                        List.of(previousOperation)));

        assertThatCode(() -> register.register(
                method, AgreementService.class, newestOperation, OperationKind.CACHEABLE))
                .doesNotThrowAnyException();

        AnnotatedElementKey elementKey = new AnnotatedElementKey(method, AgreementService.class);
        RedisCacheableOperation resolved = register.get(
                "cache-a", elementKey, OperationKind.CACHEABLE);
        assertThat(resolved).isSameAs(newestOperation);
        assertThat(register.getSnapshot(method, AgreementService.class).operations())
                .containsExactly(springOperation, newestOperation);
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
