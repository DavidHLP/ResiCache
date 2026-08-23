/**
 * Operator-only serialization migration CLI tool — a deep module separate from the runtime
 * serializer. Entry point: {@link io.github.davidhlp.spring.cache.redis.serialization.migration.SerializationMigrationCli#main}.
 *
 * <p>The 6 files in this package form one self-contained subsystem: a 4-phase workflow
 * ({@link io.github.davidhlp.spring.cache.redis.serialization.migration.SerializationMigrationPhase}),
 * an engine that scans Redis and rewrites legacy bytes into the current
 * {@code io.github.davidhlp.spring.cache.redis.serialization.VersionEnvelope} format, the
 * decoder that accepts pre-whitelist legacy values, an immutable report, and the standalone
 * Spring Boot CLI bootstrap. The only touchpoint with the runtime serializer is the shared
 * {@link io.github.davidhlp.spring.cache.redis.serialization.WhitelistPolicy} (parent package)
 * and {@link io.github.davidhlp.spring.cache.redis.serialization.SecureJacksonSerializerFactory}
 * (constructed by {@code SerializationMigrationEngine} to round-trip envelope values).
 *
 * <p>Operator invocation (see {@code SerializationMigrationCli} Javadoc for full flags):
 * <pre>{@code
 * java -cp resicache.jar:slf4j-api.jar:logback-classic.jar \
 *   io.github.davidhlp.spring.cache.redis.serialization.migration.SerializationMigrationCli \
 *   --resi-cache.serializer.migration.phase=DUAL_WRITE
 * }</pre>
 *
 * <p>Never auto-executed at application startup — {@code SerializationMigrationProperties} is
 * nested under {@code resi-cache.serializer.migration.*} purely for operator CLI ergonomics.
 */
package io.github.davidhlp.spring.cache.redis.serialization.migration;
