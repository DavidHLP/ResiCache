# ResiCache 20 Issues Fix Plan

## TL;DR

> **目标**: 系统性修复 ResiCache 项目识别的 20 个代码问题，涵盖并发安全、CI/CD、代码质量、资源管理和完善性改进。
>
> **修复范围**: 3 Critical + 5 High + 5 Medium + 7 Low = 20 个问题
>
> **测试策略**: TDD（每个修复先写失败测试，再修复代码）
>
> **执行方式**: 5 个并行 Wave + 1 个 Final Verification Wave
>
> **预计工时**: Medium-Large（约 2-3 天并行执行）
>
> **关键路径**: Wave 1（基础设施）→ Wave 2（核心并发）→ Wave 3（状态竞争）→ Wave 4（资源处理）→ Wave 5（完善）→ Final Verification

---

## Context

### Original Request
基于对 ResiCache 项目的全面代码审查，识别出 20 个需要修复的问题，要求生成一次性修复所有问题的详细工作计划。

### Interview Summary
**关键讨论**:
- 修复范围: 全部 20 个问题，不分阶段
- 测试策略: TDD（先写测试再修复）
- 框架: JUnit 5 + Mockito + AssertJ（已有基础设施）

### Research Findings
**代码库状态**:
- Java 17 + Spring Boot 3.2.4 + Maven
- 已有完善的测试基础设施（52 个测试文件）
- CI/CD 使用 GitHub Actions（Qodana + Maven Central 部署）
- 使用 JaCoCo 但阈值设为 0%（无约束力）
- Checkstyle 配置但几乎所有规则被禁用

**关键风险点**:
- TwoListLRU 并发缺陷导致 775/800 测试失败
- 分布式锁中断处理可能导致锁泄漏
- PreRefreshHandler 存在竞态条件
- CI 从不执行测试

---

## Work Objectives

### Core Objective
系统性修复全部 20 个已识别问题，提升代码质量、并发安全性和工程实践。

### Concrete Deliverables
- `.github/workflows/qodana_code_quality.yml` - 添加 mvn test 步骤
- `pom.xml` - JaCoCo 阈值调整
- `src/main/resources/checkstyle-custom.xml` - 启用核心规则
- `TwoListLRU.java` - 并发安全修复
- `DistributedLockManager.java` - 中断处理修复
- `SyncSupport.java` - 中断处理修复
- `PreRefreshHandler.java` - 竞态条件修复
- `CircuitBreakerCacheWrapper.java` - 状态竞争修复
- `RateLimiterCacheWrapper.java` - token 竞争修复
- `ThreadPoolPreRefreshExecutor.java` - 关闭顺序修复
- `SpelConditionEvaluator.java` - 异常处理修复
- 新增 `.editorconfig`、`mvnw` 等配置文件

### Definition of Done
- [ ] 所有新增测试通过
- [ ] `mvn test` 在本地和 CI 中全部通过
- [ ] `mvn verify` 通过（包括 Checkstyle、JaCoCo）
- [ ] 无新的代码质量问题引入

### Must Have
- 所有 Critical 和 High 级别问题必须修复
- 每个修复必须有对应的测试验证
- CI/CD 必须能够执行测试

### Must NOT Have (Guardrails)
- 不引入新的依赖（除 Testcontainers 用于集成测试外）
- 不改变公共 API 签名（保持向后兼容）
- 不重构整个架构（只修复具体问题）
- 不在修复中引入新的并发问题

---

## Verification Strategy

> **ZERO HUMAN INTERVENTION** - ALL verification is agent-executed.

### Test Decision
- **Infrastructure exists**: YES
- **Automated tests**: TDD
- **Framework**: JUnit 5 + Mockito + AssertJ
- **Coverage tool**: JaCoCo（阈值将调整为 60%）

### QA Policy
Every task MUST include agent-executed QA scenarios:
- **Backend**: Use Bash (mvn test) - Run specific test classes, assert PASS
- **CI/CD**: Use Bash (act 或验证 workflow 文件语法)
- **Config**: Use Bash (mvn verify) - Full build verification
- Evidence saved to `.sisyphus/evidence/task-{N}-{scenario-slug}.{ext}`

---

## Execution Strategy

### Parallel Execution Waves

