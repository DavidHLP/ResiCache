# ResiCache 设计与成熟度评估

评估日期：2026-09-05。使用 codebase-design 的 Module、Interface、Depth、Seam、Adapter、Leverage、Locality 视角。

**结论：当前设计已有清晰的收拢方向，下一步最有价值的工作是缩小并验证对外承诺、降低维护成本。暂不需要继续扩大框架能力。** 我将“无开发环境”理解为没有专用、常驻的开发部署环境；不把维护 Redis 集群、测试服务器或多套部署环境作为个人维护者的日常义务。

这是基于当前含大量未提交改动的工作区所做的定向设计评审，不代表已发布版本的状态，也不是完整缺陷审计。以下把源码可确认的问题、设计取舍和尚未取得的成熟度证据分开。

## 已有设计值得保留

- **普通调用者的 Interface 有 Depth。** 注解和 Spring Cache 使用方式封装了缓存读写及防护行为；不应要求普通调用者自行编排整条链。
- **实现收拢改善了 Locality。** 当前大量实现已移入内部 `cache` 包，并有公开类型清单检查。不能再用旧目录结构得出“到处暴露内部实现”的结论。
- **已有明确失败语义。** README 区分读失败、写失败和 loader 成功后的写回失败；`RedisProCache.get(key, loader)` 明确处理写回失败后返回已加载值。这类承诺比继续添加机制更有价值。[源码](/home/david/Projects/ResiCache/src/main/java/io/github/davidhlp/spring/cache/redis/cache/RedisProCache.java:158)
- **已经有交付基础。** CI 有构建验证、覆盖率检查、文档检查和发布前置依赖；兼容性文档明确只维护一条 Java 21 / Boot 4 构建线。单一支持线适合个人项目，不因支持范围窄而扣分。[构建](/home/david/Projects/ResiCache/.github/workflows/_build.yml) · [兼容范围](/home/david/Projects/ResiCache/COMPATIBILITY.md)

## 仍然存在的具体问题

### 1. 稳定性承诺把内部实现也冻结了，应优先修正

**已确认。** `STABILITY.md` 第 83–85 行规定：1.0 后 §2 中的内部实现、默认值、指标名全部锁定，任何改变都升主版本。而 §2 明确包含内部算法和包布局。第 91–92 行又承诺日志消息稳定。

这会让不影响调用者的内部整理也成为破坏性发布，直接抬高个人维护成本。深 Module 的价值之一就是 Implementation 能独立演进；这里的承诺反而消除了这种自由。

**最小修正：** 保证公开 Interface 的行为兼容、配置键和 wire format 的迁移规则；内部算法、内部包布局和普通日志措辞可以演进。指标是否稳定应单独列出，不要与内部实现一起打包冻结。

1.0 的生产采用者、接班人要求可以作为项目愿景；以个人项目为前提，我建议技术发布标准优先采用“可安装、可复现验证、升级行为明确”。诚实写明单人维护和 best-effort 即可，无须为满足自己写下的毕业条件建立额外治理流程。

证据：[稳定性承诺](/home/david/Projects/ResiCache/STABILITY.md:68)。

### 2. 防护开关的 Interface 与 Implementation 存在矛盾

**已确认，属于契约问题；未做运行时复现。**

- `ProtectionProperties` 的总说明写“仅启动时生效”，分项说明却写“运行时 kill-switch”。
- 分项说明声称非 `null` 表示单独覆盖总开关。
- `createChain()` 缓存首次构建结果；`resolveProtectionDisabled()` 在总开关为 `false` 时直接禁用全部四项，不处理分项 `true`。

因此，使用者可能误以为可以运行时修改开关，或用分项 `true` 覆盖总关闭；实现不提供这些行为。问题在于正确使用所需知识不一致，增加了 Interface 学习成本。

**最小修正：** 保留现有较简单的实现，统一写明“启动时解析；总关闭优先；分项只能进一步关闭；修改后重启”。补一个参数化检查覆盖总开关与分项 `null/true/false` 的组合。没有实际热更新需求就不实现动态重建链。

证据：[配置说明](/home/david/Projects/ResiCache/src/main/java/io/github/davidhlp/spring/cache/redis/config/RedisProCacheProperties.java:298) · [单次构建](/home/david/Projects/ResiCache/src/main/java/io/github/davidhlp/spring/cache/redis/cache/CacheHandlerChainFactory.java:152) · [开关解析](/home/david/Projects/ResiCache/src/main/java/io/github/davidhlp/spring/cache/redis/cache/CacheHandlerChainFactory.java:250)。

