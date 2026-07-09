package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation;
import io.github.davidhlp.spring.cache.redis.protection.bloom.BloomGate;
import io.github.davidhlp.spring.cache.redis.protection.breakdown.SyncLockTimeout;
import io.github.davidhlp.spring.cache.redis.protection.breakdown.SyncSupport;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.lang.Nullable;

import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 缓存 loader 路径编排器 — Round 49 / ADR-0062 抽出的 loader-path deep seam.
 *
 * <p>把 {@link RedisProCache#get(Object, Callable)} 的 3 步编排 + 嵌套的 3 个子 seam
 * 整体抽到本类,替换原 {@code RedisProCache} 上的
 * {@code isBloomShortCircuited} / {@code loadValue} / {@code performLockedLoad} 三个
 * package-private seam + 2 个 private helper({@code executeSyncLoad} / {@code resolveSyncTimeout}):
 *
 * <ol>
 *   <li><b>Bloom 短路检查</b> — 经 {@link BloomGate#definiteMiss} 判定「确定不存在」 →
 *       返回 {@link LoadOutcome.BloomShortCircuited};caller 据此自增 miss counter 并返回 null</li>
 *   <li><b>Sync 路径</b> — {@code sync=true} + {@link SyncSupport} 在场 →
 *       {@link SyncSupport#executeSync} + 锁内 {@link #performLockedLoad double-check + load + put};
 *       返回 {@link LoadOutcome.Loaded};锁内 loader 抛异常 →
 *       {@link LoadOutcome.LoadFailed}(由 caller 翻译)</li>
 *   <li><b>Default 路径</b> — 委派 Spring Cache
 *       {@code RedisCache.get(key, Callable)} local-lock →
 *       同样 {@link LoadOutcome.Loaded} / {@link LoadOutcome.LoadFailed}</li>
 * </ol>
 *
 * <p><b>为何独立成类</b>(deletion test):
 * <ul>
 *   <li><b>locality</b>:bloom + sync + locked-load 编排逻辑 + 异常翻译规则全部内聚在一处文件,
 *       无需在 {@code RedisProCache} 与若干 seam 间跳转</li>
 *   <li><b>testability</b>:orchestrator 仅依赖 {@link BloomGate} / {@link SyncSupport} /
 *       {@link SyncLockTimeout} + 4 个 callback(redisKey / doubleCheck / putAfterLoad / defaultLoad);
 *       单测可零 RedisProCache fixture 验证 3 决策分支({@code BloomShortCircuited} /
 *       {@code Loaded} / {@code LoadFailed})</li>
 *   <li><b>leverage</b>:{@code RedisProCache.get(key, loader)} 主体退化为 1 行委派 + switch 翻译;
 *       3 个 package-private seam + 2 个 private helper 从 {@code RedisProCache} 删除</li>
 * </ul>
 *
 * <p><b>callback 协议</b>:orchestrator 不继承 {@code RedisCache},因此需要 cache-specific 操作
 * (key 派生 / 双检 / 写回 / 默认 load)以 callback 形式由 {@code RedisProCache} 注入:
 * <ul>
 *   <li>{@code Function<Object, String> redisKeyFn} — 派生 Redis key 用于 BloomGate 与 SyncSupport</li>
 *   <li>{@code Function<Object, Cache.ValueWrapper> doubleCheckFn} — 锁内双检(走 {@code super.get}
 *       绕过 override,无 metrics)</li>
 *   <li>{@code BiConsumer<Object, Object> putAfterLoad} — 锁内 load 成功后写回(走 override 保留
 *       putTimer + putCounter metrics)</li>
 *   <li>{@code Function<Object, Callable<T>, T> defaultLoadFn} — 非 sync 路径走 Spring Cache
 *       local-lock(走 {@code super.get(key, loader)})</li>
 * </ul>
 *
 * <p><b>状态</b>:无可变状态。3 个共享依赖是 Spring 单例 bean,callback 闭包由 {@code RedisProCache}
 * 在构造期一次性 capture(指向 {@code super.get} / {@code this.put} / {@code super.createCacheKey});
 * {@code cacheName} 在每次 orchestrate() 调用传入。
 *
 * <p><b>非覆盖原行为</b>:异常翻译、键派生({@link CacheKeys} ADR-0011)、{@code -1} 永久缓存哨兵、
 * null-value 缓存等所有现有契约均逐字保留;caller-side switch 的 metric 自增对齐原
 * {@code RedisProCache} 在 {@code isBloomShortCircuited} / outer catch 中自增 miss counter 的
 * 时机(各路径恰好 1 次)。
 */
@Slf4j
public final class LoaderOrchestrator {

    /**
     * 编排结果 — caller 据此走 3 分支:miss 自增返回 null / 直接返回值 / 异常翻译后抛。
     *
     * <p>sealed 设计保证 caller 的 {@code switch} 必须覆盖全部 3 种状态,新增第 4 态
     * (例如 {@code SkippedByBloom})时 Java 21 编译期 pattern matching 强制 caller 处理 —
     * 杜绝「caller 忘了某个状态」的 runtime 漏洞。
     */
    public sealed interface LoadOutcome<T> permits BloomShortCircuited, Loaded, LoadFailed {
    }

    /** Bloom 判定确定 miss(读侧穿透短路);caller 应自增 miss counter 并返回 null。 */
    public record BloomShortCircuited<T>() implements LoadOutcome<T> {
    }

    /** 成功加载;{@code value} 为 loader 产出的值(可为 null — null-value 缓存契约)。 */
    public record Loaded<T>(@Nullable T value) implements LoadOutcome<T> {
    }

    /**
     * loader 抛异常或 default path 异常;{@code cause} 为原始异常
     * (checked 异常已在 {@link #performLockedLoad} 翻译为 {@link Cache.ValueRetrievalException})。
     * caller 应翻译为 {@link RuntimeException} 并自增 miss counter 后抛出。
     */
    public record LoadFailed<T>(Throwable cause) implements LoadOutcome<T> {
    }

    private final BloomGate bloomGate;
    private final SyncSupport syncSupport;
    private final SyncLockTimeout syncLockTimeout;

    public LoaderOrchestrator(@Nullable BloomGate bloomGate,
                              @Nullable SyncSupport syncSupport,
                              @Nullable SyncLockTimeout syncLockTimeout) {
        this.bloomGate = bloomGate;
        this.syncSupport = syncSupport;
        this.syncLockTimeout = syncLockTimeout;
    }

    /**
     * 编排 loader 路径:bloom 短路 → sync 路径(sync=true) → default 路径(Spring local-lock)。
     *
     * @param cacheName        缓存名(供 BloomGate 区分 cache;非 key 派生)
     * @param redisKeyFn       派生 Redis key(callback;caller 传 {@code key -> super.createCacheKey(key)})
     * @param doubleCheckFn    锁内双检的 cache 读原语(callback;caller 传 {@code key -> super.get(key)}
     *                         绕过 override,无 metrics)
     * @param putAfterLoad     锁内 load 成功后写回缓存的 callback(caller 传 {@code (k, v) -> put(k, v)}
     *                         闭包 → 走 override 保留 putTimer + putCounter 指标)
     * @param defaultLoadFn    非 sync 路径走 Spring Cache local-lock 的 callback(caller 传
     *                         {@code (k, l) -> super.get(k, l)} 闭包)
     * @param loader           Spring Cache {@link Callable} loader
     * @param key              缓存 key(用户传入的原始 key;由 redisKeyFn 派生 Redis key)
     * @param operation        方法级 operation(可为 null,视作「无增强属性」→ 不走 bloom / sync)
     * @param <T>              加载结果类型
     * @return {@link LoadOutcome} 三态之一
     */
    @SuppressWarnings("unchecked")
    public <T> LoadOutcome<T> orchestrate(
            String cacheName,
            Function<Object, String> redisKeyFn,
            Function<Object, Cache.ValueWrapper> doubleCheckFn,
            BiConsumer<Object, Object> putAfterLoad,
            DefaultLoadFn<T> defaultLoadFn,
            Callable<T> loader,
            Object key,
            @Nullable RedisCacheableOperation operation) {

        // 1) Bloom 短路检查 — caller 据 BloomShortCircuited 自增 miss counter
        if (isBloomShortCircuited(cacheName, redisKeyFn.apply(key), operation)) {
            return new BloomShortCircuited<>();
        }

        // 2) Sync 路径 — sync=true 且 SyncSupport 在场才走;否则降级 default 路径
        if (operation != null && operation.isSync() && syncSupport != null) {
            return executeSyncLoad(redisKeyFn, doubleCheckFn, putAfterLoad, loader, key, operation);
        }

        // 3) Default 路径 — Spring Cache local-lock 默认契约
        try {
            T value = defaultLoadFn.load(key, loader);
            return new Loaded<>(value);
        } catch (Throwable cause) {
            return new LoadFailed<>(cause);
        }
    }

    /**
     * 默认 load 函数类型 — 用 {@code @FunctionalInterface} 显式声明,使 orchestrator 的 callback
     * 协议自描述而非隐式 {@code BiFunction<Object, Callable<?>, Object>}。
     *
     * <p>采用 {@code interface} 而非 {@code BiFunction} 的原因:泛型签名 {@code <T> T load(...)}
     * 能让 caller 一次性绑定 {@code T},避免 orchestrator 内部做 {@code (T) Object} 强转 —
     * Spring Cache 的 {@code RedisCache.get(Object, Callable<T>)} 本身就是签名
     * {@code <T> T get(Object key, Callable<T> loader)},语义对齐。
     */
    @FunctionalInterface
    public interface DefaultLoadFn<T> {
        T load(Object key, Callable<T> loader);
    }

    /**
     * Bloom 短路检查 — 抽自 {@code RedisProCache.isBloomShortCircuited}(Round 47 ADR-0057 / C3),
     * 行为字节等价,差异:miss counter 自增下沉到 caller(orchestrator 不感知 metric)。
     *
     * <p>前置条件任一缺失(operation null / 未启用 bloom / bloomGate null)→ return false(不短路)。
     * 键派生经 {@link CacheKeys#fromRedisKey} 与链层 {@code BloomFilterHandler.add} 同源,杜绝
     * actualKey/redisKey 漂移缺陷(ADR-0011)。
     */
    private boolean isBloomShortCircuited(String cacheName, String redisKey,
                                          @Nullable RedisCacheableOperation operation) {
        if (operation == null || !operation.isUseBloomFilter() || bloomGate == null) {
            return false;
        }
        String bloomKey = CacheKeys.fromRedisKey(cacheName, redisKey).bloomKey();
        return bloomGate.definiteMiss(cacheName, bloomKey);
    }

    /**
     * Sync 路径编排 — 抽自 {@code RedisProCache.executeSyncLoad} + {@code resolveSyncTimeout}。
     * 职责:解析 timeout → 委派 {@link SyncSupport#executeSync} → 锁内 {@link #performLockedLoad}。
     * 锁内 loader 抛异常 → {@link LoadOutcome.LoadFailed}(cause 已被 performLockedLoad 翻译)。
     */
    @SuppressWarnings("unchecked")
    private <T> LoadOutcome<T> executeSyncLoad(
            Function<Object, String> redisKeyFn,
            Function<Object, Cache.ValueWrapper> doubleCheckFn,
            BiConsumer<Object, Object> putAfterLoad,
            Callable<T> loader,
            Object key,
            RedisCacheableOperation operation) {
        long timeout = syncLockTimeout != null
                ? syncLockTimeout.resolveSeconds(operation)
                : SyncLockTimeout.DEFAULT_LOCK_TIMEOUT_SECONDS;
        try {
            String lockKey = redisKeyFn.apply(key);
            T loaded = (T) syncSupport.executeSync(
                    lockKey,
                    () -> performLockedLoad(doubleCheckFn, putAfterLoad, loader, key),
                    timeout);
            return new Loaded<>(loaded);
        } catch (Throwable cause) {
            return new LoadFailed<>(cause);
        }
    }

    /**
     * 持锁后单飞加载契约 — 抽自 {@code RedisProCache.performLockedLoad}(Round 47 ADR-0057),
     * 行为字节等价:
     *
     * <ol>
     *   <li><b>double-check</b>:{@code doubleCheckFn.apply(key)} 已有值 → 直接返回(走
     *       {@code super.get(key)} 无 metrics,因 metrics 在外层 {@code get(key, loader)} 记录)</li>
     *   <li><b>load + put</b>:cache miss → 调 {@code loader.call()},无论 null 与否都
     *       {@link BiConsumer#accept 写回}(由 RedisCache 配置处理 null-value 缓存契约)</li>
     *   <li><b>异常翻译</b>:loader 抛 checked exception → 翻译为
     *       {@link Cache.ValueRetrievalException}(Spring 抽象层契约);RuntimeException 也走同一翻译路径
     *       以保持原 lambda 字节级行为</li>
     * </ol>
     */
    @SuppressWarnings("unchecked")
    <T> T performLockedLoad(Function<Object, Cache.ValueWrapper> doubleCheckFn,
                            BiConsumer<Object, Object> putAfterLoad,
                            Callable<T> loader,
                            Object key) {
        // 双重检查:可能在等待锁期间其他线程已加载。doubleCheckFn 由 caller 绑为 super.get(key),
        // 完整保留 null-value round-trip 语义(NullValue → null)。
        Cache.ValueWrapper existingValue = doubleCheckFn.apply(key);
        if (existingValue != null) {
            return (T) existingValue.get();
        }

        // 执行加载 + 写回(无论 null 与否都 put)
        try {
            T loaded = loader.call();
            putAfterLoad.accept(key, loaded);
            return loaded;
        } catch (Exception ex) {
            throw new Cache.ValueRetrievalException(key, loader, ex);
        }
    }
}
