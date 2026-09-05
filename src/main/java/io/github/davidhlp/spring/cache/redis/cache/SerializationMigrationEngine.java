package io.github.davidhlp.spring.cache.redis.cache;





import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import io.github.davidhlp.spring.cache.redis.serialization.migration.SerializationMigrationPhase;
import io.github.davidhlp.spring.cache.redis.serialization.migration.SerializationMigrationProperties;
import io.github.davidhlp.spring.cache.redis.serialization.migration.SerializationMigrationReport;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisClusterNode;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Component;

/**
 * Bounded, resumable legacy-value migration engine used by the operator CLI.
 */
@Slf4j
@Component
class SerializationMigrationEngine
        implements io.github.davidhlp.spring.cache.redis.serialization.migration.SerializationMigrationCli.SerializationMigrationRunner {

    static final String METRIC_NAME = "resicache.serialization.migration.keys";
    private static final byte[] COMPARE_AND_REPLACE = ("if redis.call('get', KEYS[1]) == ARGV[1] "
            + "then redis.call('set', KEYS[1], ARGV[2], 'KEEPTTL'); return 1 else return 0 end")
            .getBytes(StandardCharsets.UTF_8);

    private final RedisConnectionFactory connectionFactory;
    private final SecureJacksonRedisSerializer currentSerializer;
    private final LegacyValueDecoder legacyDecoder;
    private final SerializationMigrationProperties migration;
    private final MeterRegistry meterRegistry;

    public SerializationMigrationEngine(
            RedisConnectionFactory connectionFactory,
            ObjectMapper objectMapper,
            RedisProCacheProperties properties,
            SecureJacksonSerializerFactory serializerFactory,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.connectionFactory = connectionFactory;
        var serializer = properties.getSerializer();
        this.currentSerializer = serializerFactory.create(objectMapper, serializer);
        this.legacyDecoder = new LegacyValueDecoder(
                objectMapper, serializer.getAllowedPackagePrefixes(), serializer.getTypeProperty());
        this.migration = serializer.getMigration();
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    /**
     * Execute the configured phase once, up to {@code maxKeys} source keys.
     *
     * @return immutable operator summary
     */
    public SerializationMigrationReport migrate() {
        validateSettings();
        MutableReport report = new MutableReport();
        try (RedisConnection connection = connectionFactory.getConnection()) {
            if (migration.getPhase() == SerializationMigrationPhase.ROLLBACK) {
                rollback(connection, report);
            } else {
                migrateForward(connection, report);
            }
        }
        SerializationMigrationReport result = report.freeze();
        log.info("[ResiCache] Serialization migration complete: phase={}, dryRun={}, "
                        + "scanned={}, envelopes={}, decodedLegacy={}, written={}, "
                        + "skippedSidecars={}, failed={}",
                migration.getPhase(), migration.isDryRun(), result.scanned(), result.envelopes(),
                result.decodedLegacy(), result.written(), result.skippedSidecars(), result.failed());
        return result;
    }

    private void migrateForward(RedisConnection connection, MutableReport report) {
        scan(connection, migration.getPattern(), report, (key) -> {
            if (isSidecar(key)) {
                report.skippedSidecars++;
                record("skipped");
                return;
            }
            report.scanned++;
            migrateSource(connection, key, report);
        });
    }

    private void migrateSource(RedisConnection connection, byte[] key, MutableReport report) {
        try {
            byte[] legacyBytes = connection.stringCommands().get(key);
            if (legacyBytes == null || legacyBytes.length == 0) {
                return;
            }
            if (SerializationPreFlightProbe.isEnvelope(legacyBytes)) {
                currentSerializer.deserialize(legacyBytes);
                report.envelopes++;
                record("envelope");
                return;
            }
            Object legacyValue = legacyDecoder.decode(
                    legacyBytes, migration.getLegacySerializer());
            byte[] envelopeBytes = currentSerializer.serialize(legacyValue);
            if (migration.getPhase() == SerializationMigrationPhase.DUAL_WRITE
                    && Arrays.equals(connection.stringCommands().get(
                            appendSuffix(key, migration.getShadowSuffix())), envelopeBytes)) {
                return;
            }
            report.selected++;
            report.decodedLegacy++;
            record("decoded");

            switch (migration.getPhase()) {
                case SHADOW_READ -> { }
                case DUAL_WRITE -> writeSidecar(
                        connection, key, migration.getShadowSuffix(), envelopeBytes, report, true);
                case CUTOVER -> cutover(connection, key, legacyBytes, envelopeBytes, report);
                case ROLLBACK -> throw new IllegalStateException("ROLLBACK uses backup scan");
                default -> throw new IllegalStateException(
                        "Unsupported migration phase: " + migration.getPhase());
            }
        } catch (Exception ex) {
            report.failed++;
            record("failed");
            log.warn("[ResiCache] Serialization migration rejected key fingerprint={}: {}",
                    keyFingerprint(key), ex.getMessage());
        }
    }

    private void cutover(RedisConnection connection, byte[] key, byte[] legacyBytes,
                         byte[] envelopeBytes, MutableReport report) {
        writeSidecar(connection, key, migration.getBackupSuffix(), legacyBytes, report, true);
        if (migration.isDryRun()) {
            return;
        }
        if (!compareAndReplace(connection, key, legacyBytes, envelopeBytes)) {
            throw new IllegalStateException("Source value changed concurrently during cutover");
        }
        report.written++;
        record("written");
    }

    private void rollback(RedisConnection connection, MutableReport report) {
        scan(connection, migration.getPattern() + migration.getBackupSuffix(), report,
                backupKey -> rollbackBackup(connection, backupKey, report));
    }

    private void rollbackBackup(RedisConnection connection, byte[] backupKey,
                                MutableReport report) {
        report.scanned++;
        try {
            byte[] legacyBytes = connection.stringCommands().get(backupKey);
            if (legacyBytes == null) {
                return;
            }
            byte[] sourceKey = removeSuffix(backupKey, migration.getBackupSuffix());
            byte[] current = connection.stringCommands().get(sourceKey);
            if (Arrays.equals(current, legacyBytes)) {
                return;
            }
            report.selected++;
            Object legacyValue = legacyDecoder.decode(legacyBytes, migration.getLegacySerializer());
            byte[] expectedEnvelope = currentSerializer.serialize(legacyValue);
            if (!migration.isDryRun()) {
                restoreSource(connection, sourceKey, backupKey, expectedEnvelope, legacyBytes);
                report.written++;
                record("written");
            }
        } catch (Exception ex) {
            report.failed++;
            record("failed");
            log.warn("[ResiCache] Serialization rollback rejected key fingerprint={}: {}",
                    keyFingerprint(backupKey), ex.getMessage());
        }
    }

    private void restoreSource(RedisConnection connection, byte[] sourceKey, byte[] backupKey,
                               byte[] expectedEnvelope, byte[] legacyBytes) {
        byte[] current = connection.stringCommands().get(sourceKey);
        if (current == null) {
            Boolean restored = connection.stringCommands().set(
                    sourceKey, legacyBytes, expirationOf(connection, backupKey),
                    SetOption.SET_IF_ABSENT);
            if (!Boolean.TRUE.equals(restored)) {
                throw new IllegalStateException("Source appeared concurrently; rollback refused");
            }
        } else if (!compareAndReplace(connection, sourceKey, expectedEnvelope, legacyBytes)) {
            throw new IllegalStateException("Source value changed after cutover; rollback refused");
        }
    }

    private void scan(RedisConnection connection, String pattern, MutableReport report,
                      KeyConsumer consumer) {
        ScanOptions options = ScanOptions.scanOptions()
                .match(pattern)
                .count(migration.getBatchSize())
                .build();
        if (connection instanceof RedisClusterConnection cluster) {
            for (RedisClusterNode node : cluster.clusterCommands().clusterGetNodes()) {
                if (!node.isMaster() || node.isMarkedAsFail()) {
                    continue;
                }
                try (Cursor<byte[]> cursor = cluster.scan(node, options)) {
                    consume(cursor, report, consumer);
                }
                if (report.selected >= migration.getMaxKeys()) {
                    return;
                }
            }
        } else {
            try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
                consume(cursor, report, consumer);
            }
        }
    }

    private void consume(Cursor<byte[]> cursor, MutableReport report, KeyConsumer consumer) {
        while (cursor.hasNext() && report.selected < migration.getMaxKeys()) {
            consumer.accept(cursor.next());
        }
    }

    private void writeSidecar(RedisConnection connection, byte[] sourceKey, String suffix,
                              byte[] value, MutableReport report, boolean overwrite) {
        byte[] targetKey = appendSuffix(sourceKey, suffix);
        byte[] existing = connection.stringCommands().get(targetKey);
        if (Arrays.equals(existing, value)) {
            return;
        }
        if (existing != null && !overwrite) {
            return;
        }
        if (migration.isDryRun()) {
            return;
        }
        Boolean stored = connection.stringCommands().set(
                targetKey, value, expirationOf(connection, sourceKey), SetOption.UPSERT);
        if (!Boolean.TRUE.equals(stored)) {
            throw new IllegalStateException("Redis did not write migration sidecar");
        }
        report.written++;
        record("written");
    }

    private boolean compareAndReplace(RedisConnection connection, byte[] key,
                                      byte[] expected, byte[] replacement) {
        Long replaced = connection.scriptingCommands().eval(
                COMPARE_AND_REPLACE, ReturnType.INTEGER, 1, key, expected, replacement);
        return Long.valueOf(1L).equals(replaced);
    }

    private Expiration expirationOf(RedisConnection connection, byte[] key) {
        Long ttlMillis = connection.keyCommands().pTtl(key);
        if (ttlMillis == null || ttlMillis == -1) {
            return Expiration.persistent();
        }
        if (ttlMillis <= 0) {
            throw new IllegalStateException("Source key expired during migration");
        }
        return Expiration.milliseconds(ttlMillis);
    }

    private boolean isSidecar(byte[] key) {
        return endsWith(key, migration.getShadowSuffix())
                || endsWith(key, migration.getBackupSuffix());
    }

    private void validateSettings() {
        if (migration.getMaxKeys() < 1 || migration.getBatchSize() < 1) {
            throw new IllegalArgumentException("migration maxKeys and batchSize must be positive");
        }
        if (migration.getPattern() == null || migration.getPattern().isBlank()) {
            throw new IllegalArgumentException("migration pattern must not be blank");
        }
        if (migration.getShadowSuffix() == null || migration.getShadowSuffix().isBlank()
                || migration.getBackupSuffix() == null || migration.getBackupSuffix().isBlank()
                || migration.getShadowSuffix().equals(migration.getBackupSuffix())) {
            throw new IllegalArgumentException("migration sidecar suffixes must be non-empty and distinct");
        }
    }

    private void record(String outcome) {
        if (meterRegistry != null) {
            meterRegistry.counter(METRIC_NAME,
                    "phase", migration.getPhase().name(), "outcome", outcome).increment();
        }
    }

    private static byte[] appendSuffix(byte[] key, String suffix) {
        byte[] suffixBytes = suffix.getBytes(StandardCharsets.UTF_8);
        byte[] result = Arrays.copyOf(key, key.length + suffixBytes.length);
        System.arraycopy(suffixBytes, 0, result, key.length, suffixBytes.length);
        return result;
    }

    private static byte[] removeSuffix(byte[] key, String suffix) {
        byte[] suffixBytes = suffix.getBytes(StandardCharsets.UTF_8);
        if (!endsWith(key, suffix)) {
            throw new IllegalArgumentException("Migration key does not end with expected suffix");
        }
        return Arrays.copyOf(key, key.length - suffixBytes.length);
    }

    private static boolean endsWith(byte[] key, String suffix) {
        byte[] suffixBytes = suffix.getBytes(StandardCharsets.UTF_8);
        if (key.length < suffixBytes.length) {
            return false;
        }
        for (int index = 0; index < suffixBytes.length; index++) {
            if (key[key.length - suffixBytes.length + index] != suffixBytes[index]) {
                return false;
            }
        }
        return true;
    }

    private static String keyFingerprint(byte[] key) {
        return Integer.toHexString(Arrays.hashCode(key));
    }

    @FunctionalInterface
    private interface KeyConsumer {
        void accept(byte[] key);
    }

    private static final class MutableReport {
        private int scanned;
        private int selected;
        private int envelopes;
        private int decodedLegacy;
        private int written;
        private int skippedSidecars;
        private int failed;

        private SerializationMigrationReport freeze() {
            return new SerializationMigrationReport(
                    scanned, envelopes, decodedLegacy, written, skippedSidecars, failed);
        }
    }
}
