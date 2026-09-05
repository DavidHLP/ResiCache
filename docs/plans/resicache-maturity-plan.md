# ResiCache 成熟度收敛任务方案

> `plan_id`: `resicache-maturity`
> `plan_state`: `reviewed_with_blockers`
> `captured_at`: 2026-09-05T12:12:01+08:00
> `task_state_source`: [`.agent/tasks/resicache-maturity.yaml`](../../.agent/tasks/resicache-maturity.yaml)
> `checkpoint`: [`resicache-maturity-checkpoint.md`](resicache-maturity-checkpoint.md)

## 1. 结论、目标和权限边界

当前仓库已经有可复用的缓存入口、责任链、故障语义和单一 Java 21 / Spring Boot 4 构建线。下一阶段不应继续增加缓存机制，而应收敛 Interface、消除文档与实现漂移，并让个人维护者能在没有常驻 Docker/Redis 的机器上完成低成本验证。

本方案只规划，不实施。后续实施者必须重新确认本计划的基线；本计划不授权修改业务源码、测试源码、POM、工作流、配置，不授权运行 Maven/测试/Redis/Docker，不授权提交、推送、发布或远程写入。

目标：

1. 复核 R1–R6，并把确认问题、证据缺口和暂缓事项闭合到任务。
2. 保留普通注解和 Spring Cache 调用路径的 Depth，把内部编排复杂度留在内部 Module。
3. 使稳定契约只承诺调用者真正依赖的行为、配置键和 wire format，保留内部 Implementation 的演进空间。
4. 让配置、装配、扩展和验证结果可由一个新 Agent 会话恢复。
5. 以一个小版本的可重复验证和真实反馈作为停止点，不把项目扩张成多运行时、多发布 Module 或企业治理平台。

非目标：多 Spring Boot 支持线、Reactive、二级缓存、熔断限流、通用插件平台、常驻 Redis 集群、多套部署环境、native-image/GraalVM 认证、发布签名和采用者治理。

## 2. 事实基线

### 2.1 仓库和工作区

- 实际仓库：`/home/david/Projects/ResiCache`。
- 分支：`main`。
- HEAD：`98856ab2442393a4d25281c392cc59d5e39cad52`（`docs: expose architecture decision record`）。
- `origin/main`：`ebaca8137da41b2e73e6944b42326125a9f6b0de`；当前分支 ahead 3。
- 采集时间：`2026-09-05T12:12:01+08:00`（Asia/Shanghai）。
- 采集前工作区：59 个 tracked modified、189 个 tracked deleted、194 个 untracked；tracked Java diff 涉及 238 个文件，约 886 行新增、30644 行删除。它们属于用户已有工作，不得覆盖、回滚或解释为已发布结果。
- 当前源文件静态计数：141 个 `src/main/java` Java 文件、102 个 `src/test/java` Java 文件；这不是编译或运行证明。
- 本地 `git tag --list` 没有返回 release tag。`CHANGELOG.md:15-19` 和 `docs/adr/0001-interface-contract-closure.md:135-155` 声称 Maven Central 有早期 Boot 3 / Java 17 的 `0.0.2`，但本轮没有把该声明独立验证成同线兼容证据；同线发布物、采用者和外部实现未知。

### 2.2 技术和交付入口

