package io.github.davidhlp.spring.cache.redis.strategy.eviction.stats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EvictionStats 单元测试
 */
@DisplayName("EvictionStats Tests")
class EvictionStatsTest {

    @Nested
    @DisplayName("record 组件")
    class ComponentTests {

        @Test
        @DisplayName("创建实例并获取各组件")
        void createAndAccessComponents() {
            EvictionStats stats = new EvictionStats(100, 80, 20, 100, 50, 500);

            assertThat(stats.totalEntries()).isEqualTo(100);
            assertThat(stats.activeEntries()).isEqualTo(80);
            assertThat(stats.inactiveEntries()).isEqualTo(20);
            assertThat(stats.maxActiveSize()).isEqualTo(100);
            assertThat(stats.maxInactiveSize()).isEqualTo(50);
            assertThat(stats.totalEvictions()).isEqualTo(500);
        }
    }

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("toString 返回格式化字符串")
        void toString_returnsFormattedString() {
            EvictionStats stats = new EvictionStats(100, 80, 20, 100, 50, 500);
            String str = stats.toString();

            assertThat(str).contains("total=100");
            assertThat(str).contains("active=80/100");
            assertThat(str).contains("inactive=20/50");
            assertThat(str).contains("evictions=500");
        }
    }

    @Nested
    @DisplayName("equals 和 hashCode")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("相同参数创建的对象相等")
        void sameParams_areEqual() {
            EvictionStats stats1 = new EvictionStats(100, 80, 20, 100, 50, 500);
            EvictionStats stats2 = new EvictionStats(100, 80, 20, 100, 50, 500);

            assertThat(stats1).isEqualTo(stats2);
            assertThat(stats1.hashCode()).isEqualTo(stats2.hashCode());
        }

        @Test
        @DisplayName("不同参数创建的对象不相等")
        void differentParams_areNotEqual() {
            EvictionStats stats1 = new EvictionStats(100, 80, 20, 100, 50, 500);
            EvictionStats stats2 = new EvictionStats(100, 80, 20, 100, 50, 600);

            assertThat(stats1).isNotEqualTo(stats2);
        }
    }
}
