package io.github.davidhlp.spring.cache.redis.cache;

import java.lang.reflect.Constructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for C6: the writer no longer holds unused storage facts.
 */
@DisplayName("RedisProCacheWriter construction surface")
class RedisProCacheWriterConstructionTest {

    @Test
    @DisplayName("keeps one constructor without RedisTemplate or ValueOperations")
    void constructorSurface_hasOnlyCurrentDependencies() {
        Constructor<?>[] constructors = RedisProCacheWriter.class.getDeclaredConstructors();

        assertThat(constructors).hasSize(1);
        assertThat(constructors[0].getParameterTypes())
                .containsExactly(
                        CacheStatisticsCollector.class,
                        TypeSupport.class,
                        CacheValueCodec.class,
                        CacheHandlerChainFactory.class,
                        CacheOperationResolver.class)
                .doesNotContain(RedisTemplate.class, ValueOperations.class);
    }
}
