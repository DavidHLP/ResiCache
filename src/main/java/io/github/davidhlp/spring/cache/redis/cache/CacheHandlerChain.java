package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.chain.CacheHandler;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/** Owns immutable chain snapshots, published on mutation and reused on execution. */
@Slf4j
class CacheHandlerChain {

    /** 所有处理器列表（用于调试和后置处理；链结构单一真理源） */
    private volatile List<CacheHandler> handlers = List.of();

    /** Serializes structural changes only; execution reads the published snapshot without locking. */
    private final Object chainGuard = new Object();

    /** 推进引擎 — 构造期注入（本类由工厂构造，非 Spring bean）。 */
    private final ChainEngine engine;

    CacheHandlerChain(ChainEngine engine) {
        this.engine = engine;
    }

    /** Adds a handler to the next published snapshot. */
    public CacheHandlerChain addHandler(CacheHandler handler) {
        synchronized (chainGuard) {
            List<CacheHandler> updated = new ArrayList<>(handlers);
            updated.add(handler);
            handlers = List.copyOf(updated);
            log.debug("Added handler to chain: {}", HandlerIdentity.of(handler).tag());
            return this;
        }
    }

    /** Executes the current immutable snapshot without copying or locking. */
    public CacheResult execute(CacheContext context) {
        return engine.execute(handlers, context);
    }

    /**
     * 获取处理器数量。
     *
     * @return 处理器数量
     */
    public int size() {
        return handlers.size();
    }

    /** Publishes an empty chain; active executions finish with their original snapshot. */
    public void clear() {
        synchronized (chainGuard) {
            handlers = List.of();
            log.debug("Handler chain cleared");
        }
    }

    /**
     * 获取所有处理器名称。
     *
     * @return 处理器名称列表
     */
    public List<String> getHandlerNames() {
        return handlers.stream().map(handler -> HandlerIdentity.of(handler).tag()).toList();
    }
}
