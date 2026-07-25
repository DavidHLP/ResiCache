# Serialization Migration CLI

ResiCache stores values as a `{version,payload}` envelope. Use the standalone
`SerializationMigrationCli` to migrate Spring-native
`GenericJackson2JsonRedisSerializer` or `JdkSerializationRedisSerializer`
values without flushing the cache.

## Safety model

- The CLI is never run during normal application startup.
- `SHADOW_READ` is the default and performs no writes.
- Every invocation processes at most `max-keys` not-yet-completed actionable keys;
  rerun the same phase to resume. Already matching sidecars/envelopes do not consume
  the next invocation's quota.
- Sidecars copy the source TTL. `CUTOVER` uses a compare-and-set Lua script with
  `KEEPTTL`, so it refuses to overwrite a concurrent application write.
- `ROLLBACK` restores only the envelope produced from its backup. It refuses to
  overwrite a value written after cutover.
- Generic Jackson type IDs are checked against
  `allowed-package-prefixes` before deserialization. JDK streams use a restricted
  `ObjectInputStream`; proxy classes and classes outside the same whitelist are rejected.
- Metrics use only bounded `phase` and `outcome` tags under
  `resicache.serialization.migration.keys`; Redis keys are never tags.

## Phases

| Phase | Source key | Shadow key (`:__resicache_envelope`) | Backup key (`:__resicache_legacy`) |
|---|---|---|---|
| `SHADOW_READ` | legacy unchanged | unchanged | unchanged |
| `DUAL_WRITE` | legacy unchanged | current envelope written | unchanged |
| `CUTOVER` | compare-and-set to current envelope | unchanged | legacy bytes written |
| `ROLLBACK` | compare-and-set back to legacy | unchanged | retained for audit/retry |

The migration tool uses sidecars because one Redis key cannot simultaneously
contain two wire formats. During `DUAL_WRITE`, operators can inspect shadow
coverage before cutting over.

## Run

Package the project, then launch the CLI with the application runtime classpath:

```bash
java -cp 'target/ResiCache-0.0.2.jar:app-libs/*' \
  io.github.davidhlp.spring.cache.redis.serialization.SerializationMigrationCli \
  --spring.data.redis.host=127.0.0.1 \
  --spring.data.redis.port=6379 \
  --resi-cache.serializer.allowed-package-prefixes=com.example.* \
  --resi-cache.serializer.migration.phase=SHADOW_READ \
  --resi-cache.serializer.migration.legacy-serializer=GENERIC_JACKSON \
  --resi-cache.serializer.migration.pattern='users::*' \
  --resi-cache.serializer.migration.max-keys=1000 \
  --resi-cache.serializer.migration.batch-size=100
```

For JDK data, use `--resi-cache.serializer.migration.legacy-serializer=JDK`.
Add `--resi-cache.serializer.migration.dry-run=true` to validate a write phase
without changing Redis.

## Operator sequence

1. Run `SHADOW_READ`; fix every rejected type or narrow the key pattern.
2. Run `DUAL_WRITE` repeatedly until the report shows no failures and shadow
   coverage is complete for the bounded scope.
3. Stop legacy writers or otherwise enforce a deployment boundary.
4. Run `CUTOVER` repeatedly. Concurrently changed keys fail safely and can be
   retried after resolving ownership.
5. Deploy ResiCache readers. Keep backup keys through the rollback window.
6. If rollback is required, select `ROLLBACK` with the same pattern, legacy
   serializer and suffix configuration. Post-cutover application writes are not
   overwritten.
7. After the rollback window, remove sidecars under an audited retention policy;
   the CLI deliberately does not auto-delete recovery data.

A non-zero failed count makes the CLI exit unsuccessfully. The log summary has
`scanned`, `envelopes`, `decodedLegacy`, `written`, `skippedSidecars`, and
`failed` counts but does not log values.
