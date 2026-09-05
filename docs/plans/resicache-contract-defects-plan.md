# ResiCache 契约缺陷与遗留设计收敛方案

> **本文件是未闭合事项的唯一状态源。** 当前实现基线:`7f13ef2`(2026-09-05 文档收尾提交) + 本轮工作区修改。
> 行号与结论已对照当前实现核实;C1–C4/D1/D2 已在本轮重新实现并验证。
>
> 本方案源于 2026-09-05 两份评审报告(已完成过程材料,已删除,结论提炼至此)。
> 评审中已解决的问题不在此重复,见下方"已闭合"表。

## 1. 已闭合(评审发现中不再追踪的部分)

| 评审问题 | 闭合位置 |
|---|---|
| 稳定性承诺冻结内部实现(1.0 锁定 §2) | `STABILITY.md` §5:1.0 仅锁定 caller-observable 契约,§2 保持 minor 可演化 |
| 防护开关语义矛盾(启动时生效 vs 运行时 kill-switch) | `RedisProCacheProperties` Javadoc 统一为启动时静态解析;组合行为由 `CacheHandlerChainFactoryTest` 锁定;ADR-0001 §18 |
| 自动装配依赖包扫描、文档漂移 | 选定 Option A(仅内部 `cache` 包扫描 + 测试类排除);README/CONTRIBUTING 表述已修正 |
| 扩展 SPI 协议过宽、无书面协议 | `STABILITY.md` §4 SPI 协议 + 嵌套 public 类型清单门禁 |
| 日常验证无分层、测试分类不完整 | `pom.xml` `unit` profile + `check-test-names.sh` 正向命名门禁 |
| 公开面检查只验类型名单 | 嵌套类型清单 + `scripts/ci/check-external-consumer.sh` 打包 JAR 外部消费者门禁 |

设计决策依据见 `docs/adr/0001-interface-contract-closure.md`。

## 2. 已闭合缺陷(C1–C4)

### C1 · P1(安全):默认配置下反序列化白名单可被数组形式类型标识绕过

- **状态**: Closed (2026-09-05)。`SecureJacksonRedisSerializer` 无论默认多态开关是否开启都安装
  `PolymorphicTypeValidator`;流式预检同时检查字段级 `@class`、配置的 `typeProperty`
  以及 `payload`/`value` 的 wrapper-array 首项。字段、数组和可配置属性名的拒绝测试在
  unit 与最终 full verify 中通过。

- **问题**:预检只识别名为 `@class` 的字段;实际解析还接受数组形式(`WRAPPER_ARRAY`)
  的类型标识,预检接受的输入范围与真正反序列化的输入范围不一致。默认
  polymorphic-typing 关闭时,方法内构造的 `typeValidator` 根本没有安装到 ObjectMapper。
- **历史证据**:评审时 `SecureJacksonRedisSerializer.java` 只构造但未在默认路径安装
  validator,预检也硬编码 `"@class"`;`{"version":2,"payload":["audit.Forbidden",{...}]}`
  可到达非白名单类 `[probe]`。当前对应实现见 `SecureJacksonRedisSerializer.java:95-110`
  与 `:194-271`。未验证命令执行或具体 gadget 链,不声称 RCE。
  前提是非可信数据能进入反序列化输入(如缓存内容被污染)。
- **验收**:不允许的类型在任何类型标识形式(字段级、数组、可配置属性名)下于实例化前被拒绝;
  允许的业务对象与集合仍能往返;测试覆盖各类型标识形式。

### C2 · P1:默认序列化丢失缓存 TTL、创建时间与版本

- **状态**: Closed (2026-09-05)。`CachedValue` 显式持久化 `ttl`、`createdTime`、
  `lastAccessTime`、`visitTimes`、`expired`、`version`; `startNanoTime` 保持进程内字段并不写入
  wire format。生产 serializer 往返测试和真实 Redis serializer wiring test 通过。

- **问题**:`CachedValue` 全部 getter `@JsonIgnore`、字段 private,默认 ObjectMapper
  未开启字段可见性;序列化输出只有业务值。
- **历史证据**:评审时 `CachedValue` 元数据 getter 被 `@JsonIgnore` 且 `JacksonConfig`
  使用裸 `new ObjectMapper()`;探针往返 `ttl=600->0 created=…->0 version=…->0` `[probe]`。
  当前字段与 accessor 对应 `CachedValue.java:32-48`、`:121-147`。
  Redis 原生 TTL 仍由写命令设置,不能表述为"所有缓存变永久"。
- **影响**:比例刷新收到 `ttl=0` 拒绝刷新;版本比较失去依据;对象自身过期判断退化。
- **验收**:生产 serializer 往返后刷新所需字段保持正确;旧数据可读;跨实例时间语义明确。
  `startNanoTime` 是进程时钟状态,不得跨 JVM 使用,不纳入持久化契约。

### C3 · P1:刷新 Lua 把"格式版本"当成"缓存值版本"

- **状态**: Closed (2026-09-05)。Lua 解包 envelope 和可选 wrapper-array,只比较
  `payload.version` 与 `ARGV[1]`;顶层 `envelope.version` 仅表示格式。相同值版本缩短 TTL、
  同格式版本但值版本变化不缩短的真实 Redis 用例已通过。

- **问题**:Java 传入值版本,Lua 比较 envelope 顶层 `version`(固定格式版本 `2`);
  二者不是同一概念。C2 修复前反序列化值版本通常为 0,`2 == 0` 恒不成立,修复 C2 后
  该问题独立存在。
