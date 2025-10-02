package com.david.spring.cache.redis.core.writer.support;

import java.io.Serial;
import java.io.Serializable;

class NullValue implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    @Override
    public int hashCode() {
        return NullValue.class.hashCode();
    }

    @Override
    public String toString() {
        return "NULL_VALUE";
    }
}