```
Wave 1 (Foundation - 5 tasks, ALL parallel):
├── T1: CI/CD 添加 mvn test [quick]
├── T2: JaCoCo 覆盖率阈值调整 [quick]
├── T3: Checkstyle 核心规则启用 [quick]
├── T4: 添加 .editorconfig [quick]
└── T5: 添加 Maven Wrapper [quick]

Wave 2 (Critical Concurrency - 4 tasks, ALL parallel):
├── T6: TwoListLRU 并发缺陷修复 [deep]
├── T7: 分布式锁中断处理修复 [unspecified-high]
├── T8: SyncSupport 中断处理修复 [unspecified-high]
└── T9: PreRefreshHandler 竞态条件修复 [deep]

Wave 3 (State Competition - 3 tasks, ALL parallel):
├── T10: CircuitBreakerCacheWrapper 竞争修复 [unspecified-high]
├── T11: RateLimiterCacheWrapper 竞争修复 [unspecified-high]
└── T12: SpelConditionEvaluator 异常处理修复 [unspecified-high]

Wave 4 (Resources & Errors - 4 tasks, ALL parallel):
├── T13: ThreadPoolPreRefreshExecutor 关闭顺序 [unspecified-high]
├── T14: 重试循环中断处理修复 [quick]
├── T15: 锁释放失败重试机制 [unspecified-high]
└── T16: 锁键前缀配置化 [quick]

Wave 5 (Polish - 4 tasks, ALL parallel):
├── T17: @Deprecated 注解添加 [quick]
├── T18: 未使用参数清理 [quick]
├── T19: 集成测试添加（Testcontainers）[unspecified-high]
└── T20: TwoListLRU 性能优化（可选）[deep]

Wave FINAL (Verification - 4 tasks, ALL parallel):
├── F1: Plan compliance audit (oracle)
├── F2: Code quality review (unspecified-high)
├── F3: Full QA execution (unspecified-high)
└── F4: Scope fidelity check (deep)
```

### Dependency Matrix

| Task | Depends On | Blocks |
|------|-----------|--------|
| T1-T5 | None | F1-F4 |
| T6-T9 | None | F1-F4 |
| T10-T12 | None | F1-F4 |
| T13-T16 | None | F1-F4 |
| T17-T20 | None | F1-F4 |
| F1-F4 | ALL above | — |

> 所有实现任务互相独立，可完全并行执行。只有 Final Verification 依赖所有前置任务。

### Agent Dispatch Summary

- **Wave 1**: 5 × `quick`
- **Wave 2**: 1 × `deep` + 3 × `unspecified-high`
- **Wave 3**: 3 × `unspecified-high`
- **Wave 4**: 2 × `unspecified-high` + 2 × `quick`
- **Wave 5**: 2 × `quick` + 1 × `unspecified-high` + 1 × `deep`
- **FINAL**: 1 × `oracle` + 2 × `unspecified-high` + 1 × `deep`

---

## TODOs

- [x] 1. CI/CD 添加 `mvn test` 执行步骤

  **What to do**:
  - 修改 `.github/workflows/qodana_code_quality.yml`，在 Qodana Scan 步骤前或后添加 `mvn test` 步骤
  - 确保使用正确的 Java 版本（17）
  - 配置 Maven 缓存以加速构建

  **Must NOT do**:
  - 不修改 deploy.yml（只在 release 时触发，已满足需求）
  - 不引入新的 CI 平台或工具

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1
  - **Blocks**: F1-F4
  - **Blocked By**: None

  **References**:
  - `.github/workflows/qodana_code_quality.yml` - 当前 CI workflow 配置
  - `.github/workflows/deploy.yml` - 参考 Java 版本配置

  **Acceptance Criteria**:
  - [ ] Workflow 文件语法有效（可通过 `act` 或 GitHub 验证）
  - [ ] 包含 `- run: mvn test` 步骤
  - [ ] 使用 `actions/setup-java@v4` 配置 Java 17
  - [ ] 使用 `actions/cache` 缓存 Maven 依赖

  **QA Scenarios**:
  ```
  Scenario: CI workflow 语法验证
    Tool: Bash
    Preconditions: 已修改 workflow 文件
    Steps:
      1. cat .github/workflows/qodana_code_quality.yml | grep -q "mvn test"
      2. cat .github/workflows/qodana_code_quality.yml | grep -q "setup-java"
    Expected Result: 两个 grep 都返回 0（找到匹配）
    Evidence: .sisyphus/evidence/task-1-ci-workflow.yml
  ```

  **Commit**: YES
  - Message: `ci: add mvn test step to quality workflow`
  - Files: `.github/workflows/qodana_code_quality.yml`

- [x] 2. JaCoCo 覆盖率阈值调整

  **What to do**:
  - 修改 `pom.xml` 中 JaCoCo 插件配置
  - 将 `<minimum>0.00</minimum>` 调整为合理的阈值（建议 0.60）
  - 确保 `mvn verify` 会因覆盖率不足而失败

  **Must NOT do**:
  - 不调整其他 JaCoCo 配置（如报告格式）
  - 不删除 `check` goal

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1
  - **Blocks**: F1-F4
  - **Blocked By**: None

  **References**:
  - `pom.xml:220-223` - 当前 JaCoCo 阈值配置

  **Acceptance Criteria**:
  - [ ] `<minimum>0.60</minimum>`（或用户指定值）
  - [ ] `mvn verify` 成功执行

  **QA Scenarios**:
  ```
  Scenario: JaCoCo 阈值生效
    Tool: Bash
    Preconditions: 已修改 pom.xml
    Steps:
      1. grep -q '<minimum>0.60</minimum>' pom.xml
      2. mvn verify -DskipTests=false -q
    Expected Result: grep 返回 0，mvn verify 成功（BUILD SUCCESS）
    Evidence: .sisyphus/evidence/task-2-jacoco-threshold.log
  ```

  **Commit**: YES
  - Message: `build: set JaCoCo minimum coverage to 60%`
  - Files: `pom.xml`

