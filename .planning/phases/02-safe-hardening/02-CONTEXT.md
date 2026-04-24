# Phase 2: 安全加固 - Context

**Gathered:** 2026-04-24
**Status:** Ready for planning

<domain>
## Phase Boundary

解决 ResiCache 中的 3 个安全考虑问题：SpEL 条件求值器的反射访问、布隆过滤器 Redis 操作超时配置、序列化器包白名单文档。

</domain>

<decisions>
## Implementation Decisions

### SEC-01: SpelConditionEvaluator 反射访问重构

- **D-01:** 保留当前 try-catch 回退模式，添加更明确的日志警告
  - 当反射访问 `unless` 字段失败时，记录 warn 级别日志而非 trace
  - 不依赖 Spring 内部实现变更，保持向后兼容
  - 文件: `SpelConditionEvaluator.java` (lines 94-105)
  - 原因: Spring Cache 的 `CacheOperation` 未暴露 `unless` 字段，无需修改 Spring 依赖

[auto] SEC-01 — Q: "SpelConditionEvaluator 反射访问处理方式?" → Selected: "保留 try-catch 回退，增加 warn 日志" (recommended default)

### SEC-02: Bloom Filter Redis 操作超时配置

- **D-02:** 通过 RedisTemplate 配置统一超时，不修改 bloom filter 代码
  - RedisTemplate 已注入到 RedisBloomIFilter，可通过配置设置默认超时
  - 建议在 `RedisProCacheProperties` 中添加 `bloom-filter.timeout` 配置项（可选）
  - 文件: `RedisBloomIFilter.java` (lines 40-48, 75-80)
  - 原因: 最小改动，通过 Spring 配置解决，无需修改业务代码

[auto] SEC-02 — Q: "Bloom Filter Redis 超时处理方式?" → Selected: "通过 RedisTemplate 配置统一超时" (recommended default)

### SEC-03: Serializer 包白名单文档完善

- **D-03:** 增强 `SecureJackson2JsonRedisSerializer` 的 Javadoc 和配置示例
  - 添加完整配置示例到类 Javadoc
  - 在 `resi-cache.serializer.allowed-package-prefixes` 配置项添加注释说明
  - 文件: `SecureJackson2JsonRedisSerializer.java`
  - 原因: 用户需要明确知道如何配置包白名单以避免反序列化失败

[auto] SEC-03 — Q: "包白名单文档完善方式?" → Selected: "增强类 Javadoc 和配置示例" (recommended default)

### Folded Todos

无折叠的待办事项

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Requirements
- `.planning/REQUIREMENTS.md` § Phase 2 — SEC-01 到 SEC-03 详细描述
- `.planning/codebase/CONCERNS.md` § Security Considerations — 安全问题的完整分析

### Codebase Analysis
- `.planning/codebase/CONCERNS.md` — 所有安全考虑问题的完整分析

### Relevant Source Files
- `src/main/java/io/github/davidhlp/spring/cache/redis/core/evaluator/SpelConditionEvaluator.java` — 反射访问问题 (lines 94-105)
- `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/support/protect/bloom/filter/RedisBloomIFilter.java` — 超时配置 (lines 40-48, 75-80)
- `src/main/java/io/github/davidhlp/spring/cache/redis/config/SecureJackson2JsonRedisSerializer.java` — 包白名单文档

### Java Coding Standards
- `~/.claude/rules/java/coding-style.md` — 日志级别、异常处理规范
- `~/.claude/rules/java/security.md` — 输入验证、安全编码规范
</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `SecureJackson2JsonRedisSerializer` — 已实现白名单验证，只需补充文档
- `RedisTemplate` — Spring Data Redis 模板，已支持超时配置

### Established Patterns
- `try-catch + fallback` 模式 — SpelConditionEvaluator 已使用
- RedisTemplate executePipelined — Spring Data Redis 标准 API

### Integration Points
- RedisBloomIFilter 依赖 RedisTemplate，可通过 Spring 配置设置超时
- SecureJackson2JsonRedisSerializer 配置通过构造器注入，支持 List<String> allowedPackagePrefixes

</code_context>

<specifics>
## Specific Ideas

- SpelConditionEvaluator 的反射访问是向后兼容的 fallback，不应破坏现有功能
- Bloom filter 超时应使用毫秒级配置，与 RedisTemplate 默认超时单位一致
- 包白名单默认只允许 `io.github.davidhlp`，用户必须配置其他需要的包
</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope
</deferred>

---

*Phase: 02-safe-hardening*
*Context gathered: 2026-04-24*
