package io.github.davidhlp.spring.cache.redis.cache;






import io.github.davidhlp.spring.cache.redis.chain.model.CachePolicyView;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.lang.Nullable;

/**
 * 缓存 loader 路径编排器 — loader-path deep seam.
 *
 * <p>承接 {@link RedisProCache#get(Object, Callable)} 与
 * {@link RedisProCacheWriter#get(String, byte[], java.util.function.Supplier, java.time.Duration, boolean)}
 * 的 read-through 编排:
 *
 * <ol>
 *   <li><b>Bloom 短路检查</b> — 经 {@link BloomGate#definiteMiss} 判定「确定不存在」 →
 *       返回 {@link LoadOutcome.BloomShortCircuited};caller 据此自增 miss counter 并返回 null</li>
 *   <li><b>Sync 路径</b> — {@code sync=true} + {@link SyncSupport} 在场 →
 *       {@link SyncSupport#executeSync} + 锁内 {@link #readThrough read-through protocol};
 *       返回 {@link LoadOutcome.Loaded};锁内 loader 抛异常 →
 *       {@link LoadOutcome.LoadFailed}(由 caller 翻译)</li>
 *   <li><b>Default 路径</b> — 同一 read-through 协议({@link #readThrough}),不带分布式锁;
 *       同样 {@link LoadOutcome.Loaded} / {@link LoadOutcome.LoadFailed}</li>
 * </ol>
 *
 * <p><b>为何独立成类</b>(deletion test):
 * <ul>
 *   <li><b>locality</b>:bloom + sync + load 协议 + 异常翻译规则全部内聚在一处文件,
 *       无需在 {@code RedisProCache} 与若干 seam 间跳转</li>
 *   <li><b>testability</b>:orchestrator 仅依赖 {@link BloomGate} / {@link SyncSupport} /
 *       {@link SyncLockTimeout} + 3 个 callback(redisKey / doubleCheck / putAfterLoad);
 *       单测可零 RedisProCache fixture 验证决策分支({@code BloomShortCircuited} /
 *       {@code Loaded} / {@code LoadedWithWriteBackFailure} / {@code LoadFailed})</li>
 *   <li><b>leverage</b>:{@code RedisProCache.get(key, loader)} 主体仅 1 行委派 + switch 翻译</li>
 * </ul>
 *
 * <p><b>callback 协议</b>:orchestrator 不继承 {@code RedisCache},因此需要 cache-specific 操作
 * (key 派生 / 双检 / 写回)以 callback 形式由 {@code RedisProCache} 注入;writer 入口则直接
 * 使用静态 {@link #readThrough} 并传入字节适配:
 * <ul>
 *   <li>{@code Function<Object, String> redisKeyFn} — 派生 Redis key 用于 BloomGate 与 SyncSupport</li>
 *   <li>{@code Function<Object, Cache.ValueWrapper> doubleCheckFn} — 缓存读原语(走
 *       {@code super.get},含链 GET / bloom 短路 / null round-trip,无 metrics — metrics 在外层记)</li>
 *   <li>{@code BiConsumer<Object, Object> putAfterLoad} — load 成功后写回(走
 *       {@code RedisProCache.put} override,保留 putTimer + putCounter metrics)</li>
 * </ul>
 *
 * <p><b>一条协议,两个入口</b>:cache 与 writer 入口都走 {@link #readThrough} —
 * 同一套 double-check 语义、同一套写回容错和单点 WARN。两者只在读值表示与 loader
 * 异常翻译上不同;sync 路径另外把 cache 协议跑在 {@link SyncSupport} 的分布式锁内。
 *
 * <p><b>状态</b>:无可变状态。3 个共享依赖和 3 个 cache-specific callback 由
 * {@code RedisProCache} 在构造期一次性绑定(指向 {@code super.createCacheKey} /
 * {@code super.get} / {@code this.put});每次调用只需传入 {@code cacheName}、key、
 * loader 和 operation。
 *
 * <p><b>契约保真</b>:异常翻译、键派生({@link CacheKeys})、{@code -1} 永久缓存哨兵、
 * null-value 缓存等契约逐字保留;caller-side switch 的 metric 自增保证各路径恰好 1 次 miss 计数。
 */
