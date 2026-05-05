# Changelog

All notable changes to this project will be documented in this file.

## [1.1.0] - 2026-04-26
### Added
- Enterprise observability and resilience features
- Circuit breaker pattern implementation
- Rate limiter pattern implementation
- Bloom filter three-layer implementation (Local/Hierarchical/Redis)
- Advanced SPEL condition expression support with #method/#args/#target/#result variables
- TTL anti-avalanche with randomization
- Cache pre-refresh mechanism

### Fixed
- Multiple technical debt issues

## [1.0.0] - 2026-04-01
### Added
- Initial release
- @RedisCacheable, @RedisCacheEvict, @RedisCachePut annotations
- TwoListLRU eviction strategy with Active/Inactive partition
- SPEL-based condition expressions
- TTL management
- Distributed lock support via Redisson
- SecureJackson2JsonRedisSerializer for serialization security