package io.github.davidhlp.spring.cache.redis.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LockHandle 单元测试
 */
@DisplayName("LockHandle Tests")
class LockHandleTest {

    @Nested
    @DisplayName("AutoCloseable行为")
    class AutoCloseableTests {

        @Test
        @DisplayName("实现AutoCloseable接口")
        void implementsAutoCloseable() {
            LockHandle handle = () -> {};
            assertThat(handle instanceof AutoCloseable).isTrue();
        }

        @Test
        @DisplayName("close方法被调用")
        void closeMethodCalled() {
            boolean[] closed = new boolean[1];
            LockHandle handle = () -> { closed[0] = true; };

            handle.close();

            assertThat(closed[0]).isTrue();
        }
    }
}
