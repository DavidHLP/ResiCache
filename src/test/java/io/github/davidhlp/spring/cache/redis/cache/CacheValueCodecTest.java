package io.github.davidhlp.spring.cache.redis.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.davidhlp.spring.cache.redis.serialization.SerializationException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.cache.support.NullValue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CacheValueCodec} 的 value-bytes contract 测试。
 */
@DisplayName("CacheValueCodec Tests")
class CacheValueCodecTest {

    private CacheValueCodec codec;

    @BeforeEach
    void setUp() {
        codec = new CacheValueCodec(new ObjectMapper());
    }

    @Nested
    @DisplayName("toValueBytes")
    class ToValueBytesTests {

        @Test
        @DisplayName("序列化字符串")
        void toValueBytes_stringValue_returnsBytes() {
            byte[] result = codec.toValueBytes("test-value");

            assertThat(result).isNotEmpty();
        }

        @Test
        @DisplayName("序列化整数")
        void toValueBytes_integerValue_returnsBytes() {
            byte[] result = codec.toValueBytes(42);

            assertThat(result).isNotEmpty();
        }

        @Test
        @DisplayName("序列化复杂对象")
        void toValueBytes_object_returnsBytes() {
            byte[] result = codec.toValueBytes(new TestObject(1L, "test"));

            assertThat(result).isNotEmpty();
        }

        @Test
        @DisplayName("序列化NullValue使用Java序列化")
        void toValueBytes_nullValue_usesJavaSerialization() {
            byte[] result = codec.toValueBytes(NullValue.INSTANCE);

            assertThat((byte) result[0]).isEqualTo((byte) 0xAC);
            assertThat((byte) result[1]).isEqualTo((byte) 0xED);
        }

        @Test
        @DisplayName("Java null 同样写出 NullValue 占位字节(不与 NullValue.INSTANCE 分叉)")
        void toValueBytes_javaNull_writesNullValueBytes() {
            byte[] result = codec.toValueBytes(null);

            assertThat(result).isEqualTo(codec.toValueBytes(NullValue.INSTANCE));
            assertThat(codec.fromValueBytes(result)).isSameAs(NullValue.INSTANCE);
        }
    }

    @Nested
    @DisplayName("fromValueBytes")
    class FromValueBytesTests {

        @Test
        @DisplayName("空字节数组返回null")
        void fromValueBytes_emptyBytes_returnsNull() {
            assertThat(codec.fromValueBytes(new byte[0])).isNull();
        }

        @Test
        @DisplayName("反序列化字符串")
        void fromValueBytes_stringBytes_returnsString() {
            byte[] bytes = codec.toValueBytes("test-value");

            assertThat(codec.fromValueBytes(bytes)).isEqualTo("test-value");
        }

        @Test
        @DisplayName("反序列化整数")
        void fromValueBytes_integerBytes_returnsInteger() {
            byte[] bytes = codec.toValueBytes(42);

            assertThat(codec.fromValueBytes(bytes)).isEqualTo(42);
        }

        @Test
        @DisplayName("反序列化复杂对象为Map并保留字段")
        void fromValueBytes_objectBytes_returnsMap() {
            byte[] bytes = codec.toValueBytes(new TestObject(1L, "test"));
            Object result = codec.fromValueBytes(bytes);

            assertThat(result).isInstanceOf(java.util.Map.class);
            java.util.Map<?, ?> map = (java.util.Map<?, ?>) result;
            assertThat(map.get("id")).isEqualTo(1);
            assertThat(map.get("name")).isEqualTo("test");
        }

        @Test
        @DisplayName("NullValue的Java序列化可正确往返")
        void fromValueBytes_nullValueJavaSerialized_roundTrips() {
            byte[] javaSerialized = codec.toValueBytes(NullValue.INSTANCE);

            assertThat(codec.fromValueBytes(javaSerialized)).isSameAs(NullValue.INSTANCE);
        }

        @Test
        @DisplayName("非NullValue的Java序列化数据被拒绝")
        void fromValueBytes_nonNullValueJavaSerialized_rejected() {
            byte[] maliciousJavaSerialized;
            try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    ObjectOutputStream oos = new ObjectOutputStream(bos)) {
                oos.writeObject("malicious-non-null-payload");
                oos.flush();
                maliciousJavaSerialized = bos.toByteArray();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            assertThatThrownBy(() -> codec.fromValueBytes(maliciousJavaSerialized))
                    .isInstanceOf(SecurityException.class);
        }

        @Test
        @DisplayName("短于4字节的数据按JSON解析并失败")
        void fromValueBytes_shortBytes_throwsSerializationException() {
            assertThatThrownBy(() -> codec.fromValueBytes(new byte[]{0x01, 0x02, 0x03}))
                    .isInstanceOf(SerializationException.class);
        }

        @Test
        @DisplayName("garbage bytes surface as SerializationException")
        void fromValueBytes_garbage_throwsSerializationException() {
            assertThatThrownBy(() -> codec.fromValueBytes("garbage".getBytes()))
                    .isInstanceOf(SerializationException.class);
        }
    }

    @Nested
    @DisplayName("SerializationException")
    class SerializationExceptionTests {

        @Test
        @DisplayName("使用消息创建异常")
        void constructor_withMessage_createsException() {
            SerializationException exception = new SerializationException("Test message");

            assertThat(exception.getMessage()).isEqualTo("Test message");
            assertThat(exception.getCause()).isNull();
        }

        @Test
        @DisplayName("使用消息和原因创建异常")
        void constructor_withMessageAndCause_createsException() {
            RuntimeException cause = new RuntimeException("Cause");
            SerializationException exception = new SerializationException("Test message", cause);

            assertThat(exception.getMessage()).isEqualTo("Test message");
            assertThat(exception.getCause()).isEqualTo(cause);
        }
    }

    static class TestObject {
        private Long id;
        private String name;

        public TestObject() {
        }

        TestObject(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
