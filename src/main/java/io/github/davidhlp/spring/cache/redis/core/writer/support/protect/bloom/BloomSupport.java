package io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom;

import io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom.filter.BloomIFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Bloom 过滤器统一入口，屏蔽底层实现细节并提供降级兜底。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BloomSupport {

    private final BloomIFilter bloomIFilter;

    /**
     * 判断缓存是否可能存在指定键，异常时默认放行以避免误拒绝。
     */
    public boolean mightContain(String cacheName, String key) {
        try {
            return bloomIFilter.mightContain(cacheName, key);
        } catch (Exception ex) {
            log.error("Bloom filter mightContain failed, fallback to allow: cacheName={}, key={}", cacheName, key, ex);
            return true;
        }
    }

    /**
     * 将指定键加入 Bloom 过滤器，异常时仅记录日志。
     */
    public void add(String cacheName, String key) {
        try {
            bloomIFilter.add(cacheName, key);
        } catch (Exception ex) {
            log.error("Bloom filter add failed: cacheName={}, key={}", cacheName, key, ex);
        }
    }

    /**
     * 清理指定缓存对应的 Bloom 过滤器。
     */
    public void clear(String cacheName) {
        try {
            bloomIFilter.clear(cacheName);
        } catch (Exception ex) {
            log.error("Bloom filter clear failed: cacheName={}", cacheName, ex);
        }
    }
}
