package io.github.davidhlp.spring.cache.redis.operation;

import io.github.davidhlp.spring.cache.redis.operation.eviction.EvictionStats;
import io.github.davidhlp.spring.cache.redis.operation.eviction.TwoListLRU;

import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.context.expression.AnnotatedElementKey;

import java.lang.reflect.Method;

/**
 * Redis 缓存注册器 —— ADR-0059 / Round 45 收敛后的精简形态。
 *
 * <p><b>原 API(6 公开方法)</b>:
 * <ul>
 *   <li>{@code registerCacheableOperation} / {@code getCacheableOperation}</li>
 *   <li>{@code registerCacheEvictOperation} / {@code getCacheEvictOperation}</li>
 *   <li>{@code registerCachePutOperation} / {@code getCachePutOperation}</li>
 * </ul>
 * 三对方法各 2-3 行包 {@code registerInternal}/{@code getInternal},内部用 stringly-typed
 * tag ({@code "CACHE"} / {@code "EVICT"} / {@code "PUT"}) 区分命名空间。注释自述
 * "为方法引用稳定保留"—— 但新增第 4 种操作类型须改 register 1 处 + 注解处理器 N 处,
 * 6 方法 API 难以统一维护。
 *
 * <p><b>收敛后 API(2 公开方法)</b>:
 * <ul>
 *   <li>{@link #register(Method, Class, CacheOperation, OperationKind)} —— 1 个 seam 取代 3 个</li>
 *   <li>{@link #get(String, AnnotatedElementKey, OperationKind)} —— 1 个 seam 取代 3 个</li>
 * </ul>
 * 调用方传入 {@link OperationKind} 替代方法名;tag 字符串 + 期望 operation 类型
 * 均由 enum 派生,杜绝 stringly-typed 漂移。
 *
 * <p><b>向后兼容</b>:LRU key 中的 tag 字符串({@code "CACHE"} / {@code "PUT"} / {@code "EVICT"})
 * 与原 stringly-typed tag 字节级等价 —— 已部署的 register 数据(若被持久化,虽然
 * 当前实现是 in-memory)可平滑迁移。
 *
 * <p><b>查找键</b> = {@code <tag>:<cacheName>:<elementKey.toString()>},由
 * {@link #buildKey(String, AnnotatedElementKey, String)} 统一构造。operation 自身的
 * {@code key} 字段(SpEL/字面量)是运行时缓存键的来源,与这里的注册查找键无关。
 *
 * <p><strong>策略层删除</strong>(Round 26 之前已迁移):原 105 SLOC 的
 * {@code TwoListEvictionStrategy} 仅做 1:1 委托,本类<em>直接</em>绑 {@link TwoListLRU},
 * 省略中间包装。
 */
@Slf4j
public class RedisCacheRegister {

    /** 缓存操作淘汰策略(直接 TwoListLRU,无策略包装) */
    private final TwoListLRU<String, CacheOperation> operationLru;
    private final int maxActiveSize;
    private final int maxInactiveSize;

    public RedisCacheRegister() {
        this(2048, 1024);
    }

    public RedisCacheRegister(int maxActiveSize, int maxInactiveSize) {
        this.maxActiveSize = maxActiveSize;
        this.maxInactiveSize = maxInactiveSize;
        this.operationLru = new TwoListLRU<>(maxActiveSize, maxInactiveSize);
    }

    // ============================ 注册（ADR-0059 单一 seam）============================

    /**
     * 注册一个缓存操作 —— ADR-0059 收敛后的唯一 seam,取代原 3 个 register 方法。
     *
     * <p>按 {@code operation.getCacheNames()} 逐个 cacheName 写入 LRU,key 形如
     * {@code <kind.tag()>:<cacheName>:<elementKey>}。{@code kind} 同时决定 tag 字符串
     * 与期望 operation 类型,无需调用方额外传入。
     *
     * <p>类型校验:若 {@code operation.getClass()} 与 {@code kind.operationType()} 不一致,
     * 记 ERROR 日志并跳过 —— 防御性;正常调用路径下注解处理器构造的 operation 类型总是匹配。
     *
     * @param method      方法
     * @param targetClass 目标类
     * @param operation   要注册的 operation
     * @param kind        操作种类
     */
    public void register(Method method, Class<?> targetClass,
                         CacheOperation operation, OperationKind kind) {
        if (!kind.operationType().isInstance(operation)) {
            log.error("Operation kind mismatch: kind={} expects {} but got {}",
                    kind, kind.operationType().getSimpleName(),
                    operation.getClass().getSimpleName());
            return;
        }
        AnnotatedElementKey elementKey = new AnnotatedElementKey(method, targetClass);
        for (String cacheName : operation.getCacheNames()) {
            String key = buildKey(cacheName, elementKey, kind.tag());
            operationLru.put(key, operation);
            log.debug("Registered {} operation: cacheName={}, elementKey={}, stats={}",
                    kind.tag(), cacheName, elementKey, snapshotStats());
        }
    }

    // ============================ 查询（ADR-0059 单一 seam）============================

    /**
     * 查询一个缓存操作 —— ADR-0059 收敛后的唯一 seam,取代原 3 个 get 方法。
     *
     * <p>按 {@code kind.tag()} 派生查找键,从 LRU 取出,做 instance-of 安全转型后返回。
     * 类型不匹配(同 cacheName+elementKey 但不同 kind,或 LRU 槽位被另一种 kind 占用)
     * 视为未命中,返回 null。
     *
     * @param name       cacheName
     * @param elementKey 查找键维度
     * @param kind       操作种类
     * @param <O>        返回类型(与 {@code kind.operationType()} 兼容)
     * @return 命中的 operation;未命中返回 null
     */
    @SuppressWarnings("unchecked")
    public <O extends CacheOperation> O get(String name, AnnotatedElementKey elementKey, OperationKind kind) {
        String operationKey = buildKey(name, elementKey, kind.tag());
        CacheOperation operation = operationLru.get(operationKey);
        if (kind.operationType().isInstance(operation)) {
            return (O) operation;
        }
        log.debug("{} operation not found: name={}, elementKey={}", kind.tag(), name, elementKey);
        return null;
    }

    // ============================ 统计 ============================

    /** 当前淘汰策略的统计快照（封装 {@link EvictionStats#of} 调用） */
    public EvictionStats snapshotStats() {
        return EvictionStats.of(operationLru, maxActiveSize, maxInactiveSize);
    }

    // ============================ 键构造 ============================

    /** 构建操作查找键：{@code <tag>:<cacheName>:<elementKey>} —— tag 由 kind 派生 */
    private String buildKey(String name, AnnotatedElementKey elementKey, String tag) {
        String key = elementKey.toString();
        StringBuilder sb = new StringBuilder(tag.length() + name.length() + key.length() + 2);
        sb.append(tag).append(':').append(name).append(':').append(key);
        return sb.toString();
    }
}
