package com.david.spring.cache.redis.core.writer;

import com.david.spring.cache.redis.core.writer.support.TtlSupport;
import com.david.spring.cache.redis.core.writer.support.TypeSupport;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WriterChainableUtils {
    private final TtlSupport ttlSupport;
    private final TypeSupport typeSupport;

    public TtlSupport TtlSupport() {
        return ttlSupport;
    }

    public TypeSupport TypeSupport() {
        return typeSupport;
    }
}
