package com.david.spring.cache.redis.core.writer;

import com.david.spring.cache.redis.core.writer.support.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WriterChainableUtils {
    private final TtlSupport ttlSupport;
    private final TypeSupport typeSupport;
    private final SyncSupport syncSupport;
    private final NullValueSupport nullValueSupport;
    private final BloomFilterSupport bloomFilterSupport;

    public TtlSupport TtlSupport() {
        return ttlSupport;
    }

    public TypeSupport TypeSupport() {
        return typeSupport;
    }

    public SyncSupport SyncSupport() {
        return syncSupport;
    }

    public NullValueSupport NullValueSupport() {
        return nullValueSupport;
    }

    public BloomFilterSupport BloomFilterSupport() {
        return bloomFilterSupport;
    }
}
