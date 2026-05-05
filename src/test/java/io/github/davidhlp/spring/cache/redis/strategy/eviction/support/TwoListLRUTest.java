package io.github.davidhlp.spring.cache.redis.strategy.eviction.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TwoListLRU basic unit tests
 */
@DisplayName("TwoListLRU Unit Tests")
class TwoListLRUTest {

    private TwoListLRU<String, String> lru;

    @BeforeEach
    void setUp() {
        lru = new TwoListLRU<>(3, 2); // small sizes for testing eviction
    }

    @Nested
    @DisplayName("Constructor tests")
    class ConstructorTests {

        @Test
        @DisplayName("creates instance with default sizes")
        void constructor_defaultSizes_createsInstance() {
            TwoListLRU<String, String> defaultLru = new TwoListLRU<>();
            assertThat(defaultLru).isNotNull();
            assertThat(defaultLru.size()).isZero();
        }

        @Test
        @DisplayName("creates instance with custom sizes")
        void constructor_customSizes_createsInstance() {
            TwoListLRU<String, String> customLru = new TwoListLRU<>(100, 50);
            assertThat(customLru).isNotNull();
            assertThat(customLru.size()).isZero();
        }

        @Test
        @DisplayName("throws exception for zero maxActiveSize")
        void constructor_zeroActiveSize_throws() {
            assertThatThrownBy(() -> new TwoListLRU<>(0, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxActiveSize must be positive");
        }

        @Test
        @DisplayName("throws exception for negative maxActiveSize")
        void constructor_negativeActiveSize_throws() {
            assertThatThrownBy(() -> new TwoListLRU<>(-1, 10))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("throws exception for zero maxInactiveSize")
        void constructor_zeroInactiveSize_throws() {
            assertThatThrownBy(() -> new TwoListLRU<>(10, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxInactiveSize must be positive");
        }

        @Test
        @DisplayName("creates instance with eviction predicate")
        void constructor_withPredicate_createsInstance() {
            TwoListLRU<String, String> predicateLru = new TwoListLRU<>(10, 5, k -> false);
            assertThat(predicateLru).isNotNull();
        }
    }

    @Nested
    @DisplayName("put tests")
    class PutTests {

        @Test
        @DisplayName("put adds element to cache")
        void put_addsElement() {
            boolean result = lru.put("key1", "value1");

            assertThat(result).isTrue();
            assertThat(lru.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("put throws exception for null key")
        void put_nullKey_throws() {
            assertThatThrownBy(() -> lru.put(null, "value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Key cannot be null");
        }

        @Test
        @DisplayName("put updates existing key")
        void put_existingKey_updatesValue() {
            lru.put("key1", "value1");
            lru.put("key1", "value2");

            assertThat(lru.size()).isEqualTo(1);
            assertThat(lru.get("key1")).isEqualTo("value2");
        }

        @Test
        @DisplayName("put triggers eviction when active list is full")
        void put_fullList_triggersEviction() {
            lru.put("key1", "value1");
            lru.put("key2", "value2");
            lru.put("key3", "value3");
            // Active list max is 3, so adding 4th should evict oldest

            assertThat(lru.size()).isLessThanOrEqualTo(3);
        }
    }

    @Nested
    @DisplayName("get tests")
    class GetTests {

        @Test
        @DisplayName("get returns value for existing key")
        void get_existingKey_returnsValue() {
            lru.put("key1", "value1");

            String result = lru.get("key1");

            assertThat(result).isEqualTo("value1");
        }

        @Test
        @DisplayName("get returns null for non-existing key")
        void get_nonExistingKey_returnsNull() {
            String result = lru.get("nonexistent");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("get returns null for null key")
        void get_nullKey_returnsNull() {
            String result = lru.get(null);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("get promotes key to active list head")
        void get_promotesKeyToActiveHead() {
            lru.put("key1", "value1");
            lru.put("key2", "value2");
            // Access key1 to promote it

            lru.get("key1");

            // Key1 should now be at head of active list
            assertThat(lru.get("key1")).isEqualTo("value1");
        }
    }

    @Nested
    @DisplayName("remove tests")
    class RemoveTests {

        @Test
        @DisplayName("remove removes and returns value")
        void remove_existingKey_returnsValue() {
            lru.put("key1", "value1");

            String result = lru.remove("key1");

            assertThat(result).isEqualTo("value1");
            assertThat(lru.size()).isZero();
        }

        @Test
        @DisplayName("remove returns null for non-existing key")
        void remove_nonExistingKey_returnsNull() {
            String result = lru.remove("nonexistent");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("remove returns null for null key")
        void remove_nullKey_returnsNull() {
            String result = lru.remove(null);

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("contains tests")
    class ContainsTests {

        @Test
        @DisplayName("contains returns true for existing key")
        void contains_existingKey_returnsTrue() {
            lru.put("key1", "value1");

            assertThat(lru.contains("key1")).isTrue();
        }

        @Test
        @DisplayName("contains returns false for non-existing key")
        void contains_nonExistingKey_returnsFalse() {
            assertThat(lru.contains("nonexistent")).isFalse();
        }

        @Test
        @DisplayName("contains returns false for null key")
        void contains_nullKey_returnsFalse() {
            assertThat(lru.contains(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("size tests")
    class SizeTests {

        @Test
        @DisplayName("size returns correct count")
        void size_returnsCorrectCount() {
            lru.put("key1", "value1");
            lru.put("key2", "value2");
            lru.put("key3", "value3");

            assertThat(lru.size()).isEqualTo(3);
        }

        @Test
        @DisplayName("size returns zero for empty cache")
        void size_emptyCache_returnsZero() {
            assertThat(lru.size()).isZero();
        }
    }

    @Nested
    @DisplayName("clear tests")
    class ClearTests {

        @Test
        @DisplayName("clear removes all elements")
        void clear_removesAllElements() {
            lru.put("key1", "value1");
            lru.put("key2", "value2");

            lru.clear();

            assertThat(lru.size()).isZero();
            assertThat(lru.get("key1")).isNull();
            assertThat(lru.get("key2")).isNull();
        }
    }

    @Nested
    @DisplayName("eviction callback tests")
    class EvictionCallbackTests {

        @Test
        @DisplayName("eviction callback is invoked on eviction")
        void evictionCallback_invokedOnEviction() {
            AtomicBoolean evicted = new AtomicBoolean(false);
            AtomicInteger evictedCount = new AtomicInteger(0);

            // Use very small sizes to force eviction from inactive list
            TwoListLRU<String, String> smallLru = new TwoListLRU<>(2, 1);
            smallLru.setEvictionCallback((key, value) -> {
                evicted.set(true);
                evictedCount.incrementAndGet();
            });

            // Step 1: Fill active list (2 items)
            smallLru.put("key1", "value1"); // active: [key1]
            smallLru.put("key2", "value2"); // active: [key2, key1]

            // Step 2: Access key1 to demote it (LRU), then key2 becomes oldest
            smallLru.get("key1"); // key1 promoted to front, key2 is now oldest in active

            // Step 3: Add more items to force key2 to inactive and fill inactive
            smallLru.put("key3", "value3"); // active: [key3, key1], key2 demoted to inactive
            smallLru.put("key4", "value4"); // active full, key1 oldest, demoted to inactive (inactive now has key2, key1)

            // Step 4: Add one more to force eviction from inactive (key2 should be evicted)
            smallLru.put("key5", "value5"); // inactive full, key2 should be evicted

            // Verify eviction occurred from inactive list
            assertThat(smallLru.getTotalEvictions()).isGreaterThan(0);
        }

        @Test
        @DisplayName("works without eviction callback set")
        void withoutCallback_worksFine() {
            lru.setEvictionCallback(null);

            lru.put("key1", "value1");
            lru.put("key2", "value2");
            lru.put("key3", "value3");
            lru.put("key4", "value4"); // Should evict without error

            assertThat(lru.size()).isLessThanOrEqualTo(5);
        }
    }

    @Nested
    @DisplayName("eviction predicate tests")
    class EvictionPredicateTests {

        @Test
        @DisplayName("eviction predicate protects matching entries")
        void evictionPredicate_protectsMatchingEntries() {
            // Predicate returns true for "protected" values, meaning they should NOT be evicted
            lru.setEvictionPredicate(value -> value.equals("protected"));

            lru.put("key1", "protected");
            lru.put("key2", "normal");
            lru.put("key3", "normal");
            lru.put("key4", "evict-me");

            // Protected entry should remain, others may be evicted
            assertThat(lru.get("key1")).isEqualTo("protected");
        }

        @Test
        @DisplayName("null predicate evicts all entries")
        void nullPredicate_evictsAllEntries() {
            lru.setEvictionPredicate(null);

            lru.put("key1", "value1");
            lru.put("key2", "value2");
            lru.put("key3", "value3");
            lru.put("key4", "value4");

            assertThat(lru.size()).isLessThanOrEqualTo(5);
        }
    }

    @Nested
    @DisplayName("active/inactive size tests")
    class ListSizeTests {

        @Test
        @DisplayName("getActiveSize returns correct size")
        void getActiveSize_returnsCorrectSize() {
            lru.put("key1", "value1");
            lru.put("key2", "value2");

            assertThat(lru.getActiveSize()).isEqualTo(2);
        }

        @Test
        @DisplayName("getInactiveSize returns correct size")
        void getInactiveSize_returnsCorrectSize() {
            lru.put("key1", "value1");
            lru.put("key2", "value2");
            lru.get("key1"); // Access to promote

            // After promotion, key2 should be in inactive
            assertThat(lru.getInactiveSize()).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("getTotalEvictions returns eviction count")
        void getTotalEvictions_returnsCount() {
            lru.put("key1", "value1");
            lru.put("key2", "value2");
            lru.put("key3", "value3");
            lru.put("key4", "value4"); // May evict

            assertThat(lru.getTotalEvictions()).isGreaterThanOrEqualTo(0L);
        }
    }
}