- POM 当前声明 Java 21、Spring Boot 4.0.0、Spring Data Redis 4.0、Redisson 3.50.0、Caffeine 3.1.8，Testcontainers BOM 1.20.6；兼容文档只保留这一条构建线。
- `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 注册 `RedisCacheAutoConfiguration`、`MetricsAutoConfiguration`、`CachingEnablementValidation`。
- `RedisCacheAutoConfiguration.java:24-31` 当前仍使用对 `io...cache` 的 `@ComponentScan`；`RedisProCacheConfiguration.java:31-68` 另有显式 `@Import`，但没有覆盖所有当前由扫描发现的配置类（例如 `RedisConnectionConfiguration`、`RedisCacheRegistryConfiguration`、`RedisProxyCachingConfiguration`、`RedissonConfiguration`、`JacksonConfig`）。
- POM 只有 Surefire 配置（`pom.xml:346-354`），没有已存在的 unit/integration profile；完整 `clean verify -B` 依赖 Testcontainers/Docker。
- `scripts/ci/check-test-names.sh` 当前只拒绝 `*IT.java`，不保证带 Docker 的测试都使用 `*IntegrationTest.java`。

### 2.3 已存在计划的处理方式

仓库已有 [`docs/plans/resicache-framework-hardening-plan.md`](resicache-framework-hardening-plan.md) 和 [`.agent/tasks/resicache-framework-hardening.yaml`](../../.agent/tasks/resicache-framework-hardening.yaml)。任务清单有 41 个稳定 ID，历史 YAML 声称 39 个 `done`、2 个 `deferred`；本轮不覆盖它们，也不把这些状态直接当作当前验证。原因是当前工作区仍有大量重构差异，且本轮权限禁止重新运行其 Maven/Redis 验证。新方案使用相同的 `docs/plans` + `.agent/tasks` 约定，任务 ID 采用 `RM-` 前缀避免冲突。

`.agent/` 被根目录白名单式 `.gitignore` 忽略；它仍是本机恢复用的持久状态。计划正文和检查点放在已跟踪的 `docs/plans/`，以便随代码评审；不能因为 `.agent/tasks` 未被 Git 跟踪就声称任务状态已进入提交。

### 2.4 证据限制

- codebase-memory generation：`2026-09-04T17:29:55Z`，`parse_partial` 与 `skipped` 均为 0；`docs/`、`.agent/`、`scripts/`、部分测试目录和构建产物按设计未索引。未索引范围已直接读取，图结果仍只作定位依据。
- 本轮直接读取了当前源码、测试、POM、文档、工作流、脚本、资源和既有计划；没有运行 Maven、JUnit、Javadoc、Checkstyle、Redis、Docker 或 CI。
- Hindsight 项目页没有可用的架构/决策事实；当前结论以本轮工作区为准。
- 发布物和远程采用情况没有可靠的本地基线；后续任务必须把“无法取得”与“确认不存在”区分开。

## 3. R1–R6 核查矩阵

| ID | 问题性质 | 当前结论 | 当前工作区证据 | 适用范围和限制 | 任务 |
|---|---|---|---|---|---|
| R1 | 契约过宽、兼容前提不足 | `Confirmed`（1.0 冻结 §2 的设计过宽）；同线历史兼容为 `Needs Verification` | `STABILITY.md:13-40` 把内部实现、默认值、指标列为可变，但 `:68-92` 又规定 1.0 后锁定 §2；`CHANGELOG.md:8-19` 说明已有早期 Central 物 | 当前工作区文档；未证明早期 artifact 有现行用户 | RM-001, RM-002 |
| R2 | 配置契约矛盾 | `Confirmed` | `RedisProCacheProperties.java:298-321` 同时写“仅启动时生效”和“运行时 kill-switch”；`CacheHandlerChainFactory.java:129-178,250-274` 首次构建缓存链，总关闭优先且不动态重建 | 无运行时刷新路径证据；组合行为尚未执行验证 | RM-003 |
| R3 | 装配设计风险与文档漂移 | `Confirmed`（风险/契约漂移）；未证实启动故障 | `RedisCacheAutoConfiguration.java:24-31` 仍扫描内部包；README/CONTRIBUTING 声称显式装配且不使用扫描；`RedisProCacheConfiguration` 的 Import 清单不完整；`RedisProCacheConfigurationContractTest.java:54-57` 只检查被导入配置类，不检查真正入口 | 当前扫描范围不是根包；不能把它描述成已证实的运行故障 | RM-004, RM-005 |
| R4 | 扩展 Interface 协议过宽 | `Confirmed`（协议学习成本）；是否收窄需 `Needs Verification` | `CacheHandler.java:26-65` 有处理与 post-process；`ChainObserver.java:52-138` 有多组生命周期 hook 和 `Object` token；`CacheContext.java:40-160` 公开可变决策、engine-only `markSkipRemaining()` 和数组读取 | 没有外部采用者证据；不能删除已承诺 SPI | RM-006, RM-007, RM-010 |
| R5 | 日常验证未分层、测试分类不完整 | `Confirmed` | `CONTRIBUTING.md:15-40` 要求 Docker；POM 只有 Surefire；`check-test-names.sh` 只拒绝 `*IT.java`；多类 `*Test` 继承 `AbstractRedisIntegrationTest` | 静态发现，不代表测试已经执行失败 | RM-009, RM-011 |
| R6 | 类型名单不足以证明接口兼容 | `Confirmed`（证明能力缺口） | `PublicSurfaceContractTest.java:102-150` 只扫描 `target/classes` 的 public 顶层类型并过滤 `$`；不检查嵌套类型、方法签名或历史 JAR | 不等同于已有兼容性破坏 | RM-007, RM-010 |

### 3.1 已处理但本轮只要求回归的事项

以下是当前工作区源码可观察到的既有收拢方向，不新建等价修复任务，也不标记为运行通过：

- Bloom CLEAN 路径的注释和 `BloomFilterHandler.java:63-121` 表示 CLEAN 不改变 Bloom；后续 RM-011 只做真实 Redis 回归。
- `RedisProCache.java:164-177` 和 `RedisProCacheWriter.java:124-133` 表示 loader 成功值不被写回失败覆盖；后续 RM-008/RM-011 检查异常与双故障路径。
- `CacheHandlerChainFactory.java:116-137,220-232` 已有 `List<ChainObserver>` 注入与去重；R4 剩余的是协议深度和外部可用性，不重复规划“让 observer 自动发现”。
- `RedisProCacheProperties.java:55-83,291-321` 已有嵌套 `@Valid`/`@NotNull` 与部分数值约束；R2 只处理开关语义，RM-011 再核验完整绑定行为。

## 4. 主动补查发现

| ID | 关联问题 | 性质和结论 | 当前证据 | 去向 |
|---|---|---|---|---|
| N1 | R4、R6 | `Confirmed`：`CacheResult` 的失败不变量没有在工厂执行 | `CacheResult.java:128-138` 的唯一 failure 工厂将 operation/kind/cause 标为 `@Nullable` 且直接构造；`CacheResultTest.java:132-145` 只反射检查工厂形态，没有 null 拒绝测试 | RM-006 |
| N2 | R5、R6 | `Confirmed`：checked loader 异常的公开包装可能泄露 raw key | `RedisProCache.java:225-237` 对非 RuntimeException 生成包含 `key` 的 `RuntimeException`；当前隐私测试主要覆盖 CacheErrorHandler/WARN/ERROR，不覆盖该分支 | RM-008 |
| N3 | R5 | `Confirmed`：集成测试正向分类门禁缺失 | 15 个 `*IntegrationTest.java`，另有 `CacheLatencySmokeBenchmarkTest`、`RedisProCacheTest`、`RedisProCacheWriterTest`、`ActualCacheHandlerTest`、`EarlyExpirationHandlerTest`、`DistributedLockManagerTest`、`RedisConnectionConfigurationTest` 等带真实容器基类但不是该后缀；脚本只拒绝 `*IT.java` | RM-009 |
| N4 | R6、R4 | `Confirmed`：嵌套 public 类型未进入契约清单 | `PublicSurfaceContractTest.java:148-150` 过滤包含 `$` 的类文件；`CacheContext.InputView`、`CachePolicyView.Source`、`CacheResult.Outcome/FailureKind`、properties 嵌套类型等因此没有被清单分类 | RM-007, RM-010 |
| N5 | R3、R5 | `Confirmed`：文档/资源版本门禁不覆盖测试资源注释 | `pom.xml:87-90` 使用 Testcontainers 1.20.6，而 `src/test/resources/testcontainers.properties:10-12`、`docker-java.properties:1-7` 仍写 1.20.4；现有 `check-docs-contracts.sh` 只检查 docs 数组 | RM-009 |

N1/N2 是可观察行为和安全契约问题，优先级高于 N5。N5 不改变运行时版本，但会误导排障和环境复现，随测试分类任务一次收口。

## 5. 七项成熟度证明

| 证明 | 状态 | 已有证据 | 缺口/后续门槛 |
|---|---|---|---|
| 正确接入 | `partial` | 自动配置 imports、README quick start、typed back-off 测试存在 | R3 入口仍扫描且文档不一致；RM-005、RM-010 需从打包 JAR 验证 |
| 可预测配置 | `partial` | 集中 `RedisProCacheProperties`、嵌套校验和配置 metadata | R2 开关语义矛盾；组合绑定与启动失败尚未运行；RM-003、RM-011 |
| 可维护扩展 | `partial` | `CacheHandler`/`ChainObserver`/Bloom/Lock 稳定 Seam，内部实现收拢 | hook/token/context 协议宽，嵌套类型未分类；RM-007、RM-010 |
| 升级安全 | `gap` | wire envelope、STABILITY、CHANGELOG 有文字契约 | 0.0.2 是旧 Boot 线；无本地同线 tag，未验证旧消费者/数据样本；RM-001、RM-002、RM-010 |
| 环境可复现 | `partial` | Maven wrapper、CI、Testcontainers、Docker API 辅助设施 | 日常 unit 入口不存在，容器测试分类不完整；RM-009、RM-011 |
| 故障语义可信 | `partial` | GET/写/loader 写回文档与多类测试存在 | 本轮未运行；N1/N2 仍破坏不变量或隐私边界；RM-006、RM-008、RM-011 |
| 发布与真实反馈可追踪 | `evidence_gap` | release workflow、CHANGELOG、单人维护说明 | 无本地 release tag；Central 同线 provenance、采用者、升级反馈未知；RM-001，发布任务暂缓 |

“缺失”表示本轮没有可验证证明，不表示外部证据一定不存在。发布和真实采用不能靠静态门禁制造，也不作为当前实现收敛的永久阻塞。

## 6. 关键设计决策

### DEC-001：稳定性承诺按调用者依赖收敛

推荐：继续稳定四个注解的属性/语义、`resi-cache.*` 配置键、wire envelope、已承诺的公开 SPI 行为和显式故障分类；允许 0.x 内部算法、内部包布局和普通日志措辞演进。1.0 不把全部 §2 内部项自动锁成 2.0 级别，指标只有在明确列出命名/标签契约并有迁移说明时才稳定。

替代：保留当前“1.0 锁定所有 §2”文字。放弃理由：它把内部 Implementation 的低风险整理变成主版本义务，不适合单人维护；如果 RM-001 发现已有同线消费者依赖某项 §2 行为，必须为该项单独建立迁移承诺，而不能只改文档。

### DEC-002：防护开关采用启动时静态解析

推荐：文档与测试统一为“创建责任链时解析；`protection.enabled=false` 优先关闭四项；分项 `false` 只能进一步关闭；分项 `true` 不能从总关闭中重新启用；修改后重启”。不实现动态重建。

替代：增加运行时配置监听和链重建。放弃理由：当前链有单例缓存，没有热更新需求证据；动态重建会引入并发、旧请求和指标注册问题。

### DEC-003：装配先选择最小可维护 Seam

推荐：先由 RM-004 用 ApplicationContextRunner 证明当前单一发布物的最小装配方式。若包扫描仅限内部 `cache` 且能由精确测试锁定，个人项目可保留该扫描，修正文档为“内部包扫描”；若需要消除隐式发现，则采用明确导入机制，同时保持 runtime 类型 package-private。

替代：直接把 `RedisProCacheConfiguration` 和所有 runtime 配置类改成 public 并在入口 `@Import`。放弃理由：会扩大 public surface；当前 Import 列表还不完整，未经证明的机械替换可能丢 Bean。

### DEC-004：先证明 SPI，再决定是否收窄

推荐：保留已承诺 SPI，先写短协议和外部实现样例；明确 handler 返回非空 `HandlerResult`、post-process/observer 异常隔离、顺序和线程安全。以后可以把引擎专用可变状态留在内部，只向扩展者提供必要视图；不要直接删除已承诺的 SPI。

替代：立即删除 `markSkipRemaining`、嵌套 Source 或重做 sealed hierarchy。放弃理由：没有同线消费者证据，直接删除会制造兼容风险；新层次也会增加个人维护成本。

### DEC-005：验证分成无 Docker 日常路径和真实 Redis 完整路径

推荐：整理所有真实容器测试后新增明确的 unit 入口；完整 Redis/Cluster/Fault/serialization 证明继续在临时 CI 或有 Docker 的机器执行。快速入口必须明确“未验证真实 Redis”，并且不产生 skipped-as-green 的假象。

替代：引入 Failsafe、独立测试 Maven Module 或常驻 Redis。放弃理由：当前项目规模不需要更多生命周期和基础设施；先用现有 Surefire、命名和最小 profile 验证成本。

## 7. 分阶段路线和依赖

### Phase 0：事实与兼容前提

`RM-001` 复核当前 Git/发布/采用者事实。它不修改仓库实现，只为 R1 的兼容判断提供分支证据。

### Phase 1：契约收敛

`RM-002`、`RM-003`、`RM-004` 可并行开始，前提是 RM-001 的事实输出已保存。RM-002 处理稳定性文字和迁移分界；RM-003 处理静态开关；RM-004 证明装配选项。

### Phase 2：实现边界和日常成本

`RM-005` 依赖 RM-004，落地选定装配方案和对应文档/测试。`RM-006` 可与 RM-005 并行，但要遵守 RM-002 的公开契约决定。`RM-009` 可与 RM-005/RM-006 并行，整理测试分类和 unit 入口。

### Phase 3：扩展和安全证明

`RM-007` 依赖 RM-002、RM-004、RM-006，收敛 SPI 协议与 nested public inventory。`RM-008` 依赖 RM-002，修复并证明 checked loader 异常隐私。

### Phase 4：外部消费者和完整验证

`RM-010` 依赖 RM-005、RM-006、RM-007、RM-009，从打包 JAR 运行最小注解接入和受支持扩展。`RM-011` 依赖 RM-003、RM-005、RM-008、RM-009、RM-010，执行 unit/完整 Redis/静态门禁的最终证明。

关键路径：

```text
RM-001
 ├─ RM-002 ─┬─ RM-006 ─┐
 ├─ RM-003  ├─ RM-007 ─┼─ RM-010 ─┐
 ├─ RM-004 ─┴─ RM-005 ─┘          ├─ RM-011
 └─ RM-009 ───────────────────────┘
                    RM-008 ────────┘
