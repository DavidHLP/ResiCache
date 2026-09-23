package io.github.davidhlp.spring.cache.redis.cache;




import io.micrometer.core.instrument.MeterRegistry;
import lombok.Builder;
import lombok.Value;
import org.springframework.lang.Nullable;

/**
 * ResiCache 可选特性集合 — 把「哪些增强特性开启 + 其协作者」收口到单一值对象.
 *
 * <p>此前 {@link RedisProCache} 与 {@link RedisProCacheManager} 各自以一串<em>位置可空参数</em>
 * ({@code meterRegistry} / {@code bloomGate} / {@code operationResolver} / {@code syncSupport} …)
 * 承载相同的「null = 该特性禁用」契约,每个构造器的 Javadoc 各自重述一遍该契约,新增一个特性
 * 需同时改动多个构造器 + bean 装配 + 各自 Javadoc。本值对象让该契约<b>只存在一处</b>:消费方
 * 询问本对象,而非各自记忆可空语义;新增特性只动本类一处。
 *
 * <p><b>no-op seam 语义</b>:只有 {@code meterRegistry} 承载「指标禁用」信息,但它永不为
 * {@code null} —— 指标未启用（或应用无 {@code MeterRegistry} bean）时它是共享无状态的
 * {@link DisabledMetricsRegistry#INSTANCE}（唯一判据
 * {@link DisabledMetricsRegistry#isDisabledSeam(MeterRegistry)}），在其上的注册是 no-op
 * 分配:不发布、不保留任何 meter;其余字段是生产恒装配的协作对象,消费方构造期校验非 null
 * (装配错误即抛,不静默降级)。
 */
@Value
@Builder
class ResiCacheFeatures {

    /** 指标注册表 —— 永不为 null;关闭路径为共享 no-op seam(不采集 timer/counter). */
    @Nullable
    MeterRegistry meterRegistry;

    /** 布隆读侧穿透闸门 —— 生产恒装配. */
    BloomGate bloomGate;

    /** 方法级 operation 元数据解析器 —— 生产恒装配. */
    CacheOperationResolver operationResolver;

    /** 分布式同步锁支持 —— 生产恒装配. */
    SyncSupport syncSupport;

    /** 分布式锁超时解析规则 —— 生产恒装配. */
    SyncLockTimeout syncLockTimeout;
}
