package io.github.davidhlp.spring.cache.redis.cache;




import io.github.davidhlp.spring.cache.redis.protection.bloom.filter.BloomIFilter;
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
 * HierarchicalBloomIFilter 单元测试
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HierarchicalBloomIFilter Tests")
class HierarchicalBloomIFilterTest {

    @Mock
    private BloomIFilter localFilter;

    @Mock
    private BloomIFilter remoteFilter;

    private HierarchicalBloomIFilter hierarchicalFilter;

    @BeforeEach
    void setUp() {
        hierarchicalFilter = new HierarchicalBloomIFilter(localFilter, remoteFilter);
    }

    @Nested
    @DisplayName("add")
    class AddTests {

        @Test
        @DisplayName("adds key to both local and remote filters")
        void add_validKey_addsToBothFilters() {
            String cacheName = "test-cache";
            String key = "test-key";

            hierarchicalFilter.add(cacheName, key);

            verify(localFilter).add(cacheName, key);
            verify(remoteFilter).add(cacheName, key);
        }

        @Test
        @DisplayName("adds to local filter first then remote")
        void add_order_localFirstThenRemote() {
            String cacheName = "test-cache";
            String key = "test-key";

            hierarchicalFilter.add(cacheName, key);

            var inOrder = inOrder(localFilter, remoteFilter);
            inOrder.verify(localFilter).add(cacheName, key);
            inOrder.verify(remoteFilter).add(cacheName, key);
        }
    }

    @Nested
    @DisplayName("mightContain")
    class MightContainTests {

        @Test
        @DisplayName("returns true when local filter hits")
        void mightContain_localHit_returnsTrue() {
            String cacheName = "test-cache";
            String key = "test-key";
            when(localFilter.mightContain(cacheName, key)).thenReturn(true);

            boolean result = hierarchicalFilter.mightContain(cacheName, key);

            assertThat(result).isTrue();
            verify(remoteFilter, never()).mightContain(anyString(), anyString());
        }

        @Test
        @DisplayName("local miss checks remote and warms local on remote hit")
        void mightContain_localMiss_checksRemote() {
            String cacheName = "test-cache";
            String key = "test-key";
            when(localFilter.mightContain(cacheName, key)).thenReturn(false);
            when(remoteFilter.mightContain(cacheName, key)).thenReturn(true);

            boolean result = hierarchicalFilter.mightContain(cacheName, key);

            assertThat(result).isTrue();
            verify(remoteFilter).mightContain(cacheName, key);
            verify(localFilter).add(cacheName, key);
        }

        @Test
        @DisplayName("returns false when both filters miss")
        void mightContain_bothMiss_returnsFalse() {
            String cacheName = "test-cache";
            String key = "test-key";
            when(localFilter.mightContain(cacheName, key)).thenReturn(false);
            when(remoteFilter.mightContain(cacheName, key)).thenReturn(false);

            boolean result = hierarchicalFilter.mightContain(cacheName, key);

            assertThat(result).isFalse();
            verify(localFilter, never()).add(anyString(), anyString());
        }


    }

    @Nested
    @DisplayName("clear")
    class ClearTests {

        @Test
        @DisplayName("clears both local and remote filters")
        void clear_clearsBothFilters() {
            String cacheName = "test-cache";

            hierarchicalFilter.clear(cacheName);

            verify(localFilter).clear(cacheName);
            verify(remoteFilter).clear(cacheName);
        }

        @Test
        @DisplayName("clears local filter first then remote")
        void clear_order_localFirstThenRemote() {
            String cacheName = "test-cache";

            hierarchicalFilter.clear(cacheName);

            var inOrder = inOrder(localFilter, remoteFilter);
            inOrder.verify(localFilter).clear(cacheName);
            inOrder.verify(remoteFilter).clear(cacheName);
        }
    }

    @Test
    void nullCacheName_isPassedToBothFilters() {
        assertThat(hierarchicalFilter.mightContain(null, "key")).isFalse();
        verify(localFilter).mightContain(null, "key");
        verify(remoteFilter).mightContain(null, "key");
        verify(localFilter, never()).add(null, "key");
    }

    @Test
    void nullKey_isPassedToBothFilters() {
        assertThat(hierarchicalFilter.mightContain("cache", null)).isFalse();
        verify(localFilter).mightContain("cache", null);
        verify(remoteFilter).mightContain("cache", null);
        verify(localFilter, never()).add("cache", null);
    }
}
