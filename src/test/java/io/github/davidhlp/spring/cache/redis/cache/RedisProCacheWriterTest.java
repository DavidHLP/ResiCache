package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.holder.CacheOperationMetadataHolder;
import io.github.davidhlp.spring.cache.redis.chain.CacheHandlerChain;
import io.github.davidhlp.spring.cache.redis.chain.CacheHandlerChainFactory;
import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import io.github.davidhlp.spring.cache.redis.serialization.TypeSupport;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheRegister;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.expression.AnnotatedElementKey;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Method;
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
    private Method dummyMethod;

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        when(chainFactory.createChain()).thenReturn(chain);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        writer = new RedisProCacheWriter(
                redisTemplate,
                valueOperations,
                statistics,
                redisCacheRegister,
                typeSupport,
                chainFactory);

        dummyMethod = RedisProCacheWriterTest.class.getMethod("toString");
        CacheOperationMetadataHolder.setCurrentKey(dummyMethod, RedisProCacheWriterTest.class);
    }

    @AfterEach
    void tearDown() {
        CacheOperationMetadataHolder.clear();
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
            when(redisCacheRegister.getCacheableOperation(eq(name), any(AnnotatedElementKey.class))).thenReturn(null);
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
            when(redisCacheRegister.getCacheableOperation(eq(name), any(AnnotatedElementKey.class))).thenReturn(null);
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
            when(redisCacheRegister.getCacheableOperation(eq(name), any(AnnotatedElementKey.class))).thenReturn(null);
            when(chain.execute(any(CacheContext.class))).thenReturn(CacheResult.success());

            writer.put(name, key, value, ttl);

            // 用 ArgumentCaptor 捕获传给责任链的 context,验证操作类型/key/缓存名,
            // 而非仅 verify(any(CacheContext.class)) —— 后者即使 put 误传 GET 操作也会通过
            ArgumentCaptor<CacheContext> captor = ArgumentCaptor.forClass(CacheContext.class);
            verify(chain).execute(captor.capture());
            CacheContext ctx = captor.getValue();
            assertThat(ctx.getOperation()).isEqualTo(CacheOperation.PUT);
            assertThat(ctx.getCacheName()).isEqualTo(name);
            assertThat(ctx.getRedisKey()).isEqualTo(redisKey);
            assertThat(ctx.getActualKey()).isEqualTo("key1");
            assertThat(ctx.getTtl()).isEqualTo(ttl);
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

            // 验证传入的 operation 配置被正确保留到 context(而非丢失或被覆盖)
            ArgumentCaptor<CacheContext> captor = ArgumentCaptor.forClass(CacheContext.class);
            verify(chain).execute(captor.capture());
            CacheContext ctx = captor.getValue();
            assertThat(ctx.getOperation()).isEqualTo(CacheOperation.PUT);
            assertThat(ctx.getCacheName()).isEqualTo(name);
            assertThat(ctx.getCacheOperation()).isSameAs(operation);
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
            when(redisCacheRegister.getCacheableOperation(eq(name), any(AnnotatedElementKey.class))).thenReturn(null);
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
            when(redisCacheRegister.getCacheableOperation(eq(name), any(AnnotatedElementKey.class))).thenReturn(null);
            when(chain.execute(any(CacheContext.class))).thenReturn(CacheResult.success());

            writer.remove(name, key);

            ArgumentCaptor<CacheContext> captor = ArgumentCaptor.forClass(CacheContext.class);
            verify(chain).execute(captor.capture());
            CacheContext ctx = captor.getValue();
            assertThat(ctx.getOperation()).isEqualTo(CacheOperation.REMOVE);
            assertThat(ctx.getCacheName()).isEqualTo(name);
            assertThat(ctx.getActualKey()).isEqualTo("key1");
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

            when(typeSupport.bytesToString(pattern)).thenReturn(keyPattern);
            when(redisCacheRegister.getCacheableOperation(eq(name), any(AnnotatedElementKey.class))).thenReturn(null);
            when(chain.execute(any(CacheContext.class))).thenReturn(CacheResult.success());

            writer.clean(name, pattern);

            ArgumentCaptor<CacheContext> captor = ArgumentCaptor.forClass(CacheContext.class);
            verify(chain).execute(captor.capture());
            CacheContext ctx = captor.getValue();
            assertThat(ctx.getOperation()).isEqualTo(CacheOperation.CLEAN);
            assertThat(ctx.getCacheName()).isEqualTo(name);
            assertThat(ctx.getRedisKey()).isEqualTo(keyPattern);
            assertThat(ctx.getKeyPattern()).isEqualTo(keyPattern);
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
