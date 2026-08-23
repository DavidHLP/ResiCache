package io.github.davidhlp.spring.cache.redis.serialization.migration;

import lombok.Getter;
import lombok.Setter;

/**
 * CLI-only serialization migration settings.
 */
@Getter
@Setter
public class SerializationMigrationProperties {

    /** Migration phase; the safe default performs no writes. */
    private SerializationMigrationPhase phase = SerializationMigrationPhase.SHADOW_READ;

    /** Redis SCAN match pattern. */
    private String pattern = "*";

    /** Maximum number of not-yet-completed actionable keys processed per invocation. */
    private int maxKeys = 1000;

    /** SCAN count hint. */
    private int batchSize = 100;

    /** Legacy serializer used for non-envelope source values. */
    private LegacySerializer legacySerializer = LegacySerializer.GENERIC_JACKSON;

    /** Sidecar suffix used by DUAL_WRITE. */
    private String shadowSuffix = ":__resicache_envelope";

    /** Sidecar suffix used by CUTOVER and ROLLBACK. */
    private String backupSuffix = ":__resicache_legacy";

    /** If true, report planned writes without mutating Redis. */
    private boolean dryRun;

    /** Legacy formats supported by the migration CLI. */
    public enum LegacySerializer {
        GENERIC_JACKSON,
        JDK
    }
}