@Slf4j
final class LoaderOrchestrator {

    /**
     * 编排结果 — caller 据此走 4 分支:miss 自增返回 null / 直接返回值 / 写回失败
     * 仍返回值 / 异常翻译后抛。
     *
     * <p>sealed 设计保证 caller 的 {@code switch} 必须覆盖全部状态,新增状态时
     * Java 21 编译期 pattern matching 强制 caller 处理 —— 杜绝「caller 忘了某个
     * 状态」的 runtime 漏洞。
     */
    public sealed interface LoadOutcome<T> permits BloomShortCircuited, Loaded,
            LoadedWithWriteBackFailure, LoadFailed {
    }

    /** Bloom 判定确定 miss(读侧穿透短路);caller 应自增 miss counter 并返回 null。 */
    public record BloomShortCircuited<T>() implements LoadOutcome<T> {
    }

    /**
     * 成功加载且缓存写回成功;{@code value} 为 loader 产出的值
     * (可为 null — null-value 缓存契约)。
     */
    public record Loaded<T>(@Nullable T value) implements LoadOutcome<T> {
    }

    /**
     * loader 成功,但缓存写回失败(ADR-02 availability-first)。
     *
     * <p>{@code value} 仍为 loader 产出的业务值,必须返回给调用方;{@code cause}
     * 为写回失败的原始异常,供 caller 记录诊断与失败指标,不覆盖返回值。
     * 锁内 double-check 命中(他线程已加载)不会产生本 outcome —— 该路径无写回。
     */
    public record LoadedWithWriteBackFailure<T>(@Nullable T value, Throwable cause)
            implements LoadOutcome<T> {
    }

    /**
     * loader 抛异常或 default path 异常;{@code cause} 为原始异常
     * (cache 入口由 {@link #readThrough} 翻译为 {@link Cache.ValueRetrievalException},
     * writer 入口保留原始异常)。
     * caller 应翻译为 {@link RuntimeException} 并自增 miss counter 后抛出。
     */
    public record LoadFailed<T>(Throwable cause) implements LoadOutcome<T> {
    }

    private final BloomGate bloomGate;
    private final SyncSupport syncSupport;
    private final SyncLockTimeout syncLockTimeout;
    private final Function<Object, String> boundRedisKeyFn;
    private final Function<Object, Cache.ValueWrapper> boundDoubleCheckFn;
    private final BiConsumer<Object, Object> boundPutAfterLoad;

    public LoaderOrchestrator(@Nullable BloomGate bloomGate,
                              @Nullable SyncSupport syncSupport,
                              @Nullable SyncLockTimeout syncLockTimeout) {
        this(bloomGate, syncSupport, syncLockTimeout, null, null, null);
    }

    /**
     * 生产构造:一次性绑定 cache-specific 回调,隐藏 loader 编排的 callback 组装细节。
     */
    LoaderOrchestrator(
            @Nullable BloomGate bloomGate,
            @Nullable SyncSupport syncSupport,
            @Nullable SyncLockTimeout syncLockTimeout,
            @Nullable Function<Object, String> redisKeyFn,
            @Nullable Function<Object, Cache.ValueWrapper> doubleCheckFn,
            @Nullable BiConsumer<Object, Object> putAfterLoad) {
        this.bloomGate = bloomGate;
        this.syncSupport = syncSupport;
        this.syncLockTimeout = syncLockTimeout;
        this.boundRedisKeyFn = redisKeyFn;
        this.boundDoubleCheckFn = doubleCheckFn;
        this.boundPutAfterLoad = putAfterLoad;
    }

    /**
     * 生产调用入口:使用构造期绑定的 callbacks 执行 loader 路径。
     *
     * @param cacheName 缓存名
     * @param loader    Spring Cache loader
     * @param key       用户缓存 key
     * @param operation 方法级缓存 operation,可为 null
     * @param <T>       加载结果类型
     * @return 编排结果
     */
    <T> LoadOutcome<T> orchestrate(
            String cacheName,
            Callable<T> loader,
            Object key,
            @Nullable CachePolicyView.Source operation) {
        if (boundRedisKeyFn == null
                || boundDoubleCheckFn == null
                || boundPutAfterLoad == null) {
            throw new IllegalStateException("LoaderOrchestrator callbacks are not bound");
        }
        return orchestrate(
                cacheName,
                boundRedisKeyFn,
                boundDoubleCheckFn,
                boundPutAfterLoad,
                loader,
                key,
                operation);
    }

