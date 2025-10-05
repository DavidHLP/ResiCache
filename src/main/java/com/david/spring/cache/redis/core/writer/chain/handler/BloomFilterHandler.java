package com.david.spring.cache.redis.core.writer.chain.handler;

import com.david.spring.cache.redis.core.writer.chain.CacheResult;
import com.david.spring.cache.redis.core.writer.support.protect.bloom.BloomFilter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.stereotype.Component;

/** Bloom filter handler preventing cache penetration. */
@Slf4j
@Component
@RequiredArgsConstructor
public class BloomFilterHandler extends AbstractCacheHandler {

    private final BloomFilter bloomFilter;
    private final CacheStatisticsCollector statistics;

    @Override
    protected boolean shouldHandle(CacheContext context) {
        return context.getCacheOperation() != null
                && context.getCacheOperation().isUseBloomFilter();
    }

    @Override
    protected CacheResult doHandle(CacheContext context) {
        return switch (context.getOperation()) {
            case GET -> handleGet(context);
            case PUT, PUT_IF_ABSENT -> handlePut(context);
            case CLEAN -> handleClean(context);
            default -> invokeNext(context);
        };
    }

    private CacheResult handleGet(CacheContext context) {
        boolean mightContain =
                bloomFilter.mightContain(context.getCacheName(), context.getActualKey());

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

        return invokeNext(context);
    }

    private CacheResult handlePut(CacheContext context) {
        CacheResult result = invokeNext(context);

        if (result.isSuccess()) {
            try {
                bloomFilter.add(context.getCacheName(), context.getActualKey());
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

    private CacheResult handleClean(CacheContext context) {
        CacheResult result = invokeNext(context);

        if (result.isSuccess()
                && context.getKeyPattern() != null
                && context.getKeyPattern().endsWith("*")) {
            try {
                bloomFilter.clear(context.getCacheName());
                log.debug(
                        "Bloom filter cleared along with cache: cacheName={}",
                        context.getCacheName());
            } catch (Exception e) {
                log.error("Failed to clear bloom filter: cacheName={}", context.getCacheName(), e);
            }
        }

        return result;
    }
}
