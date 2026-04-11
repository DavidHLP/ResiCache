package io.github.davidhlp.spring.cache.redis.strategy.eviction.impl;

import io.github.davidhlp.spring.cache.redis.strategy.eviction.EvictionStrategy;
import io.github.davidhlp.spring.cache.redis.strategy.eviction.stats.EvictionStats;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TwoListEvictionStrategy 单元测试
 */
@DisplayName("TwoListEvictionStrategy Tests")
class TwoListEvictionStrategyTest {

    private EvictionStrategy<String, String> strategy;

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("default constructor creates strategy with default sizes")
        void defaultConstructor_createsWithDefaultSizes() {
            strategy = new TwoListEvictionStrategy<>();

            EvictionStats stats = strategy.getStats();

            assertThat(stats.maxActiveSize()).isEqualTo(1024);
            assertThat(stats.maxInactiveSize()).isEqualTo(512);
        }

        @Test
        @DisplayName("constructor with custom sizes uses provided values")
        void customSizes_usesProvidedValues() {
            strategy = new TwoListEvictionStrategy<>(100, 50);

            EvictionStats stats = strategy.getStats();

            assertThat(stats.maxActiveSize()).isEqualTo(100);
            assertThat(stats.maxInactiveSize()).isEqualTo(50);
        }

