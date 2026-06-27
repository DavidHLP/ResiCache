# ADR-0003: 保留序列化信封 + 提供迁移路径,不放松白名单

- **Status**: Accepted
- **Date**: 2026-06-27
- **Deciders**: DavidHLP
- **Related**: Q4（序列化兼容）

## Context

ResiCache 用 `SecureJackson` 的 **`VersionEnvelope {version, payload:{@class}}`**
序列化缓存值(`serialization/SecureJackson*`),并配白名单
`resi-cache.serializer.allowed-package-prefixes`(默认 `io.github.davidhlp`,
见 `RedisProCacheProperties.SerializerProperties`)。

这与 Spring 原生 `GenericJackson2JsonRedisSerializer` / `JdkSerializer` **格式不兼容**。
存量项目接入时,旧格式缓存无法被 ResiCache 反序列化 → 全量 miss。

曾考虑的修复:**放松白名单 + 关闭 fail-on-unknown-type**。但这**不解决格式不兼容**——
信封结构差异是根本,白名单只影响类型校验。放松白名单反而削弱对 Jackson 多态类型攻击
的防护(见 SECURITY.md)。

## Decision

1. **保留信封 + 白名单**(安全优先,不放松)。
2. **提供兼容开关与迁移路径**(v0.2.0,同一发布单元):
   - `envelope-enabled` 开关:可关闭信封用裸 JSON;
   - `legacy` fallback:读取时兼容旧格式;
   - `keyPrefix`:新旧缓存隔离命名空间;
   - **迁移工具**:`shadow(双读)→ dual-write(双写)→ cutover(切流)` 三阶段。
3. README / COMPATIBILITY 显式声明不兼容与迁移义务。

## Consequences

- **正面**:保持安全基线(白名单防多态攻击);存量项目有受控迁移路径,而非"静默全 miss"。
- **负面**:v0.2.0 前接入存量项目需要清空缓存或手写迁移——README 已在 Known Limitations
  明示。
- **不变**:`fail-on-unknown-type=true`、`polymorphic-typing-enabled=false` 保持安全默认。
