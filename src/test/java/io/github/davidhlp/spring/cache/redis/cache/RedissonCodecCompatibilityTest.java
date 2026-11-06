package io.github.davidhlp.spring.cache.redis.cache;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.redisson.client.codec.Codec;
import org.redisson.client.handler.State;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixed old-byte checks. Never replace a removed codec automatically: a new
 * codec name does not establish compatibility with previously stored values.
 */
class RedissonCodecCompatibilityTest {

    private static final String VALUE = "resicache-pr47-legacy-codec-payload:你好";

    @Test
    void legacySnappyShortValueRemainsReadable() throws Exception {
        assertReadable("SnappyCodec", "short", VALUE,
                "9fc7282fd96f1be03f787f58cb98b34430f21dab8ab1eed3906df832550b76fb");
    }

    @Test
    void legacySnappyMultichunkValueRemainsReadable() throws Exception {
        assertReadable("SnappyCodec", "multichunk", VALUE.repeat(3000),
                "08bb9f1630b5337dd8421ad7ffbd7d6bb7b8c74e225bea2a8d8965a4ef7bc33f");
    }

    @Test
    void legacyKryoShortValueRemainsReadable() throws Exception {
        assertReadable("Kryo5Codec", "short", VALUE,
                "2b07de67988cf94ecfc1c6dc7e779df0a614e4f8126e583ab4402e0285746850");
    }

    @Test
    void legacyKryoMultichunkValueRemainsReadable() throws Exception {
        assertReadable("Kryo5Codec", "multichunk", VALUE.repeat(3000),
                "fb6e5391c6a0fc63462998bf17310bb64e304497f2c2538373fb9d32bace6e4e");
    }

    private void assertReadable(String name, String kind, String expected, String sha256) throws Exception {
        byte[] bytes;
        try (InputStream input = getClass().getResourceAsStream(
                "/redisson-compatibility/" + name + "-" + kind + ".bin")) {
            assertThat(input).as("immutable legacy fixture").isNotNull();
            bytes = input.readAllBytes();
        }
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                .as("fixture checksum").isEqualTo(sha256);
        // Reflection lets the same test source compile against a candidate
        // that removed the class. Such removal must fail this contract.
        Codec codec = (Codec) Class.forName("org.redisson.codec." + name).getConstructor().newInstance();
        ByteBuf buffer = Unpooled.wrappedBuffer(bytes);
        try {
            assertThat(codec.getValueDecoder().decode(buffer, new State())).isEqualTo(expected);
            assertThat(buffer.isReadable()).as("no unconsumed framing bytes").isFalse();
        } finally {
            buffer.release();
        }
    }
}
