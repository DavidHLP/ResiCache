package io.github.davidhlp.spring.cache.redis.cache;






import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.lang.Nullable;

/**
 * 缓存 loader 路径编排器 — loader-path deep seam.
 *
 * <p>承接 {@link RedisProCache#get(Object, Callable)} 的 3 步 loader 编排:
 *
 * <ol>
 *   <li><b>Bloom 短路检查</b> — 经 {@link BloomGate#definiteMiss} 判定「确定不存在」 →
 *       返回 {@link LoadOutcome.BloomShortCircuited};caller 据此自增 miss counter 并返回 null</li>
 *   <li><b>Sync 路径</b> — {@code sync=true} + {@link SyncSupport} 在场 →
 *       {@link SyncSupport#executeSync} + 锁内 {@link #performLoad double-check + load + put};
 *       返回 {@link LoadOutcome.Loaded};锁内 loader 抛异常 →
 *       {@link LoadOutcome.LoadFailed}(由 caller 翻译)</li>
 *   <li><b>Default 路径</b> — 同一 load 协议({@link #performLoad}),不带分布式锁;
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
 * (key 派生 / 双检 / 写回)以 callback 形式由 {@code RedisProCache} 注入:
 * <ul>
 *   <li>{@code Function<Object, String> redisKeyFn} — 派生 Redis key 用于 BloomGate 与 SyncSupport</li>
 *   <li>{@code Function<Object, Cache.ValueWrapper> doubleCheckFn} — 缓存读原语(走
 *       {@code super.get},含链 GET / bloom 短路 / null round-trip,无 metrics — metrics 在外层记)</li>
 *   <li>{@code BiConsumer<Object, Object> putAfterLoad} — load 成功后写回(走
 *       {@code RedisProCache.put} override,保留 putTimer + putCounter metrics)</li>
 * </ul>
 *
 * <p><b>一条协议,两个入口</b>:sync 与非 sync 路径都在本类内走 {@link #performLoad} —
 * 同一套 double-check 语义、同一套写回容错({@link LoadOutcome.LoadedWithWriteBackFailure})、
 * 同一套 metric 记账。两者唯一差别是 sync 路径把协议跑在 {@link SyncSupport} 的分布式锁内。
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
     * (checked 异常已在 {@link #performLoad} 翻译为 {@link Cache.ValueRetrievalException})。
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
            @Nullable RedisCacheableOperation operation) {
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
     * @param operation        方法级 operation(可为 null,视作「无增强属性」→ 不走 bloom / sync)
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
            @Nullable RedisCacheableOperation operation) {

        // 1) Bloom 短路检查 — caller 据 BloomShortCircuited 自增 miss counter
        if (isBloomShortCircuited(cacheName, redisKeyFn.apply(key), operation)) {
            return new BloomShortCircuited<>();
        }

        // 2) Sync 路径 — sync=true 且 SyncSupport 在场才走;否则降级 default 路径
        if (operation != null && operation.isSync() && syncSupport != null) {
            return executeSyncLoad(redisKeyFn, doubleCheckFn, putAfterLoad, loader, key, operation);
        }

        // 3) Default 路径 — 与 sync 路径同一 load 协议,只是不跑在分布式锁内
        return executeLoad(doubleCheckFn, putAfterLoad, loader, key);
    }

    /**
     * 把 {@link #performLoad} 的异常语义翻译为 {@link LoadOutcome} —— 两条 load 路径
     * (sync / default)共用的容错规则,单点定义 availability-first 与失败归类。
     *
     * <ul>
     *   <li>loader 失败(已翻译为 {@link Cache.ValueRetrievalException})→ {@link LoadOutcome.LoadFailed}</li>
     *   <li>写回失败但 loader 已成功 → {@link LoadOutcome.LoadedWithWriteBackFailure}(值保留)</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private <T> LoadOutcome<T> executeLoad(
            Function<Object, Cache.ValueWrapper> doubleCheckFn,
            BiConsumer<Object, Object> putAfterLoad,
            Callable<T> loader,
            Object key) {
        try {
            return new Loaded<>(performLoad(doubleCheckFn, putAfterLoad, loader, key));
        } catch (WriteBackFailureException wbf) {
            // 写回失败不覆盖 loader 值(ADR-02):值已捕获在异常中,直接以该 outcome 返回
            return new LoadedWithWriteBackFailure<>((T) wbf.loadedValue(), wbf.getCause());
        } catch (Throwable cause) {
            return new LoadFailed<>(cause);
        }
    }

    /**
     * Sync 路径编排。
     * 职责:解析 timeout → 委派 {@link SyncSupport#executeSync} → 锁内 {@link #executeLoad}
     * (同一 load 协议 + 同一容错规则);锁基础设施失败(未获取锁 / 中断 / follower 超时)
     * → {@link LoadOutcome.LoadFailed}。
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
            return syncSupport.executeSync(
                    lockKey,
                    () -> executeLoad(doubleCheckFn, putAfterLoad, loader, key),
                    timeout);
        } catch (Throwable cause) {
            return new LoadFailed<>(cause);
        }
    }

    /**
     * Bloom 短路检查 — miss counter 自增下沉到 caller(orchestrator 不感知 metric)。
     *
     * <p>前置条件任一缺失(operation null / 未启用 bloom / bloomGate null)→ return false(不短路)。
     * 键派生经 {@link CacheKeys#fromRedisKey} 与链层 {@code BloomFilterHandler.add} 同源,杜绝
     * actualKey/redisKey 漂移缺陷。
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
     * 缓存 load 协议:double-check → loader → write-back —— sync 与非 sync 路径共用同一实现。
     *
     * <ol>
     *   <li><b>double-check</b>:{@code doubleCheckFn.apply(key)} 已有值 → 直接返回(走
     *       {@code super.get(key)} 无 metrics,因 metrics 在外层 {@code get(key, loader)} 记录);
     *       缓存 null 值经 NullValue round-trip 在此同样命中(非 null wrapper + null value)</li>
     *   <li><b>load</b>:cache miss → 调 {@code loader.call()};loader 异常 →
     *       {@link Cache.ValueRetrievalException}(Spring 抽象层契约,checked 亦翻译)</li>
     *   <li><b>write-back</b>:loader 成功(无论 null 与否)后
     *       {@link BiConsumer#accept 写回}(由 RedisCache 配置处理 null-value 缓存契约);
     *       写回失败 → 抛 {@link WriteBackFailureException}(携带已加载值),由
     *       {@link #executeLoad} 翻译为 {@link LoadedWithWriteBackFailure},不覆盖业务值</li>
     * </ol>
     */
    @SuppressWarnings("unchecked")
    <T> T performLoad(Function<Object, Cache.ValueWrapper> doubleCheckFn,
                      BiConsumer<Object, Object> putAfterLoad,
                      Callable<T> loader,
                      Object key) {
        // 双重检查:可能在等待锁期间其他线程已加载。doubleCheckFn 由 caller 绑为 super.get(key),
        // 完整保留 null-value round-trip 语义(NullValue → null)。
        Cache.ValueWrapper existingValue = doubleCheckFn.apply(key);
        if (existingValue != null) {
            return (T) existingValue.get();
        }

        // 执行加载 — loader 异常在此翻译为 Spring 抽象层契约
        final T loaded;
        try {
            loaded = loader.call();
        } catch (Exception ex) {
            throw new Cache.ValueRetrievalException(key, loader, ex);
        }

        // 写回 — 失败时保留 loader 值,以 WriteBackFailureException 冒泡(不吞、不覆盖)
        try {
            putAfterLoad.accept(key, loaded);
        } catch (RuntimeException ex) {
            throw new WriteBackFailureException(loaded, ex);
        }
        return loaded;
    }

    /**
     * 写回失败哨兵 — 把「loader 已成功 + 写回失败」的状态从
     * {@link #performLoad} 传出到 {@link #executeLoad} 翻译为
     * {@link LoadedWithWriteBackFailure}。私有控制流异常,不跨类。
     */
    private static final class WriteBackFailureException extends RuntimeException {

        private final Object loadedValue;

        WriteBackFailureException(Object loadedValue, RuntimeException cause) {
            super(cause);
            this.loadedValue = loadedValue;
        }

        Object loadedValue() {
            return loadedValue;
        }
    }
}
