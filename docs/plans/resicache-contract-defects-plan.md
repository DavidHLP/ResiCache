# ResiCache 契约缺陷与遗留设计收敛方案

> **本文件是未闭合事项的唯一状态源。** 基线:`9846ce6`(2026-09-05 提交的包收拢重构)。
> 行号与结论已对照该提交核实;标注 `[probe]` 的行为输出来自 2026-09-05 评审探针,
> 本轮未复跑;标注 `[source]` 的结论以当前源码协议比对为准,无 Redis 实测。
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

## 2. 未闭合缺陷(按处理顺序)

### C1 · P1(安全):默认配置下反序列化白名单可被数组形式类型标识绕过

- **问题**:预检只识别名为 `@class` 的字段;实际解析还接受数组形式(`WRAPPER_ARRAY`)
  的类型标识,预检接受的输入范围与真正反序列化的输入范围不一致。默认
  polymorphic-typing 关闭时,方法内构造的 `typeValidator` 根本没有安装到 ObjectMapper。
- **证据**:`SecureJacksonRedisSerializer.java:91`(构造但默认未安装)、`:212`/`:247`
  (预检硬编码 `"@class"`)`[source]`;`{"version":2,"payload":["audit.Forbidden",{...}]}`
  解码出非白名单类 `[probe]`。未验证命令执行或具体 gadget 链,不声称 RCE。
  前提是非可信数据能进入反序列化输入(如缓存内容被污染)。
- **验收**:不允许的类型在任何类型标识形式(字段级、数组、可配置属性名)下于实例化前被拒绝;
  允许的业务对象与集合仍能往返;测试覆盖各类型标识形式。

### C2 · P1:默认序列化丢失缓存 TTL、创建时间与版本

- **问题**:`CachedValue` 全部 getter `@JsonIgnore`、字段 private,默认 ObjectMapper
  未开启字段可见性;序列化输出只有业务值。
- **证据**:`CachedValue.java:107-171`(`@JsonIgnore`)、`JacksonConfig.java:33`
  (裸 `new ObjectMapper()`)`[source]`;探针往返 `ttl=600->0 created=…->0 version=…->0` `[probe]`。
  Redis 原生 TTL 仍由写命令设置,不能表述为"所有缓存变永久"。
- **影响**:比例刷新收到 `ttl=0` 拒绝刷新;版本比较失去依据;对象自身过期判断退化。
- **验收**:生产 serializer 往返后刷新所需字段保持正确;旧数据可读;跨实例时间语义明确。
  `startNanoTime` 是进程时钟状态,不得跨 JVM 使用,不纳入持久化契约。

### C3 · P1:刷新 Lua 把"格式版本"当成"缓存值版本"

- **问题**:Java 传入值版本,Lua 比较 envelope 顶层 `version`(固定格式版本 `2`);
  二者不是同一概念。C2 修复前反序列化值版本通常为 0,`2 == 0` 恒不成立,修复 C2 后
  该问题独立存在。
- **证据**:`EarlyExpirationScripts.java:49-52` `[source]`;未启动 Redis 执行 Lua,非端到端实测。
- **验收**:正常创建的值能缩短 TTL;被另一写入覆盖后,即使格式版本相同也不缩短新值 TTL;
  版本 token 用精确字符串比较,保留"值未变才缩短 TTL"的原子性。

### C4 · P2:固定 60 秒提前返回改变用户配置的刷新比例

- **问题**:`FAST_PATH_TTL_SECONDS=60` 之下直接跳过 policy,阈值语义与用户配置不符
  (TTL 600s、阈值 0.3 时剩余 150s 应进入刷新窗口但被跳过)。
- **证据**:`EarlyExpirationHandler.java:64`、`:114` `[source]`;探针
  `remaining=150 policy refresh=true HANDLER_BYPASS=true` `[probe]`。
  该分支仍执行一次 TTL 查询,"一次 RTT"的说法不成立。
- **验收**:handler 与配置比例一致;性能改动同时验收结果语义与实际 Redis 命令数。

## 3. 遗留设计收敛(P2)

### D1 · CacheContext 暴露过多执行协议

`markSkipRemaining()` 仍为 public(`CacheContext.java:153`)而注释要求仅引擎调用;
`byte[]` 输入视图直接交给调用方。方向:明确稳定扩展真正需要的读视图与可返回决策,
引擎独占内部推进状态;遵守 STABILITY 兼容承诺,不直接删除公开成员。

### D2 · LoaderOrchestrator 调用方仍承担编排知识

每次调用需装配大量参数(含多个回调),结果还需外层分支翻译。方向:创建时绑定固定协作
或保留在同一 Module;以"调用方只需表达加载这个 key"衡量,不以类长度衡量。
保留写回失败仍返回业务值的既有行为。

## 4. 贯穿门槛:Seam 对齐的回归验证(D3)

当前测试证明局部行为正确,但生产路径组合契约未锁定。修复 C1–C4 时同步补三组
与调用方跨越相同 Seam 的回归:

1. 生产 serializer 的缓存对象往返 + 不在白名单的不同类型编码(覆盖 C1/C2)。
2. 真实写入格式配合 Lua:原值、并发覆盖值、非 `2` 值版本(覆盖 C3)。
3. 完整刷新读取路径:多组 TTL/threshold,含刷新窗口超过 60s 的情形(覆盖 C4)。

标准:新测试修复前失败、修复后通过;不做镜像 Implementation 的数量补齐。

## 5. 顺序与停止条件

C1(安全)→ C2 + C3(存储与刷新协议)→ C4(刷新时机),D3 贯穿;D1/D2 为后续
Interface 收缩,不阻塞缺陷修复。C1–C4 全部有"修复前失败"的回归后,本文件整体关闭并删除;
届时状态以提交与 CHANGELOG 为准。
