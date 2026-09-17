package io.github.davidhlp.spring.cache.redis.cache;

import java.nio.charset.StandardCharsets;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

/**
 * 类型转换支持工具类。
 *
 * <p>仅负责缓存 key 字节与字符串之间的转换。缓存 value 字节契约由
 * {@link CacheValueCodec} 单一持有。
 */
@Component
class TypeSupport {

    /**
     * 字节数组转字符串。
     *
     * @param bytes 字节数组
     * @return 字符串
     */
    @NonNull
    public String bytesToString(@NonNull byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