    /**
     * 编排 loader 路径:bloom 短路 → sync 路径(sync=true) → default 路径(同一协议,无锁)。
     *
     * @param cacheName        缓存名(供 BloomGate 区分 cache;非 key 派生)
     * @param redisKeyFn       派生 Redis key(callback;caller 传 {@code key -> super.createCacheKey(key)})
     * @param doubleCheckFn    缓存读原语(callback;caller 传 {@code key -> super.get(key)};
     *                         含链 GET + null round-trip,无 metrics — metrics 在外层记)
     * @param putAfterLoad     load 成功后写回缓存的 callback(caller 传 {@code (k, v) -> put(k, v)}
     *                         闭包 → 走 override 保留 putTimer + putCounter 指标)
     * @param loader           Spring Cache {@link Callable} loader
     * @param key              缓存 key(用户传入的原始 key;由 redisKeyFn 派生 Redis key)
     * @param operation        方法级策略视图(可为 null,视作「无增强属性」→ 不走 bloom / sync)
     * @param <T>              加载结果类型
     * @return {@link LoadOutcome} 四态之一
     */
    @SuppressWarnings("unchecked")
    public <T> LoadOutcome<T> orchestrate(
            String cacheName,
            Function<Object, String> redisKeyFn,
            Function<Object, Cache.ValueWrapper> doubleCheckFn,
            BiConsumer<Object, Object> putAfterLoad,
            Callable<T> loader,
            Object key,
            @Nullable CachePolicyView.Source operation) {

        // 1) Bloom 短路检查 — caller 据 BloomShortCircuited 自增 miss counter
        if (isBloomShortCircuited(cacheName, redisKeyFn.apply(key), operation)) {
            return new BloomShortCircuited<>();
        }

        // 2) Sync 路径 — sync=true 且 SyncSupport 在场才走;否则降级 default 路径
        if (operation != null && operation.isSync() && syncSupport != null) {
            return executeSyncLoad(cacheName, redisKeyFn, doubleCheckFn, putAfterLoad,
                    loader, key, operation);
        }

        // 3) Default 路径 — 与 sync 路径同一 load 协议,只是不跑在分布式锁内
        return executeLoad(cacheName, doubleCheckFn, putAfterLoad, loader, key);
    }

    /**
     * Sync 路径编排。
     * 职责:解析 timeout → 委派 {@link SyncSupport#executeSync} → 锁内 {@link #executeLoad}
     * (同一 load 协议 + 同一容错规则);锁基础设施失败(未获取锁 / 中断 / follower 超时)
     * → {@link LoadOutcome.LoadFailed}。
     */
    private <T> LoadOutcome<T> executeSyncLoad(
            String cacheName,
            Function<Object, String> redisKeyFn,
            Function<Object, Cache.ValueWrapper> doubleCheckFn,
            BiConsumer<Object, Object> putAfterLoad,
            Callable<T> loader,
            Object key,
            CachePolicyView.Source operation) {
        long timeout = syncLockTimeout != null
                ? syncLockTimeout.resolveSeconds(operation)
                : SyncLockTimeout.DEFAULT_LOCK_TIMEOUT_SECONDS;
        try {
            String lockKey = redisKeyFn.apply(key);
            return syncSupport.executeSync(
                    lockKey,
                    () -> executeLoad(cacheName, doubleCheckFn, putAfterLoad, loader, key),
                    timeout);
        } catch (Throwable cause) {
            return new LoadFailed<>(cause);
        }
    }

