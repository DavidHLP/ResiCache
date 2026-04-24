# Phase 4: 测试覆盖增强 - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-24
**Phase:** 04-test-coverage
**Areas discussed:** TEST-01 并发测试框架, TEST-04 Bloom Filter 测试粒度, TEST-05 SpEL 测试策略

---

## TEST-01: 并发测试框架

| Option | Description | Selected |
|--------|-------------|----------|
| ConcurrentUnit | 轻量级并发测试，API 简洁，与 JUnit 5 集成好 | ✓ |
| CountDownLatch + ExecutorService | JDK 原生无依赖，更灵活但代码较多 | |
| jMockit | 功能强大，但重量级且许可证复杂 | |
| 你来决定 | 让我根据现有测试框架推断 | |

**User's choice:** ConcurrentUnit
**Notes:** 轻量级并发测试框架，与现有 JUnit 5 + Mockito 测试体系一致

---

## TEST-04: Bloom Filter 测试粒度

| Option | Description | Selected |
|--------|-------------|----------|
| 验证行为存在 | 只验证假阳性时缓存不阻塞，继续回源 | ✓ |
| 测量假阳性率 | 使用统计方法验证假阳性率在预期范围内 | |
| 两者都要 | 既验证保护行为又测量假阳性率 | |

**User's choice:** 验证行为存在
**Notes:** 假阳性率测量属于性能测试范畴，行为验证测试更实用

---

## TEST-05: SpEL 测试策略

| Option | Description | Selected |
|--------|-------------|----------|
| 评估器单元测试 | 直接测试 SpelConditionEvaluator，验证恶意表达式被拒绝 | ✓ |
| 端到端集成测试 | 测试缓存操作时 SpEL 条件不会被注入 | |
| 两者都要 | 单元测试 + 集成测试双重覆盖 | |

**User's choice:** 评估器单元测试
**Notes:** 评估器单元测试已足够覆盖安全验证需求

---

## Claude's Discretion

- 并发测试线程数：4-8 个线程
- 测试数据集：使用模拟数据
- SpEL 恶意表达式参考 OWASP 安全测试用例

## Deferred Ideas

None — discussion stayed within phase scope