- **历史证据**:原 Lua 比较顶层 `version` `[source]`;当前脚本对应
  `EarlyExpirationScripts.java:47-66`。本轮已用真实 Redis 执行,不再是“未启动 Redis”证据。
- **验收**:正常创建的值能缩短 TTL;被另一写入覆盖后,即使格式版本相同也不缩短新值 TTL;
  版本 token 用精确字符串比较,保留"值未变才缩短 TTL"的原子性。

### C4 · P2:固定 60 秒提前返回改变用户配置的刷新比例

- **状态**: Closed (2026-09-05)。删除 `FAST_PATH_TTL_SECONDS` 和绝对 60 秒 bypass;启用提前过期
  时读取 `CachedValue`,由 `EarlyExpirationPolicy` 按用户 TTL/threshold 决策。高剩余 TTL 的策略
  命中、同步/异步刷新和真实 Redis 路径测试已通过;非 `CachedValue` 的旧原始值安全跳过。

- **问题**:`FAST_PATH_TTL_SECONDS=60` 之下直接跳过 policy,阈值语义与用户配置不符
  (TTL 600s、阈值 0.3 时剩余 150s 应进入刷新窗口但被跳过)。
- **历史证据**:原 fast path 位于 `EarlyExpirationHandler` 的固定阈值分支;探针
  `remaining=150 policy refresh=true HANDLER_BYPASS=true` `[probe]`。当前按策略读取与
  预取见 `EarlyExpirationHandler.java:88-117`。
- **验收**:handler 与配置比例一致;性能改动同时验收结果语义与实际 Redis 命令数。

## 3. 遗留设计收敛(P2)

### D1 · CacheContext 暴露过多执行协议 (部分收敛)

`markSkipRemaining()` 仍为 public(`CacheContext.java:153`)而注释要求仅引擎调用;
本轮保留它作为兼容 shim,避免删除已承诺的成员。`CacheInput` record 构造期和
`CacheContext.getValueBytes()` 现在都做防御性复制,调用方不能修改链内输入。方向仍是
未来在有真实兼容证据时收敛 engine-only 状态;当前不再扩大 public Interface。

- **状态**: Partially addressed (bytes immutable at the public view; engine-only shim retained).
- **验证**:`CacheContextTest.valueBytes_areDefensivelyCopiedAtInputAndContextViews` 在 unit
  与 full verify 中通过。

### D2 · LoaderOrchestrator 调用方仍承担编排知识 (已闭合)

生产构造期现在绑定 key 派生、双检、写回和 default-load 四个 callback;
`RedisProCache.get(key, loader)` 只传 cacheName、loader、key、operation。旧的全参数
overload 仅保留给内部 isolation tests,不属于外部 Interface。写回失败仍返回业务值。

- **状态**: Closed (2026-09-05)。`LoaderOrchestratorTest.boundCallbacks_productionEntryUsesOnlyLoaderAndKey`
  与 `RedisProCacheIntegrationTest` 通过。

## 4. 贯穿门槛:Seam 对齐的回归验证(D3)

当前测试证明局部行为正确,但生产路径组合契约未锁定。修复 C1–C4 时同步补三组
与调用方跨越相同 Seam 的回归:

1. 生产 serializer 的缓存对象往返 + 不在白名单的不同类型编码(覆盖 C1/C2)。
2. 真实写入格式配合 Lua:原值、并发覆盖值、非 `2` 值版本(覆盖 C3)。
3. 完整刷新读取路径:多组 TTL/threshold,含刷新窗口超过 60s 的情形(覆盖 C4)。

标准:新测试修复前失败、修复后通过;不做镜像 Implementation 的数量补齐。

### 4.1 本轮执行记录 (2026-09-05)

- JDK21 下运行 `./mvnw -Punit test -B`（当前隔离终端为 Surefire 显式注入 Mockito 5.20.0
  agent）：750 tests, 0 failures/errors/skipped, exit 0。
- JDK21 下运行 `./mvnw clean verify -B`（context-mode 后台执行，含 Mockito agent）：941 tests,
  0 failures/errors/skipped, exit 0;JaCoCo 通过；其中包含真实 Redis 的 C3/C4、序列化迁移和
  Cluster slot 用例。普通终端直接运行会因 PID 隔离导致 Mockito 自附加失败，不能作为源码失败
  证据。
- `... ./mvnw checkstyle:check -B`：0 violations; `... ./mvnw javadoc:javadoc -B`：exit 0。
- `bash scripts/ci/check-test-names.sh`、`bash scripts/ci/check-docs-contracts.sh`、`git diff --check`：均 exit 0。
- `bash scripts/ci/check-external-consumer.sh`：exit 0，输出 `EXTERNAL_CONSUMER_OK`；仅证明打包 JAR 编译和纯值路径，不宣称 Redis 行为。
- 工作区未提交、未推送、未发布；D1 的 engine-only public shim 仍保留以遵守兼容边界。

## 5. 顺序与停止条件

C1(安全)→ C2 + C3(存储与刷新协议)→ C4(刷新时机),D3 贯穿;D2 已完成,D1 保留
兼容 shim。C1–C4 已有修复前可复现的回归和修复后执行证据;本文件保留为闭合记录,
不再作为未修复缺陷阻塞后续工作。若未来有真实外部实现要求移除 D1 shim,重新开启
一个带迁移方案的独立任务。RELEASE-001、AOT-001、历史 JAR 二进制兼容和旧线采用者
接触仍按项目范围暂缓。
