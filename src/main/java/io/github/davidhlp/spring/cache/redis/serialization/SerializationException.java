package io.github.davidhlp.spring.cache.redis.serialization;

import org.springframework.core.NestedRuntimeException;

public class SerializationException extends NestedRuntimeException {

    /**
     * Internal cross-package bridge retained for source/binary compatibility.
     * Encoding and wire-format ownership live in {@link VersionEnvelope}.
     */
    public static final class EnvelopeCodec {
        private EnvelopeCodec() { }

        public static Object create(Object payload) {
            return VersionEnvelope.create(payload);
        }

        public static boolean isEnvelope(byte[] bytes) {
            return VersionEnvelope.isEnvelope(bytes);
        }

        public static Object read(com.fasterxml.jackson.databind.ObjectMapper mapper, byte[] bytes)
                throws java.io.IOException {
            return VersionEnvelope.read(mapper, bytes);
        }

        public static int version(Object envelope) {
            return ((VersionEnvelope) envelope).getVersion();
        }

        public static Object payload(Object envelope) {
            return ((VersionEnvelope) envelope).getPayload();
        }

        public static int currentVersion() {
            return VersionEnvelope.CURRENT_VERSION;
        }
    }

    public SerializationException(String msg) {
        super(msg);
    }

    public SerializationException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
