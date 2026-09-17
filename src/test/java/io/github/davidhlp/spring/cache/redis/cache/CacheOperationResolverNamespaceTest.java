package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.expression.AnnotatedElementKey;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 解析命名空间契约 —— 每个链侧操作读<b>自己</b>注解声明的策略。
 *
 * <p>测试通过生产 {@link RedisCacheRegister#registerSnapshot} seam 写入快照，锁定：
 * <ol>
 *   <li>GET → CACHEABLE,PUT / PUT_IF_ABSENT → CACHE_PUT,REMOVE / CLEAN → CACHE_EVICT</li>
 *   <li>写路径在自身命名空间未命中时回退 CACHEABLE —— 读穿透写回由 {@code @RedisCacheable}
 *       方法承担,该场景策略不得丢失</li>
 * </ol>
 */
@DisplayName("CacheOperationResolver 命名空间选择")
class CacheOperationResolverNamespaceTest {

    private static final String CACHE = "ns-cache";

    private RedisCacheRegister register;
    private CacheOperationResolver resolver;
    private AnnotatedElementKey elementKey;
    private Method annotatedMethod;

    @BeforeEach
    void setUp() throws Exception {
        annotatedMethod = CacheOperationResolverNamespaceTest.class
                .getDeclaredMethod("annotatedMethod");
        elementKey = new AnnotatedElementKey(annotatedMethod, CacheOperationResolverNamespaceTest.class);
        register = new RedisCacheRegister();

        MethodMetadataResolver metadata = Mockito.mock(MethodMetadataResolver.class);
        Mockito.when(metadata.currentKey()).thenReturn(elementKey);
        resolver = new CacheOperationResolver(metadata, register);
    }

    /** 仅供 {@link AnnotatedElementKey} 定位用,不会被执行。 */
    @SuppressWarnings("unused")
    private void annotatedMethod() {
    }

    private RedisCacheableOperation cacheable(long ttl) {
        return RedisCacheableOperation.builder()
                .name("m").cacheNames(CACHE).key("k").ttl(ttl).build();
    }

    private RedisCachePutOperation put(long ttl) {
        return RedisCachePutOperation.builder()
                .name("m").cacheNames(CACHE).key("k").ttl(ttl).build();
    }

    private void registerSnapshot(
            org.springframework.cache.interceptor.CacheOperation... operations) {
        List<org.springframework.cache.interceptor.CacheOperation> snapshotOperations =
                Arrays.asList(operations);
        register.registerSnapshot(method(), CacheOperationResolverNamespaceTest.class,
                new AnnotationParser.ParsedAnnotations(snapshotOperations, snapshotOperations));
    }

    @Test
    @DisplayName("PUT 读 @RedisCachePut 自己的声明(修复前恒为 null)")
    void putOperation_resolvesPutDeclaration() {
        registerSnapshot(put(120));

        assertThat(resolver.resolve(CACHE, CacheOperation.PUT)).isSameAs(
                register.get(CACHE, elementKey, OperationKind.CACHE_PUT));
        assertThat(resolver.resolve(CACHE, CacheOperation.PUT).getTtl())
                .as("@RedisCachePut(ttl) 必须真正生效")
                .isEqualTo(120L);
    }

    @Test
    @DisplayName("GET 仍读 @RedisCacheable")
    void getOperation_resolvesCacheableDeclaration() {
        registerSnapshot(cacheable(300));

        assertThat(resolver.resolve(CACHE, CacheOperation.GET).getTtl()).isEqualTo(300L);
    }

    @Test
    @DisplayName("写路径回退:只有 @RedisCacheable 时,PUT/PUT_IF_ABSENT 沿用读侧策略")
    void writeOperation_fallsBackToCacheable() {
        registerSnapshot(cacheable(300));

        assertThat(resolver.resolve(CACHE, CacheOperation.PUT))
                .as("读穿透写回的策略来自 @RedisCacheable 声明,不得丢失")
                .isSameAs(register.get(CACHE, elementKey, OperationKind.CACHEABLE));
        assertThat(resolver.resolve(CACHE, CacheOperation.PUT_IF_ABSENT).getTtl()).isEqualTo(300L);
    }

    @Test
    @DisplayName("同方法两种声明时,读侧声明优先 —— 读穿透写回不得被写侧默认值改写")
    void bothDeclarations_readDeclarationWins() {
        registerSnapshot(cacheable(300), put(60));

        assertThat(resolver.resolve(CACHE, CacheOperation.GET).getTtl()).isEqualTo(300L);
        assertThat(resolver.resolve(CACHE, CacheOperation.PUT).getTtl())
                .as("写回是读操作的一部分:沿用 @RedisCacheable 的 ttl / cacheNullValues")
                .isEqualTo(300L);
    }

    @Test
    @DisplayName("REMOVE/CLEAN 无策略命名空间(不查,也不回退到写侧)")
    void evictOperations_resolveNothing() {
        registerSnapshot(put(60), cacheable(300));

        assertThat(OperationKind.forCacheOperation(CacheOperation.REMOVE)).isNull();
        assertThat(OperationKind.forCacheOperation(CacheOperation.CLEAN)).isNull();
        assertThat(resolver.resolve(CACHE, CacheOperation.REMOVE)).isNull();
        assertThat(resolver.resolve(CACHE, CacheOperation.CLEAN)).isNull();
    }

    private Method method() {
        return annotatedMethod;
    }
}
