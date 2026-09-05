package io.github.davidhlp.spring.cache.redis.cache;




import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RedisCacheAttributeSink} 契约测试。
 *
 * <p>本测试是"14 共享字段单一真相 seam"的<b>可执行规约</b>:断言三个 Operation Builder
 * 均实现 {@link RedisCacheAttributeSink} 接口(即该 seam 有 3 个真实 adapter,而非假想 seam)。
 * 漂移守护本身由编译期承担(Builder 缺方法即编译失败);本测试固化"哪 3 个类是 adapter"
 * 这一导航事实,防止未来有人误删 {@code implements} 子句。
 *
 * <p>字段填充的行为正确性(14 字段是否真正写入 Builder)由
 * {@code OperationFromAttributesTest} + {@code RedisCacheAttributesProjectorTest} 作为回归
 * oracle 覆盖;本测试不重复。
 */
@DisplayName("RedisCacheAttributeSink 契约 (单一真相)")
class RedisCacheAttributeSinkContractTest {

    @Test
    @DisplayName("三个 Operation Builder 均实现 RedisCacheAttributeSink —— seam 有 3 个真实 adapter")
    void threeBuildersAreRealAdapters() {
        assertThat(RedisCacheAttributeSink.class)
                .as("Cacheable Builder is an adapter")
                .isAssignableFrom(RedisCacheableOperation.Builder.class);
        assertThat(RedisCacheAttributeSink.class)
                .as("Put Builder is an adapter")
                .isAssignableFrom(RedisCachePutOperation.Builder.class);
        assertThat(RedisCacheAttributeSink.class)
                .as("Evict Builder is an adapter")
                .isAssignableFrom(RedisCacheEvictOperation.Builder.class);
    }

    @Test
    @DisplayName("接口暴露且仅暴露 14 个共享 setter —— 单一真相的形状")
    void interfaceDeclaresFourteenSharedSetters() {
        assertThat(RedisCacheAttributeSink.class.getMethods())
                .as("RedisCacheAttributeSink declares exactly the 14 common setters")
                .hasSize(14);
    }
}
