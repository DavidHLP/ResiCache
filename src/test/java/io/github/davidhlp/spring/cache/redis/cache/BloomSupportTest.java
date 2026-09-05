package io.github.davidhlp.spring.cache.redis.cache;




import io.github.davidhlp.spring.cache.redis.protection.bloom.filter.BloomIFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BloomSupport 单元测试。
 *
 * <p>验证 Bloom 代理在底层异常时 fail-open；rebuilding marker 不属于该模块。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BloomSupport Tests")
class BloomSupportTest {

    private static final String CACHE = "cache";

    @Mock
    private BloomIFilter bloomFilter;

    private BloomSupport bloomSupport;

    @BeforeEach
    void setUp() {
        bloomSupport = new BloomSupport(bloomFilter);
    }

    @Test
    @DisplayName("delegates mightContain to the underlying filter")
    void mightContain_delegatesToFilter() {
        when(bloomFilter.mightContain(CACHE, "key")).thenReturn(true);

        assertThat(bloomSupport.mightContain(CACHE, "key")).isTrue();
        verify(bloomFilter).mightContain(CACHE, "key");
    }

    @Test
    @DisplayName("returns false when the underlying filter returns false")
    void mightContain_filterReturnsFalse_returnsFalse() {
        when(bloomFilter.mightContain(CACHE, "key")).thenReturn(false);

        assertThat(bloomSupport.mightContain(CACHE, "key")).isFalse();
    }

    @Test
    @DisplayName("fails open when the underlying filter fails")
    void mightContain_filterThrows_returnsTrue() {
        when(bloomFilter.mightContain(CACHE, "key"))
                .thenThrow(new RuntimeException("Filter error"));

        assertThat(bloomSupport.mightContain(CACHE, "key")).isTrue();
    }

    @Test
    @DisplayName("delegates add to the underlying filter")
    void add_delegatesToFilter() {
        bloomSupport.add(CACHE, "key");

        verify(bloomFilter).add(CACHE, "key");
    }

    @Test
    @DisplayName("swallows add failures so cache writes can continue")
    void add_filterThrows_doesNotPropagate() {
        doThrow(new RuntimeException("Filter error"))
                .when(bloomFilter).add(CACHE, "key");

        bloomSupport.add(CACHE, "key");

        verify(bloomFilter).add(CACHE, "key");
    }

    @Test
    @DisplayName("clear remains an explicit filter operation, not cache CLEAN")
    void clear_delegatesToFilter() {
        bloomSupport.clear(CACHE);

        verify(bloomFilter).clear(CACHE);
    }

    @Test
    @DisplayName("swallows clear failures")
    void clear_filterThrows_doesNotPropagate() {
        doThrow(new RuntimeException("clear error"))
                .when(bloomFilter).clear(CACHE);

        bloomSupport.clear(CACHE);

        verify(bloomFilter).clear(CACHE);
    }
}
