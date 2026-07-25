package io.github.davidhlp.spring.cache.redis.integration;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import io.github.davidhlp.spring.cache.redis.serialization.SerializationMigrationEngine;
import io.github.davidhlp.spring.cache.redis.serialization.SerializationMigrationPhase;
import io.github.davidhlp.spring.cache.redis.serialization.SerializationMigrationProperties;
import io.github.davidhlp.spring.cache.redis.serialization.SerializationMigrationReport;
import io.github.davidhlp.spring.cache.redis.config.SerializationPreFlightProbe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TestApplication.class)
@Import(TestRedisConfiguration.class)
@DisplayName("Serialization Migration Integration Tests")
class SerializationMigrationIntegrationTest extends AbstractRedisClusterIntegrationTest {

    private static final byte[] JSON_KEY = bytes("migration:json");
    private static final byte[] JDK_KEY = bytes("migration:jdk");
    private static final byte[] BAD_KEY = bytes("migration:bad");

    @Autowired
    private RedisConnectionFactory connectionFactory;

    @Autowired
    private SerializationMigrationEngine migrationEngine;

    @Autowired
    private RedisProCacheProperties properties;

    private SerializationMigrationProperties migration;

    @BeforeEach
    void setUp() throws Exception {
        migration = properties.getSerializer().getMigration();
        for (int port = 7000; port < 7003; port++) {
            redisCliAt(port, "flushdb");
        }
        migration.setPattern("migration:*");
        migration.setBatchSize(20);
        migration.setMaxKeys(20);
        migration.setDryRun(false);
    }

    @Test
    void shadowRead_decodesLegacyWithoutWriting() {
        put(JSON_KEY, new GenericJackson2JsonRedisSerializer().serialize("legacy"), 30);
        migration.setPhase(SerializationMigrationPhase.SHADOW_READ);
        migration.setLegacySerializer(SerializationMigrationProperties.LegacySerializer.GENERIC_JACKSON);

        SerializationMigrationReport report = migrationEngine.migrate();

        assertThat(report.decodedLegacy()).isEqualTo(1);
        assertThat(report.written()).isZero();
        assertThat(raw(JSON_KEY)).isNotNull();
        assertThat(raw(shadowKey(JSON_KEY))).isNull();
        assertThat(ttl(JSON_KEY)).isBetween(1L, 30L);
    }

    @Test
    void dualWrite_preservesLegacyAndTtlAndIsIdempotent() {
        byte[] legacy = new GenericJackson2JsonRedisSerializer().serialize("dual");
        put(JSON_KEY, legacy, 30);
        migration.setPhase(SerializationMigrationPhase.DUAL_WRITE);
        migration.setLegacySerializer(SerializationMigrationProperties.LegacySerializer.GENERIC_JACKSON);

        SerializationMigrationReport first = migrationEngine.migrate();
        SerializationMigrationReport second = migrationEngine.migrate();

        assertThat(raw(JSON_KEY)).isEqualTo(legacy);
        assertThat(SerializationPreFlightProbe.isEnvelope(raw(shadowKey(JSON_KEY)))).isTrue();
        assertThat(first.written()).isEqualTo(1);
        assertThat(second.written()).isZero();
        assertThat(ttl(shadowKey(JSON_KEY))).isBetween(1L, ttl(JSON_KEY));
    }

    @Test
    void dualWriteResumesPastCompletedKeysWhenMaxKeysIsOne() {
        String first = "migration:resume:first";
        String second = "migration:resume:second";
        var serializer = new GenericJackson2JsonRedisSerializer();
        put(bytes(first), serializer.serialize("first"), 30);
        put(bytes(second), serializer.serialize("second"), 30);
        migration.setPhase(SerializationMigrationPhase.DUAL_WRITE);
        migration.setLegacySerializer(
                SerializationMigrationProperties.LegacySerializer.GENERIC_JACKSON);
        migration.setMaxKeys(1);

        SerializationMigrationReport firstRun = migrationEngine.migrate();
        SerializationMigrationReport secondRun = migrationEngine.migrate();

        assertThat(firstRun.written()).isEqualTo(1);
        assertThat(secondRun.written()).isEqualTo(1);
        assertThat(raw(bytes(first + migration.getShadowSuffix()))).isNotNull();
        assertThat(raw(bytes(second + migration.getShadowSuffix()))).isNotNull();
    }

    @Test
    void cutoverBacksUpLegacyKeepsTtlAndRollbackRestores() {
        byte[] legacy = new JdkSerializationRedisSerializer().serialize("rollback");
        put(JDK_KEY, legacy, 30);
        migration.setPhase(SerializationMigrationPhase.CUTOVER);
        migration.setLegacySerializer(SerializationMigrationProperties.LegacySerializer.JDK);

        SerializationMigrationReport cutover = migrationEngine.migrate();

        assertThat(cutover.written()).isEqualTo(2);
        assertThat(SerializationPreFlightProbe.isEnvelope(raw(JDK_KEY))).isTrue();
        assertThat(raw(backupKey(JDK_KEY))).isEqualTo(legacy);
        assertThat(ttl(JDK_KEY)).isBetween(1L, 30L);

        migration.setPhase(SerializationMigrationPhase.ROLLBACK);
        SerializationMigrationReport rollback = migrationEngine.migrate();

        assertThat(rollback.written()).isEqualTo(1);
        assertThat(raw(JDK_KEY)).isEqualTo(legacy);
        assertThat(ttl(JDK_KEY)).isBetween(1L, 30L);
    }