### 3. 自动装配仍依赖包扫描，装配规则不够明确

**已确认的设计风险，不是已证实的启动故障。** 自动配置通过 `@ComponentScan` 扫描内部 `cache` 包，并用 `.*Test.*` 排除测试类。

把扫描范围缩小到内部包已有改善，但新加一个带 Spring 注解的类仍会隐式改变装配结果；生产入口还承担了按名称过滤测试类的责任。目录放置和命名因此成为隐藏的 Interface。

Spring Boot 官方明确建议自动配置不要通过组件扫描寻找附加类型，而应使用明确导入。[官方说明](https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html)

**最小修正方向：** 保留单一发布物及内部包，在同包内集中声明内部装配，通过明确入口导入。不要为解决扫描问题把内部类重新全部公开，也不要新建多个 Maven Module。验证重点是：总关闭不装配、可选依赖缺失时行为正确、自定义支持的 Adapter 能正常替换。

证据：[自动配置](/home/david/Projects/ResiCache/src/main/java/io/github/davidhlp/spring/cache/redis/config/RedisCacheAutoConfiguration.java:23)。

### 4. 扩展 Interface 仍然偏宽，内部化不等于契约已经足够小

**设计取舍。** `CacheHandler.handle(context)` 看上去只有一个方法，但真实 Interface 还包括后置回调、执行顺序、短路规则，以及 `CacheContext` 中 TTL、空值、预取决策和清理模式等可变状态。

例如 `markSkipRemaining()` 是公开方法，说明却要求仅由引擎调用。这是一个“类型允许调用，但协议禁止调用”的内部知识泄漏。公开扩展者仍需要了解引擎如何执行。

同时，稳定性文档把这些传递类型和数字顺序列为稳定承诺。普通注解使用路径的 Depth 已较好，扩展路径的 Depth 仍偏低。

**最小修正方向：** 先明确哪些扩展场景真正受支持，为扩展者写出一份短协议，并用外部包中的最小自定义 Handler 验证它。以后可以把引擎专用可变状态留在内部，只向扩展者提供必要视图；不要直接删除已承诺的 SPI 或再叠加一层通用插件框架。是否收窄现有 SPI，应先查发布物和真实采用情况。

证据：[Handler](/home/david/Projects/ResiCache/src/main/java/io/github/davidhlp/spring/cache/redis/chain/CacheHandler.java:26) · [上下文](/home/david/Projects/ResiCache/src/main/java/io/github/davidhlp/spring/cache/redis/chain/model/CacheContext.java:40) · [承诺范围](/home/david/Projects/ResiCache/STABILITY.md:24)。

### 5. 日常验证流程对“无专用开发环境”的维护方式不够友好

**已确认的工作流缺口。** CONTRIBUTING 要求本地完整 `clean verify`，环境要求包括 Docker；POM 当前将测试放在 Surefire 中，未提供文档化的单元/集成验证入口区分。CI 同样运行完整验证。

真实 Redis 验证有价值，问题在于普通维护者没有清晰、低成本的日常入口。个人项目需要把耗时或依赖环境的证明交给临时 CI，而非依赖常驻环境。

**最小修正：** 提供一个无 Docker 的单元测试命令，以及一个明确需要 Docker 的完整验证命令；后者继续作为发布门槛。可以复用现有 `*IntegrationTest` 命名做排除，不必为了分层立即引入 Failsafe、多套配置或新脚手架。单元入口的命名和输出必须表明“没有验证 Redis 行为”，不能把跳过集成测试当完整绿灯。

没有 Docker 与完全离线是两件事：首次 Maven 依赖下载仍需要网络或预先准备的缓存。

证据：[贡献流程](/home/david/Projects/ResiCache/CONTRIBUTING.md) · [Surefire 配置](/home/david/Projects/ResiCache/pom.xml:348) · [CI](/home/david/Projects/ResiCache/.github/workflows/_build.yml)。

### 6. 公开面检查验证了“类型名单”，尚不足以证明 Interface 稳定

**该测试的可确认能力限制；不声称全仓库没有其他契约测试。** `PublicSurfaceContractTest` 扫描公开顶层类型，显式排除包含 `$` 的嵌套类型。它不能单独发现已有类型的方法签名改变，或公开嵌套 Interface 的变化，例如 `CacheContext.InputView`。

