package io.github.davidhlp.spring.cache.redis.cache;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import static org.assertj.core.api.Assertions.assertThat;

/** Proves native annotation adaptation with the real cache manager, writer and Redis. */
@SpringBootTest(classes = TestApplication.class,
        properties = "resi-cache.native-annotation-mode=FULL")
@Import(NativeAnnotationIntegrationTest.NativeWriteConfig.class)
class NativeAnnotationIntegrationTest extends AbstractRedisIntegrationTest {
    @Autowired private CacheManager cacheManager;
    @Autowired private NativeWriteService service;

    @Test
    void nativePutReplacesOldValueAndEvictRemovesIt() {
        var cache = cacheManager.getCache("native-write");
        cache.put("key", "old");
        assertThat(service.put("key")).isEqualTo("new");
        assertThat(cache.get("key", String.class)).isEqualTo("new");
        service.evict("key");
        assertThat(cache.get("key")).isNull();
    }

    @TestConfiguration
    static class NativeWriteConfig {
        @Bean NativeWriteService nativeWriteService() { return new NativeWriteService(); }
    }

    static class NativeWriteService {
        @CachePut(cacheNames = "native-write", key = "#p0")
        public String put(String key) { return "new"; }

        @CacheEvict(cacheNames = "native-write", key = "#p0")
        public void evict(String key) { }
    }
}