    @Test
    void rollbackRefusesToOverwriteValueWrittenAfterCutover() {
        byte[] legacy = new GenericJackson2JsonRedisSerializer().serialize("old");
        put(JSON_KEY, legacy, 30);
        migration.setPhase(SerializationMigrationPhase.CUTOVER);
        migration.setLegacySerializer(
                SerializationMigrationProperties.LegacySerializer.GENERIC_JACKSON);
        migrationEngine.migrate();
        byte[] concurrent = new GenericJackson2JsonRedisSerializer().serialize("new-owner");
        put(JSON_KEY, concurrent, 30);

        migration.setPhase(SerializationMigrationPhase.ROLLBACK);
        SerializationMigrationReport rollback = migrationEngine.migrate();

        assertThat(rollback.failed()).isEqualTo(1);
        assertThat(rollback.written()).isZero();
        assertThat(raw(JSON_KEY)).isEqualTo(concurrent);
    }

    @Test
    void mixedDatasetMigratesValidKeysAndReportsRejectedValue() {
        put(JSON_KEY, new GenericJackson2JsonRedisSerializer().serialize("valid"), 30);
        put(BAD_KEY, bytes("{\"@class\":\"com.attacker.Gadget\"}"), 30);
        migration.setPhase(SerializationMigrationPhase.DUAL_WRITE);
        migration.setLegacySerializer(SerializationMigrationProperties.LegacySerializer.GENERIC_JACKSON);

        SerializationMigrationReport report = migrationEngine.migrate();

        assertThat(report.decodedLegacy()).isEqualTo(1);
        assertThat(report.written()).isEqualTo(1);
        assertThat(report.failed()).isEqualTo(1);
        assertThat(raw(shadowKey(JSON_KEY))).isNotNull();
        assertThat(raw(shadowKey(BAD_KEY))).isNull();
    }

    @Test
    void clusterScanVisitsKeysOwnedByDifferentMasters() throws Exception {
        String[] keys = findKeysOnDifferentClusterNodes();
        put(bytes(keys[0]), new GenericJackson2JsonRedisSerializer().serialize("node-a"), 30);
        put(bytes(keys[1]), new GenericJackson2JsonRedisSerializer().serialize("node-b"), 30);
        migration.setPhase(SerializationMigrationPhase.SHADOW_READ);
        migration.setLegacySerializer(
                SerializationMigrationProperties.LegacySerializer.GENERIC_JACKSON);

        SerializationMigrationReport report = migrationEngine.migrate();

        assertThat(report.decodedLegacy()).isEqualTo(2);
        assertThat(report.failed()).isZero();
    }

    @Test
    void dryRunReportsDecodeButDoesNotWrite() {
        put(JSON_KEY, new GenericJackson2JsonRedisSerializer().serialize("dry"), 30);
        migration.setPhase(SerializationMigrationPhase.CUTOVER);
        migration.setLegacySerializer(SerializationMigrationProperties.LegacySerializer.GENERIC_JACKSON);
        migration.setDryRun(true);

        SerializationMigrationReport report = migrationEngine.migrate();

        assertThat(report.decodedLegacy()).isEqualTo(1);
        assertThat(report.written()).isZero();
        assertThat(SerializationPreFlightProbe.isEnvelope(raw(JSON_KEY))).isFalse();
        assertThat(raw(backupKey(JSON_KEY))).isNull();
    }

    private String[] findKeysOnDifferentClusterNodes() throws Exception {
        String firstKey = null;
        Integer firstPort = null;
        for (int index = 0; index < 1000; index++) {
            String key = "migration:cross-node:" + index;
            int slot = Integer.parseInt(redisCli("cluster", "keyslot", key));
            int port = portServingSlot(slot);
            if (firstPort == null) {
                firstPort = port;
                firstKey = key;
            } else if (firstPort != port) {
                return new String[]{firstKey, key};
            }
        }
        throw new IllegalStateException("Could not find keys on two Cluster masters");
    }

    private int portServingSlot(int slot) throws Exception {
        for (int port = 7000; port < 7003; port++) {
            String ranges = redisCliAt(port, "cluster", "nodes");
            String self = ranges.lines().filter(line -> line.contains(" myself,")).findFirst()
                    .orElseThrow();
            for (String token : self.split(" ")) {
                String[] bounds = token.split("-", 2);
                try {
                    int start = Integer.parseInt(bounds[0]);
                    int end = bounds.length == 1 ? start : Integer.parseInt(bounds[1]);
                    if (slot >= start && slot <= end) {
                        return port;
                    }
                } catch (NumberFormatException ignored) {
                    // Ignore node metadata tokens.
                }
            }
        }
        throw new IllegalStateException("No Cluster master serves slot " + slot);
    }

    private void put(byte[] key, byte[] value, long seconds) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.stringCommands().set(key, value);
            connection.keyCommands().expire(key, Duration.ofSeconds(seconds));
        }
    }

    private byte[] raw(byte[] key) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            return connection.stringCommands().get(key);
        }
    }

    private long ttl(byte[] key) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            return connection.keyCommands().ttl(key, TimeUnit.SECONDS);
        }
    }

    private byte[] shadowKey(byte[] source) {
        return bytes(new String(source, StandardCharsets.UTF_8) + migration.getShadowSuffix());
    }

    private byte[] backupKey(byte[] source) {
        return bytes(new String(source, StandardCharsets.UTF_8) + migration.getBackupSuffix());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
