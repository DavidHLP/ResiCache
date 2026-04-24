# Phase 4: 测试覆盖增强 - Context

**Gathered:** 2026-04-24
**Status:** Ready for planning

<domain>
## Phase Boundary

为 ResiCache 添加关键场景的测试覆盖，包括 TwoListLRU 并发访问、PreRefreshHandler 竞态条件、Handler Chain 异常处理、Bloom Filter 行为验证和 SpEL 表达式安全。
</domain>

<decisions>
## Implementation Decisions

### TEST-01: TwoListLRU 并发访问测试

- **D-01:** 使用 ConcurrentUnit 进行并发测试
  - 轻量级并发测试框架，与 JUnit 5 集成良好
  - 支持 await() 语义，代码简洁
  - 验证多线程同时 put/get 不会导致列表损坏或死锁
  - 文件: `TwoListLRUTest.java` (新建)

### TEST-04: Bloom Filter 假阳性影响测试

- **D-02:** 验证假阳性时缓存保护行为存在
  - 测试假阳性时不阻塞缓存回源，继续返回结果
  - 不测量具体假阳性率（性能测试范畴）
  - 补充现有 BloomFilterHandlerTest.java 的行为覆盖
  - 文件: `BloomFilterFalsePositiveTest.java` (新建)

### TEST-05: SpEL 表达式注入测试

- **D-03:** 使用评估器单元测试验证 SpEL 表达式安全
  - 直接测试 SpelConditionEvaluator
  - 验证恶意表达式被拒绝或安全处理
  - 不做端到端集成测试（评估器测试已足够）
  - 文件: `SpelConditionEvaluatorTest.java` (新建)

### Claude's Discretion
- 并发测试线程数：建议 4-8 个线程，覆盖典型并发场景
- 测试数据集：使用模拟数据而非真实数据集（保持测试确定性）
- SpEL 恶意表达式样例：参考 OWASP 安全测试用例
</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Requirements
- `.planning/REQUIREMENTS.md` § Phase 4 — TEST-01 到 TEST-05 详细描述
- `.planning/phases/03-performance-optimization/03-CONTEXT.md` — Phase 3 性能优化决策

### Relevant Source Files
- `src/main/java/io/github/davidhlp/spring/cache/redis/strategy/eviction/support/TwoListLRU.java` — 并发测试目标
- `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/support/protect/bloom/filter/RedisBloomIFilter.java` — Bloom Filter
- `src/main/java/io/github/davidhlp/spring/cache/redis/core/condition/SpelConditionEvaluator.java` — SpEL 评估器
- `src/test/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/PreRefreshHandlerTest.java` — 已有测试参考
- `src/test/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/BloomFilterHandlerTest.java` — 已有测试参考

### Java Testing Standards
- `~/.claude/rules/java/testing.md` — JUnit 5 + AssertJ + Mockito 测试规范
- `~/.claude/rules/java/coding-style.md` — Java 编码规范
</canonical_refs>

<codebase>
## Existing Code Insights

### Reusable Assets
- JUnit 5 + Mockito — 现有测试框架
- AssertJ — 现有断言库
- PreRefreshHandlerTest.java — PreRefreshHandler 测试参考
- BloomFilterHandlerTest.java — BloomFilter 测试参考

### Established Patterns
- @ExtendWith(MockitoExtension.class) — Mock 测试模式
- @Nested + @DisplayName — 测试组织模式
- @ParameterizedTest — 参数化测试（需要时）

### Integration Points
- TwoListLRU: 并发测试需覆盖 put/get/evict 全流程
- BloomFilterHandler: 假阳性测试需模拟 mayContain() 返回 true 但实际 key 不存在
- SpelConditionEvaluator: 注入测试需构造恶意 SpEL 表达式
</codebase>

<specifics>
## Specific Ideas

- ConcurrentUnit 依赖添加到 pom.xml
- SpEL 恶意表达式样例：`T(java.lang.Runtime).getRuntime().exec(...)`
- 假阳性测试模拟：BloomFilter 返回 true 但 Redis 中 key 不存在
</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope
</deferred>

---

*Phase: 04-test-coverage*
*Context gathered: 2026-04-24*
