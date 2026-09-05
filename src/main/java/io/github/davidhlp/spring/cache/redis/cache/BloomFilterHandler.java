package io.github.davidhlp.spring.cache.redis.cache;





import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.chain.HandlerOrder;
import io.github.davidhlp.spring.cache.redis.chain.HandlerPriority;
import io.github.davidhlp.spring.cache.redis.chain.HandlerResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.stereotype.Component;

/**
 * 布隆过滤器处理器，防止缓存穿透
 *
 * <p>职责：
 * <ul>
 *   <li>GET: 经 {@link BloomGate#definiteMiss} 判定「确定不存在」→ 短路返回 miss
 *       (读侧穿透判定 + 统一日志收口到 BloomGate,与 {@code RedisProCache} loader 路径共享)</li>
 *   <li>PUT / PUT_IF_ABSENT:标记需要后置处理，由
 *       {@link #afterChainExecution} 在责任链执行完成后经 {@link BloomSupport} 回填布隆。
 *       CLEAN 只清缓存数据,不改变 Bloom。</li>
 * </ul>
 */
@Slf4j
@Component
@HandlerPriority(HandlerOrder.BLOOM_FILTER)
class BloomFilterHandler extends AbstractCacheHandler {

    private final BloomGate bloomGate;
    private final BloomSupport bloomSupport;
    private final CacheStatisticsCollector statistics;

    public BloomFilterHandler(BloomGate bloomGate,
                              BloomSupport bloomSupport,
                              CacheStatisticsCollector statistics) {
        this.bloomGate = bloomGate;
        this.bloomSupport = bloomSupport;
        this.statistics = statistics;
    }

    /**
     * 语义 counter 元数据声明:Bloom 拒绝事件计数(key 判定不在集合 → 直接短路)。
     */
    @Override
    protected CounterMetadata semanticCounter() {
        return new CounterMetadata(
                "resicache.handler.bloom.blocked",
                "Bloom filter rejections — key definitely not in cache, request short-circuited");
    }

    @Override
    protected boolean shouldHandle(CacheContext context) {
        // 经稳定 CachePolicyView 读取(不依赖内部 RedisCacheableOperation)
        return context.policy().useBloomFilter();
    }

    @Override
    protected HandlerResult doHandle(CacheContext context) {
        // PUT/PIF/CLEAN 的"实际工作"在 afterChainExecution() 后置路径,
        // requiresPostProcess() 派生自 operation 枚举,不在此处重复分派。
        return switch (context.getOperation()) {
            case GET -> handleGet(context);
            case PUT, PUT_IF_ABSENT, CLEAN -> HandlerResult.continueChain();
            default -> HandlerResult.continueChain();
        };
    }

    /**
     * 处理 GET 操作
     *
     * <p>Writer 层短路：若 Bloom 判定 key 不可能存在，直接返回 miss，避免查询 Redis。
     * 对于 sync 模式，{@link io.github.davidhlp.spring.cache.redis.cache.RedisProCache#get(Object, Callable)}
     * 会在调用 loader 前再做一次 Bloom 拦截，防止触发数据源查询。
     */
    private HandlerResult handleGet(CacheContext context) {
        // 读侧确定 miss 判定 + 统一 debug 日志收口到 BloomGate(与 RedisProCache loader 路径共享)
        if (bloomGate.definiteMiss(context.getCacheName(), context.getActualKey())) {
            statistics.incMisses(context.getCacheName());
            // Bloom 拒绝事件计数
            safeIncrementSemantic();
            return HandlerResult.terminate(CacheResult.miss());
        }

        log.debug(
                "Bloom filter passed (key might exist): cacheName={}, key={}",
                context.getCacheName(),
                context.getRedisKey());
        return HandlerResult.continueChain();
    }

    /**
     * 谓词「PUT / PUT_IF_ABSENT」收口于 {@link CacheOperation#requiresBloomPostProcess()}。
     *
     * <p>post-process 判定走类型化的 operation enum,保持 locality。
     */
    @Override
    public boolean requiresPostProcess(CacheContext context) {
        return context.getOperation().requiresBloomPostProcess();
    }

    /**
     * 后置处理：责任链执行完成后调用
     */
    @Override
    public void afterChainExecution(CacheContext context, CacheResult result) {
        // 空值检查
        if (context == null || result == null) {
            log.warn("Post-processing skipped: null context or result");
            return;
        }

        // 只在成功时执行后置处理
        if (!result.isSuccess() || context.isSkipRemaining()) {
            return;
        }

        // 仅写入成功路径回填 Bloom；CLEAN 不改变数据源存在集合。
        if (context.getOperation().isWrite()) {
            addToBloomFilter(context);
        }
    }

    private void addToBloomFilter(CacheContext context) {
        bloomSupport.add(context.getCacheName(), context.getActualKey());
        log.debug(
                "Added key to bloom filter: cacheName={}, key={}",
                context.getCacheName(),
                context.getRedisKey());
    }
}
