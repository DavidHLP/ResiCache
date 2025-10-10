package io.github.davidhlp.spring.cache.redis.core.writer.chain.handler;

import io.github.davidhlp.spring.cache.redis.core.writer.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom.BloomSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.stereotype.Component;

/** Bloom filter handler preventing cache penetration. */
@Slf4j
@Component
@RequiredArgsConstructor
public class BloomFilterHandler extends AbstractCacheHandler {

    private final BloomSupport bloomSupport;
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
                bloomSupport.mightContain(context.getCacheName(), context.getActualKey());

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
            bloomSupport.add(context.getCacheName(), context.getActualKey());
            log.debug(
                    "Added key to bloom filter: cacheName={}, key={}",
                    context.getCacheName(),
                    context.getRedisKey());
        }

        return result;
    }

    private CacheResult handleClean(CacheContext context) {
        CacheResult result = invokeNext(context);

        if (result.isSuccess()
                && context.getKeyPattern() != null
                && context.getKeyPattern().endsWith("*")) {
            bloomSupport.clear(context.getCacheName());
            log.debug(
                    "Bloom filter cleared along with cache: cacheName={}",
                    context.getCacheName());
        }

        return result;
    }
}
