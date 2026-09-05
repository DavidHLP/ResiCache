/**
 * Operator-only serialization migration CLI tool — a deep module separate from the runtime
 * serializer. Entry point: {@link io.github.davidhlp.spring.cache.redis.serialization.migration.SerializationMigrationCli#main}.
 *
 * <p>The operator contract in this package defines the 4-phase workflow
 * ({@link io.github.davidhlp.spring.cache.redis.serialization.migration.SerializationMigrationPhase}),
 * immutable properties/report types, and the CLI. The scanning engine lives in
 * the package-private {@code cache} runtime and rewrites legacy bytes into the current
 * {@code io.github.davidhlp.spring.cache.redis.serialization.VersionEnvelope} format, the
 * decoder that accepts pre-whitelist legacy values, an immutable report, and the standalone
 * Spring Boot CLI bootstrap. The only touchpoint with the runtime serializer is the shared
 * {@link io.github.davidhlp.spring.cache.redis.cache.WhitelistPolicy} (parent package)
 * and {@link io.github.davidhlp.spring.cache.redis.cache.SecureJacksonSerializerFactory}
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
