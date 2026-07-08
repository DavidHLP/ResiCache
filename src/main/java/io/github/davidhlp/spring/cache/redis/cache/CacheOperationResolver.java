package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.chain.MethodMetadataResolver;
import io.github.davidhlp.spring.cache.redis.operation.OperationKind;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheRegister;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.expression.AnnotatedElementKey;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 当前方法缓存操作元数据解析器 —— 收敛 {@link RedisProCache#lookupOperation} 与
 * {@link RedisProCacheWriter#resolveOperation} 两处 4 行镜像 lookup 协议的 deep seam。
 *
 * <p><b>problem (背景)</b>:原架构下 {@link RedisProCache} 与 {@link RedisProCacheWriter}
 * 各持有一份"读 ThreadLocal AnnotatedElementKey → 查 RedisCacheRegister"的协议。
 * 两处 4 行近镜像:
 * <ul>
 *   <li>{@code RedisProCache.lookupOperation}:ThreadLocal key 为 null → 返回 null;否则查 register</li>
 *   <li>{@code RedisProCacheWriter.resolveOperation}:ThreadLocal key 为 null → 返回 null;否则查 register + null 日志</li>
 * </ul>
 * 两处任一写错(null-safe 漏检查、log tag 漂移、key derivation 不一致),另一边静默失效。
 *
 * <p><b>solution</b>:本类把"读 ThreadLocal key → 查 register"协议收口到单一 seam,
 * 两个调用方简化为 {@code resolver.resolve(cacheName)},null-safe + 日志在一处。
 *
 * <p><b>deletion test</b>:删本类 → 两调用方各自重新实现 4 行镜像;ThreadLocal 协议与
 * 日志形式在两处独立漂移。本 seam 挣得起存在代价。
 *
 * <p><b>Spring 装配</b>:{@code @Component} 让 Spring 自动注入
 * {@link MethodMetadataResolver} 与 {@link RedisCacheRegister}(两者本身也是
 * {@code @Component});用户可通过 {@code @Bean @ConditionalOnMissingBean}
 * 顶替本类。
 *
 * <p><b>线程安全</b>:方法无状态;并发安全由底层 {@link RedisCacheRegister}
 * (内部 {@code TwoListLRU}) 与 {@link MethodMetadataResolver}
 * (ThreadLocal 隔离) 保证。
 *
 * @see RedisProCache#lookupOperation()
 * @see RedisProCacheWriter#resolveOperation(String)
 */
@Slf4j
@Component
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
     * 异步边界 ThreadLocal + MDC 快照透传 — ADR-0057 在本 seam 上的扩展职责。
     *
     * <p>{@link RedisProCacheWriter#retrieve} 与 {@code store} 走 commonPool 异步线程,
     * 异步线程读不到原提交线程的 ThreadLocal {@code AnnotatedElementKey} 与 MDC。
     * 原架构下本职责由 {@link MethodMetadataResolver#runWithSnapshot} 直接承担,
     * {@code RedisProCacheWriter} 持有 {@code MethodMetadataResolver} 字段调用。
     *
     * <p>收敛到本 seam 后,writer 不再直接持 {@code MethodMetadataResolver};此处
     * 委派给内嵌 resolver,行为字节级等价(若内嵌 resolver 为 null,fallback 到
     * 直接同步执行 — 异步语义降级为同步,但行为正确)。
     *
     * @param work 要在快照上下文中执行的工作
     * @param <T>  返回值类型
     * @return work 的返回值
     */
    public <T> T runWithSnapshot(Supplier<T> work) {
        if (methodResolver == null) {
            return work.get();
        }
        return methodResolver.runWithSnapshot(work);
    }
}