```

所有验证任务在 TASKS.yaml 中仍是 `not_run`；图中的完成关系是未来依赖，不是当前通过状态。

## 8. 验证门槛

### 8.1 现有可运行命令（本轮不执行）

- `bash scripts/ci/check-docs-contracts.sh`：文档关键词和关键类型静态门禁；不能证明配置语义、嵌套 API 或运行时装配。
- `bash scripts/ci/check-test-names.sh`：当前只拒绝 `*IT.java`；RM-009 完成前不能把它当成完整容器分类证明。
- `./mvnw checkstyle:check -B`：Checkstyle；不证明运行行为。
- `./mvnw javadoc:javadoc -B`：Javadoc；不证明 API 二进制兼容。
- `./mvnw clean verify -B`：当前 CI 的完整入口，包含 Surefire、Testcontainers 和 JaCoCo；需要 JDK 21、依赖下载和 Docker，仍不能替代外部消费者/历史 JAR 证明。

### 8.2 拟新增命令（必须先由 RM-009/RM-010 创建）

- `./mvnw -Punit test -B`：只跑不依赖 Docker 的 unit/contract tests；profile 当前不存在，不能提前标为可运行。
- `bash scripts/ci/check-external-consumer.sh`：先打包当前 JAR，再从临时目录用 JAR 编译运行最小消费者；脚本当前不存在，不能用同工程 source/test classpath 冒充。

### 8.3 证据分级

每个任务必须分别记录源码推断、编译证明、JVM 运行证明、真实 Redis 证明、打包 JAR 外部消费者证明和历史版本兼容证明。一个层级通过不能替代另一个层级；任何未执行项都写 `execution_state: not_run`。

## 9. 任务概览

任务状态唯一来源是 [`.agent/tasks/resicache-maturity.yaml`](../../.agent/tasks/resicache-maturity.yaml)。本表只说明范围和依赖，不维护第二份状态。

| ID | 类型 / 优先级 | 目标 | 依赖 | 当前状态 |
|---|---|---|---|---|
| RM-001 | investigation / P1 | 复核 HEAD、发布物、tag、采用者和旧计划状态 | 无 | planned |
| RM-002 | implementation+docs / P1 | 收敛 STABILITY/CHANGELOG 的稳定性分界 | RM-001 | planned |
| RM-003 | implementation+contract-test / P1 | 统一 protection 开关启动时语义 | RM-001 | planned |
| RM-004 | investigation+decision / P1 | 证明内部扫描与显式导入的装配选项 | RM-001 | planned |
| RM-005 | implementation+contract-test / P1 | 落地选定装配 Seam 并同步文档 | RM-004 | planned |
| RM-006 | implementation+contract-test / P1 | 修复 CacheResult failure 不变量 | RM-002 | planned |
| RM-007 | implementation+docs+contract-test / P1 | 定义 SPI 协议并盘点嵌套 public 类型 | RM-002, RM-004, RM-006 | planned |
| RM-008 | implementation+security-test / P1 | 消除 checked loader 异常 raw key 泄露 | RM-002 | planned |
| RM-009 | implementation+workflow-test / P1 | 正向分类容器测试并新增无 Docker unit 入口 | RM-001 | planned |
| RM-010 | external-consumer-validation / P1 | 从打包 JAR 验证接入和一个受支持扩展 | RM-005, RM-006, RM-007, RM-009 | planned |
| RM-011 | verification+gate / P1 | 完整 unit/Redis/静态/文档/Javadoc 证明和回归 | RM-003, RM-005, RM-008, RM-009, RM-010 | planned |

每项的文件、步骤、验收、命令、风险、回退和 required evidence 见 TASKS.yaml；未来实施者不可只凭本表工作。

## 10. 风险、暂缓和停止条件

风险与回退：

- R1 修改可能影响已发布的旧 0.0.2 使用者；RM-001 未确认前不得把内部化当作无兼容义务。回退是恢复文字/迁移说明，不是静默破坏旧消费者。
- R3 装配替换可能漏注册 `RedisConnectionConfiguration` 等当前由扫描发现的配置；RM-004 必须先得到 Bean inventory 和 ApplicationContextRunner 证据。任何失败都回到“内部扫描 + 精确门禁”方案。
- R4/R6 直接删 SPI 或 nested type 会产生源码/二进制风险；先保留并用外部消费者证明，必要时新增迁移条目。
- R5 快速入口若仍包含容器类，会出现 Docker 初始化或 skipped-as-green；RM-009 的正向扫描门禁是前置条件。
- N1 修复 failure factory 可能改变扩展 Adapter 的编译行为；回退只能保留清晰的兼容适配，不恢复可构造非法状态。
- N2 隐私修复不能吞掉 loader failure；必须保留 cause identity/`ValueRetrievalException` 语义，只去掉 raw key 文本。

暂缓：

- `RELEASE-001`：同线发布、Central 签名、SBOM、dependency-check、历史消费者和 adopter 记录。当前无明确发布请求和同线 tag；RM-001 只收集事实，不能把发布流程变成当前技术收敛的阻塞。
- `AOT-001`：native-image/GraalVM；现有 ADR 已把它列为非阻塞，除非出现真实 native 使用者和工具链。
- 性能正式 JMH/Toxiproxy：保留现有 smoke/bench 入口，只有真实性能回归证据触发时再立项。

规划完成条件：R1–R6 有结论；N1–N5 有去向；七项成熟度证明有状态；任务 ID/依赖/引用无误；计划和检查点已落盘并自检。

未来实施完成条件：RM-011 的命令和外部消费者证明均有实际输出；真实 Redis 和无 Docker unit 结果分开记录；未完成/暂缓项仍显式保留；没有把当前工作区重构直接宣布为发布或兼容通过。

达到未来实施完成条件后停止架构扩张，发布一个小版本并等待真实反馈；没有新的生产问题或采用证据，不继续增加 Module、Seam 或保护机制。

## 11. 本轮自审记录

- 覆盖完整性：R1–R6 全部出现在矩阵并映射任务；N1–N5 全部有去向；七项证明全部有状态。
- 任务可执行性：TASKS.yaml 的每项任务均有真实路径、符号/行号、范围、非目标、步骤、依赖、验收和验证方法；不存在把“测试通过”当唯一验收的任务。
- 证据诚实性：未来验证全部 `not_run`；已有计划的 `done` 只作为历史声明；没有把静态扫描等同运行或发布证明。
- 契约与成本：推荐保留单一构建线、单一发布物、内部 Module 和临时 Docker；没有规划多 Boot、Reactive、常驻基础设施或通用插件平台。
- 写入边界：本轮只应新增本文件、`.agent/tasks/resicache-maturity.yaml` 和 `docs/plans/resicache-maturity-checkpoint.md`；不能用 Git 状态中的其他差异归因于本轮。
