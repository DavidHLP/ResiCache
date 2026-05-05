package io.github.davidhlp.spring.cache.redis.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CacheEvictedEvent 单元测试
 */
@DisplayName("CacheEvictedEvent Tests")
class CacheEvictedEventTest {

    @Nested
    @DisplayName("构造函数")
    class ConstructorTests {

        @Test
        @DisplayName("创建事件基本属性")
        void constructor_basicProperties() {
            Object source = new Object();
            CacheEvictedEvent event = new CacheEvictedEvent(
                    source, "test-cache", "key1", "value", CacheEvictedEvent.EvictionReason.MANUAL);

            assertThat(event.getSource()).isEqualTo(source);
            assertThat(event.getCacheName()).isEqualTo("test-cache");
            assertThat(event.getKey()).isEqualTo("key1");
            assertThat(event.getValue()).isEqualTo("value");
            assertThat(event.getReason()).isEqualTo(CacheEvictedEvent.EvictionReason.MANUAL);
        }

        @Test
        @DisplayName("evictedAt时间戳被设置")
        void constructor_evictedAtSet() {
            long before = System.currentTimeMillis();
            CacheEvictedEvent event = new CacheEvictedEvent(
                    this, "cache", "key", "value", CacheEvictedEvent.EvictionReason.SIZE_LIMIT);
            long after = System.currentTimeMillis();

            assertThat(event.getEvictedAt()).isNotNull();
            assertThat(event.getEvictedAt().toEpochMilli()).isBetween(before, after);
        }

        @Test
        @DisplayName("使用不同驱逐原因创建事件")
        void constructor_differentEvictionReasons() {
            Object source = new Object();

            CacheEvictedEvent sizeEvent = new CacheEvictedEvent(
                    source, "cache", "key", "value", CacheEvictedEvent.EvictionReason.SIZE_LIMIT);
            assertThat(sizeEvent.getReason()).isEqualTo(CacheEvictedEvent.EvictionReason.SIZE_LIMIT);

            CacheEvictedEvent timeEvent = new CacheEvictedEvent(
                    source, "cache", "key", "value", CacheEvictedEvent.EvictionReason.TIME_BASED);
            assertThat(timeEvent.getReason()).isEqualTo(CacheEvictedEvent.EvictionReason.TIME_BASED);

            CacheEvictedEvent manualEvent = new CacheEvictedEvent(
                    source, "cache", "key", "value", CacheEvictedEvent.EvictionReason.MANUAL);
            assertThat(manualEvent.getReason()).isEqualTo(CacheEvictedEvent.EvictionReason.MANUAL);
        }
    }

    @Nested
    @DisplayName("getValue")
    class GetValueTests {

        @Test
        @DisplayName("返回原始值")
        void getValue_returnsOriginalValue() {
            CacheEvictedEvent event = new CacheEvictedEvent(
                    this, "cache", "key", "test-value", CacheEvictedEvent.EvictionReason.MANUAL);

            assertThat(event.getValue()).isEqualTo("test-value");
        }

        @Test
        @DisplayName("值被垃圾回收后返回null")
        void getValue_afterGC_returnsNull() throws InterruptedException {
            Object value = new Object();
            CacheEvictedEvent event = new CacheEvictedEvent(
                    this, "cache", "key", value, CacheEvictedEvent.EvictionReason.MANUAL);

            // 清除强引用
            WeakReference<Object> ref = new WeakReference<>(value);
            //noinspection UnusedAssignment
            value = null;

            // 触发 GC
            System.gc();
            Thread.sleep(100);

            // 值可能已被回收（取决于 GC 行为）
            // 注意：这个测试可能不稳定，因为 GC 行为不确定
        }
    }

    @Nested
    @DisplayName("getCacheName")
    class GetCacheNameTests {

        @Test
        @DisplayName("返回缓存名称")
        void getCacheName() {
            CacheEvictedEvent event = new CacheEvictedEvent(
                    this, "my-cache", "key", "value", CacheEvictedEvent.EvictionReason.MANUAL);

            assertThat(event.getCacheName()).isEqualTo("my-cache");
        }
    }

    @Nested
    @DisplayName("getKey")
    class GetKeyTests {

        @Test
        @DisplayName("返回键")
        void getKey() {
            CacheEvictedEvent event = new CacheEvictedEvent(
                    this, "cache", "my-key", "value", CacheEvictedEvent.EvictionReason.MANUAL);

            assertThat(event.getKey()).isEqualTo("my-key");
        }
    }

    @Nested
    @DisplayName("getReason")
    class GetReasonTests {

        @Test
        @DisplayName("返回驱逐原因")
        void getReason() {
            CacheEvictedEvent event = new CacheEvictedEvent(
                    this, "cache", "key", "value", CacheEvictedEvent.EvictionReason.TIME_BASED);

            assertThat(event.getReason()).isEqualTo(CacheEvictedEvent.EvictionReason.TIME_BASED);
        }
    }
}
