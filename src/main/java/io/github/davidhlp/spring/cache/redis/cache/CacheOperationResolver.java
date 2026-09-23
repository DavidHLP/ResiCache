package io.github.davidhlp.spring.cache.redis.cache;






import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.model.CachePolicyView;
import java.util.Map;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.expression.AnnotatedElementKey;
import org.springframework.lang.Nullable;

/**
 * 当前方法缓存操作元数据解析器 —— 收敛 {@code RedisProCache} 与
 * {@code RedisProCacheWriter} 两处 4 行镜像 lookup 协议的 deep seam。
 *
 * <p><b>problem</b>:"读 ThreadLocal AnnotatedElementKey → 查 RedisCacheRegister"协议若在
 * {@code RedisProCache} 与 {@code RedisProCacheWriter} 各持一份,两处 4 行近镜像任一写错
 * (log tag 漂移、查询命名空间不一致),另一边静默失效。
 *
 * <p><b>solution</b>:本类把"读 ThreadLocal key → 查 register"协议收口到单一 seam,
 * 两个调用方简化为 {@code resolver.resolve(cacheName, operation)},命名空间选择 + 日志在一处。
 *
 * <p><b>deletion test</b>:删本类 → 两调用方各自重新实现 4 行镜像;ThreadLocal 协议与
 * 日志形式在两处独立漂移。本 seam 挣得起存在代价。
 *
 * <p><b>Spring 装配</b>:由 {@code RedisProCacheConfiguration} 显式注册并按类型
 * back-off；内部依赖 {@link MethodMetadataResolver} 与
 * {@link RedisCacheRegister}。
 *
 * <p><b>线程安全</b>:方法无状态;并发安全由底层 {@link RedisCacheRegister}
 * (内部 concurrent map) 与 {@link MethodMetadataResolver}
 * (ThreadLocal 隔离) 保证。
 *
 * @see io.github.davidhlp.spring.cache.redis.cache.RedisProCache
 * @see io.github.davidhlp.spring.cache.redis.cache.RedisProCacheWriter
 */
@Slf4j
class CacheOperationResolver {

    private final MethodMetadataResolver methodResolver;
    private final RedisCacheRegister register;

    /**
     * Spring 装配构造入口:双依赖必传。
     *
     * <p>「无元数据」不是本类的构造模式:无当前方法上下文时
     * {@link #resolve(String, CacheOperation)} 读到的 ThreadLocal key 为 null,
     * 直接返回 null(见 {@link MethodMetadataResolver#currentKey()})。
     */
    @Autowired
    public CacheOperationResolver(MethodMetadataResolver methodResolver,
                                  RedisCacheRegister register) {
        this.methodResolver = methodResolver;
        this.register = register;
    }

    /**
     * 解析指定缓存名对应的方法级缓存操作配置 —— 单一收敛 seam。
     *
     * <p>流程:
     * <ol>
     *   <li>读 ThreadLocal AnnotatedElementKey;为 null → 返回 null(无当前方法上下文)</li>
     *   <li>查 register;未命中 → 记 debug 日志,返回 null</li>
     * </ol>
     *
     * <p><b>读侧声明优先</b>:方法声明了 {@code @RedisCacheable} 时,它就是该方法<b>及其读穿透
     * 写回</b>的唯一策略来源 —— 写回是读操作的一部分,必须沿用同一份 ttl / cacheNullValues /
     * bloom 声明,不能被写侧注解的默认值悄悄改掉。写路径只有在方法<em>没有</em>
     * {@code @RedisCacheable} 声明时才读 {@code @RedisCachePut},即写侧注解只对「只写」方法生效。
     *
     * <p>REMOVE / CLEAN 无方法级策略可解析(见 {@link OperationKind#forCacheOperation}),
     * 直接返回 null,不做必然未命中的查询。
     *
     * @param cacheName 缓存名(由调用方解析为 {@link io.github.davidhlp.spring.cache.redis.cache.RedisProCache#getName()}
     *                   或 Spring Cache 抽象传入)
     * @param operation 当前链侧操作类型(决定查询哪个命名空间)
     * @return 命中的方法级策略视图;未命中返回 null
     */
    @Nullable
    public CachePolicyView.Source resolve(@Nullable String cacheName, CacheOperation operation) {
        AnnotatedElementKey key = methodResolver.currentKey();
        if (key == null) {
            return null;
        }

        OperationKind operationKind = OperationKind.forCacheOperation(operation);
        if (operationKind == null) {
            return null;
        }

        CachePolicyView.Source resolved = lookup(cacheName, key, OperationKind.CACHEABLE);
        if (resolved == null && operation.isWrite()) {
            // 方法没有 @RedisCacheable 声明:只有 @RedisCachePut 的「只写」方法读自己的命名空间
            resolved = lookup(cacheName, key, operationKind);
        }

        if (resolved == null) {
            log.debug("No metadata resolved for cacheName={}, elementKey={}, operation={}",
                    cacheName, key, operation);
        }
        return resolved;
    }

    /**
     * 单次命名空间查询 —— 未命中,或类型不实现 {@link CachePolicyView.Source} 时返回 null。
     */
    @Nullable
    private CachePolicyView.Source lookup(String cacheName, AnnotatedElementKey key,
                                          OperationKind kind) {
        org.springframework.cache.interceptor.CacheOperation registered =
                register.get(cacheName, key, kind);
        return registered instanceof CachePolicyView.Source source ? source : null;
    }

    /**
     * Captures metadata on the calling thread before work crosses an async boundary.
     */
    @Nullable
    public MethodSnapshot capture() {
        return methodResolver.capture();
    }

    /**
     * Runs work with a submitter-thread snapshot and restores worker state in finally.
     */
    public <T> T runWithSnapshot(
            @Nullable MethodSnapshot snapshot,
            @Nullable Map<String, String> mdcSnapshot,
            Supplier<T> work) {
        return methodResolver.runWithSnapshot(snapshot, mdcSnapshot, work);
    }
}
