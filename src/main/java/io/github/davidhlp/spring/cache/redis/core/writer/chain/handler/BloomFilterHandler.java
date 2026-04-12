package io.github.davidhlp.spring.cache.redis.core.writer.chain.handler;

import io.github.davidhlp.spring.cache.redis.core.writer.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.core.writer.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom.BloomSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.stereotype.Component;

/**
 * 布隆过滤器处理器，防止缓存穿透
 *
 * <p>职责：
 * <ul>
 *   <li>GET: 检查 key 是否可能存在，不存在则直接返回 miss</li>
 *   <li>PUT/PUT_IF_ABSENT: 先让后续 Handler 执行，成功后将 key 添加到布隆过滤器</li>
 *   <li>CLEAN: 清理缓存时，同时清理布隆过滤器</li>
 * </ul>
 *
 * <p>设计说明：
 * <ul>
 *   <li>对于 PUT 操作，采用前置检查+后置处理模式</li>
 *   <li>通过 markPostProcess 标记请求后置处理</li>
 *   <li>在责任链执行完成后执行后置逻辑</li>
 * </ul>
 *
 * <p>假阳性处理：
 * <ul>
 *   <li>当 bloom filter 返回 positive 但 cache 实际 miss 时，在 post-processing 时
 *       将 key 添加到 bloom filter，避免对不存在的 key 重复进行缓存查找</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@HandlerPriority(HandlerOrder.BLOOM_FILTER)
public class BloomFilterHandler extends AbstractCacheHandler
        implements PostProcessHandler {

    /** 上下文属性键：标记需要后置处理 */
    private static final String POST_PROCESS_KEY = "bloom.postProcess";

    /** 上下文属性键：bloom filter 对 GET 返回 positive（可能存在） */
    private static final String BLOOM_POSITIVE_GET_KEY = "bloom.get.positive";

    private final BloomSupport bloomSupport;
    private final CacheStatisticsCollector statistics;

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
     * <p>检查布隆过滤器，如果 key 不可能存在，直接返回 miss。
     * 如果 key 可能存在（假阳性），继续执行但标记 post-processing，
     * 后续根据 cache 结果决定是否添加到 bloom filter。
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
            // 明确终止，不继续执行
            return HandlerResult.terminate(CacheResult.rejectedByBloomFilter());
        }

        log.debug(
                "Bloom filter passed (key might exist): cacheName={}, key={}",
                context.getCacheName(),
                context.getRedisKey());

        // 标记该 GET 请求的 bloom filter 返回了 positive
        // 在 post-processing 时，如果 cache miss，则添加 key 到 bloom filter
        context.setAttribute(BLOOM_POSITIVE_GET_KEY, true);

        // 继续执行后续 Handler
        return HandlerResult.continueChain();
    }

    /**
     * 处理 PUT 操作
     *
     * <p>标记需要后置处理，继续责任链。
     * 后续 Handler 执行完成后，会检查标记并执行后置逻辑。
     */
    private HandlerResult handlePut(CacheContext context) {
        // 标记需要后置处理
        context.setAttribute(POST_PROCESS_KEY, true);

        // 继续执行后续 Handler
        return HandlerResult.continueChain();
    }

    /**
     * 处理 PUT_IF_ABSENT 操作
     *
     * <p>同 PUT，标记后置处理。
     */
    private HandlerResult handlePutIfAbsent(CacheContext context) {
        context.setAttribute(POST_PROCESS_KEY, true);
        return HandlerResult.continueChain();
    }

    /**
     * 处理 CLEAN 操作
     *
     * <p>标记后置处理。
     */
    private HandlerResult handleClean(CacheContext context) {
        context.setAttribute(POST_PROCESS_KEY, true);
        return HandlerResult.continueChain();
    }

    /**
     * 判断是否需要执行后置处理
     *
     * <p>只在标记了 POST_PROCESS_KEY 且操作成功时执行。
     */
    @Override
    public boolean requiresPostProcess(CacheContext context) {
        Boolean postProcess = context.getAttribute(POST_PROCESS_KEY);
        return postProcess != null && postProcess;
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
            case GET -> handleGetPostProcessing(context, result);
            default -> { /* 其他操作无需后置处理 */ }
        }
    }

    /**
     * 处理 GET 操作的后置处理
     *
     * <p>当 bloom filter 返回 positive 但 cache miss 时，将 key 添加到 bloom filter，
     * 避免对不存在的 key 重复进行缓存查找。
     */
    private void handleGetPostProcessing(CacheContext context, CacheResult result) {
        // 检查该 GET 请求是否 bloom filter 返回了 positive
        Boolean bloomPositive = context.getAttribute(BLOOM_POSITIVE_GET_KEY);
        if (bloomPositive == null || !bloomPositive) {
            // bloom filter 未返回 positive，无需处理
            return;
        }

        // 如果 cache miss（bloom 返回 positive 但实际不存在）
        // 添加 key 到 bloom filter，避免后续重复查询
        if (!result.isHit()) {
            bloomSupport.add(context.getCacheName(), context.getActualKey());
            log.debug(
                    "Added false positive key to bloom filter: cacheName={}, key={}",
                    context.getCacheName(),
                    context.getRedisKey());
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
        if (context.getKeyPattern() != null && context.getKeyPattern().endsWith("*")) {
            bloomSupport.clear(context.getCacheName());
            log.debug(
                    "Bloom filter cleared along with cache: cacheName={}",
                    context.getCacheName());
        }
    }
}
