package com.david.spring.cache.redis.core.writer;

import com.david.spring.cache.redis.core.writer.support.NullValueSupport;
import com.david.spring.cache.redis.core.writer.support.SyncSupport;
import com.david.spring.cache.redis.core.writer.support.TtlSupport;
import com.david.spring.cache.redis.core.writer.support.TypeSupport;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WriterChainableUtils {
    private final TtlSupport ttlSupport;
    private final TypeSupport typeSupport;
    private final SyncSupport syncSupport;
    private final NullValueSupport nullValueSupport;

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
}
