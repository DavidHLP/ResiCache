# Phase 5: 文档完善 - Context

**Gathered:** 2026-04-25
**Status:** Ready for planning

<domain>
## Phase Boundary

为 ResiCache 完善三类文档：包白名单配置、Actuator 端点使用、缓存事件监听器配置。

</domain>

<decisions>
## Implementation Decisions

### DOC-01: 包白名单配置文档

- **D-01:** 在 SecureJackson2JsonRedisSerializer.java 的 Javadoc 基础上补充完整配置示例
  - 该类已有详细 Javadoc（lines 13-114），包含 RCE 风险说明和配置示例
  - 补充完整 application.yml 示例，包含常见业务包配置
  - 文件: `src/main/java/.../config/SecureJackson2JsonRedisSerializer.java`

[auto] DOC-01 — Q: "包白名单配置文档形式?" → Selected: "增强现有 Javadoc + application.yml 示例" (recommended default)

### DOC-02: Actuator 端点使用文档

- **D-02:** 创建完整的 Actuator 端点文档，包括缓存指标、健康检查、统计信息
  - 端点设计需遵循 Spring Boot Actuator 规范
  - 暴露缓存命中率、预刷新状态、布隆过滤器统计等指标
  - 文件: 新建 `docs/actuator-endpoints.md`

[auto] DOC-02 — Q: "Actuator 端点文档深度?" → Selected: "完整文档 + 使用示例" (recommended default)

### DOC-03: 缓存事件监听器配置文档

- **D-03:** 定义并文档化缓存事件监听器 API
  - 事件类型：缓存命中、缓存miss、预刷新触发、TTL过期等
  - 使用 Spring 的 ApplicationEventPublisher 机制
  - 文件: 新建 `docs/cache-event-listeners.md`

[auto] DOC-03 — Q: "事件监听器文档形式?" → Selected: "事件类型说明 + 配置示例" (recommended default)

### Claude's Discretion
- 文档格式：Markdown 格式，保存在 `docs/` 目录
- 代码示例：使用 Spring Boot 3.x 风格的 YAML 配置
- API 设计：遵循 Spring 惯例，使用 `@EventListener` 注解
</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Requirements
- `.planning/REQUIREMENTS.md` § Phase 5 — DOC-01 到 DOC-03 详细描述
- `.planning/PROJECT.md` — ResiCache 项目概述
- `.planning/codebase/STRUCTURE.md` — 代码库结构

### Prior Phase Context
- `.planning/phases/02-safe-hardening/02-CONTEXT.md` — SEC-03 Serializer 包白名单决策
- `.planning/phases/04-test-coverage/04-CONTEXT.md` — Phase 4 测试决策

### Existing Documentation
- `src/main/java/.../config/SecureJackson2JsonRedisSerializer.java` — 包白名单已有 Javadoc（lines 13-114）
- `readme.md` — 项目主文档
- `readme-en.md` — 英文版文档

### Java Documentation Standards
- `~/.claude/rules/java/coding-style.md` — Java 编码规范（Javadoc 要求）
- `~/.claude/rules/java/patterns.md` — Spring Boot 模式

</canonical_refs>

<codebase>
## Existing Code Insights

### Reusable Assets
- SecureJackson2JsonRedisSerializer.java — 已有完整 Javadoc
- RedisProCacheProperties.java — 配置属性定义
- readme.md — 现有文档结构可参考

### Established Patterns
- Spring Boot Actuator — 标准端点模式
- ApplicationEventPublisher — Spring 事件机制
- @EventListener — 事件监听注解

### Integration Points
- Actuator 端点需接入 RedisProCache 的指标数据
- 事件监听器需接入 CacheHandlerChain 的操作流程
- 文档放在 `docs/` 目录（参考 readme.md 位置）

</codebase>

<specifics>
## Specific Ideas

- 包白名单配置示例：需包含 `io.github.davidhlp`、`com.example.domain` 等典型包
- Actuator 端点：/actuator/resicache/cache/{cacheName}/stats
- 事件类型：CacheHitEvent, CacheMissEvent, PreRefreshTriggeredEvent, TtlExpiryEvent
</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope
</deferred>

---

*Phase: 05-documentation*
*Context gathered: 2026-04-25*
