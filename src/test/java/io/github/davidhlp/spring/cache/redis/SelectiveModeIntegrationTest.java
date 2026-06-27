package io.github.davidhlp.spring.cache.redis;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheRegister;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.expression.AnnotatedElementKey;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Method;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SELECTIVE 模式(默认)端到端集成测试.
 *
 * <p>v0.0.3 起 {@code nativeAnnotationMode} 默认为 {@code SELECTIVE}。本类验证默认模式下的
 * 端到端行为,弥补此前仅有 {@code RedisCacheOperationSourceSelectiveTest}(OperationSource
 * 单测)而缺集成层覆盖的缺口(CR Claude H2:默认模式需端到端测试覆盖)。
 *
 * <p>覆盖:
 * <ul>
 *   <li>{@code @RedisCacheable} 端到端写入 Redis,且第二次调用命中缓存(方法体不执行);</li>
 *   <li>写入的 key 具有正 TTL(呼应 TtlHandler 不可禁用的契约——缓存不会退化为永久缓存);</li>
 *   <li>纯 {@code @Cacheable} 在 SELECTIVE 下不被 ResiCache 接管(不注册到 RedisCacheRegister)。</li>
 * </ul>
 *
 * <p><b>命名注意</b>:内部 service 类与 bean 方法用唯一名({@code SelectiveService} /
 * {@code selectiveService}),避免与 {@code KeyResolutionIntegrationTest} 的 {@code TestService}
 * / {@code testService} 在 {@code TestApplication} 的 component scan 下产生同名 bean 冲突。
 */
@SpringBootTest(classes = TestApplication.class,
        properties = "resi-cache.native-annotation-mode=SELECTIVE")
@ActiveProfiles("integration-test")
@Import({TestRedisConfiguration.class, SelectiveModeIntegrationTest.SelectiveTestConfig.class})
@DisplayName("SELECTIVE Mode Integration Tests")
class SelectiveModeIntegrationTest extends AbstractRedisIntegrationTest {

    @Autowired
    private SelectiveService selectiveService;

    @Autowired
    private RedisCacheRegister redisCacheRegister;

    @Autowired
    private RedisTemplate<String, Object> redisCacheTemplate;

    @BeforeEach
    void setUp() {
        redisCacheTemplate.getConnectionFactory().getConnection().flushDb();
    }

    @Test
    @DisplayName("@RedisCacheable 端到端:第二次调用命中缓存(方法体仅执行一次)")
    void redisCacheable_secondCallHitsCache() {
        String first = selectiveService.getCached("k1");
        String second = selectiveService.getCached("k1");

        // 命中缓存:第二次返回首次写入的值(若未命中,方法体再执行会带上递增后缀 "v-k1-2")
        assertThat(first).isEqualTo("v-k1-1");
        assertThat(second).isEqualTo("v-k1-1");
    }

    @Test
    @DisplayName("@RedisCacheable 写入的 key 具有正 TTL(非永久缓存)")
    void redisCacheable_writtenKeyHasPositiveTtl() {
        selectiveService.getCached("k1");

        Set<String> keys = redisCacheTemplate.keys("*");
        assertThat(keys).isNotEmpty();
        // 所有写入的 key 都应有正 TTL,不应是永久缓存(getExpire 返回 -1 表示永久)
        for (String key : keys) {
            Long ttl = redisCacheTemplate.getExpire(key);
            assertThat(ttl)
                    .as("key %s should have a positive TTL, not be permanent", key)
                    .isPositive();
        }
    }

    @Test
    @DisplayName("纯 @Cacheable 在 SELECTIVE 下不被 ResiCache 接管(未注册到 RedisCacheRegister)")
    void plainCacheable_notInterceptedByResiCache() throws NoSuchMethodException {
        selectiveService.getPlain("k2");

        // SELECTIVE:纯 @Cacheable 的 OperationSource 返回 null → redisCacheAdvisor 不匹配
        // → RedisCacheInterceptor 不触发 → RedisCacheRegister 无对应操作注册
        Method method = SelectiveService.class.getMethod("getPlain", String.class);
        AnnotatedElementKey elementKey = new AnnotatedElementKey(method, SelectiveService.class);
        assertThat(redisCacheRegister.getCacheableOperation("plain-cache", elementKey))
                .as("纯 @Cacheable 在 SELECTIVE 模式下不应被 ResiCache 注册")
                .isNull();
    }

    @TestConfiguration
    static class SelectiveTestConfig {

        @Bean
        public SelectiveService selectiveService() {
            return new SelectiveService();
        }
    }

    /**
     * 测试服务:含 {@code @RedisCacheable}(被 ResiCache 接管)与纯 {@code @Cacheable}(SELECTIVE 下不被接管)。
     *
     * <p>注意:不通过实例字段断言调用次数——CGLIB 代理跳过构造器,代理实例的字段未初始化。
     * 改用 getCached 返回值嵌入递增计数,从返回值差异证明缓存命中。
     */
    static class SelectiveService {

        private int invocations = 0;

        @RedisCacheable(cacheNames = "redis-cache", key = "#id", ttl = 60)
        public String getCached(String id) {
            invocations++;
            return "v-" + id + "-" + invocations;
        }

        @Cacheable(value = "plain-cache", key = "#id")
        public String getPlain(String id) {
            return "plain-" + id;
        }
    }
}