- [x] 3. Checkstyle 核心规则启用

  **What to do**:
  - 修改 `src/main/resources/checkstyle-custom.xml`
  - 将以下规则的 `severity="ignore"` 改为 `severity="error"`：
    - `UnusedImports` - 未使用的导入
    - `RedundantImport` - 冗余导入
    - `MagicNumber` - 魔术数字（可考虑保留 ignore，或改为 warning）
    - `EmptyBlock` - 空代码块
    - `NeedBraces` - if/else 缺少花括号
  - 保留其他规则为 ignore（如 Javadoc、Whitespace 等，避免一次性引入太多噪音）

  **Must NOT do**:
  - 不修改 Checkstyle DTD 或模块结构
  - 不引入新的 Checkstyle 规则

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1
  - **Blocks**: F1-F4
  - **Blocked By**: None

  **References**:
  - `src/main/resources/checkstyle-custom.xml` - 当前 Checkstyle 配置

  **Acceptance Criteria**:
  - [ ] 至少启用 UnusedImports、RedundantImport、EmptyBlock、NeedBraces
  - [ ] `mvn checkstyle:check` 通过（或现有代码已符合这些规则）

  **QA Scenarios**:
  ```
  Scenario: Checkstyle 规则生效
    Tool: Bash
    Preconditions: 已修改 checkstyle-custom.xml
    Steps:
      1. grep 'UnusedImports' src/main/resources/checkstyle-custom.xml | grep -v 'ignore'
      2. mvn checkstyle:check -q
    Expected Result: grep 找到非 ignore 的配置，mvn 成功
    Evidence: .sisyphus/evidence/task-3-checkstyle.log
  ```

  **Commit**: YES
  - Message: `style: enable core checkstyle rules (unused imports, empty blocks, need braces)`
  - Files: `src/main/resources/checkstyle-custom.xml`

- [x] 4. 添加 .editorconfig

  **What to do**:
  - 在项目根目录创建 `.editorconfig` 文件
  - 配置 Java 文件使用 UTF-8、LF 换行、4 空格缩进
  - 配置 XML/YML 文件使用 2 空格缩进
  - 配置最大行宽 200（与现有 Checkstyle LineLength 一致）

  **Must NOT do**:
  - 不强制改变现有文件格式（只配置新文件的默认行为）

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1
  - **Blocks**: F1-F4
  - **Blocked By**: None

  **Acceptance Criteria**:
  - [ ] `.editorconfig` 文件存在于根目录
  - [ ] 包含 Java、XML、YML 的基本格式配置

  **QA Scenarios**:
  ```
  Scenario: .editorconfig 存在且有效
    Tool: Bash
    Preconditions: 已创建 .editorconfig
    Steps:
      1. test -f .editorconfig
      2. grep -q 'indent_size = 4' .editorconfig
    Expected Result: 两个测试都通过
    Evidence: .sisyphus/evidence/task-4-editorconfig.txt
  ```

  **Commit**: YES
  - Message: `chore: add .editorconfig for consistent formatting`
  - Files: `.editorconfig`

- [x] 5. 添加 Maven Wrapper

  **What to do**:
  - 运行 `mvn wrapper:wrapper` 生成 Maven Wrapper
  - 确保 `mvnw` 和 `mvnw.cmd` 被创建
  - 将 wrapper 相关文件添加到 `.gitignore` 的例外（或确认已配置）

  **Must NOT do**:
  - 不修改 pom.xml 中的 Maven 版本要求

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1
  - **Blocks**: F1-F4
  - **Blocked By**: None

  **Acceptance Criteria**:
  - [ ] `./mvnw` 可执行
  - [ ] `./mvnw test` 能成功运行

  **QA Scenarios**:
  ```
  Scenario: Maven Wrapper 可用
    Tool: Bash
    Preconditions: 已生成 wrapper
    Steps:
      1. test -x mvnw
      2. ./mvnw -version | grep -q 'Apache Maven'
    Expected Result: 两个测试都通过
    Evidence: .sisyphus/evidence/task-5-maven-wrapper.log
  ```

  **Commit**: YES
  - Message: `chore: add Maven wrapper for consistent build environment`
  - Files: `mvnw`, `mvnw.cmd`, `.mvn/wrapper/*`

