package io.github.davidhlp.spring.cache.redis.protection.bloom;

import io.github.davidhlp.spring.cache.redis.chain.*;
import io.github.davidhlp.spring.cache.redis.chain.model.*;


import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.stereotype.Component;

/**
 * 布隆过滤器处理器，防止缓存穿透
 *
 * <p>职责：
 * <ul>
 *   <li>GET: Writer 层透传，Bloom 短路检查已移至 {@link io.github.davidhlp.spring.cache.redis.cache.RedisProCacheWriter#get}
 *       前置（{@link BloomSupport#mightContain}）。本 handler 仅承担 observability
 *       与 attributes 标记职责</li>
 *   <li>PUT / PUT_IF_ABSENT / CLEAN: 标记需要后置处理，由
 *       {@link #afterChainExecution} 在责任链执行完成后回填 / 清空布隆</li>
 * </ul>
 */
@Slf4j
@Component
@HandlerPriority(HandlerOrder.BLOOM_FILTER)
public class BloomFilterHandler extends AbstractCacheHandler {

    private final BloomSupport bloomSupport;
    private final CacheStatisticsCollector statistics;

    public BloomFilterHandler(BloomSupport bloomSupport, CacheStatisticsCollector statistics) {
        this.bloomSupport = bloomSupport;
        this.statistics = statistics;
    }

    /**
     * ADR-0018 — 语义 counter 元数据声明。WS-1.4 per-handler tag 试点：
     * Bloom 拒绝事件计数（key 判定不在集合 → 直接短路）。
     */
    @Override
    protected CounterMetadata semanticCounter() {
        return new CounterMetadata(
                "resicache.handler.bloom.blocked",
                "Bloom filter rejections — key definitely not in cache, request short-circuited");
    }

    @Override
    protected boolean shouldHandle(CacheContext context) {
        return context.getCacheOperation() != null
                && context.getCacheOperation().isUseBloomFilter();
    }

    @Override
    protected HandlerResult doHandle(CacheContext context) {
        return switch (context.getOperation()) {
            case GET -> handleGet(context);
            case PUT -> handlePut(context);
            case PUT_IF_ABSENT -> handlePutIfAbsent(context);
            case CLEAN -> handleClean(context);
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
        boolean mightContain =
                bloomSupport.mightContain(context.getCacheName(), context.getActualKey());

        if (!mightContain) {
            log.debug(
                    "Bloom filter rejected (key does not exist): cacheName={}, key={}",
                    context.getCacheName(),
                    context.getRedisKey());
            statistics.incMisses(context.getCacheName());
            // WS-1.4 per-handler tag 试点:Bloom 拒绝事件计数
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
     * 处理 PUT 操作
     *
     * <p>PUT 需要后置回填布隆 — opt-in 走 {@link #requiresPostProcess(CacheContext)}
     * 按 {@link CacheContext#getOperation()} 派生,不再用 stringly-typed
     * attributes 标记(ADR-0045)。
     */
    private HandlerResult handlePut(CacheContext context) {
        return HandlerResult.continueChain();
    }

    /**
     * 处理 PUT_IF_ABSENT 操作
     *
     * <p>同 PUT — 后置回填走 requiresPostProcess 操作类型判定。
     */
    private HandlerResult handlePutIfAbsent(CacheContext context) {
        return HandlerResult.continueChain();
    }

    /**
     * 处理 CLEAN 操作
     *
     * <p>清空布隆过滤器。
     */
    private HandlerResult handleClean(CacheContext context) {
        return HandlerResult.continueChain();
    }

    /**
     * 判断是否需要执行后置处理 — ADR-0045 替代原 POST_PROCESS_KEY stringly-typed
     * 标记,从 {@link CacheContext#getOperation()} 直接派生:
     * <ul>
     *   <li>PUT / PUT_IF_ABSENT — 回填布隆</li>
     *   <li>CLEAN — 清空布隆</li>
     *   <li>GET 等其他操作 — 无需后置</li>
     * </ul>
     *
     * <p>locality-first:post-process 判定走类型化的 operation enum,不再跨 seam
     * 通过 {@code context.setAttribute} 写 stringly-typed 标记。
     */
    @Override
    public boolean requiresPostProcess(CacheContext context) {
        CacheOperation op = context.getOperation();
        return op == CacheOperation.PUT
                || op == CacheOperation.PUT_IF_ABSENT
                || op == CacheOperation.CLEAN;
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

        // 根据操作类型执行相应的后置处理
        switch (context.getOperation()) {
            case PUT, PUT_IF_ABSENT -> addToBloomFilter(context);
            case CLEAN -> clearBloomFilter(context);
            default -> { /* GET 等操作无需后置处理 */ }
        }
    }

    private void addToBloomFilter(CacheContext context) {
        bloomSupport.add(context.getCacheName(), context.getActualKey());
        log.debug(
                "Added key to bloom filter: cacheName={}, key={}",
                context.getCacheName(),
                context.getRedisKey());
    }

    private void clearBloomFilter(CacheContext context) {
        // 仅清空布隆过滤器，不做增量删除（布隆过滤器不支持精确删除）
        // 但记录警告：清空后短时间内会有穿透风险，直到新 PUT 重新填充
        bloomSupport.clear(context.getCacheName());
        log.warn(
                "Bloom filter cleared along with cache: cacheName={} — " +
                "cache penetration risk until filter is repopulated by subsequent PUTs",
                context.getCacheName());
    }
}
