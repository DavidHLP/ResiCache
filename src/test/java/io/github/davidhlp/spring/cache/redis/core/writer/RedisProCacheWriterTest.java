package io.github.davidhlp.spring.cache.redis.core.writer;

import io.github.davidhlp.spring.cache.redis.core.writer.chain.CacheHandlerChain;
import io.github.davidhlp.spring.cache.redis.core.writer.chain.CacheHandlerChainFactory;
import io.github.davidhlp.spring.cache.redis.core.writer.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.core.writer.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.core.writer.chain.handler.CacheContext;
import io.github.davidhlp.spring.cache.redis.core.writer.support.type.TypeSupport;
import io.github.davidhlp.spring.cache.redis.register.RedisCacheRegister;
import io.github.davidhlp.spring.cache.redis.register.operation.RedisCacheableOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RedisProCacheWriter Tests")
class RedisProCacheWriterTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private CacheStatisticsCollector statistics;

    @Mock
    private RedisCacheRegister redisCacheRegister;

    @Mock
    private TypeSupport typeSupport;

    @Mock
    private CacheHandlerChainFactory chainFactory;

    @Mock
    private CacheHandlerChain chain;

    private RedisProCacheWriter writer;

    @BeforeEach
    void setUp() {
        when(chainFactory.createChain()).thenReturn(chain);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        writer = new RedisProCacheWriter(
                redisTemplate,
                valueOperations,
                statistics,
                redisCacheRegister,
                typeSupport,
                chainFactory);
    }

    @Nested
    @DisplayName("get() Tests")
    class GetTests {

        @Test
        @DisplayName("get returns bytes from chain execution")
        void get_withValidKey_returnsBytes() {
            String name = "testCache";
            byte[] key = "key1".getBytes();
            byte[] expectedBytes = "value".getBytes();
            String redisKey = name + "::key1";
            String actualKey = "key1";

            when(typeSupport.bytesToString(key)).thenReturn(redisKey);
            when(redisCacheRegister.getCacheableOperation(name, actualKey)).thenReturn(null);
            when(chain.execute(any(CacheContext.class))).thenReturn(CacheResult.success(expectedBytes));

            byte[] result = writer.get(name, key);

            assertThat(result).isEqualTo(expectedBytes);
            verify(chain).execute(any(CacheContext.class));
        }

        @Test
        @DisplayName("get returns null when chain returns miss")
        void get_whenChainReturnsMiss_returnsNull() {
            String name = "testCache";
            byte[] key = "key1".getBytes();
            String redisKey = name + "::key1";
            String actualKey = "key1";

            when(typeSupport.bytesToString(key)).thenReturn(redisKey);
            when(redisCacheRegister.getCacheableOperation(name, actualKey)).thenReturn(null);
            when(chain.execute(any(CacheContext.class))).thenReturn(CacheResult.miss());

            byte[] result = writer.get(name, key);

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("put() Tests")
    class PutTests {

        @Test
        @DisplayName("put executes chain with PUT operation")
        void put_withValidInput_executesChain() {
            String name = "testCache";
            byte[] key = "key1".getBytes();
            byte[] value = "value".getBytes();
            Duration ttl = Duration.ofSeconds(60);
            String redisKey = name + "::key1";
            String actualKey = "key1";

            when(typeSupport.bytesToString(key)).thenReturn(redisKey);
            when(typeSupport.deserializeFromBytes(value)).thenReturn("value");
            when(redisCacheRegister.getCacheableOperation(name, actualKey)).thenReturn(null);
            when(chain.execute(any(CacheContext.class))).thenReturn(CacheResult.success());

            writer.put(name, key, value, ttl);

            verify(chain).execute(any(CacheContext.class));
        }

        @Test
        @DisplayName("put with operation executes chain with operation context")
        void put_withOperation_executesChainWithOperation() {
            String name = "testCache";
            byte[] key = "key1".getBytes();
            byte[] value = "value".getBytes();
            Duration ttl = Duration.ofSeconds(60);
            RedisCacheableOperation operation = RedisCacheableOperation.builder()
                    .name("testMethod")
                    .cacheNames(name)
                    .key("key1")
                    .build();
            String redisKey = name + "::key1";

            when(typeSupport.bytesToString(key)).thenReturn(redisKey);
            when(typeSupport.deserializeFromBytes(value)).thenReturn("value");

            writer.put(name, key, value, ttl, operation);

            verify(chain).execute(any(CacheContext.class));
        }
    }

    @Nested
    @DisplayName("putIfAbsent() Tests")
    class PutIfAbsentTests {

        @Test
        @DisplayName("putIfAbsent returns existing value when key exists")
        void putIfAbsent_whenKeyExists_returnsExistingValue() {
            String name = "testCache";
            byte[] key = "key1".getBytes();
            byte[] value = "value".getBytes();
            byte[] existingValue = "existing".getBytes();
            String redisKey = name + "::key1";
            String actualKey = "key1";

            when(typeSupport.bytesToString(key)).thenReturn(redisKey);
            when(typeSupport.deserializeFromBytes(value)).thenReturn("value");
            when(redisCacheRegister.getCacheableOperation(name, actualKey)).thenReturn(null);
            when(chain.execute(any(CacheContext.class))).thenReturn(CacheResult.success(existingValue));

            byte[] result = writer.putIfAbsent(name, key, value, null);

            assertThat(result).isEqualTo(existingValue);
        }
    }

    @Nested
    @DisplayName("remove() Tests")
    class RemoveTests {

        @Test
        @DisplayName("remove executes chain with REMOVE operation")
        void remove_withValidKey_executesChain() {
            String name = "testCache";
            byte[] key = "key1".getBytes();
            String redisKey = name + "::key1";
            String actualKey = "key1";

            when(typeSupport.bytesToString(key)).thenReturn(redisKey);
            when(redisCacheRegister.getCacheableOperation(name, actualKey)).thenReturn(null);
            when(chain.execute(any(CacheContext.class))).thenReturn(CacheResult.success());

            writer.remove(name, key);

            verify(chain).execute(any(CacheContext.class));
        }
    }

    @Nested
    @DisplayName("clean() Tests")
    class CleanTests {

        @Test
        @DisplayName("clean executes chain with CLEAN operation and pattern")
        void clean_withPattern_executesChain() {
            String name = "testCache";
            byte[] pattern = "key*".getBytes();
            String keyPattern = "key*";
            String redisKey = name + "::key*";
            String actualKey = "key*";

            when(typeSupport.bytesToString(pattern)).thenReturn(keyPattern);
            when(typeSupport.bytesToString(keyPattern.getBytes())).thenReturn(redisKey);
            when(redisCacheRegister.getCacheableOperation(name, actualKey)).thenReturn(null);
            when(chain.execute(any(CacheContext.class))).thenReturn(CacheResult.success());

            writer.clean(name, pattern);

            verify(chain).execute(any(CacheContext.class));
        }
    }

    @Nested
    @DisplayName("clearStatistics() Tests")
    class ClearStatisticsTests {

        @Test
        @DisplayName("clearStatistics resets statistics for cache")
        void clearStatistics_resetsStatistics() {
            String name = "testCache";

            writer.clearStatistics(name);

            verify(statistics).reset(name);
        }
    }
}
