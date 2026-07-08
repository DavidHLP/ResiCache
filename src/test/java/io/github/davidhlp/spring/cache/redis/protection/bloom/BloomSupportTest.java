package io.github.davidhlp.spring.cache.redis.protection.bloom;

import io.github.davidhlp.spring.cache.redis.protection.bloom.filter.BloomIFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * BloomSupport 单元测试。
 *
 * <p>ADR-0058 收敛后,本测试聚焦"代理 + fail-open"契约(rebuilding 窗口状态机细节
 * 由 {@link BloomRebuilder} 独立覆盖,见 {@link BloomRebuilderTest})。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BloomSupport Tests")
class BloomSupportTest {

    private static final String CACHE = "cache";

    @Mock
    private BloomIFilter bloomFilter;

    @Mock
    private BloomRebuilder rebuilder;

    private BloomSupport bloomSupport;

    @BeforeEach
    void setUp() {
        // 默认 rebuilder 不在 rebuilding 窗口(返回 false),window 禁用态(0)与启用态(>0)
        // 由 BloomRebuilderTest 覆盖。
        when(rebuilder.isRebuilding(CACHE)).thenReturn(false);
        bloomSupport = new BloomSupport(bloomFilter, rebuilder);
    }

    @Nested
    @DisplayName("mightContain")
    class MightContainTests {

        @Test
        @DisplayName("非 rebuilding 期委托给底层过滤器并返回结果")
        void mightContain_delegatesToFilter() {
            when(bloomFilter.mightContain(CACHE, "key")).thenReturn(true);

            boolean result = bloomSupport.mightContain(CACHE, "key");

            assertThat(result).isTrue();
            verify(bloomFilter).mightContain(CACHE, "key");
        }

        @Test
        @DisplayName("过滤器返回 false 时返回 false")
        void mightContain_filterReturnsFalse_returnsFalse() {
            when(bloomFilter.mightContain(CACHE, "key")).thenReturn(false);

            assertThat(bloomSupport.mightContain(CACHE, "key")).isFalse();
        }

        @Test
        @DisplayName("底层过滤器异常时 fail-open 返回 true")
        void mightContain_filterThrows_returnsTrue() {
            when(bloomFilter.mightContain(CACHE, "key")).thenThrow(new RuntimeException("Filter error"));

            assertThat(bloomSupport.mightContain(CACHE, "key")).isTrue();
        }

        @Test
        @DisplayName("rebuilding 期内 fail-open,不查底层 bloom")
        void mightContain_duringRebuilding_failOpens() {
            when(rebuilder.isRebuilding(CACHE)).thenReturn(true);

            boolean result = bloomSupport.mightContain(CACHE, "key");

            assertThat(result).isTrue();
            verify(bloomFilter, never()).mightContain(anyString(), anyString());
        }

        @Test
        @DisplayName("rebuilder 为 null 时仍走底层 bloom 路径(向后兼容)")
        void mightContain_nullRebuilder_fallsThroughToFilter() {
            BloomSupport noRebuilder = new BloomSupport(bloomFilter, null);
            when(bloomFilter.mightContain(CACHE, "key")).thenReturn(true);

            assertThat(noRebuilder.mightContain(CACHE, "key")).isTrue();
        }
    }

    @Nested
    @DisplayName("add")
    class AddTests {

        @Test
        @DisplayName("委托给底层过滤器添加键")
        void add_delegatesToFilter() {
            bloomSupport.add(CACHE, "key");
            verify(bloomFilter).add(CACHE, "key");
        }

        @Test
        @DisplayName("过滤器异常时只记录日志不抛出")
        void add_filterThrows_noException() {
            doThrow(new RuntimeException("Filter error")).when(bloomFilter).add(CACHE, "key");

            bloomSupport.add(CACHE, "key");

            verify(bloomFilter).add(CACHE, "key");
        }
    }

    @Nested
    @DisplayName("clear")
    class ClearTests {

        @Test
        @DisplayName("clear 委托底层并通知 rebuilder 开启窗口")
        void clear_delegatesAndMarksRebuilding() {
            bloomSupport.clear(CACHE);

            verify(bloomFilter).clear(CACHE);
            verify(rebuilder).markRebuilding(CACHE);
        }

        @Test
        @DisplayName("底层 clear 异常时仍通知 rebuilder 开启窗口")
        void clear_filterThrows_stillMarksRebuilding() {
            doThrow(new RuntimeException("clear error")).when(bloomFilter).clear(CACHE);

            bloomSupport.clear(CACHE);

            verify(rebuilder).markRebuilding(CACHE);
        }

        @Test
        @DisplayName("rebuilder 为 null 时仍清空底层 bloom(向后兼容)")
        void clear_nullRebuilder_stillDelegatesToFilter() {
            BloomSupport noRebuilder = new BloomSupport(bloomFilter, null);

            noRebuilder.clear(CACHE);

            verify(bloomFilter).clear(CACHE);
        }
    }
}
