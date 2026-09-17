package io.github.davidhlp.spring.cache.redis.cache;

import com.example.domain.CustomDomainValue;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T0 verification gate for the cache-value codec candidate (architecture review
 * rev 2, candidate C2; see {@code /tmp/resicache-review-20260917/}).
 *
 * <p>Characterizes the value-bytes contract between the Spring cache layer and
 * {@link RedisProCacheWriter}: a typed POJO written through the cache path must
 * come back as the same POJO type through the cache path, and the stored document
 * shape plus the incompatible-serializer behavior are pinned as assertions.
 *
 * <p>This test exists to decide candidate C2's shape/rank <em>before</em> any
 * refactor: if the typed round-trip fails, the candidate upgrades to a runtime
 * contract defect; if it passes, the candidate is a hardening refactor and these
 * assertions become its characterization baseline.
 */
@Slf4j
@DisplayName("Cache value codec round-trip (T0 gate)")
class CacheValueCodecRoundTripIntegrationTest extends AbstractRedisIntegrationTest {

    private static final String CACHE_NAME = "codecProbe";
    private static final String KEY = "probe-key";
    private static final String RAW_KEY = CACHE_NAME + "::" + KEY;

    /**
     * The test POJO lives in {@code com.example.domain}, outside the default
     * whitelist — mirroring {@code RedisConnectionConfigurationIntegrationTest}.
     */
    @DynamicPropertySource
    static void serializerWhitelist(DynamicPropertyRegistry registry) {
        registry.add("resi-cache.serializer.allowed-package-prefixes",
                () -> "com.example.domain,io.github.davidhlp");
    }

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private RedisTemplate<String, Object> redisCacheTemplate;

    @Autowired
    private CacheValueCodec valueCodec;

    private ValueOperations<String, Object> valueOps;
    private Cache cache;

    @BeforeEach
    void setUp() {
        valueOps = redisCacheTemplate.opsForValue();
        redisCacheTemplate.getConnectionFactory().getConnection().flushDb();
        cache = cacheManager.getCache(CACHE_NAME);
        assertThat(cache).isNotNull();
    }

    @Test
    @DisplayName("typed POJO survives put → get(key) → get(key, type) with type and fields intact")
    void typedPojoRoundTripThroughCachePath() {
        CustomDomainValue original = new CustomDomainValue(42L, "probe-value");

        // Write through the cache abstraction: RedisCache.put → writer.put(valueBytes)
        cache.put(KEY, original);

        // Assert (1): cache.get(key) — RedisCache decodes the writer-returned bytes
        Cache.ValueWrapper wrapper = cache.get(KEY);
        assertThat(wrapper)
                .as("cache.get(key) must return the stored value")
                .isNotNull();
        assertThat(wrapper.get())
                .as("the value must round-trip as the original POJO, not degrade to a Map")
                .isInstanceOf(CustomDomainValue.class);
        CustomDomainValue restored = (CustomDomainValue) wrapper.get();
        assertThat(restored.getId()).isEqualTo(42L);
        assertThat(restored.getLabel()).isEqualTo("probe-value");

        // Assert (2): cache.get(key, type) — the typed accessor must agree
        CustomDomainValue typed = cache.get(KEY, CustomDomainValue.class);
        assertThat(typed).isNotNull();
        assertThat(typed.getLabel()).isEqualTo("probe-value");
    }

    @Test
    @DisplayName("stored document shape: envelope-wrapped CachedValue whose value is the envelope-shaped Map")
    void storedDocumentShapeIsRecorded() {
        cache.put(KEY, new CustomDomainValue(42L, "probe-value"));

        Object stored = valueOps.get(RAW_KEY);
        assertThat(stored)
                .as("the template decodes the stored document with the secure serializer")
                .isInstanceOf(CachedValue.class);

        Object storedValue = ((CachedValue) stored).getValue();
        log.info("T0 observation: stored CachedValue.value class={} value={}",
                storedValue == null ? null : storedValue.getClass().getName(), storedValue);

        assertThat(storedValue)
                .as("the chain stores the envelope-shaped Map as CachedValue.value "
                        + "(T0 hypothesis to verify/refute)")
                .isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> storedMap = (Map<String, Object>) storedValue;
        assertThat(storedMap)
                .as("the stored map must expose the envelope keys")
                .containsKeys("version", "payload");
    }

    @Test
    @DisplayName("incompatible serializer bytes fail fast with SerializationException, not silently")
    void incompatibleBytesFailFast() {
        assertThatThrownBy(() -> valueCodec.fromValueBytes(new byte[] {0x01, 0x02, 0x03}))
                .as("non-JSON, non-NullValue bytes must surface as a typed serialization failure")
                .isInstanceOf(io.github.davidhlp.spring.cache.redis.serialization.SerializationException.class);
    }
}
