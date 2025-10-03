package com.david.spring.cache.redis;

import java.util.concurrent.atomic.AtomicInteger;

public class BasicService {
    protected final AtomicInteger callCount = new AtomicInteger(0);

    public int getCallCount() {
        return callCount.get();
    }

    public void resetCallCount() {
        callCount.set(0);
    }
}
