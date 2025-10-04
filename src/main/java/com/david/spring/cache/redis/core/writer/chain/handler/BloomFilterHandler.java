package com.david.spring.cache.redis.core.writer.chain.handler;

import com.david.spring.cache.redis.core.writer.chain.AbstractCacheHandler;
import com.david.spring.cache.redis.core.writer.support.BloomFilterSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.stereotype.Component;

/**
 * 布隆过滤器处理器 职责：防止缓存穿透 - GET 操作：检查 key 是否可能存在 - PUT/PUT_IF_ABSENT 操作：将 key 添加到布隆过滤器 - CLEAN
 * 操作：清理布隆过滤器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BloomFilterHandler extends AbstractCacheHandler {

    private final BloomFilterSupport bloomFilterSupport;
    private final CacheStatisticsCollector statistics;

    @Override
    protected boolean shouldHandle(CacheContext context) {
        // 检查是否启用了布隆过滤器
        return context.getCacheOperation() != null
                && context.getCacheOperation().isUseBloomFilter();
    }

    @Override
    protected CacheResult doHandle(CacheContext context) {
        switch (context.getOperation()) {
            case GET -> {
                return handleGet(context);
            }
            case PUT, PUT_IF_ABSENT -> {
                return handlePut(context);
            }
            case CLEAN -> {
                return handleClean(context);
            }
            default -> {
                // 其他操作不需要布隆过滤器处理
                return invokeNext(context);
            }
        }
    }

    /** 处理 GET 操作：检查布隆过滤器 */
    private CacheResult handleGet(CacheContext context) {
        boolean mightContain =
                bloomFilterSupport.mightContain(context.getCacheName(), context.getActualKey());

        if (!mightContain) {
            log.debug(
                    "Bloom filter rejected (key does not exist): cacheName={}, key={}",
                    context.getCacheName(),
                    context.getRedisKey());
            statistics.incMisses(context.getCacheName());
            return CacheResult.rejectedByBloomFilter();
        }

        log.debug(
                "Bloom filter passed (key might exist): cacheName={}, key={}",
                context.getCacheName(),
                context.getRedisKey());

        // 继续责任链
        return invokeNext(context);
    }

    /** 处理 PUT 操作：添加到布隆过滤器 */
    private CacheResult handlePut(CacheContext context) {
        // 先继续责任链执行实际的缓存操作
        CacheResult result = invokeNext(context);

        // 如果缓存操作成功，添加到布隆过滤器
        if (result.isSuccess()) {
            try {
                bloomFilterSupport.add(context.getCacheName(), context.getActualKey());
                log.debug(
                        "Added key to bloom filter: cacheName={}, key={}",
                        context.getCacheName(),
                        context.getRedisKey());
            } catch (Exception e) {
                log.error(
                        "Failed to add key to bloom filter: cacheName={}, key={}",
                        context.getCacheName(),
                        context.getRedisKey(),
                        e);
            }
        }

        return result;
    }

    /** 处理 CLEAN 操作：清理布隆过滤器 */
    private CacheResult handleClean(CacheContext context) {
        // 先继续责任链执行实际的清理操作
        CacheResult result = invokeNext(context);

        // 如果清理操作成功且是清空所有 key（pattern 以 * 结尾），则清理布隆过滤器
        if (result.isSuccess() && context.getKeyPattern() != null && context.getKeyPattern().endsWith("*")) {
            try {
                bloomFilterSupport.delete(context.getCacheName());
                log.debug(
                        "Bloom filter cleared along with cache: cacheName={}",
                        context.getCacheName());
            } catch (Exception e) {
                log.error(
                        "Failed to clear bloom filter: cacheName={}", context.getCacheName(), e);
            }
        }

        return result;
    }
}