- [x] 6. TwoListLRU 并发缺陷修复

  **What to do**:
  - 修复 `get()` 方法中读锁释放与写锁获取之间的竞态窗口
  - 当前问题：释放读锁后、获取写锁前，其他线程可能修改 nodeMap 导致节点状态不一致
  - 方案选择（推荐方案 A）：
    - **方案 A**：移除读锁优化，统一使用写锁（简化且安全，Metis 推荐）
    - **方案 B**：使用 StampedLock 的乐观读 + 验证模式（性能更好但复杂）
  - 确保 `promoteNodeUnsafe()` 等方法的锁假设一致
  - 添加并发压力测试验证修复

  **Must NOT do**:
  - 不改变公共 API 签名
  - 不引入新的数据结构（保持现有 Node 双向链表结构）
  - 不降低现有单线程性能

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: []
  - **Reason**: 并发修复需要深入理解锁语义和内存模型

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2
  - **Blocks**: F1-F4
  - **Blocked By**: None

  **References**:
  - `src/main/java/.../strategy/eviction/support/TwoListLRU.java:169-206` - get() 方法的锁交互
  - `TwoListLRUConcurrentTest.java` - 现有并发测试（775/800 失败）

  **Acceptance Criteria**:
  - [ ] `TwoListLRUConcurrentTest` 所有测试通过（0 失败）
  - [ ] `TwoListLRUTest` 现有单线程测试不受影响
  - [ ] 新增压力测试：100 线程并发 put/get 30 秒无数据损坏

  **QA Scenarios**:
  ```
  Scenario: TwoListLRU 并发测试通过
    Tool: Bash (mvn test)
    Preconditions: 已修复并发缺陷
    Steps:
      1. mvn test -Dtest=TwoListLRUConcurrentTest
      2. mvn test -Dtest=TwoListLRUTest
    Expected Result: 两个测试套件全部通过（Tests run: N, Failures: 0）
    Evidence: .sisyphus/evidence/task-6-twolistlru-concurrent.log

  Scenario: 压力测试无数据损坏
    Tool: Bash (运行自定义压测)
    Preconditions: 已修复
    Steps:
      1. 运行 100 线程 * 1000 次 put/get 操作
      2. 验证 retrieved value == stored value（当 retrieved != null 时）
    Expected Result: corruptionCount == 0
    Evidence: .sisyphus/evidence/task-6-twolistlru-stress.log
  ```

  **Commit**: YES
  - Message: `fix(lru): fix race condition in TwoListLRU get() method`
  - Files: `TwoListLRU.java`, `TwoListLRUConcurrentTest.java`

- [x] 7. 分布式锁中断处理修复（SyncSupport）

  **What to do**:
  - 修复 `SyncSupport.executeSync()` 中 `InterruptedException` 被吞掉的问题
  - 当前代码：
    ```java
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return loader.get(); // 吞掉中断！
    }
    ```
  - 改为抛出包含中断状态的异常：
    ```java
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Thread interrupted while acquiring lock for key: " + key, e);
    }
    ```
  - 确保调用方（如 SyncLockHandler）能够正确处理此异常
  - 添加测试验证中断行为

  **Must NOT do**:
  - 不改变锁获取的核心逻辑
  - 不修改 LockStack 的关闭语义

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2
  - **Blocks**: F1-F4
  - **Blocked By**: None

  **References**:
  - `src/main/java/.../writer/support/lock/SyncSupport.java:50-70` - executeSync 方法
  - `src/main/java/.../writer/support/lock/DistributedLockManager.java` - 锁获取实现
  - `src/main/java/.../writer/chain/handler/SyncLockHandler.java` - 调用方

  **Acceptance Criteria**:
  - [ ] 新增测试：线程中断时抛出异常而非静默返回
  - [ ] `SyncLockHandler` 正确处理异常（返回适当的 CacheResult）
  - [ ] `DistributedLockManagerTest` 通过

  **QA Scenarios**:
  ```
  Scenario: 中断时抛出异常
    Tool: Bash (mvn test)
    Preconditions: 已修复 SyncSupport
    Steps:
      1. mvn test -Dtest=SyncSupportTest
      2. 验证测试中包含 InterruptedException 场景
    Expected Result: 测试通过，中断时抛出异常
    Evidence: .sisyphus/evidence/task-7-sync-interrupt.log
  ```

  **Commit**: YES
  - Message: `fix(lock): propagate InterruptedException instead of swallowing in SyncSupport`
  - Files: `SyncSupport.java`, `SyncSupportTest.java`, `SyncLockHandler.java`

- [x] 8. 分布式锁释放安全修复（DistributedLockManager）

  **What to do**:
  - 修复 `DistributedLockManager.tryAcquire()` 中 `Thread.currentThread().interrupt()` 导致中断状态丢失的问题
  - 当前问题：捕获 `InterruptedException` 后设置中断标志然后重新抛出，但上层可能再次捕获并吞掉
  - 确保锁释放逻辑在异常路径上始终执行（try-finally 模式）
  - 添加锁释放失败的重试机制（最多 3 次）

  **Must NOT do**:
  - 不改变锁的语义（仍为可重入锁）

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2
  - **Blocks**: F1-F4
  - **Blocked By**: None

  **References**:
  - `src/main/java/.../writer/support/lock/DistributedLockManager.java`
  - `src/main/java/.../writer/support/lock/LockStack.java`

  **Acceptance Criteria**:
  - [ ] `DistributedLockManagerTest` 通过
  - [ ] 新增测试：中断后锁正确释放
  - [ ] 新增测试：锁释放失败时重试

  **QA Scenarios**:
  ```
  Scenario: 锁释放安全性
    Tool: Bash (mvn test)
    Steps:
      1. mvn test -Dtest=DistributedLockManagerTest
    Expected Result: 全部通过
    Evidence: .sisyphus/evidence/task-8-lock-release.log
  ```

  **Commit**: YES
  - Message: `fix(lock): ensure lock release on interrupt and add retry`
  - Files: `DistributedLockManager.java`, `DistributedLockManagerTest.java`

