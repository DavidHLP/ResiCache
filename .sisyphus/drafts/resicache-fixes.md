# Draft: ResiCache 20个问题修复计划

## 修复范围确认
- **包含**: 全部20个问题（3 Critical + 5 High + 5 Medium + 7 Low）
- **目标**: 一次性生成完整修复计划，不分阶段

## 测试策略决策
- **策略**: TDD（先写测试再修复）
- **框架**: JUnit 5 + Mockito + AssertJ（已有基础设施）
- **每个修复任务**: 先写失败的测试 → 修复代码 → 验证测试通过

## 关键依赖关系（初步）
1. **Wave 1（基础设施）**: CI/CD添加mvn test、Checkstyle规则修复、JaCoCo阈值调整
   - 这些不依赖其他代码修改，可最先完成
2. **Wave 2（核心并发修复）**: TwoListLRU、PreRefreshHandler、SyncSupport、DistributedLockManager
   - 需要仔细设计测试场景验证并发行为
3. **Wave 3（状态竞争修复）**: CircuitBreakerCacheWrapper、RateLimiterCacheWrapper
4. **Wave 4（代码质量）**: SpelConditionEvaluator、ThreadPoolPreRefreshExecutor、其他Low优先级
5. **Wave 5（完善）**: .editorconfig、mvnw、集成测试、@Deprecated等

## 风险点
- TwoListLRU 并发修复可能引入新的死锁
- 分布式锁修改可能影响现有行为
- PreRefreshHandler 竞态修复需要Redis环境测试

## 验收标准
- 所有测试通过（包括新增测试）
- CI/CD中mvn test通过
- Checkstyle无错误
- JaCoCo覆盖率达到设定阈值
