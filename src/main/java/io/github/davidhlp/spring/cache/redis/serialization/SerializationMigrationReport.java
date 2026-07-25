package io.github.davidhlp.spring.cache.redis.serialization;

/**
 * Immutable summary returned by one migration invocation.
 *
 * @param scanned          source keys inspected
 * @param envelopes        source keys already in the current envelope format
 * @param decodedLegacy    legacy values decoded and accepted by policy
 * @param written          Redis values written
 * @param skippedSidecars  shadow/backup keys excluded from source scanning
 * @param failed           values rejected or operations that failed
 */
public record SerializationMigrationReport(
        int scanned,
        int envelopes,
        int decodedLegacy,
        int written,
        int skippedSidecars,
        int failed) {
}
