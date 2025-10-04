package com.david.spring.cache.redis.core.writer.chain;

import com.david.spring.cache.redis.core.writer.chain.handler.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 缓存处理器责任链工厂 职责：构建和配置责任链
 *
 * <p>责任链顺序： 1. BloomFilterHandler - 布隆过滤器检查（防止缓存穿透） 2. SyncLockHandler - 同步锁处理（防止缓存击穿） 3.
 * TtlHandler - TTL 计算和配置 4. NullValueHandler - 空值处理 5. ActualCacheHandler - 实际缓存操作
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheHandlerChainFactory {

    private final BloomFilterHandler bloomFilterHandler;
    private final SyncLockHandler syncLockHandler;
    private final TtlHandler ttlHandler;
    private final NullValueHandler nullValueHandler;
    private final ActualCacheHandler actualCacheHandler;

    /**
     * 创建完整的缓存处理器责任链
     *
     * @return 配置好的责任链
     */
    public CacheHandlerChain createChain() {
        CacheHandlerChain chain = new CacheHandlerChain();

        // 按顺序添加处理器
        chain.addHandler(bloomFilterHandler)
                .addHandler(syncLockHandler)
                .addHandler(ttlHandler)
                .addHandler(nullValueHandler)
                .addHandler(actualCacheHandler);

        log.info("Cache handler chain created with {} handlers: {}",
                chain.size(),
                chain.getHandlerNames());

        return chain;
    }

    /**
     * 创建 GET 操作专用的责任链（优化性能）
     *
     * @return GET 操作责任链
     */
    public CacheHandlerChain createGetChain() {
        CacheHandlerChain chain = new CacheHandlerChain();

        // GET 操作需要的处理器
        chain.addHandler(bloomFilterHandler)
                .addHandler(syncLockHandler)
                .addHandler(actualCacheHandler);

        log.debug("GET operation handler chain created");
        return chain;
    }

    /**
     * 创建 PUT 操作专用的责任链（优化性能）
     *
     * @return PUT 操作责任链
     */
    public CacheHandlerChain createPutChain() {
        CacheHandlerChain chain = new CacheHandlerChain();

        // PUT 操作需要的处理器
        chain.addHandler(ttlHandler)
                .addHandler(nullValueHandler)
                .addHandler(actualCacheHandler)
                .addHandler(bloomFilterHandler); // 布隆过滤器在最后添加

        log.debug("PUT operation handler chain created");
        return chain;
    }
}
