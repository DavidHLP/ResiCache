package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.chain.CacheHandler;
import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.chain.HandlerResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import io.github.davidhlp.spring.cache.redis.chain.observer.ChainObserver;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Mutating registration during a callback must not change an active execution's snapshot. */
class PublishedChainSnapshotTest {
    @Test
    void clearingFacadeInsideHandlerDoesNotDropActiveSuccessors() {
        CacheHandlerChain chain = new CacheHandlerChain(new ChainEngine());
        AtomicInteger successorCalls = new AtomicInteger();
        chain.addHandler(context -> {
            chain.clear();
            return HandlerResult.continueChain();
        });
        chain.addHandler(context -> {
            successorCalls.incrementAndGet();
            return HandlerResult.continueChain();
        });
        chain.execute(context());
        assertThat(successorCalls.get()).isEqualTo(1);
        assertThat(chain.size()).isZero();
        chain.execute(context());
        assertThat(successorCalls.get()).isEqualTo(1);
    }

    @Test
    void observerAddedAtChainStartParticipatesOnlyInNextExecution() {
        ChainEngine engine = new ChainEngine();
        AtomicBoolean registered = new AtomicBoolean();
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger nodes = new AtomicInteger();
        AtomicInteger ends = new AtomicInteger();
        Object token = new Object();
        ChainObserver added = new ChainObserver() {
            @Override public Object onChainStart(CacheContext context) {
                starts.incrementAndGet();
                return token;
            }
            @Override public Object onNodeStart(CacheHandler handler, CacheContext context) {
                nodes.incrementAndGet();
                return null;
            }
            @Override public void onChainEnd(CacheContext context, Object scope, CacheResult result) {
                assertThat(scope).isSameAs(token);
                ends.incrementAndGet();
            }
        };
        engine.addObserver(new ChainObserver() {
            @Override public Object onChainStart(CacheContext context) {
                if (registered.compareAndSet(false, true)) {
                    engine.addObserver(added);
                }
                return null;
            }
        });
        List<CacheHandler> handlers = List.of(context -> HandlerResult.continueChain());
        engine.execute(handlers, context());
        assertThat(starts.get()).isZero();
        assertThat(nodes.get()).isZero();
        assertThat(ends.get()).isZero();
        engine.execute(handlers, context());
        assertThat(starts.get()).isEqualTo(1);
        assertThat(nodes.get()).isEqualTo(1);
        assertThat(ends.get()).isEqualTo(1);
    }

    private CacheContext context() {
        return CacheContext.of(CacheInput.builder().operation(CacheOperation.GET)
                .cacheName("snapshot").redisKey("snapshot:key").actualKey("key").build());
    }
}