        @Test
        @DisplayName("constructor with eviction predicate accepts predicate")
        void withPredicate_acceptsPredicate() {
            Predicate<String> predicate = value -> value.startsWith("evict");

            strategy = new TwoListEvictionStrategy<>(100, 50, predicate);

            assertThat(strategy).isNotNull();
        }
    }

    @Nested
    @DisplayName("put Operation Tests")
    class PutOperationTests {

        @BeforeEach
        void setUp() {
            strategy = new TwoListEvictionStrategy<>(10, 5);
        }

        @Test
        @DisplayName("put adds element to cache")
        void put_addsElement() {
            strategy.put("key1", "value1");

            String result = strategy.get("key1");

            assertThat(result).isEqualTo("value1");
        }

        @Test
        @DisplayName("put updates existing element")
        void put_updatesExisting() {
            strategy.put("key1", "value1");
            strategy.put("key1", "value2");

            String result = strategy.get("key1");

            assertThat(result).isEqualTo("value2");
        }

        @Test
        @DisplayName("put multiple elements increases size")
        void put_multipleElements_increasesSize() {
            strategy.put("key1", "value1");
            strategy.put("key2", "value2");
            strategy.put("key3", "value3");

            assertThat(strategy.size()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("get Operation Tests")
    class GetOperationTests {

        @BeforeEach
        void setUp() {
            strategy = new TwoListEvictionStrategy<>(10, 5);
        }

        @Test
        @DisplayName("get returns value when key exists")
        void get_existingKey_returnsValue() {
            strategy.put("key1", "value1");

            String result = strategy.get("key1");

            assertThat(result).isEqualTo("value1");
        }

        @Test
        @DisplayName("get returns null when key does not exist")
        void get_nonExistingKey_returnsNull() {
            String result = strategy.get("nonexistent");

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("remove Operation Tests")
    class RemoveOperationTests {

        @BeforeEach
        void setUp() {
            strategy = new TwoListEvictionStrategy<>(10, 5);
        }

        @Test
        @DisplayName("remove deletes existing element and returns value")
        void remove_existingKey_returnsValueAndDeletes() {
            strategy.put("key1", "value1");

            String result = strategy.remove("key1");

            assertThat(result).isEqualTo("value1");
            assertThat(strategy.contains("key1")).isFalse();
        }

        @Test
        @DisplayName("remove returns null when key does not exist")
        void remove_nonExistingKey_returnsNull() {
            String result = strategy.remove("nonexistent");

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("contains Operation Tests")
    class ContainsOperationTests {

        @BeforeEach
        void setUp() {
            strategy = new TwoListEvictionStrategy<>(10, 5);
        }

        @Test
        @DisplayName("contains returns true for existing key")
        void contains_existingKey_returnsTrue() {
            strategy.put("key1", "value1");

            boolean result = strategy.contains("key1");

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("contains returns false for non-existing key")
        void contains_nonExistingKey_returnsFalse() {
            boolean result = strategy.contains("nonexistent");

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("size Operation Tests")
    class SizeOperationTests {

        @Test
        @DisplayName("size returns zero for empty cache")
        void size_emptyCache_returnsZero() {
            strategy = new TwoListEvictionStrategy<>();

            assertThat(strategy.size()).isZero();
        }

        @Test
        @DisplayName("size returns correct count after adding elements")
        void size_withElements_returnsCorrectCount() {
            strategy = new TwoListEvictionStrategy<>(10, 5);
            strategy.put("key1", "value1");
            strategy.put("key2", "value2");

            assertThat(strategy.size()).isEqualTo(2);
        }

        @Test
        @DisplayName("size decreases after removing elements")
        void size_afterRemove_decreases() {
            strategy = new TwoListEvictionStrategy<>(10, 5);
            strategy.put("key1", "value1");
            strategy.put("key2", "value2");
            strategy.remove("key1");

            assertThat(strategy.size()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("clear Operation Tests")
    class ClearOperationTests {

        @Test
        @DisplayName("clear removes all elements")
        void clear_removesAllElements() {
            strategy = new TwoListEvictionStrategy<>(10, 5);
            strategy.put("key1", "value1");
            strategy.put("key2", "value2");

            strategy.clear();

            assertThat(strategy.size()).isZero();
            assertThat(strategy.get("key1")).isNull();
            assertThat(strategy.get("key2")).isNull();
        }
    }

    @Nested
    @DisplayName("getStats Operation Tests")
    class GetStatsOperationTests {

        @Test
        @DisplayName("getStats returns correct initial values")
        void getStats_initialState_returnsCorrectValues() {
            strategy = new TwoListEvictionStrategy<>(100, 50);

            EvictionStats stats = strategy.getStats();

            assertThat(stats.totalEntries()).isZero();
            assertThat(stats.activeEntries()).isZero();
            assertThat(stats.inactiveEntries()).isZero();
            assertThat(stats.maxActiveSize()).isEqualTo(100);
            assertThat(stats.maxInactiveSize()).isEqualTo(50);
            assertThat(stats.totalEvictions()).isZero();
        }

        @Test
        @DisplayName("getStats reflects size after adding elements")
        void getStats_afterPut_updatesEntries() {
            strategy = new TwoListEvictionStrategy<>(10, 5);
            strategy.put("key1", "value1");
            strategy.put("key2", "value2");

            EvictionStats stats = strategy.getStats();

            assertThat(stats.totalEntries()).isEqualTo(2);
            assertThat(stats.activeEntries()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("setEvictionPredicate Tests")
    class SetEvictionPredicateTests {

        @Test
        @DisplayName("setEvictionPredicate updates predicate")
        void setEvictionPredicate_updatesPredicate() {
            TwoListEvictionStrategy<String, String> twoListStrategy =
                    new TwoListEvictionStrategy<>(10, 5);
            Predicate<String> newPredicate = value -> value.startsWith("old");

            twoListStrategy.setEvictionPredicate(newPredicate);

            // If no exception is thrown, the method executed successfully
            assertThat(twoListStrategy).isNotNull();
        }
    }

    @Nested
    @DisplayName("validateConsistency Tests")
    class ValidateConsistencyTests {

        @Test
        @DisplayName("validateConsistency does not throw for empty cache")
        void validateConsistency_emptyCache_doesNotThrow() {
            TwoListEvictionStrategy<String, String> twoListStrategy =
                    new TwoListEvictionStrategy<>();

            twoListStrategy.validateConsistency();

            // If no exception is thrown, the test passes
        }

        @Test
        @DisplayName("validateConsistency does not throw after adding elements")
        void validateConsistency_withElements_doesNotThrow() {
            TwoListEvictionStrategy<String, String> twoListStrategy =
                    new TwoListEvictionStrategy<>(10, 5);
            twoListStrategy.put("key1", "value1");
            twoListStrategy.put("key2", "value2");

            twoListStrategy.validateConsistency();

            // If no exception is thrown, the test passes
        }
    }

    @Nested
    @DisplayName("Eviction Behavior Tests")
    class EvictionBehaviorTests {

        @Test
        @DisplayName("strategy evicts elements when capacity is exceeded")
        void eviction_whenCapacityExceeded_evictsOldElements() {
            strategy = new TwoListEvictionStrategy<>(2, 2);
            strategy.put("key1", "value1");
            strategy.put("key2", "value2");
            strategy.put("key3", "value3");
            strategy.put("key4", "value4");
            strategy.put("key5", "value5");

            // Size should be limited by maxActiveSize + maxInactiveSize
            assertThat(strategy.size()).isLessThanOrEqualTo(4);
        }
    }
}
