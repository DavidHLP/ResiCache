package io.github.davidhlp.spring.cache.redis.serialization;



import org.springframework.core.NestedRuntimeException;

public class SerializationException extends NestedRuntimeException {

    /** Internal envelope bridge; keeps the wire-format type name in this package. */
    public static final class EnvelopeCodec {
        private EnvelopeCodec() {
        }

        public static Object create(Object payload) {
            return new VersionEnvelope(VersionEnvelope.CURRENT_VERSION, payload);
        }

        public static Object read(com.fasterxml.jackson.databind.ObjectMapper mapper, byte[] bytes)
                throws java.io.IOException {
            return mapper.readValue(bytes, VersionEnvelope.class);
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