- [x] 9. PreRefreshHandler 竞态条件修复

  **What to do**:
  - 修复 `scheduleAsyncRefresh()` 中版本检查与 Redis 操作之间的竞态窗口
  - 当前问题：检查 `liveValue.getVersion() == originalVersion` 后，其他线程可能修改缓存值
  - 方案：使用 Redis 原子操作（如 Lua 脚本或 Redis 事务）确保 TTL 缩短操作的原子性
  - 或在版本检查时添加更严格的条件

  **Must NOT do**:
  - 不引入 Redis 之外的依赖
  - 不改变预刷新的业务语义

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: []
  - **Reason**: 需要理解 Redis 原子操作和并发语义

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2
  - **Blocks**: F1-F4
  - **Blocked By**: None

  **References**:
  - `src/main/java/.../writer/chain/handler/PreRefreshHandler.java:131-174`
  - `PreRefreshHandlerRaceConditionTest.java` - 现有竞态测试

  **Acceptance Criteria**:
  - [ ] `PreRefreshHandlerRaceConditionTest` 全部通过
  - [ ] 新增测试：并发预刷新不会覆盖更新的值

  **QA Scenarios**:
  ```
  Scenario: 预刷新竞态测试
    Tool: Bash (mvn test)
    Steps:
      1. mvn test -Dtest=PreRefreshHandlerRaceConditionTest
    Expected Result: 全部通过
    Evidence: .sisyphus/evidence/task-9-prerefresh-race.log
  ```

  **Commit**: YES
  - Message: `fix(refresh): fix race condition in PreRefreshHandler async refresh`
  - Files: `PreRefreshHandler.java`, `PreRefreshHandlerRaceConditionTest.java`

- [x] 10. CircuitBreakerCacheWrapper 状态竞争修复

  **What to do**:
  - 修复 `CircuitBreakerState` 中 `currentState`（volatile）与 `failureTimestamps`（非原子）之间的状态不一致
  - 当前问题：`transitionToHalfOpen()` 中修改 currentState 后，其他线程可能看到旧的状态但新的 failureTimestamps
  - 方案：使用 `AtomicReference<CircuitBreakerState>` 包装整个状态对象，或 synchronized 保护状态变更
  - 确保 `recordFailure()` 和 `recordSuccess()` 的原子性

  **Must NOT do**:
  - 不改变熔断器状态机语义（CLOSED → OPEN → HALF_OPEN）

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3
  - **Blocks**: F1-F4
  - **Blocked By**: None

  **References**:
  - `src/main/java/.../core/wrapper/CircuitBreakerCacheWrapper.java`
  - `CircuitBreakerCacheWrapperTest.java`

  **Acceptance Criteria**:
  - [ ] `CircuitBreakerCacheWrapperTest` 通过
  - [ ] 新增并发测试：100 线程同时调用 recordFailure/recordSuccess 无状态不一致

  **QA Scenarios**:
  ```
  Scenario: 熔断器状态一致性
    Tool: Bash (mvn test)
    Steps:
      1. mvn test -Dtest=CircuitBreakerCacheWrapperTest
    Expected Result: 全部通过
    Evidence: .sisyphus/evidence/task-10-circuitbreaker.log
  ```

  **Commit**: YES
  - Message: `fix(circuitbreaker): fix race condition in state transitions`
  - Files: `CircuitBreakerCacheWrapper.java`, `CircuitBreakerCacheWrapperTest.java`

- [x] 11. RateLimiterCacheWrapper token 竞争修复

  **What to do**:
  - 修复 `tryAcquire()` 中 `tokens.compareAndSet()` 与 `lastUpdate.set()` 之间的非原子更新
  - 当前问题：CAS 成功后设置 lastUpdate，但失败时未重试，且 lastUpdate 可能与其他线程的更新冲突
  - 方案：在锁保护下使用原子操作，或重构为无锁算法

  **Must NOT do**:
  - 不改变限流的核心算法（令牌桶）

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3
  - **Blocks**: F1-F4
  - **Blocked By**: None

  **References**:
  - `src/main/java/.../ratelimit/RateLimiterCacheWrapper.java`
  - `RateLimiterCacheWrapperTest.java`

  **Acceptance Criteria**:
  - [ ] `RateLimiterCacheWrapperTest` 通过
  - [ ] 新增并发测试：QPS 控制误差 < 5%

  **QA Scenarios**:
  ```
  Scenario: 限流器并发正确性
    Tool: Bash (mvn test)
    Steps:
      1. mvn test -Dtest=RateLimiterCacheWrapperTest
    Expected Result: 全部通过
    Evidence: .sisyphus/evidence/task-11-ratelimiter.log
  ```

  **Commit**: YES
  - Message: `fix(ratelimit): fix token bucket race condition`
  - Files: `RateLimiterCacheWrapper.java`, `RateLimiterCacheWrapperTest.java`

