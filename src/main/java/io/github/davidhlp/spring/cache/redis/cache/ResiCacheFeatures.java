package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.protection.bloom.BloomGate;
import io.github.davidhlp.spring.cache.redis.protection.breakdown.SyncLockTimeout;
import io.github.davidhlp.spring.cache.redis.protection.breakdown.SyncSupport;
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
 * <p><b>可空语义</b>:每个字段为 {@code null} 表示对应特性未启用,消费方走 null-safe 降级路径
 * (与原逐参数可空行为字节等价)。{@link #none()} 提供「全部禁用」的测试便捷入口。
 */
@Value
@Builder
public class ResiCacheFeatures {

    /** 指标注册表 —— null 表示不采集 timer/counter(null-safe no-op). */
    @Nullable
    MeterRegistry meterRegistry;

    /** 布隆读侧穿透闸门 —— null 表示关闭缓存穿透防护(GET/loader 路径跳过布隆短路). */
    @Nullable
    BloomGate bloomGate;

    /** 方法级 operation 元数据解析器 —— null 表示关闭元数据查找. */
    @Nullable
    CacheOperationResolver operationResolver;

    /** 分布式同步锁支持 —— null 表示关闭分布式锁(loader 走 Spring 默认本地锁). */
    @Nullable
    SyncSupport syncSupport;

    /** 分布式锁超时解析规则 —— null 时回退内置默认(仅测试;生产始终装配). */
    @Nullable
    SyncLockTimeout syncLockTimeout;

    /** 全部特性禁用 —— 测试便捷入口. */
    public static ResiCacheFeatures none() {
        return ResiCacheFeatures.builder().build();
    }
}
