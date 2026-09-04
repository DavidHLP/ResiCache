package io.github.davidhlp.spring.cache.redis.cache.loader;

import io.github.davidhlp.spring.cache.redis.chain.metadata.MethodMetadataResolver;
import io.github.davidhlp.spring.cache.redis.chain.metadata.MethodSnapshot;
import io.github.davidhlp.spring.cache.redis.operation.OperationKind;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheRegister;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.expression.AnnotatedElementKey;
import org.springframework.lang.Nullable;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 当前方法缓存操作元数据解析器 —— 收敛 {@link RedisProCache#lookupOperation} 与
 * {@link RedisProCacheWriter#resolveOperation} 两处 4 行镜像 lookup 协议的 deep seam。
 *
 * <p><b>problem</b>:"读 ThreadLocal AnnotatedElementKey → 查 RedisCacheRegister"协议若在
 * {@code RedisProCache} 与 {@code RedisProCacheWriter} 各持一份,两处 4 行近镜像任一写错
 * (null-safe 漏检查、log tag 漂移、key derivation 不一致),另一边静默失效。
 *
 * <p><b>solution</b>:本类把"读 ThreadLocal key → 查 register"协议收口到单一 seam,
 * 两个调用方简化为 {@code resolver.resolve(cacheName)},null-safe + 日志在一处。
 *
 * <p><b>deletion test</b>:删本类 → 两调用方各自重新实现 4 行镜像;ThreadLocal 协议与
 * 日志形式在两处独立漂移。本 seam 挣得起存在代价。
 *
 * <p><b>Spring 装配</b>:由 {@code RedisProCacheConfiguration} 显式注册并按类型
 * back-off；内部依赖 {@link MethodMetadataResolver} 与
 * {@link RedisCacheRegister}。
 *
 * <p><b>线程安全</b>:方法无状态;并发安全由底层 {@link RedisCacheRegister}
 * (内部 {@code TwoListLRU}) 与 {@link MethodMetadataResolver}
 * (ThreadLocal 隔离) 保证。
 *
 * @see RedisProCache#lookupOperation()
 * @see RedisProCacheWriter#resolveOperation(String)
 */
@Slf4j
public class CacheOperationResolver {

    private final MethodMetadataResolver methodResolver;
    private final RedisCacheRegister register;

    /**
     * Spring 装配构造入口:双依赖必传。
     *
     * <p>允许 {@code register} 为 null(测试场景关闭元数据查找,fallback 到 null),
     * {@code methodResolver} 为 null 同理(null resolver 直接短路返回 null,
     * 等价于"无 ThreadLocal 上下文")。
     */
    @Autowired
    public CacheOperationResolver(@Nullable MethodMetadataResolver methodResolver,
                                  @Nullable RedisCacheRegister register) {
        this.methodResolver = methodResolver;
        this.register = register;
    }

    /**
     * 解析指定缓存名对应的方法级缓存操作配置 —— 单一收敛 seam。
     *
     * <p>流程:
     * <ol>
     *   <li>若 {@link MethodMetadataResolver} 为 null,直接返回 null(无 ThreadLocal 上下文)</li>
     *   <li>读 ThreadLocal AnnotatedElementKey;为 null → 返回 null(无当前方法上下文)</li>
     *   <li>若 {@link RedisCacheRegister} 为 null,返回 null(测试关闭 register)</li>
     *   <li>查 register;未命中 → 记 debug 日志,返回 null</li>
     * </ol>
     *
     * @param cacheName 缓存名(由调用方解析为 {@link io.github.davidhlp.spring.cache.redis.cache.RedisProCache#getName()}
     *                   或 Spring Cache 抽象传入)
     * @return 命中的 {@link RedisCacheableOperation};未命中返回 null
     */
    @Nullable
    public RedisCacheableOperation resolve(@Nullable String cacheName) {
        if (methodResolver == null) {
            return null;
        }
        AnnotatedElementKey key = methodResolver.currentKey();
        if (key == null) {
            return null;
        }
        if (register == null) {
            return null;
        }
        RedisCacheableOperation operation = register.get(cacheName, key, OperationKind.CACHEABLE);
        if (operation == null) {
            log.debug("No metadata resolved for cacheName={}, elementKey={}",
                    cacheName, key);
        }
        return operation;
    }

    /**
     * Captures metadata on the calling thread before work crosses an async boundary.
     */
    @Nullable
    public MethodSnapshot capture() {
        return methodResolver == null ? null : methodResolver.capture();
    }

    /**
     * Runs work with a submitter-thread snapshot and restores worker state in finally.
     */
    public <T> T runWithSnapshot(
            @Nullable MethodSnapshot snapshot,
            @Nullable Map<String, String> mdcSnapshot,
            Supplier<T> work) {
        if (methodResolver == null) {
            return work.get();
        }
        return methodResolver.runWithSnapshot(snapshot, mdcSnapshot, work);
    }

    /**
     * Compatibility overload for synchronous callers. Async callers must use
     * {@link #capture()} before queueing work.
     */
    public <T> T runWithSnapshot(Supplier<T> work) {
        if (methodResolver == null) {
            return work.get();
        }
        return methodResolver.runWithSnapshot(work);
    }
}
