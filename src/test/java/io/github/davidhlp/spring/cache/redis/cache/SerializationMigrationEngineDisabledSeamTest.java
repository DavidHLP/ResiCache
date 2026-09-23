package io.github.davidhlp.spring.cache.redis.cache;




import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import io.github.davidhlp.spring.cache.redis.serialization.migration.SerializationMigrationProperties;
import io.github.davidhlp.spring.cache.redis.serialization.migration.SerializationMigrationReport;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SerializationMigrationEngine} 关闭路径(共享 no-op seam)的 per-key 契约。
 *
 * <p>关闭路径的 seam 是吞掉一切调用的共享单例,它没有可观测输出 —— 这正是 deny-all
 * filter 只能"不保留"、不能"不分配"的原因。因此本测试是白盒探针:把
 * {@code migration.phase} 置为 {@code null},{@code record(outcome)} 只有在真正求值
 * {@code migration.getPhase().name()} 时才会 NPE。关闭路径上 record 若在构造期推导出
 * 的 disabled 信号处提前返回,则整个 migrate() 完成且不触碰 phase。
 *
 * <p>RED(11b0a754):sidecar 分支命中 {@code record("skipped")},求值 null phase →
 * NPE 穿出 migrate()。修后 record 提前返回,migrate() 正常返回报告。
 */
@DisplayName("SerializationMigrationEngine disabled metrics seam")
class SerializationMigrationEngineDisabledSeamTest {

    @Test
    @DisplayName("关闭 seam:每个 key 的 record() 在构造 metric tag 之前返回")
    void disabledSeam_sidecarKey_skipsMeterWork() {
        RedisProCacheProperties properties = new RedisProCacheProperties();
        SerializationMigrationProperties migration = properties.getSerializer().getMigration();
        migration.setPattern("privacy:*");
        migration.setMaxKeys(10);
        migration.setBatchSize(10);
        migration.setDryRun(false);
        // 关闭路径探针:phase 为 null 时,record() 里唯一的 getPhase().name() 会 NPE。
        migration.setPhase(null);

        @SuppressWarnings("unchecked")
        Cursor<byte[]> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(sidecarKeyBytes(migration));

        RedisKeyCommands keyCommands = mock(RedisKeyCommands.class);
        when(keyCommands.scan(any(ScanOptions.class))).thenReturn(cursor);
        RedisConnection connection = mock(RedisConnection.class);
        when(connection.keyCommands()).thenReturn(keyCommands);
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenReturn(connection);

        SerializationMigrationEngine engine = new SerializationMigrationEngine(
                factory, new ObjectMapper(), properties, new SecureJacksonSerializerFactory(),
                ResolvedMetrics.resolve(null, new MockEnvironment()));

        SerializationMigrationReport report = engine.migrate();

        assertThat(report.skippedSidecars()).isEqualTo(1);
        assertThat(DisabledMetricsRegistry.INSTANCE.getMeters())
                .as("关闭 seam 不保留任何 meter")
                .isEmpty();
    }

    private byte[] sidecarKeyBytes(SerializationMigrationProperties migration) {
        return ("privacy:migrated" + migration.getShadowSuffix()).getBytes(StandardCharsets.UTF_8);
    }
}
