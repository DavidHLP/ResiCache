package io.github.davidhlp.spring.cache.redis.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** The two production policy builders implement the typed common mapping contract. */
@DisplayName("RedisCacheAttributeSink 契约 (单一真相)")
class RedisCacheAttributeSinkContractTest {

    @Test
    @DisplayName("两个 Operation Builder 均实现 RedisCacheAttributeSink —— seam 有 2 个真实 adapter")
    void twoBuildersAreRealAdapters() {
        assertThat(RedisCacheAttributeSink.class)
                .as("Cacheable Builder is an adapter")
                .isAssignableFrom(RedisCacheableOperation.Builder.class);
        assertThat(RedisCacheAttributeSink.class)
                .as("Put Builder is an adapter")
                .isAssignableFrom(RedisCachePutOperation.Builder.class);
    }

    @Test
    @DisplayName("接口暴露且仅暴露 12 个共享 setter —— 单一真相的形状")
    void interfaceDeclaresTwelveSharedSetters() {
        assertThat(RedisCacheAttributeSink.class.getMethods())
                .as("RedisCacheAttributeSink declares exactly the 12 common setters")
                .hasSize(12);
    }
}
