# ResiCache Cache Event Listeners

ResiCache publishes cache-related events that can be consumed using Spring's `@EventListener` mechanism.

## Event Types

| Event | Description |
|-------|-------------|
| `CacheHitEvent` | Published when a cache hit occurs |
| `CacheMissEvent` | Published when a cache miss occurs |
| `PreRefreshTriggeredEvent` | Published when a pre-refresh is triggered |
| `TtlExpiryEvent` | Published when a TTL expires |

## Event Structure

### CacheHitEvent

Published when requested data is found in the cache.

**Fields:**

| Field | Type | Description |
|-------|------|-------------|
| `cacheName` | String | Name of the cache |
| `key` | Object | Cache key that was hit |
| `timestamp` | Instant | When the hit occurred |
| `ttl` | Duration | Remaining TTL at time of hit |

### CacheMissEvent

Published when requested data is not found in the cache.

**Fields:**

| Field | Type | Description |
|-------|------|-------------|
| `cacheName` | String | Name of the cache |
| `key` | Object | Cache key that missed |
| `timestamp` | Instant | When the miss occurred |

### PreRefreshTriggeredEvent

Published when a pre-refresh operation is triggered for a cache entry.

**Fields:**

| Field | Type | Description |
|-------|------|-------------|
| `cacheName` | String | Name of the cache |
| `key` | Object | Cache key being pre-refreshed |
| `timestamp` | Instant | When pre-refresh was triggered |
| `reason` | String | Reason for pre-refresh (e.g., "HIT_COUNT_THRESHOLD", "MANUAL") |

### TtlExpiryEvent

Published when a cache entry expires due to TTL.

**Fields:**

| Field | Type | Description |
|-------|------|-------------|
| `cacheName` | String | Name of the cache |
| `key` | Object | Cache key that expired |
| `timestamp` | Instant | When the expiry occurred |
| `expiredTtl` | Duration | Original TTL that was set |

## Configuration

### Enable Event Publishing

Events are published automatically when ResiCache is configured. No additional setup is required beyond including the ResiCache dependency.

### Event Listener Configuration

```yaml
# application.yml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}

resi-cache:
  default-ttl: 30m
  serializer:
    allowed-package-prefixes:
      - io.github.davidhlp
      - com.example.business
```

## Event Listener Examples

### Basic Event Listener

```java
import io.github.davidhlp.spring.cache.redis.core.event.CacheHitEvent;
import io.github.davidhlp.spring.cache.redis.core.event.CacheMissEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class CacheMetricsListener {

    private long hitCount = 0;
    private long missCount = 0;

    @EventListener
    public void onCacheHit(CacheHitEvent event) {
        hitCount++;
        log.info("Cache HIT - cache: {}, key: {}, remainingTTL: {}",
            event.getCacheName(),
            event.getKey(),
            event.getTtl());
    }

    @EventListener
    public void onCacheMiss(CacheMissEvent event) {
        missCount++;
        log.info("Cache MISS - cache: {}, key: {}",
            event.getCacheName(),
            event.getKey());
    }

    public double getHitRate() {
        long total = hitCount + missCount;
        return total > 0 ? (double) hitCount / total : 0.0;
    }
}
```

### Async Event Processing

For high-throughput scenarios, process events asynchronously:

```java
import io.github.davidhlp.spring.cache.redis.core.event.TtlExpiryEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class TtlExpiryHandler {

    @Async
    @EventListener
    public void onTtlExpiry(TtlExpiryEvent event) {
        log.info("TTL EXPIRED - cache: {}, key: {}, originalTTL: {}",
            event.getCacheName(),
            event.getKey(),
            event.getExpiredTtl());

        // Trigger external analytics, logging, or remediation
        analyticsService.recordExpiry(event.getCacheName(), event.getKey());
    }
}
```

### Pre-Refresh Monitoring