- [x] 12. SpelConditionEvaluator 异常处理修复

  **What to do**:
  - 修复 `evaluate()` 中 `catch (Exception e)` 过于宽泛的问题
  - 当前问题：任何异常都返回 `true`，可能掩盖配置错误
  - 方案：区分异常类型，配置错误（如 SpEL 语法错误）应抛出异常，运行时异常（如 null 值）可返回默认值
  - 添加配置选项 `failOnSpelError`（默认 true）

  **Must NOT do**:
  - 不改变 SpEL 表达式的求值逻辑

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3
  - **Blocks**: F1-F4
  - **Blocked By**: None

  **References**:
  - `src/main/java/.../core/evaluator/SpelConditionEvaluator.java`
  - `SpelConditionEvaluatorTest.java`

  **Acceptance Criteria**:
  - [ ] `SpelConditionEvaluatorTest` 通过
  - [ ] 新增测试：SpEL 语法错误时抛出异常
  - [ ] 新增测试：运行时异常时返回默认值（当配置为 false 时）

  **QA Scenarios**:
  ```
  Scenario: SpEL 异常处理
    Tool: Bash (mvn test)
    Steps:
      1. mvn test -Dtest=SpelConditionEvaluatorTest
    Expected Result: 全部通过
    Evidence: .sisyphus/evidence/task-12-spel-exception.log
  ```

  **Commit**: YES
  - Message: `fix(spel): throw exception on SpEL config errors instead of silently returning true`
  - Files: `SpelConditionEvaluator.java`, `SpelConditionEvaluatorTest.java`

- [x] 13. ThreadPoolPreRefreshExecutor 关闭顺序修复

  **What to do**:
  - 修复 `shutdown()` 中 `executorService` 先于 `cleanupScheduler` 关闭的问题
  - 当前问题：executorService 中可能有任务需要 cleanupScheduler 清理
  - 方案：先关闭 cleanupScheduler，等待完成后再关闭 executorService

  **Must NOT do**:
  - 不改变线程池配置（core/max pool size、queue size）

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 4
  - **Blocks**: F1-F4
  - **Blocked By**: None

  **References**:
  - `src/main/java/.../writer/support/refresh/ThreadPoolPreRefreshExecutor.java:310-336`
  - `ThreadPoolPreRefreshExecutorTest.java`

  **Acceptance Criteria**:
  - [ ] `ThreadPoolPreRefreshExecutorTest` 通过
  - [ ] 新增测试：关闭时无资源泄漏

  **QA Scenarios**:
  ```
  Scenario: 资源关闭顺序
    Tool: Bash (mvn test)
    Steps:
      1. mvn test -Dtest=ThreadPoolPreRefreshExecutorTest
    Expected Result: 全部通过
    Evidence: .sisyphus/evidence/task-13-shutdown-order.log
  ```

  **Commit**: YES
  - Message: `fix(refresh): correct shutdown order in ThreadPoolPreRefreshExecutor`
  - Files: `ThreadPoolPreRefreshExecutor.java`, `ThreadPoolPreRefreshExecutorTest.java`

- [x] 14. 重试循环中断处理修复

  **What to do**:
  - 修复 `ThreadPoolPreRefreshExecutor.executeWithRetry()` 中 `Thread.sleep()` 被中断后直接退出的问题
  - 当前问题：中断后 `break` 退出重试循环，任务过早失败
  - 方案：中断后使用指数退避重试，或抛出异常让调用方处理

  **Must NOT do**:
  - 不改变重试次数（仍为 3 次）

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 4
  - **Blocks**: F1-F4
  - **Blocked By**: None

  **References**:
  - `src/main/java/.../writer/support/refresh/ThreadPoolPreRefreshExecutor.java:211-217`

  **Acceptance Criteria**:
  - [ ] 新增测试：中断后正确重试或抛出异常

  **QA Scenarios**:
  ```
  Scenario: 重试中断处理
    Tool: Bash (mvn test)
    Steps:
      1. mvn test -Dtest=ThreadPoolPreRefreshExecutorTest
    Expected Result: 全部通过
    Evidence: .sisyphus/evidence/task-14-retry-interrupt.log
  ```

  **Commit**: YES
  - Message: `fix(refresh): improve interrupt handling in retry loop`
  - Files: `ThreadPoolPreRefreshExecutor.java`

