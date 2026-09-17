package io.github.davidhlp.spring.cache.redis.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TypeSupport 单元测试。
 */
@DisplayName("TypeSupport Tests")
class TypeSupportTest {

    private final TypeSupport typeSupport = new TypeSupport();

    @Test
    @DisplayName("字节数组转换为字符串")
    void bytesToString_validBytes_returnsString() {
        assertThat(typeSupport.bytesToString("Hello World".getBytes())).isEqualTo("Hello World");
    }

    @Test
    @DisplayName("空字节数组返回空字符串")
    void bytesToString_emptyBytes_returnsEmptyString() {
        assertThat(typeSupport.bytesToString(new byte[0])).isEmpty();
    }

    @Test
    @DisplayName("UTF-8中文字符转换正确")
    void bytesToString_chineseCharacters_returnsCorrectString() {
        assertThat(typeSupport.bytesToString("你好世界".getBytes())).isEqualTo("你好世界");
    }
}
