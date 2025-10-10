package io.github.davidhlp.spring.cache.redis.core.writer.support.type;

import org.springframework.core.NestedRuntimeException;

public class SerializationException extends NestedRuntimeException {

    public SerializationException(String msg) {
        super(msg);
    }

    public SerializationException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
