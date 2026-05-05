package io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom;

import io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom.filter.BloomIFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * BloomSupport 单元测试
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BloomSupport Tests")
class BloomSupportTest {

    @Mock
    private BloomIFilter bloomFilter;

    private BloomSupport bloomSupport;

    @BeforeEach
    void setUp() {
        bloomSupport = new BloomSupport(bloomFilter);
    }

    @Nested
    @DisplayName("mightContain")
    class MightContainTests {

        @Test
        @DisplayName("委托给底层过滤器并返回结果")
        void mightContain_delegatesToFilter() {
            when(bloomFilter.mightContain("cache", "key")).thenReturn(true);

            boolean result = bloomSupport.mightContain("cache", "key");

            assertThat(result).isTrue();
            verify(bloomFilter).mightContain("cache", "key");
        }

        @Test
        @DisplayName("过滤器返回false时返回false")
        void mightContain_filterReturnsFalse_returnsFalse() {
            when(bloomFilter.mightContain("cache", "key")).thenReturn(false);

            boolean result = bloomSupport.mightContain("cache", "key");

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("过滤器异常时返回true进行降级")
        void mightContain_filterThrows_returnsTrue() {
            when(bloomFilter.mightContain("cache", "key")).thenThrow(new RuntimeException("Filter error"));

            boolean result = bloomSupport.mightContain("cache", "key");

            // 降级策略：异常时返回true避免误拒绝
            assertThat(result).isTrue();
        }
    }

    @Nested
    @DisplayName("add")
    class AddTests {

        @Test
        @DisplayName("委托给底层过滤器添加键")
        void add_delegatesToFilter() {
            bloomSupport.add("cache", "key");

            verify(bloomFilter).add("cache", "key");
        }

        @Test
        @DisplayName("过滤器异常时只记录日志不抛出")
        void add_filterThrows_noException() {
            doThrow(new RuntimeException("Filter error")).when(bloomFilter).add("cache", "key");

            // 不应抛出异常
            bloomSupport.add("cache", "key");

            verify(bloomFilter).add("cache", "key");
        }
    }

    @Nested
    @DisplayName("clear")
    class ClearTests {

        @Test
        @DisplayName("委托给底层过滤器清理")
        void clear_delegatesToFilter() {
            bloomSupport.clear("cache");

            verify(bloomFilter).clear("cache");
        }

        @Test
        @DisplayName("过滤器异常时只记录日志不抛出")
        void clear_filterThrows_noException() {
            doThrow(new RuntimeException("Filter error")).when(bloomFilter).clear("cache");

            // 不应抛出异常
            bloomSupport.clear("cache");

            verify(bloomFilter).clear("cache");
        }
    }
}
