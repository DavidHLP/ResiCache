# ResiCache 成熟度计划恢复检查点

## 本轮目标与权限

本轮目标是复核 R1–R6、主动发现关联问题、形成可执行任务依赖，并持久化恢复状态。本轮仅规划：没有修改业务源码、测试源码、POM、配置、工作流或既有项目说明；没有运行 Maven、JUnit、Javadoc、Checkstyle、Redis、Docker 或 CI；没有执行 Git 写入、提交、推送、发布或远程写入。

## 计划文件和状态源

- 计划正文：docs/plans/resicache-maturity-plan.md
- 任务状态唯一来源：.agent/tasks/resicache-maturity.yaml
- 本检查点：docs/plans/resicache-maturity-checkpoint.md
- 采用的既有计划约定白名单：docs/plans/*.md 用于可评审正文/检查点，.agent/tasks/*.yaml 用于本机任务状态。没有新建平行的 .agent/plans/ 体系。
- 注意：.agent/ 被根目录 .gitignore 忽略；任务 YAML 是工作区持久状态但不自动进入 Git。计划正文和检查点位于 docs/plans/，可在后续审阅中看到。

## 当前基线

- 仓库：/home/david/Projects/ResiCache
- 分支：main
- HEAD：98856ab2442393a4d25281c392cc59d5e39cad52
- origin/main：ebaca8137da41b2e73e6944b42326125a9f6b0de
- 采集时间：2026-09-05T12:12:01+08:00
- 采集前状态：59 tracked modified、189 tracked deleted、194 untracked；用户已有重构差异非常大，必须保留。
- 技术线：Java 21 / Spring Boot 4.0.0 / Spring Data Redis 4.0 / Redisson 3.50.0 / Caffeine 3.1.8 / Testcontainers BOM 1.20.6。
- 本地 release tag：git tag --list 未观察到 tag；CHANGELOG/ADR 的旧 Boot 3 / Java 17 0.0.2 Central 说明未在本轮独立确认成同线证明。
- Hindsight：相关项目页没有可用的架构或决策事实；源码、文档和当前 Git 状态是本检查点的事实来源。

## 已检查范围和关键证据

- 规则：AGENTS.md 是指向 CLAUDE.md 的兼容指针；已读取 CLAUDE.md 的当前技术线、目录和架构约定。
- 既有计划：已读取 docs/plans/resicache-framework-hardening-plan.md 与 .agent/tasks/resicache-framework-hardening.yaml；其 41 个 ID、39 done/2 deferred 是历史计划声明，不作为当前通过证据。
- R1：STABILITY.md:13-40,68-92 的 §2/1.0 冻结范围；CHANGELOG.md:8-19 的旧 artifact 声明。
- R2：RedisProCacheProperties.java:298-321 与 CacheHandlerChainFactory.java:129-178,250-274 的开关文档、单次链缓存和优先级。
- R3：RedisCacheAutoConfiguration.java:24-31 仍有 @ComponentScan；RedisProCacheConfiguration.java:31-68 有不完整的显式 Import；现有配置测试只检查后者。
- R4：CacheHandler.java:26-65、ChainObserver.java:52-138、CacheContext.java:40-160 的 public hook、token、可变状态和 engine-only 方法。
- R5：CONTRIBUTING.md:15-40、pom.xml:346-354、scripts/ci/check-test-names.sh:1-17；15 个 IntegrationTest 与多个非后缀容器测试的静态分类。
- R6：PublicSurfaceContractTest.java:102-150,182-232 只检查 public 顶层 class 文件并过滤 $。
- N1：CacheResult.java:128-138 failure factory 仍接受 nullable 元数据。
- N2：RedisProCache.java:225-237 checked loader wrapper 包含 raw key。
- N3/N5：容器测试非后缀与 src/test/resources/*properties 的 1.20.4 stale comments；现有 docs gate 不检查这些资源。
- 最终覆盖核对：纳入索引的源码/文档路径均为 metadata_match/no_recorded_issue；CHANGELOG、ADR、既有计划、.agent/、scripts 和 AutoConfiguration.imports 按设计排除，已直接读取并在计划限制中标明。

## 当前决策和假设

- 推荐 DEC-001：稳定调用者可见注解、配置键、wire format、文档化 SPI 行为和故障分类；不自动冻结内部算法/布局/普通日志。
- 推荐 DEC-002：防护开关启动时解析；总关闭优先；分项只能进一步关闭；修改后重启；不实现动态重建。
- DEC-003 待 RM-004：在“精确内部扫描”与“显式注册但不公开 runtime”之间选择最小且已证明的单一发布物装配。
- 推荐 DEC-004：先保留 SPI，写短协议和外部实现证明；不立即删除嵌套类型或重做 sealed hierarchy。
- 推荐 DEC-005：正向分类容器测试后提供无 Docker unit 入口；完整 Redis 验证仍由 clean verify -B/临时 CI 承担。
- 非阻塞暂缓：同线发布/签名/采用者反馈、native-image/GraalVM、正式性能基准；激活条件写在 TASKS.yaml。

## 最近一次计划自检

2026-09-05T12:12:01+08:00：计划正文、任务 YAML、检查点已按 ID/依赖/引用人工交叉检查；R1–R6、N1–N5、七项成熟度证明、停止条件均有记录；RM-001–RM-011 状态均为 planned，未来验证均为 execution_state: not_run；拟新增命令 ./mvnw -Punit test -B 与 bash scripts/ci/check-external-consumer.sh 已标为 not_yet_exists；依赖图按拓扑顺序无环。机器校验结果：YAML 解析成功，11 个任务 ID 唯一，依赖无环，26 个验证项全部 not_run，4 个未来命令明确 not_yet_exists，计划/检查点引用完整；计划文档无空白尾随字符。落盘后 Git 状态为 59 modified、189 deleted、196 untracked，其中新增可见计划文档恰为 2 个，其他用户状态项未变化；任务 YAML 被 .gitignore 忽略。没有执行 Maven 或其他运行验证。

## 下一次恢复顺序

1. 读取本检查点。
2. 读取 docs/plans/resicache-maturity-plan.md，再读取 .agent/tasks/resicache-maturity.yaml；任务状态以 YAML 为准。
3. 重新执行只读 Git 基线检查：git status --short --branch、git rev-parse HEAD、git rev-parse origin/main；如果 HEAD、分支或相关文件变化，暂停复用旧证据。
4. 若基线未变化，先执行 RM-001 的发布/采用者事实核查；若基线变化，重跑受影响的 R1–R6 证据并保留任务 ID。
5. 按 DAG 继续 RM-002/RM-003/RM-004/RM-009；在 RM-004 的装配决策落定前不要实施 RM-005。
6. 仍然遵守“恢复规划不等于开始实施”：没有新的明确实施指令，后续会话不能改实现、运行验证或发布。

## 基线变化触发器

下列变化会使对应证据失效，需要重新核查而不是沿用旧状态：

- HEAD、分支、origin/main、release tag 或 Maven Central 坐标变化：重核 RM-001/RM-002。
- RedisCacheAutoConfiguration.java、RedisProCacheConfiguration.java、imports、配置测试变化：重核 RM-004/RM-005。
- RedisProCacheProperties.java、CacheHandlerChainFactory.java 或 protection 文档变化：重核 RM-003。
- CacheResult.java、CacheContext.java、CacheHandler.java、ChainObserver.java 或 allowlist 变化：重核 RM-006/RM-007/RM-010。
- POM、Surefire、测试命名脚本、测试基类或 test resources 变化：重核 RM-009/RM-011。
- 任意用户已有未提交差异被整理、提交或回滚：重新记录 dirty 基线，不能用本检查点推断归因。

## 实施记录(2026-09-05,实施轮次)

RM-001 至 RM-011 全部 done。状态唯一来源仍为 `.agent/tasks/resicache-maturity.yaml`(每任务 `execution_summary`);本节为恢复摘要。

**RM-001**:Central 全部 7 个版本(0.0.1–0.0.5、0.0.7、3.2.4)经 POM 直接核实均为旧 Boot 3.2.4/Java 17/Redisson 3.17.6 线;同线(Boot 4/Java 21)发布物确认不存在;受限公开检索(2026-09-05)未发现任何外部消费者(1 个第一方文档示例)。结论:RM-002 可调整 1.0 稳定性文字,无需迁移承诺;RELEASE-001 保持暂缓。

**RM-002**:STABILITY §4 SPI 协议 + 嵌套类型分类表 + 1.0 graduation 规则重写(仅 caller-observable 契约在 1.0 锁定;§2 保持 minor 可演化);CHANGELOG/COMPATIBILITY/README(中英)/ADR 0001 同步已核实发布来源;README 依赖坐标标注为规划坐标(0.0.2 是旧线,Boot 4 线未发布)。

**RM-003**:ProtectionProperties Javadoc 改为"启动时静态解析"语义(总开关优先、分项 null 继承/false 再关/true 不能重启用、重启生效);CacheHandlerChainFactoryTest 新增 6 个组合测试(含缓存链不变性);README/zh-CN/COMPATIBILITY 同语义。

**RM-004/005**:DEC-003 选 Option A——保留仅限内部 `cache` 包的扫描;显式 Import 因需要 publicize 包私有配置类被拒。行为探针发现并修复真实装配缺陷:用户自定义 CacheManager 时 advisor/interceptor 现随 RedisProCacheManager 一起 back-off(原先 UnsatisfiedDependency 启动失败);README/CONTRIBUTING 的"显式装配/不使用扫描"错误表述已修正;入口扫描锁定测试 + 用户 seam back-off 探针 + Redisson 过滤的默认装配探针就位。

**RM-006**:CacheResult.failure 三参数 requireNonNull;3 个 null 输入回归测试;8 个调用方全部核查,无合法 null 场景。

**RM-007**:STABILITY §4 协议(handler 非空 HandlerResult——ChainEngine 显式 IllegalStateException 指名违规 handler、FlowControl、post-process 隔离、observer 钩子顺序/scope token、context 读写规则、byte[] 防御复制);嵌套 public 清单(39 项)进入 public-surface-nested.txt + PublicSurfaceContractTest 门禁,全部分类(user/extension/operator/implementation);未删除任何已承诺 SPI。

**RM-008**:translateFailure 重建 Spring VRE(key 位以 cacheName 替换 raw key,type/cause 保留;Spring 7 不存 loader,loader 参数置 null 因 loader toString 不可控)+ checked 包装只带 cacheName;RedisProCacheLoaderFailurePrivacyTest 4 个无容器测试(含基于内存 miss RedisCacheWriter fake 的真实 get(key,loader) 路径级测试);COMPATIBILITY 更新。

**RM-009**:22 个可执行容器测试正向分类(10 个改名 *IntegrationTest),2 抽象基类 + 6 helper 显式 allowlist;正向命名门禁(验证过失败演示);pom `unit` profile(exclude `**/*IntegrationTest*.java`,嵌套类文件安全);`./mvnw -Punit test -B` 744/0/0/0 且零 Testcontainers 初始化(DOCKER_HOST=unix:///nonexistent.sock);CONTRIBUTING/CI workflow/资源注释/文档门禁 1.20.4 全部落位。

**RM-010**:scripts/ci/check-external-consumer.sh:打包 JAR + 声明依赖 classpath,临时目录编译外部消费者(@RedisCacheable + CacheHandler + ChainObserver 扩展),运行输出 EXTERNAL_CONSUMER_OK;compile-only + 纯值路径,不声明 Redis 行为验证。

**RM-011 分层验证**(全部独立记录,互不替代):

| 层 | 命令 | 结果 |
|---|---|---|
| unit(无 Docker) | `./mvnw -Punit test -B`(DOCKER_HOST=unix:///nonexistent.sock) | 744/0/0/0,exit 0,零容器初始化 |
| targeted | `-Dtest=CacheHandlerChainFactoryTest,RedisProCacheConfigurationContractTest,CacheResultTest,ChainEngineTest,ChainObserverTest,CacheErrorHandlerTest,RedisProCacheLoaderFailurePrivacyTest test` | 100/0/0/0,exit 0 |
| full Redis | `./mvnw clean verify -B` | 934 tests/0 fail/0 err/0 skip,JaCoCo check 通过,exit 0,真实 redis:7 容器 |
| checkstyle | `./mvnw checkstyle:check -B` | exit 0,0 violations |
| javadoc | `./mvnw javadoc:javadoc -B` | exit 0 |
| 测试分类 | `bash scripts/ci/check-test-names.sh` | exit 0(失败演示已验证) |
| 文档一致性 | `bash scripts/ci/check-docs-contracts.sh` | exit 0 |
| 外部 JAR | `bash scripts/ci/check-external-consumer.sh` | exit 0,EXTERNAL_CONSUMER_OK |
| 工作区 | `git diff --check` / `git status` | clean;61 M / 192 D / 198 untracked,无 staging/commit/push |

**环境**:Docker 29.7.2 可用;JDK:temurin-21.0.12(shell 默认 java 为 17,所有 Maven 以 JAVA_HOME=temurin-21 执行)。

**保持暂缓**:RELEASE-001(同线发布/签名/采用者)、AOT-001(native-image)、历史 JAR 二进制兼容证明、旧线采用者接触。

**已知证据限制**:外部消费者为 compile+纯值路径(非 Redis 行为验证);unit 层不证明真实 Redis;CI workflow 修改未在远程 CI 执行过;RM-009 的改名会改变 surefire 报告类名。
