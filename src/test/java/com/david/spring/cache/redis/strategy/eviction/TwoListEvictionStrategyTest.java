package com.david.spring.cache.redis.strategy.eviction;

import static org.assertj.core.api.Assertions.*;

import com.david.spring.cache.redis.strategy.eviction.impl.TwoListEvictionStrategy;
import com.david.spring.cache.redis.strategy.eviction.stats.EvictionStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TwoListEvictionStrategy 测试
 *
 * <p>职责说明：
 * - 本测试类仅测试 EvictionStrategy 接口的实现正确性
 * - 核心算法逻辑由 TwoListLRUTest 覆盖
 * - 重点验证策略接口适配、统计信息和参数配置
 */
@DisplayName("TwoListEvictionStrategy 接口适配测试")
public class TwoListEvictionStrategyTest {

    private TwoListEvictionStrategy<String, String> strategy;

    @BeforeEach
    void setUp() {
        strategy = new TwoListEvictionStrategy<>(5, 3);
    }

    @Test
    @DisplayName("应该正确实现 EvictionStrategy 接口")
    void shouldImplementEvictionStrategyInterface() {
        // 验证接口方法正常工作
        strategy.put("key1", "value1");
        assertThat(strategy.get("key1")).isEqualTo("value1");
        assertThat(strategy.contains("key1")).isTrue();
        assertThat(strategy.size()).isEqualTo(1);

        String removed = strategy.remove("key1");
        assertThat(removed).isEqualTo("value1");
        assertThat(strategy.size()).isZero();
    }

    @Test
    @DisplayName("应该正确返回统计信息")
    void shouldProvideCorrectStats() {
        // 添加元素并验证统计信息
        for (int i = 1; i <= 7; i++) {
            strategy.put("key" + i, "value" + i);
        }

        EvictionStats stats = strategy.getStats();
        assertThat(stats.totalEntries()).isEqualTo(7);
        assertThat(stats.activeEntries()).isEqualTo(5);
        assertThat(stats.inactiveEntries()).isEqualTo(2);
        assertThat(stats.maxActiveSize()).isEqualTo(5);
        assertThat(stats.maxInactiveSize()).isEqualTo(3);
        assertThat(stats.totalEvictions()).isZero();
    }

    @Test
    @DisplayName("应该支持淘汰判断器设置")
    void shouldSupportEvictionPredicate() {
        // 设置保护以"protected"开头的值
        strategy.setEvictionPredicate(value -> !value.startsWith("protected"));

        strategy.put("key1", "protected-value1");
        strategy.put("key2", "normal-value2");
        strategy.put("key3", "normal-value3");

        // 填满并触发淘汰
        for (int i = 4; i <= 10; i++) {
            strategy.put("key" + i, "normal-value" + i);
        }

        // 受保护的元素应该保留
        assertThat(strategy.contains("key1")).isTrue();
        assertThat(strategy.get("key1")).isEqualTo("protected-value1");
    }

    @Test
    @DisplayName("默认构造函数应该使用正确的默认容量")
    void shouldUseCorrectDefaultCapacity() {
        TwoListEvictionStrategy<String, String> defaultStrategy = new TwoListEvictionStrategy<>();

        EvictionStats stats = defaultStrategy.getStats();
        assertThat(stats.maxActiveSize()).isEqualTo(1024);
        assertThat(stats.maxInactiveSize()).isEqualTo(512);
    }

    @Test
    @DisplayName("clear 方法应该清空所有元素")
    void shouldClearAllElements() {
        for (int i = 1; i <= 5; i++) {
            strategy.put("key" + i, "value" + i);
        }

        assertThat(strategy.size()).isEqualTo(5);

        strategy.clear();

        assertThat(strategy.size()).isZero();
        EvictionStats stats = strategy.getStats();
        assertThat(stats.totalEntries()).isZero();
        assertThat(stats.activeEntries()).isZero();
        assertThat(stats.inactiveEntries()).isZero();
    }

    @Test
    @DisplayName("validateConsistency 应该不抛出异常")
    void shouldValidateConsistencyWithoutException() {
        for (int i = 1; i <= 10; i++) {
            strategy.put("key" + i, "value" + i);
        }

        assertThatCode(() -> strategy.validateConsistency()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("构造函数参数校验")
    void shouldValidateConstructorParameters() {
        assertThatThrownBy(() -> new TwoListEvictionStrategy<String, String>(0, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxActiveSize must be positive");

        assertThatThrownBy(() -> new TwoListEvictionStrategy<String, String>(5, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxInactiveSize must be positive");
    }

    @Test
    @DisplayName("带淘汰判断器的构造函数应该正常工作")
    void shouldWorkWithPredicateConstructor() {
        TwoListEvictionStrategy<String, String> strategyWithPredicate =
                new TwoListEvictionStrategy<>(3, 2, value -> !value.equals("protected"));

        strategyWithPredicate.put("key1", "protected");
        strategyWithPredicate.put("key2", "normal");

        // 填满触发淘汰
        for (int i = 3; i <= 7; i++) {
            strategyWithPredicate.put("key" + i, "normal");
        }

        // 受保护的值应该保留
        assertThat(strategyWithPredicate.contains("key1")).isTrue();
    }
}
