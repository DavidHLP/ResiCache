package com.david.spring.cache.redis.core.writer;

import com.david.spring.cache.redis.core.writer.support.*;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

/**
 * Writer 工具类外观（Facade 模式）
 * 提供对各个 Support 类的统一访问
 *
 * 注意：在新的责任链架构中，此类主要用于向后兼容
 * 新代码应该直接注入所需的 Support 类或使用责任链
 */
@Component
@RequiredArgsConstructor
@Getter
public class WriterChainableUtils {
    private final TtlSupport ttlSupport;
    private final TypeSupport typeSupport;
    private final SyncSupport syncSupport;
    private final NullValueSupport nullValueSupport;
    private final BloomFilterSupport bloomFilterSupport;
    private final PreRefreshSupport preRefreshSupport;

    // 以下方法用于向后兼容，保持原有的调用方式
    // 但推荐使用 Lombok 生成的 getter 方法

    /**
     * @deprecated 使用 {@link #getTtlSupport()} 代替
     */
    @Deprecated(since = "2.0", forRemoval = true)
    public TtlSupport TtlSupport() {
        return ttlSupport;
    }

    /**
     * @deprecated 使用 {@link #getTypeSupport()} 代替
     */
    @Deprecated(since = "2.0", forRemoval = true)
    public TypeSupport TypeSupport() {
        return typeSupport;
    }

    /**
     * @deprecated 使用 {@link #getSyncSupport()} 代替
     */
    @Deprecated(since = "2.0", forRemoval = true)
    public SyncSupport SyncSupport() {
        return syncSupport;
    }

    /**
     * @deprecated 使用 {@link #getNullValueSupport()} 代替
     */
    @Deprecated(since = "2.0", forRemoval = true)
    public NullValueSupport NullValueSupport() {
        return nullValueSupport;
    }

    /**
     * @deprecated 使用 {@link #getBloomFilterSupport()} 代替
     */
    @Deprecated(since = "2.0", forRemoval = true)
    public BloomFilterSupport BloomFilterSupport() {
        return bloomFilterSupport;
    }

    /**
     * @deprecated 使用 {@link #getPreRefreshSupport()} 代替
     */
    @Deprecated(since = "2.0", forRemoval = true)
    public PreRefreshSupport PreRefreshSupport() {
        return preRefreshSupport;
    }
}
