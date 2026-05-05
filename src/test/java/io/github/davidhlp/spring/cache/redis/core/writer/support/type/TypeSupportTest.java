package io.github.davidhlp.spring.cache.redis.core.writer.support.type;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.cache.support.NullValue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TypeSupport 单元测试
 */
@DisplayName("TypeSupport Tests")
class TypeSupportTest {

    private TypeSupport typeSupport;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        typeSupport = new TypeSupport(objectMapper);
    }

    @Nested
    @DisplayName("bytesToString")
    class BytesToStringTests {

        @Test
        @DisplayName("字节数组转换为字符串")
        void bytesToString_validBytes_returnsString() {
            byte[] bytes = "Hello World".getBytes();
            String result = typeSupport.bytesToString(bytes);
            assertThat(result).isEqualTo("Hello World");
        }

        @Test
        @DisplayName("空字节数组返回空字符串")
        void bytesToString_emptyBytes_returnsEmptyString() {
            byte[] bytes = new byte[0];
            String result = typeSupport.bytesToString(bytes);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("UTF-8中文字符转换正确")
        void bytesToString_chineseCharacters_returnsCorrectString() {
            byte[] bytes = "你好世界".getBytes();
            String result = typeSupport.bytesToString(bytes);
            assertThat(result).isEqualTo("你好世界");
        }
    }

    @Nested
    @DisplayName("serializeToBytes")
    class SerializeToBytesTests {

        @Test
        @DisplayName("序列化字符串")
        void serializeToBytes_stringValue_returnsBytes() {
            byte[] result = typeSupport.serializeToBytes("test-value");
            assertThat(result).isNotNull();
            assertThat(result.length).isGreaterThan(0);
        }

        @Test
        @DisplayName("序列化整数")
        void serializeToBytes_integerValue_returnsBytes() {
            byte[] result = typeSupport.serializeToBytes(42);
            assertThat(result).isNotNull();
            assertThat(result.length).isGreaterThan(0);
        }

        @Test
        @DisplayName("序列化复杂对象")
        void serializeToBytes_object_returnsBytes() {
            TestObject obj = new TestObject(1L, "test");
            byte[] result = typeSupport.serializeToBytes(obj);
            assertThat(result).isNotNull();
            assertThat(result.length).isGreaterThan(0);
        }

        @Test
        @DisplayName("序列化NullValue使用Java序列化")
        void serializeToBytes_nullValue_usesJavaSerialization() {
            Object nullValue = NullValue.INSTANCE;
            byte[] result = typeSupport.serializeToBytes(nullValue);
            assertThat(result).isNotNull();
            // NullValue 应该使用Java序列化（以 AC ED 00 05 开头）
            assertThat((byte) result[0]).isEqualTo((byte) 0xAC);
            assertThat((byte) result[1]).isEqualTo((byte) 0xED);
        }
    }

    @Nested
    @DisplayName("deserializeFromBytes")
    class DeserializeFromBytesTests {

        @Test
        @DisplayName("反序列化字符串")
        void deserializeFromBytes_stringBytes_returnsString() {
            byte[] bytes = typeSupport.serializeToBytes("test-value");
            Object result = typeSupport.deserializeFromBytes(bytes);
            assertThat(result).isEqualTo("test-value");
        }

        @Test
        @DisplayName("反序列化整数")
        void deserializeFromBytes_integerBytes_returnsInteger() {
            byte[] bytes = typeSupport.serializeToBytes(42);
            Object result = typeSupport.deserializeFromBytes(bytes);
            assertThat(result).isEqualTo(42);
        }

        @Test
        @DisplayName("反序列化复杂对象")
        void deserializeFromBytes_objectBytes_returnsObject() {
            TestObject obj = new TestObject(1L, "test");
            byte[] bytes = typeSupport.serializeToBytes(obj);
            Object result = typeSupport.deserializeFromBytes(bytes);
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("空字节数组返回null")
        void deserializeFromBytes_emptyBytes_returnsNull() {
            byte[] bytes = new byte[0];
            Object result = typeSupport.deserializeFromBytes(bytes);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Java序列化数据被拒绝")
        void deserializeFromBytes_javaSerialized_rejected() {
            // 创建一个Java序列化的NullValue
            byte[] javaSerialized = typeSupport.serializeToBytes(NullValue.INSTANCE);

            assertThatThrownBy(() -> typeSupport.deserializeFromBytes(javaSerialized))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("Java deserialization is not allowed");
        }

        @Test
        @DisplayName("短于4字节的数据尝试JSON反序列化")
        void deserializeFromBytes_shortBytes_throwsException() {
            byte[] shortBytes = new byte[]{0x01, 0x02, 0x03};
            // 短于4字节的数据不会触发Java序列化检测，但JSON反序列化会失败
            assertThatThrownBy(() -> typeSupport.deserializeFromBytes(shortBytes))
                    .isInstanceOf(io.github.davidhlp.spring.cache.redis.core.writer.support.type.SerializationException.class);
        }
    }

    @Nested
    @DisplayName("SerializationException")
    class SerializationExceptionTests {

        @Test
        @DisplayName("使用消息创建异常")
        void constructor_withMessage_createsException() {
            SerializationException ex = new SerializationException("Test message");
            assertThat(ex.getMessage()).isEqualTo("Test message");
            assertThat(ex.getCause()).isNull();
        }

        @Test
        @DisplayName("使用消息和原因创建异常")
        void constructor_withMessageAndCause_createsException() {
            RuntimeException cause = new RuntimeException("Cause");
            SerializationException ex = new SerializationException("Test message", cause);
            assertThat(ex.getMessage()).isEqualTo("Test message");
            assertThat(ex.getCause()).isEqualTo(cause);
        }
    }

    // Test helper class
    static class TestObject {
        private Long id;
        private String name;

        public TestObject() {}

        public TestObject(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }
}
