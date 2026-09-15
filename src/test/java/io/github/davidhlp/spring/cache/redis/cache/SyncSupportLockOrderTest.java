package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import io.github.davidhlp.spring.cache.redis.protection.breakdown.LockManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SyncSupport lock precedence tests")
class SyncSupportLockOrderTest {

    @Test
    @DisplayName("acquires lock managers from smallest order to largest")
    void executeSync_acquiresManagersInAscendingOrder() {
        List<String> acquisitions = new ArrayList<>();
        LockManager highPriority = manager("high", 1, acquisitions);
        LockManager lowPriority = manager("low", 9, acquisitions);
        SyncSupport support = new SyncSupport(
                List.of(lowPriority, highPriority), new RedisProCacheProperties());

        assertThat(support.executeSync("order-key", () -> "VALUE", 5)).isEqualTo("VALUE");
        assertThat(acquisitions).containsExactly("high", "low");
    }

    private static LockManager manager(String name, int order, List<String> acquisitions) {
        return new LockManager() {
            @Override
            public Optional<LockHandle> tryAcquire(String key, long timeoutSeconds) {
                acquisitions.add(name);
                return Optional.of(() -> { });
            }

            @Override
            public int getOrder() {
                return order;
            }
        };
    }
}