此外，这个测试为了读取 `ACC_PUBLIC` 自行解析 class 常量池，维护了一段二进制格式逻辑。注释担心类初始化，但可以使用禁止初始化的类加载方式；也可以复用 Spring 已有的元数据读取能力。具体替换要保留对可选依赖缺失的处理，不能把读取失败静默当作非公开类型。

**最小修正方向：** 保留类型清单的用途；新增一个通过已打包 JAR 编译、运行的外部消费者样例，覆盖注解配置和一个受支持的扩展。它比继续扩大白名单扫描规则更接近使用者真正跨越的 Seam。出现真实历史版本兼容需求后，再考虑自动化二进制差异工具。

证据：[扫描逻辑](/home/david/Projects/ResiCache/src/test/java/io/github/davidhlp/spring/cache/redis/PublicSurfaceContractTest.java:139) · [class 解析](/home/david/Projects/ResiCache/src/test/java/io/github/davidhlp/spring/cache/redis/PublicSurfaceContractTest.java:182)。

## 与成熟项目的距离，应怎样衡量

这里比较的是框架使用者需要的可靠性，不是项目规模、功能数量或企业平台配置。

| 维度 | 当前证据 | 最值得补上的证明 |
|---|---|---|
| 正确接入 | 有注解、自动配置和兼容文档 | 从发布 JAR 出发的最小消费者在临时 CI 中可运行 |
| 可预测配置 | 有集中 properties 和校验 | 开关优先级、启动生效范围与实际行为完全一致 |
| 可维护扩展 | 实现已收拢，SPI 明确列出 | 扩展者仅凭公开说明就能实现 Adapter，不依赖内部类型或引擎知识 |
| 升级安全 | 有 wire format 和稳定性文档 | 对上一受支持版本的消费者和数据样本进行回归；内部重构自由仍保留 |
| 环境可复现 | 有 Maven wrapper、Testcontainers、CI | 日常单元验证无需 Docker，完整验证由临时 CI 承担 |
| 故障可信度 | 已声明读写及写回失败语义 | 核心契约用少量真实 Redis 故障用例证明，发布记录能对应结果 |
| 长期可信度 | 有发布流程与明确单人维护说明 | 至少经历实际发布/升级反馈，积累可追踪的回归证据 |

本次没有检查远程 Actions 运行结果、Maven Central 发布物或采用者情况，不能断言这些证据不存在，也不能仅凭本地工作流文件宣称已经获得它们。运行时间和真实使用反馈无法用增加抽象层替代。

## 建议执行顺序与停止条件

1. **先修承诺：** 改正 1.0 冻结内部实现的条款；统一开关优先级及生效时机。完成标准是文档不再互相矛盾，且开关组合有行为检查。
2. **降低日常成本：** 给出单元与完整验证两个入口，保持完整 Redis 验证为 CI 发布门槛。完成标准是在没有 Docker 的机器上可以明确完成单元验证。
3. **让装配可推断：** 用明确内部装配替代扫描，并验证关闭和 Adapter 替换场景。完成标准是目录中增加无关类不会自动改变装配。
4. **证明公开 Interface：** 用一个外部消费者通过打包 JAR 验证关键契约。完成标准是它不能借助内部包权限，也能接入和扩展。
5. **然后停止架构扩张：** 先做一个小版本发布并收集反馈，真实问题出现后再调整 Seam。

当前不建议增加多 Boot 支持线、Reactive、二级缓存、熔断限流、通用插件平台、常驻 Redis 集群或多套部署环境。无专用开发环境并不妨碍成为成熟的小型开源库；需要的是可重复的验证与准确的承诺。

## 本次验证与限制

- 使用图索引完成定向发现、关键调用方向与源码片段核查；索引 generation 为 `2026-09-04T17:29:55Z`，所依赖源码路径的覆盖元数据匹配，未报告解析缺口。这是 best-effort 证据，不是全仓库完整性证明。
- 图索引排除了 `scripts/`；相关检查脚本已直接读取。文档、POM 和工作流均直接核查。
- 实际执行 `scripts/ci/check-docs-contracts.sh` 和 `scripts/ci/check-test-names.sh`，均退出 0。前者仍未检出本报告中的语义矛盾，说明关键词门禁只能承担有限职责。
- 未运行 Maven 编译、单元测试或 Redis 集成测试；不据此判断当前重构已通过验收。未修改实现和既有工作区改动，仅新增本报告。
- 已查询项目 Hindsight 页面，但相关页没有可用历史事实；报告未依赖其中的架构或决策结论。