- [x] 15. 锁释放失败重试机制

  **What to do**:
  - 为 `DistributedLockManager` 添加锁释放失败时的重试机制
  - 当前问题：锁释放失败只记录 error 日志，无恢复机制
  - 方案：释放失败时重试最多 3 次，每次间隔 100ms

  **Must NOT do**:
  - 不改变锁获取逻辑

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 4
  - **Blocks**: F1-F4
  - **Blocked By**: None

  **References**:
  - `src/main/java/.../writer/support/lock/DistributedLockManager.java:105-107`

  **Acceptance Criteria**:
  - [ ] 新增测试：模拟锁释放失败，验证重试机制

  **QA Scenarios**:
  ```
  Scenario: 锁释放重试
    Tool: Bash (mvn test)
    Steps:
      1. mvn test -Dtest=DistributedLockManagerTest
    Expected Result: 全部通过
    Evidence: .sisyphus/evidence/task-15-lock-retry.log
  ```

  **Commit**: YES
  - Message: `fix(lock): add retry mechanism for lock release failures`
  - Files: `DistributedLockManager.java`, `DistributedLockManagerTest.java`

- [x] 16. 锁键前缀配置化

  **What to do**:
  - 将 `DistributedLockManager.LOCK_PREFIX` 从硬编码改为从配置读取
  - 当前：`private static final String LOCK_PREFIX = "cache:lock:"`
  - 方案：添加 `RedisProCacheProperties.syncLock.prefix` 配置项，默认值为 `"cache:lock:"`

  **Must NOT do**:
  - 不改变默认前缀（保持向后兼容）

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 4
  - **Blocks**: F1-F4
  - **Blocked By**: None

  **References**:
  - `src/main/java/.../writer/support/lock/DistributedLockManager.java:21`
  - `src/main/java/.../config/RedisProCacheProperties.java`

  **Acceptance Criteria**:
  - [ ] `DistributedLockManagerTest` 通过
  - [ ] 新增测试：配置自定义前缀后锁键正确

  **QA Scenarios**:
  ```
  Scenario: 锁前缀配置
    Tool: Bash (mvn test)
    Steps:
      1. mvn test -Dtest=DistributedLockManagerTest
    Expected Result: 全部通过
    Evidence: .sisyphus/evidence/task-16-lock-prefix.log
  ```

  **Commit**: YES
  - Message: `feat(lock): make lock key prefix configurable`
  - Files: `DistributedLockManager.java`, `RedisProCacheProperties.java`, `DistributedLockManagerTest.java`

- [x] 17. @Deprecated 注解添加

  **What to do**:
  - 为 `EvictionStrategyFactory` 添加 `@Deprecated` 注解（已有 Javadoc @deprecated 注释）

  **Must NOT do**:
  - 不改变类的功能

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 5
  - **Blocks**: F1-F4
  - **Blocked By**: None

  **References**:
  - `src/main/java/.../strategy/eviction/EvictionStrategyFactory.java`

  **Acceptance Criteria**:
  - [ ] 类上有 `@Deprecated` 注解

  **QA Scenarios**:
  ```
  Scenario: 注解存在
    Tool: Bash
    Steps:
      1. grep -q '@Deprecated' src/main/java/.../EvictionStrategyFactory.java
    Expected Result: grep 返回 0
    Evidence: .sisyphus/evidence/task-17-deprecated.log
  ```

  **Commit**: YES
  - Message: `chore: add @Deprecated annotation to EvictionStrategyFactory`
  - Files: `EvictionStrategyFactory.java`

- [x] 18. 未使用参数清理

  **What to do**:
  - 检查并清理 `RedisProCacheProperties` 中未使用的 `unit` 字段
  - 如果确实未使用，添加 `@SuppressWarnings("unused")` 或移除
  - 如果应该使用，在适当位置添加使用逻辑

  **Must NOT do**:
  - 不删除仍在使用的字段

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 5
  - **Blocks**: F1-F4
  - **Blocked By**: None

  **References**:
  - `src/main/java/.../config/RedisProCacheProperties.java`

  **Acceptance Criteria**:
  - [ ] 无编译警告（如果移除）或字段被正确使用

  **QA Scenarios**:
  ```
  Scenario: 无未使用警告
    Tool: Bash (mvn compile)
    Steps:
      1. mvn compile -q
    Expected Result: 编译成功，无 "unused" 警告
    Evidence: .sisyphus/evidence/task-18-unused.log
  ```

  **Commit**: YES
  - Message: `chore: clean up unused parameter in RedisProCacheProperties`
  - Files: `RedisProCacheProperties.java`

