# Phase 2: 安全加固 - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-24
**Phase:** 2-safe-hardening
**Areas discussed:** SEC-01 (SpelConditionEvaluator), SEC-02 (Bloom Filter timeout), SEC-03 (Serializer documentation)

---

## SEC-01: SpelConditionEvaluator 反射访问重构

| Option | Description | Selected |
|--------|-------------|----------|
| 保留 try-catch 回退，增加 warn 日志 | 当前实现保持不变，失败时记录 warn 而非 trace | ✓ |
| 请求 Spring 暴露 unless 字段 | 提交 PR 到 Spring 等待合入，依赖外部变更 | |
| 使用 CGLIB 代理替换反射 | 增加复杂度和依赖，不推荐 | |

**User's choice:** [auto] 保留 try-catch 回退，增加 warn 日志
**Notes:** 自动模式下使用推荐默认值。Spring Cache 的 CacheOperation 未暴露 unless 字段，最小改动方案是增强日志。

---

## SEC-02: Bloom Filter Redis 操作超时配置

| Option | Description | Selected |
|--------|-------------|----------|
| 通过 RedisTemplate 配置统一超时 | 通过 Spring 配置设置 RedisTemplate 超时，不修改业务代码 | ✓ |
| 在 RedisBloomIFilter 中硬编码超时 | 每个操作添加 explicit timeout 参数，增加代码复杂度 | |
| 使用 Redis 命令本身的 timeout 参数 | 部分 Redis 命令支持 timeout 参数，非通用方案 | |

**User's choice:** [auto] 通过 RedisTemplate 配置统一超时
**Notes:** 自动模式下使用推荐默认值。Spring Data Redis 的 RedisTemplate 支持统一超时配置。

---

## SEC-03: Serializer 包白名单文档完善

| Option | Description | Selected |
|--------|-------------|----------|
| 增强类 Javadoc 和配置示例 | 补充完整配置示例到类文档 | ✓ |
| 创建独立配置指南文档 | 新建文档但增加维护成本 | |
| 添加 Spring Boot Starter 自动配置 | 需要额外开发工作，超出当前范围 | |

**User's choice:** [auto] 增强类 Javadoc 和配置示例
**Notes:** 自动模式下使用推荐默认值。最小改动方案，直接在代码中完善文档。

---

## Claude's Discretion

无 — 所有决策均通过 auto 模式使用推荐默认值

## Deferred Ideas

无
