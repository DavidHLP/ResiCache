package com.david.spring.cache.redis.core.writer;

import com.david.spring.cache.redis.core.writer.support.TtlSupport;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WriterChainableUtils {
    private final TtlSupport ttlSupport;

    public TtlSupport TtlSupport() {
        return ttlSupport;
    }
}