- [x] 19. 集成测试添加（Testcontainers）

  **What to do**:
  - 使用 Testcontainers 添加 Redis 集成测试
  - 测试场景：
    - 缓存写入和读取
    - 分布式锁在真实 Redis 中的行为
    - 布隆过滤器在真实 Redis 中的行为
  - 添加 `org.testcontainers:junit-jupiter` 依赖

  **Must NOT do**:
  - 不替换现有的单元测试
  - 不影响无 Docker 环境的构建（使用 `@EnabledIf` 或 profile 控制）

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 5
  - **Blocks**: F1-F4
  - **Blocked By**: None

  **References**:
  - `src/test/resources/application-test.yml` - 测试配置
  - `pom.xml` - 依赖管理

  **Acceptance Criteria**:
  - [ ] 新增至少 3 个集成测试类
  - [ ] `mvn test -P integration-test` 通过（或有 Docker 时通过）
  - [ ] 无 Docker 时 `mvn test` 不受影响

  **QA Scenarios**:
  ```
  Scenario: 集成测试通过
    Tool: Bash (mvn test)
    Steps:
      1. mvn test -Dtest=*IntegrationTest
    Expected Result: 全部通过（如果 Docker 可用）
    Evidence: .sisyphus/evidence/task-19-integration.log
  ```

  **Commit**: YES
  - Message: `test: add Redis integration tests with Testcontainers`
  - Files: `pom.xml`, `src/test/java/.../*IntegrationTest.java`

- [x] 20. TwoListLRU 性能优化（可选）

  **What to do**:
  - 如果 Wave 2 的修复导致性能下降，考虑优化
  - 方案：使用 `StampedLock` 替代 `ReentrantReadWriteLock`，或引入分段锁
  - 添加 JMH 基准测试验证性能

  **Must NOT do**:
  - 不引入新的并发缺陷

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 5
  - **Blocks**: F1-F4
  - **Blocked By**: T6（必须在 T6 完成后评估是否需要）

  **References**:
  - `src/main/java/.../strategy/eviction/support/TwoListLRU.java`

  **Acceptance Criteria**:
  - [ ] JMH 基准测试显示性能不低于修复前

  **QA Scenarios**:
  ```
  Scenario: 性能基准
    Tool: Bash (mvn jmh:run)
    Steps:
      1. 运行 JMH 基准测试
    Expected Result: throughput >= 修复前 90%
    Evidence: .sisyphus/evidence/task-20-performance.log
  ```

  **Commit**: YES（如需要）
  - Message: `perf(lru): optimize TwoListLRU concurrency with StampedLock`
  - Files: `TwoListLRU.java`

---

## Final Verification Wave

> 4 review agents run in PARALLEL. ALL must APPROVE. Present consolidated results to user and get explicit "okay" before completing.

- [x] F1. **Plan Compliance Audit** — `oracle`
  Read the plan end-to-end. For each "Must Have": verify implementation exists (read file, run test, check CI). For each "Must NOT Have": search codebase for forbidden patterns. Check evidence files exist in `.sisyphus/evidence/`.
  Output: `Must Have [N/N] | Must NOT Have [N/N] | Tasks [N/N] | VERDICT: APPROVE/REJECT`

- [x] F2. **Code Quality Review** — `unspecified-high`
  Run `mvn verify` (includes Checkstyle + JaCoCo). Review all changed files for: `as any`/`@ts-ignore`, empty catches, `System.out.println`, commented-out code, unused imports. Check AI slop: excessive comments, over-abstraction.
  Output: `Build [PASS/FAIL] | Checkstyle [PASS/FAIL] | JaCoCo [N%] | Tests [N pass/N fail] | VERDICT`

- [x] F3. **Full QA Execution** — `unspecified-high`
  Start from clean state. Execute EVERY QA scenario from EVERY task. Test cross-task integration. Test edge cases: empty state, invalid input, rapid actions. Save evidence to `.sisyphus/evidence/final-qa/`.
  Output: `Scenarios [N/N pass] | Integration [N/N] | Edge Cases [N tested] | VERDICT`

- [x] F4. **Scope Fidelity Check** — `deep`
  For each task: read "What to do", read actual diff (git log). Verify 1:1 — everything in spec was built, nothing beyond spec was built. Check "Must NOT do" compliance.
  Output: `Tasks [N/N compliant] | Unaccounted [CLEAN/N files] | VERDICT`

---

## Commit Strategy

- **Wave 1 commits** (T1-T5): 独立提交，可合并为一个 PR
- **Wave 2 commits** (T6-T9): 独立提交，建议一个 PR（互相关联的并发修复）
- **Wave 3 commits** (T10-T12): 独立提交，可合并为一个 PR
- **Wave 4 commits** (T13-T16): 独立提交，可合并为一个 PR
- **Wave 5 commits** (T17-T20): 独立提交，可合并为一个 PR
- **Final commits** (F1-F4): 仅验证，不提交代码变更

**提交格式**: `type(scope): description` (遵循 Conventional Commits)

---

## Success Criteria

### Verification Commands
```bash
# 完整构建验证
mvn clean verify

# 快速测试验证
mvn test

# 检查代码风格
mvn checkstyle:check

# 检查覆盖率
mvn jacoco:check
```

### Final Checklist
- [ ] All "Must Have" present (20/20 tasks completed)
- [ ] All "Must NOT Have" absent (no API changes, no new deps)
- [ ] CI/CD passes (GitHub Actions green)
- [ ] `mvn verify` passes locally
- [ ] JaCoCo coverage >= 60%
- [ ] Checkstyle passes
- [ ] All tests pass (including new ones)
- [ ] No regression in existing tests
- [ ] Evidence files present for all tasks

