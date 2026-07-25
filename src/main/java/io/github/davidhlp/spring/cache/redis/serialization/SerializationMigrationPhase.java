package io.github.davidhlp.spring.cache.redis.serialization;

/**
 * Operator-controlled phases of the legacy serialization migration workflow.
 */
public enum SerializationMigrationPhase {
    /** Decode and validate legacy values without writing Redis. */
    SHADOW_READ,
    /** Write current envelopes to sidecar shadow keys while preserving legacy sources. */
    DUAL_WRITE,
    /** Back up legacy bytes and replace source values with current envelopes. */
    CUTOVER,
    /** Restore source values from cutover backup keys. */
    ROLLBACK
}
