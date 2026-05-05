package io.github.davidhlp.spring.cache.redis.strategy.eviction.stats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LockPoolStats 单元测试
 */
@DisplayName("LockPoolStats Tests")
class LockPoolStatsTest {

    @Nested
    @DisplayName("record 组件")
    class ComponentTests {

        @Test
        @DisplayName("创建实例并获取各组件")
        void createAndAccessComponents() {
            LockPoolStats stats = new LockPoolStats(
                    50, 30, 20, 40, 30,
                    1000, 900, 800, 200, 50);

            assertThat(stats.totalSize()).isEqualTo(50);
            assertThat(stats.activeSize()).isEqualTo(30);
            assertThat(stats.inactiveSize()).isEqualTo(20);
            assertThat(stats.maxActiveSize()).isEqualTo(40);
            assertThat(stats.maxInactiveSize()).isEqualTo(30);
            assertThat(stats.totalAcquires()).isEqualTo(1000);
            assertThat(stats.totalReleases()).isEqualTo(900);
            assertThat(stats.cacheHits()).isEqualTo(800);
            assertThat(stats.cacheMisses()).isEqualTo(200);
            assertThat(stats.totalEvictions()).isEqualTo(50);
        }
    }

    @Nested
    @DisplayName("hitRate")
    class HitRateTests {

        @Test
        @DisplayName("有命中和未命中时计算命中率")
        void withHitsAndMisses_calculatesCorrectly() {
            LockPoolStats stats = new LockPoolStats(
                    50, 30, 20, 40, 30,
                    1000, 900, 800, 200, 50);

            assertThat(stats.hitRate()).isEqualTo(0.8);
        }

        @Test
        @DisplayName("无命中和未命中时返回0")
        void noHitsOrMisses_returnsZero() {
            LockPoolStats stats = new LockPoolStats(
                    50, 30, 20, 40, 30,
                    1000, 900, 0, 0, 50);

            assertThat(stats.hitRate()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("只有命中时返回1")
        void onlyHits_returnsOne() {
            LockPoolStats stats = new LockPoolStats(
                    50, 30, 20, 40, 30,
                    1000, 900, 100, 0, 50);

            assertThat(stats.hitRate()).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("activeUtilization")
    class ActiveUtilizationTests {

        @Test
        @DisplayName("正常计算活跃锁使用率")
        void normalCalculation() {
            LockPoolStats stats = new LockPoolStats(
                    50, 20, 30, 40, 30,
                    1000, 900, 800, 200, 50);

            assertThat(stats.activeUtilization()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("maxActiveSize为0时返回0")
        void maxActiveSizeZero_returnsZero() {
            LockPoolStats stats = new LockPoolStats(
                    50, 20, 30, 0, 30,
                    1000, 900, 800, 200, 50);

            assertThat(stats.activeUtilization()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("100%使用率")
        void fullUtilization() {
            LockPoolStats stats = new LockPoolStats(
                    50, 40, 10, 40, 30,
                    1000, 900, 800, 200, 50);

            assertThat(stats.activeUtilization()).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("inactiveUtilization")
    class InactiveUtilizationTests {

        @Test
        @DisplayName("正常计算不活跃锁使用率")
        void normalCalculation() {
            LockPoolStats stats = new LockPoolStats(
                    50, 20, 15, 40, 30,
                    1000, 900, 800, 200, 50);

            assertThat(stats.inactiveUtilization()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("maxInactiveSize为0时返回0")
        void maxInactiveSizeZero_returnsZero() {
            LockPoolStats stats = new LockPoolStats(
                    50, 20, 15, 40, 0,
                    1000, 900, 800, 200, 50);

            assertThat(stats.inactiveUtilization()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("totalUtilization")
    class TotalUtilizationTests {

        @Test
        @DisplayName("正常计算总使用率")
        void normalCalculation() {
            LockPoolStats stats = new LockPoolStats(
                    35, 20, 15, 40, 30,
                    1000, 900, 800, 200, 50);

            // totalSize=35, maxTotal=70
            assertThat(stats.totalUtilization()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("总max为0时返回0")
        void maxTotalZero_returnsZero() {
            LockPoolStats stats = new LockPoolStats(
                    35, 20, 15, 0, 0,
                    1000, 900, 800, 200, 50);

            assertThat(stats.totalUtilization()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("estimatedMemoryKB")
    class EstimatedMemoryKBTests {

        @Test
        @DisplayName("计算内存占用")
        void calculation() {
            LockPoolStats stats = new LockPoolStats(
                    100, 50, 50, 80, 80,
                    1000, 900, 800, 200, 50);

            // 100 * 48 / 1024 = 4.6875 KB
            assertThat(stats.estimatedMemoryKB()).isEqualTo(4);
        }

        @Test
        @DisplayName("零锁时返回0")
        void zeroSize_returnsZero() {
            LockPoolStats stats = new LockPoolStats(
                    0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0);

            assertThat(stats.estimatedMemoryKB()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("toString包含所有信息")
        void toString_containsAllInfo() {
            LockPoolStats stats = new LockPoolStats(
                    50, 20, 15, 40, 30,
                    1000, 900, 800, 200, 50);

            String str = stats.toString();

            assertThat(str).contains("total=50");
            assertThat(str).contains("active=20/40");
            assertThat(str).contains("inactive=15/30");
            assertThat(str).contains("acquires=1000");
            assertThat(str).contains("releases=900");
            assertThat(str).contains("hits=800");
            assertThat(str).contains("misses=200");
            assertThat(str).contains("hitRate=");
            assertThat(str).contains("evictions=50");
        }
    }
}