    /**
     * 缓存侧 loader 入口对共享协议的适配。
     *
     * <p>{@link Cache.ValueWrapper} 代表读侧的命中状态,而 loader 结果是 wrapper 内的业务值;
     * Spring 的 loader 异常在这里显式翻译,原始字节 writer 则传入身份翻译。
     */
    @SuppressWarnings("unchecked")
    private <T> LoadOutcome<T> executeLoad(
            String cacheName,
            Function<Object, Cache.ValueWrapper> doubleCheckFn,
            BiConsumer<Object, Object> putAfterLoad,
            Callable<T> loader,
            Object key) {
        return readThrough(
                cacheName,
                () -> doubleCheckFn.apply(key),
                cached -> cached != null,
                cached -> (T) cached.get(),
                loader::call,
                value -> putAfterLoad.accept(key, value),
                cause -> translateCacheLoaderFailure(key, loader, cause));
    }

    /**
     * 一条 read-through 协议的唯一实现。
     *
     * <p>读侧表示({@code R})和业务值表示({@code T})由 caller 显式适配;因此
     * {@link Cache.ValueWrapper} 与 writer 的 {@code byte[]} 不需要各自复制协议。
     * 写回失败只在此处容忍并发出一次脱敏 WARN;{@link IllegalArgumentException} 保持为
     * {@link LoadOutcome.LoadFailed},由 caller 原样抛出。
     *
     * @param cacheName         缓存名称,仅用于脱敏诊断
     * @param read              读原语
     * @param isHit             判断读结果是否命中
     * @param hitValue          把命中表示转换为业务值
     * @param loader            回源 loader
     * @param writeBack         成功加载后的写回原语
     * @param loaderFailure     入口特定的 loader 异常翻译
     * @param <R>               读原语返回的表示
     * @param <T>               loader 业务值表示
     * @return 共享的四态编排结果
     */
    static <R, T> LoadOutcome<T> readThrough(
            String cacheName,
            Supplier<R> read,
            Predicate<R> isHit,
            Function<R, T> hitValue,
            ThrowingSupplier<T> loader,
            Consumer<T> writeBack,
            Function<Throwable, Throwable> loaderFailure) {
        final R cached;
        try {
            cached = read.get();
            if (isHit.test(cached)) {
                return new Loaded<>(hitValue.apply(cached));
            }
        } catch (Throwable cause) {
            return new LoadFailed<>(cause);
        }

        final T loaded;
        try {
            loaded = loader.get();
        } catch (Throwable cause) {
            return new LoadFailed<>(loaderFailure.apply(cause));
        }

        try {
            writeBack.accept(loaded);
        } catch (IllegalArgumentException configError) {
            return new LoadFailed<>(configError);
        } catch (RuntimeException writeBackFailure) {
            log.warn(
                    "Cache write-back failed after successful load; returning loaded value: "
                            + "cacheName={}, failure={}",
                    cacheName,
                    FailureDiagnostics.sanitizedFailure(writeBackFailure));
            return new LoadedWithWriteBackFailure<>(loaded, writeBackFailure);
        } catch (Throwable cause) {
            return new LoadFailed<>(cause);
        }
        return new Loaded<>(loaded);
    }

    /**
     * Spring Cache loader 契约的异常适配;writer 入口传入 identity。
     */
    private static <T> Throwable translateCacheLoaderFailure(
            Object key, Callable<T> loader, Throwable cause) {
        if (cause instanceof Exception exception) {
            return new Cache.ValueRetrievalException(key, loader, exception);
        }
        return cause;
    }

    @FunctionalInterface
    interface ThrowingSupplier<T> {
        T get() throws Throwable;
    }

    /**
     * Bloom 短路检查 — miss counter 自增下沉到 caller(orchestrator 不感知 metric)。
     *
     * <p>前置条件任一缺失(operation null / 未启用 bloom / bloomGate null)→ return false(不短路)。
     * 键派生经 {@link CacheKeys#fromRedisKey} 与链层 {@code BloomFilterHandler.add} 同源,杜绝
     * actualKey/redisKey 漂移缺陷。
     */
    private boolean isBloomShortCircuited(String cacheName, String redisKey,
                                          @Nullable CachePolicyView.Source operation) {
        if (operation == null || !operation.isUseBloomFilter() || bloomGate == null) {
            return false;
        }
        String bloomKey = CacheKeys.fromRedisKey(cacheName, redisKey).bloomKey();
        return bloomGate.definiteMiss(cacheName, bloomKey);
    }
}
