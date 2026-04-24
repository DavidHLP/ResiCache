# Roadmap — v1.0 缺陷修复

## Phase 1: 技术债务修复

**Goal:** 修复高优先级的技术债务问题

**Plans:** 2 plans

Plans:
- [x] 01-01-PLAN.md — TwoListLRU + ThreadPoolPreRefreshExecutor 修复
- [x] 01-02-PLAN.md — PreRefreshHandler + RedisProCacheWriter 修复

### Requirements
- TECH-01: TwoListLRU 读锁竞争优化
- TECH-02: ThreadPoolPreRefreshExecutor 清理机制修复
- TECH-03: PreRefreshHandler TTL 竞态条件修复
- TECH-04: RedisProCacheWriter.getChain() DCL 简化

### Success Criteria
1. TwoListLRU getActiveSize/getInactiveSize 不再每次获取读锁
2. cleanFinished() 有独立的清理机制，不依赖 getActiveCount()
3. PreRefreshHandler 在填充前检查 TTL，避免过期数据
4. getChain() 使用更简洁的延迟初始化模式

---

## Phase 2: 安全加固

**Goal:** 解决安全考虑中的风险点

**Plans:** 2 plans

Plans:
- [x] 02-01-PLAN.md — SpelConditionEvaluator + RedisBloomIFilter 增强
- [x] 02-02-PLAN.md — SecureJackson2JsonRedisSerializer 文档完善

### Requirements
- SEC-01: SpelConditionEvaluator 反射访问重构
- SEC-02: Bloom Filter Redis 操作添加超时
- SEC-03: Serializer 包白名单文档完善

### Success Criteria
1. SpelConditionEvaluator 不再使用反射访问 unless 字段
2. Bloom Filter 操作配置了显式超时
3. 包白名单配置有清晰的文档说明

---

## Phase 3: 性能优化

**Goal:** 优化关键路径性能

**Plans:** 3 plans

Plans:
- [x] 03-01-PLAN.md — StripedReadWriteLock 分段锁实现
- [x] 03-02-PLAN.md — Bloom Filter 哈希缓存实现
- [x] 03-03-PLAN.md — PERF-03 验证（已实现）

### Requirements
- PERF-01: TwoListLRU put() 写锁优化
- PERF-02: Bloom Filter 哈希缓存
- PERF-03: inFlight Map 自动清理机制

### Success Criteria
1. TwoListLRU 写锁竞争显著减少
2. 频繁访问的 key 哈希位置被缓存
3. inFlight Map 有自动清理机制

---

## Phase 4: 测试覆盖增强

**Goal:** 添加关键场景的测试覆盖

### Requirements
- TEST-01: TwoListLRU 并发访问测试
- TEST-02: PreRefreshHandler 竞态条件测试
- TEST-03: Handler Chain 异常处理测试
- TEST-04: Bloom Filter 假阳性影响测试
- TEST-05: SpEL 表达式注入测试

### Success Criteria
1. TwoListLRU 并发测试通过，无数据损坏
2. PreRefreshHandler 竞态条件被识别并有保护
3. Handler Chain 异常被正确处理
4. Bloom Filter 假阳性率在预期范围内
5. SpEL 表达式不会被注入

---

## Phase 5: 文档完善

**Goal:** 完善用户文档和 API 文档

### Requirements
- DOC-01: 更新包白名单配置文档
- DOC-02: 添加 Actuator 端点使用文档
- DOC-03: 添加缓存事件监听器配置文档

### Success Criteria
1. 用户能理解包白名单配置方法
2. Actuator 端点文档完整可用
3. 事件监听器配置有清晰示例

---

## Summary

| Phase | Name | Requirements | Success Criteria |
|-------|------|--------------|------------------|
| 1 | 技术债务修复 | 4 | 4 |
| 2 | 安全加固 | 3 | 3 |
| 3 | 性能优化 | 3 | 3 |
| 4 | 测试覆盖增强 | 5 | 5 |
| 5 | 文档完善 | 3 | 3 |

**Total:** 5 phases | 18 requirements | 18 success criteria
