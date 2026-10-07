package com.example.consumer;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.chain.CacheHandler;
import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.ChainContinuation;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.chain.FlowControl;
import io.github.davidhlp.spring.cache.redis.chain.HandlerOrder;
import io.github.davidhlp.spring.cache.redis.chain.HandlerPriority;
import io.github.davidhlp.spring.cache.redis.chain.HandlerResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import io.github.davidhlp.spring.cache.redis.chain.model.CachePolicyView;
import io.github.davidhlp.spring.cache.redis.chain.observer.ChainObserver;

/**
 * External consumer sample (RM-010) — annotation surface + one supported
 * extension seam, using ONLY classified public contract types.
 */
public class ExternalConsumerDemo {

    @RedisCacheable(value = "demo", key = "#id", ttl = 60)
    public String load(String id) {
        return "value-" + id;
    }

    /** Supported extension: a custom CacheHandler. */
    @HandlerPriority(HandlerOrder.TTL)
    static class DemoHandler implements CacheHandler {
        @Override
        public HandlerResult handle(CacheContext context) {
            return HandlerResult.continueWith(CacheResult.miss());
        }
    }

    /**
     * Supported extension: nested advancement. The engine hands this handler a
     * ChainContinuation bound to its position, so it can run the remainder of the
     * chain inside its own critical section (this is what sync=true does inside
     * the distributed lock) and then end the chain.
     */
    static class SyncLikeHandler implements CacheHandler {

        @Override
        public HandlerResult handle(CacheContext context) {
            throw new AssertionError("engine must call the 2-arg form");
        }

        @Override
        public HandlerResult handle(CacheContext context, ChainContinuation next) {
            return HandlerResult.terminate(next.advance());
        }
    }

    /** Supported extension: a custom ChainObserver with scope token. */
    static class DemoObserver implements ChainObserver {
        @Override
        public Object onChainStart(CacheContext context) {
            return "demo-token";
        }

        @Override
        public void onChainEnd(CacheContext context, Object scopeToken, CacheResult result) {
            // token is the same reference returned by this observer's onChainStart
        }
    }

    public static void main(String[] args) {
        // Value-path protocol demo (no Spring, no Redis):
        CacheResult hit = CacheResult.success("bytes".getBytes());
        if (!hit.isSuccess() || hit.outcome() != CacheResult.Outcome.SUCCESS) {
            throw new AssertionError("success contract broken");
        }
        CacheResult failure = CacheResult.failure(
                CacheOperation.PUT, CacheResult.FailureKind.REDIS, new IllegalStateException("x"));
        if (failure.isSuccess() || failure.operation() != CacheOperation.PUT) {
            throw new AssertionError("failure contract broken");
        }
        HandlerResult r = HandlerResult.terminate(hit);
        if (r.decision() != FlowControl.TERMINATE || !r.shouldTerminate()) {
            throw new AssertionError("flow control contract broken");
        }
        DemoHandler handler = new DemoHandler();
        DemoObserver observer = new DemoObserver();
        if (observer.onChainStart(null) != "demo-token") {
            throw new AssertionError("observer token contract broken");
        }
        if (handler.handle(null).decision() != FlowControl.CONTINUE) {
            throw new AssertionError("handler contract broken");
        }
        ChainContinuation next = () -> CacheResult.success();
        if (new SyncLikeHandler().handle(null, next).decision() != FlowControl.TERMINATE) {
            throw new AssertionError("nested advancement contract broken");
        }
        if (CachePolicyView.NONE.useBloomFilter()) {
            throw new AssertionError("policy view default contract broken");
        }
        System.out.println("EXTERNAL_CONSUMER_OK");
    }
}
