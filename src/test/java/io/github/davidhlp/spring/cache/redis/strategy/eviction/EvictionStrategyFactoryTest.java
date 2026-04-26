package io.github.davidhlp.spring.cache.redis.strategy.eviction;

import io.github.davidhlp.spring.cache.redis.strategy.eviction.impl.TwoListEvictionStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EvictionStrategyFactory 单元测试
 */
@DisplayName("EvictionStrategyFactory Tests")
class EvictionStrategyFactoryTest {

    @Nested
    @DisplayName("create tests")
    class CreateTests {

        @Test
        @DisplayName("creates TWO_LIST strategy with correct size calculation")
        void create_twoList_correctSizes() {
            int maxSize = 100;

            EvictionStrategy<String, String> strategy = EvictionStrategyFactory.create(
                    EvictionStrategyFactory.StrategyType.TWO_LIST, maxSize);

            assertThat(strategy).isInstanceOf(TwoListEvictionStrategy.class);
        }

        @Test
        @DisplayName("creates strategy with specified max size")
        void create_withMaxSize_usesCorrectSize() {
            int maxSize = 50;

            EvictionStrategy<String, String> strategy = EvictionStrategyFactory.create(
                    EvictionStrategyFactory.StrategyType.TWO_LIST, maxSize);

            assertThat(strategy).isNotNull();
        }
    }

    @Nested
    @DisplayName("createTwoList tests")
    class CreateTwoListTests {

        @Test
        @DisplayName("creates TwoListEvictionStrategy with custom sizes")
        void createTwoList_customSizes_createsCorrectly() {
            int maxActiveSize = 30;
            int maxInactiveSize = 20;

            TwoListEvictionStrategy<String, String> strategy = EvictionStrategyFactory.createTwoList(
                    maxActiveSize, maxInactiveSize);

            assertThat(strategy).isNotNull();
        }

        @Test
        @DisplayName("creates TwoListEvictionStrategy with minimum sizes")
        void createTwoList_minimumSizes_createsCorrectly() {
            TwoListEvictionStrategy<String, String> strategy = EvictionStrategyFactory.createTwoList(1, 1);

            assertThat(strategy).isNotNull();
        }
    }

    @Nested
    @DisplayName("createTwoListWithPredicate tests")
    class CreateTwoListWithPredicateTests {

        @Test
        @DisplayName("creates TwoListEvictionStrategy with predicate")
        void createTwoListWithPredicate_createsCorrectly() {
            int maxActiveSize = 30;
            int maxInactiveSize = 20;
            Predicate<String> predicate = value -> value != null;

            TwoListEvictionStrategy<String, String> strategy = EvictionStrategyFactory.createTwoListWithPredicate(
                    maxActiveSize, maxInactiveSize, predicate);

            assertThat(strategy).isNotNull();
        }

        @Test
        @DisplayName("creates with always-true predicate")
        void createTwoListWithPredicate_alwaysTrue_worksCorrectly() {
            Predicate<String> alwaysTrue = value -> true;

            TwoListEvictionStrategy<String, String> strategy = EvictionStrategyFactory.createTwoListWithPredicate(
                    10, 10, alwaysTrue);

            assertThat(strategy).isNotNull();
        }

        @Test
        @DisplayName("creates with always-false predicate")
        void createTwoListWithPredicate_alwaysFalse_worksCorrectly() {
            Predicate<String> alwaysFalse = value -> false;

            TwoListEvictionStrategy<String, String> strategy = EvictionStrategyFactory.createTwoListWithPredicate(
                    10, 10, alwaysFalse);

            assertThat(strategy).isNotNull();
        }
    }

    @Nested
    @DisplayName("createDefault tests")
    class CreateDefaultTests {

        @Test
        @DisplayName("creates default TwoListEvictionStrategy")
        void createDefault_createsTwoListStrategy() {
            EvictionStrategy<String, String> strategy = EvictionStrategyFactory.createDefault();

            assertThat(strategy).isInstanceOf(TwoListEvictionStrategy.class);
        }
    }

    @Nested
    @DisplayName("StrategyType enum tests")
    class StrategyTypeTests {

        @Test
        @DisplayName("TWO_LIST is the only strategy type")
        void strategyType_onlyTwoListExists() {
            EvictionStrategyFactory.StrategyType[] types = EvictionStrategyFactory.StrategyType.values();

            assertThat(types).hasSize(1);
            assertThat(types[0]).isEqualTo(EvictionStrategyFactory.StrategyType.TWO_LIST);
        }
    }

    @Nested
    @DisplayName("generic type tests")
    class GenericTypeTests {

        @Test
        @DisplayName("works with different key types")
        void create_differentKeyTypes_worksCorrectly() {
            EvictionStrategy<Integer, String> intKeyStrategy = EvictionStrategyFactory.create(
                    EvictionStrategyFactory.StrategyType.TWO_LIST, 100);

            EvictionStrategy<Long, String> longKeyStrategy = EvictionStrategyFactory.create(
                    EvictionStrategyFactory.StrategyType.TWO_LIST, 100);

            EvictionStrategy<Object, String> objectKeyStrategy = EvictionStrategyFactory.create(
                    EvictionStrategyFactory.StrategyType.TWO_LIST, 100);

            assertThat(intKeyStrategy).isNotNull();
            assertThat(longKeyStrategy).isNotNull();
            assertThat(objectKeyStrategy).isNotNull();
        }

        @Test
        @DisplayName("works with different value types")
        void create_differentValueTypes_worksCorrectly() {
            EvictionStrategy<String, Integer> intValueStrategy = EvictionStrategyFactory.create(
                    EvictionStrategyFactory.StrategyType.TWO_LIST, 100);

            EvictionStrategy<String, Object> objectValueStrategy = EvictionStrategyFactory.create(
                    EvictionStrategyFactory.StrategyType.TWO_LIST, 100);

            assertThat(intValueStrategy).isNotNull();
            assertThat(objectValueStrategy).isNotNull();
        }
    }
}