```java
import io.github.davidhlp.spring.cache.redis.core.event.PreRefreshTriggeredEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class PreRefreshMonitor {

    @EventListener
    public void onPreRefreshTriggered(PreRefreshTriggeredEvent event) {
        log.info("PRE-REFRESH TRIGGERED - cache: {}, key: {}, reason: {}, timestamp: {}",
            event.getCacheName(),
            event.getKey(),
            event.getReason(),
            event.getTimestamp());
    }
}
```

### Conditional Event Listeners

Process events only under certain conditions:

```java
import io.github.davidhlp.spring.cache.redis.core.event.CacheHitEvent;
import io.github.davidhlp.spring.cache.redis.core.event.CacheMissEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class SpecificCacheListener {

    // Only process events for the "users" cache
    @EventListener
    public void onUsersCacheHit(CacheHitEvent event) {
        if (!"users".equals(event.getCacheName())) {
            return;
        }
        log.debug("User cache hit for key: {}", event.getKey());
    }

    @EventListener
    public void onUsersCacheMiss(CacheMissEvent event) {
        if (!"users".equals(event.getCacheName())) {
            return;
        }
        log.debug("User cache miss for key: {}", event.getKey());
    }
}
```

### SpEL-based Event Filtering

Use SpEL expressions to filter events:

```java
import io.github.davidhlp.spring.cache.redis.core.event.CacheHitEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class HighTtlCacheListener {

    // Only process events where TTL is greater than 10 minutes
    @EventListener(condition = "#event.ttl?.toMinutes() > 10")
    public void onLongTtlHit(CacheHitEvent event) {
        log.info("Long TTL cache hit - cache: {}, key: {}, ttl: {}",
            event.getCacheName(),
            event.getKey(),
            event.getTtl());
    }
}
```

## Complete Spring Boot 3.x Configuration Example

```yaml
# application.yml
spring:
  application:
    name: resicache-demo
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      timeout: 5000ms
  main:
    allow-bean-definition-overriding: true

resi-cache:
  default-ttl: 30m
  serializer:
    allowed-package-prefixes:
      - io.github.davidhlp
      - com.example.business
  bloom-filter:
    enabled: true
    expected-insertions: 100000
    false-probability: 0.01
  pre-refresh:
    enabled: true
    core-pool-size: 4
    max-pool-size: 10
    queue-capacity: 100
  lock:
    enabled: true
    wait-time: 5
    lease-time: 30
  ttl:
    variance: 0.2

# Enable async processing for event listeners
spring.task.execution:
  pool:
    core-size: 4
    max-size: 16
    queue-capacity: 100
  thread-name-prefix: resicache-event-
```

## Event Ordering

Events are published in the order they occur. If you need ordered processing:

```java
import io.github.davidhlp.spring.cache.redis.core.event.CacheHitEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class OrderedListener {

    @EventListener
    @Order(1)
    public void first(CacheHitEvent event) {
        log.debug("First listener: {}", event.getKey());
    }

    @EventListener
    @Order(2)
    public void second(CacheHitEvent event) {
        log.debug("Second listener: {}", event.getKey());
    }
}
```

## Testing Event Listeners

```java
import io.github.davidhlp.spring.cache.redis.core.event.CacheHitEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class CacheEventListenerTest {

    @Autowired
    private TestEventListener testListener;

    @Test
    void shouldReceiveCacheHitEvent() {
        // ... trigger cache hit ...

        assertThat(testListener.getReceivedEvents())
            .hasSize(1)
            .first()
            .isInstanceOf(CacheHitEvent.class);
    }

    @Component
    static class TestEventListener {
        private final CopyOnWriteArrayList<Object> events = new CopyOnWriteArrayList<>();

        @EventListener
        public void onAny(Object event) {
            events.add(event);
        }

        public CopyOnWriteArrayList<Object> getReceivedEvents() {
            return events;
        }
    }
}
```

## Best Practices

1. **Use async for expensive operations** - Event listeners run synchronously by default
2. **Filter early** - Use `@EventListener(condition = ...)` or method-level checks
3. **Don't block** - Avoid long-running operations in synchronous listeners
4. **Handle exceptions** - Consider using `@Retryable` for transient failures
5. **Monitor memory** - Don't store events in unbounded collections
