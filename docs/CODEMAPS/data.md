# Data Model Map (Redis)

<!-- Generated: 2026-06-21 | Token estimate: ~600 -->

All state lives in Redis — no RDBMS, no broker. Keys derive from
`resi-cache.key-prefix` + cache name + SpEL/hashed key.

## Key Namespaces

| Purpose | Key pattern | TTL | Set by |
|---------|-------------|-----|--------|
| Cache value | `{keyPrefix}{cacheName}:{key}` | per-cache ttl (jittered) | ActualCacheHandler |
| Null sentinel | `{keyPrefix}{cacheName}:{key}` | short ttl | NullValueHandler |
| Distributed lock | `cache:lock:{cacheName}:{key}` | lease timeout | SyncLockHandler |
| Bloom filter bitmap | bloom:{cacheName} | none (persistent) | BloomFilterHandler |
| LRU eviction lists | lru:{cacheName}:{active\|inactive} | sliding | TwoListLRU |

Defaults: `keyPrefix = ""`, `sync-lock.prefix = "cache:lock:"`.

## TTL Strategy (protection/avalanche)

- Base: `resi-cache.default-ttl` (30m) or per-cache `caches.<name>.ttl`
- `TtlHandler` applies bounded random jitter (`TtlPolicy`) to avoid simultaneous expiry → avalanche
- Null values cached with shorter TTL (`NullValuePolicy`)

## Value Serialization (serialization/)

- `SecureJackson2JsonRedisSerializer` with type tag `@class` (`serializer.type-property`)
- Polymorphic typing gated by `serializer.polymorphic-typing-enabled` (default **false**)
- `serializer.allowed-package-prefixes` whitelist restricts deser target types (anti-gadget)
- `serializer.fail-on-unknown-type` (default true)
- Null encoded as `NullValue` marker → `SecureNullValueDeserializer` returns it safely

## Per-Cache Config (`resi-cache.caches.<name>`)

```
ttl, cacheNullValues, keyPrefix, enableBloomFilter, enableEarlyExpiration
```

## Redis Deployment (`resi-cache.redis`)

```
mode: single | cluster | sentinel   →   host/port | cluster-nodes | sentinel-*
tls-enabled: false
redisson.connection-pool-size: 64, connectionMinimumIdleSize: 10
```

## Health & Metrics

- `RedisCacheHealthIndicator` exposes Redis reachability via actuator
- `CacheMetricsRecorder` tracks hits/misses/latency per cache (see observability/)
