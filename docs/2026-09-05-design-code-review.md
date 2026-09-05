# ResiCache 设计与代码缺陷审查

审查日期：2026-09-05。基线：`98856ab` 加当前工作区未提交改动。

**结论：当前最需要补齐的是可验证的安全与行为契约。已确认 4 项代码缺陷，以及 3 项设计/验证问题。其中反序列化白名单绕过应作为发布前必须修复的问题。** 当前内部实现收拢、类型化决策和写回失败保留业务结果的方向值得保留；继续拆类不能替代这些契约修复。

本文使用 codebase-design 的 Module、Interface、Implementation、Seam、Depth、Locality 术语。Interface 包括调用方必须知道的配置、失败方式、时序与语义，并不只指 Java 方法签名。

## 代码缺陷

### C1 · P1：默认配置下，反序列化白名单可被数组形式的类型标识绕过

证据：[SecureJacksonRedisSerializer.java:91](/home/david/Projects/ResiCache/src/main/java/io/github/davidhlp/spring/cache/redis/cache/SecureJacksonRedisSerializer.java:91)、[流式检查:212](/home/david/Projects/ResiCache/src/main/java/io/github/davidhlp/spring/cache/redis/cache/SecureJacksonRedisSerializer.java:212)、[VersionEnvelope.java:53](/home/david/Projects/ResiCache/src/main/java/io/github/davidhlp/spring/cache/redis/serialization/VersionEnvelope.java:53)。

预检只识别名为 `@class` 的字段。实际解析还接受数组形式的类型标识，因此预检接受的输入范围与真正反序列化的输入范围不一致。此外，方法内构造了 `typeValidator`，却没有把它安装到返回的 ObjectMapper 上。

使用默认 serializer、默认关闭全局多态的配置，以无害本地测试类复现：

```json
{"version":2,"payload":["audit.Forbidden",{"value":"benign"}]}
```

实际结果为 `WRAPPER_ARRAY decoded=audit.Forbidden`。该类不属于默认允许的包，仍被成功实例化。**这证明白名单约束失效；本次没有验证命令执行或具体 gadget 攻击链，不能据此直接声称已实现远程代码执行。** 安全影响的前提是非可信数据能够进入反序列化输入，例如缓存内容被污染。

最小修复方向：让真正解析类型的 Jackson 路径强制执行允许列表，并验证字段级类型信息、数组类型信息和可配置类型属性都不能绕过。不要仅给文本扫描再补一种 JSON 外形。

验收：上述无害样例在实例化前被拒绝；允许的业务对象与集合仍能往返；测试覆盖不同类型标识形式。

### C2 · P1：默认序列化会丢失缓存 TTL、创建时间与版本

证据：[CachedValue.java:113](/home/david/Projects/ResiCache/src/main/java/io/github/davidhlp/spring/cache/redis/cache/CachedValue.java:113)、[版本 getter:143](/home/david/Projects/ResiCache/src/main/java/io/github/davidhlp/spring/cache/redis/cache/CachedValue.java:143)、[JacksonConfig.java:30](/home/david/Projects/ResiCache/src/main/java/io/github/davidhlp/spring/cache/redis/cache/JacksonConfig.java:30)。

这些属性的 getter 使用 `@JsonIgnore`，字段是 private，默认 ObjectMapper 没有开启相应字段可见性。注释声称“序列化走字段”，实际输出只有业务值：

```json
{"version":2,"payload":{"@class":"io.github.davidhlp.spring.cache.redis.cache.CachedValue","value":"audit"}}
```

当前源码编译后的往返探针输出：

```text
ROUNDTRIP ttl=600->0 created=1788578609374->0 version=59713530750137->0
```

影响：读取后比例刷新策略收到 `ttl=0`，直接拒绝刷新；版本比较丢失依据；对象自身的过期判断退化。Redis 原生 TTL 仍由写入命令设置，因此**不能把该问题描述成所有 Redis 缓存都变成永久缓存**。

最小修复方向：明确持久化的字段契约，显式保留业务需要的 TTL、时间和版本，并为已有缺失字段的数据规定退化行为。不要简单把所有字段全量开放：`startNanoTime` 是进程时钟状态，不能把其他 JVM 的 `nanoTime` 起点当成本进程时间使用。

验收：经过生产 serializer 的完整往返后，刷新策略需要的字段保持正确；旧数据可读；跨实例时间语义明确。

### C3 · P1：刷新 Lua 将“格式版本”误当成“缓存值版本”

证据：[EarlyExpirationScripts.java:52](/home/david/Projects/ResiCache/src/main/java/io/github/davidhlp/spring/cache/redis/cache/EarlyExpirationScripts.java:52)、[EarlyExpirationHandler.java:274](/home/david/Projects/ResiCache/src/main/java/io/github/davidhlp/spring/cache/redis/cache/EarlyExpirationHandler.java:274)、[EnvelopeCodec:10](/home/david/Projects/ResiCache/src/main/java/io/github/davidhlp/spring/cache/redis/serialization/SerializationException.java:10)。

Java 传入 `CachedValue.getVersion()`，Lua 比较 `parsed.version`。但实际 wire format 顶层 `version` 固定为格式版本 `2`；缓存值位于 `payload` 中。二者不是同一个概念。

当前 C2 导致反序列化后的值版本通常为 `0`，Lua 比较 `2 == 0`，不会缩短 TTL。即使修复 C2，正常生成的值版本仍不等于格式版本 `2`，所以这个问题独立存在。

已有 [EarlyExpirationHandlerTest.java:384](/home/david/Projects/ResiCache/src/test/java/io/github/davidhlp/spring/cache/redis/cache/EarlyExpirationHandlerTest.java:384) 把 captured/live 的版本人为设为 `2L`，恰好与格式版本一致，不能证明比较的是实际值版本。该测试也不能代替“不同值版本、相同格式版本”的并发覆盖检查。

最小修复方向：统一值版本在 Java 与 Lua 中的位置和表示。版本 token 宜采用精确字符串，避免把任意 Java long 交给 Lua 数字转换后再比较。保留值变化时不修改 TTL 的原子性。

验收：正常创建的值能缩短 TTL；另一个写入覆盖后，即使格式版本相同也不能缩短新值的 TTL。

证据等级：实际 wire 输出加源码协议比对；本轮没有启动 Redis 执行 Lua，因此不标记为 Redis 端到端实测。

### C4 · P2：固定 60 秒提前返回改变了用户配置的刷新比例

证据：[EarlyExpirationHandler.java:114](/home/david/Projects/ResiCache/src/main/java/io/github/davidhlp/spring/cache/redis/cache/EarlyExpirationHandler.java:114)、[DefaultEarlyExpirationPolicy.java:31](/home/david/Projects/ResiCache/src/main/java/io/github/davidhlp/spring/cache/redis/cache/DefaultEarlyExpirationPolicy.java:31)。

总 TTL 为 600 秒、阈值为 0.3 时，剩余 180 秒就应进入刷新窗口。剩余 150 秒时，实际 policy 返回 true，但 handler 因 `150 > 60` 直接跳过 policy。

探针输出：

```text
POLICY ttl=600 remaining=150 threshold=.3 refresh=true HANDLER_BYPASS=true
```

该缺陷目前可能被 C2 掩盖，修复元数据后仍然存在。另外，此分支仍执行一次 TTL 查询，再由 ActualCacheHandler GET；代码注释宣称的“一次 RTT”并未由这段实现成立。

最小修复方向：删除不能证明语义等价的固定阈值优化，复用已有预取值；只有在掌握真实 TTL 与阈值后才判断是否可跳过。性能改动应同时验收结果语义和实际 Redis 命令数。

## 设计与验证问题

### D1 · P2：公开 CacheContext 暴露了过多执行协议，Interface 的 Depth 仍不足

证据：[CacheHandler.java:26](/home/david/Projects/ResiCache/src/main/java/io/github/davidhlp/spring/cache/redis/chain/CacheHandler.java:26)、[CacheContext.java:43](/home/david/Projects/ResiCache/src/main/java/io/github/davidhlp/spring/cache/redis/chain/model/CacheContext.java:43)、[引擎标记:153](/home/david/Projects/ResiCache/src/main/java/io/github/davidhlp/spring/cache/redis/chain/model/CacheContext.java:153)。

`handle(context)` 看起来很小，但调用方仍须理解 TTL/null/prefetch 决策的生产者与消费者、执行顺序、哪些 setter 能写、skip 标记由谁设置。这些知识都属于 Interface。包私有 Implementation 的收拢没有自动消除这部分复杂度。

`markSkipRemaining()` 注释要求仅引擎调用，实际仍为 public；InputView 的 `byte[]` 也直接交给调用方，不能把整个输入视为深度不可变。这里是契约脆弱性，不等于已经观察到恶意扩展破坏执行。

建议：首先明确稳定扩展确实需要的读视图和可返回决策；让引擎独占内部推进状态。尊重 STABILITY.md 已承诺的兼容性，通过兼容迁移收缩 Interface，避免直接删除公开成员，也不必为每种字段另建一层抽象。

### D2 · P2：LoaderOrchestrator 分出了代码，但调用方仍承担大量编排知识

证据：[LoaderOrchestrator.java:137](/home/david/Projects/ResiCache/src/main/java/io/github/davidhlp/spring/cache/redis/cache/LoaderOrchestrator.java:137)、[RedisProCache.java:151](/home/david/Projects/ResiCache/src/main/java/io/github/davidhlp/spring/cache/redis/cache/RedisProCache.java:151)。

这个 Module 具有实际行为，不是应直接删除的透传。但每次调用需要 8 个参数，其中包含 4 个回调。调用方必须知道哪些走 `super`、哪些保留指标、哪个用于锁内双检；结果还需外层分支翻译，写回失败在内部通过携值异常运输。

这是一个尚未充分加深的内部 Seam：可测试性改善了，Locality 却仍横跨两个文件。当前只有一个生产调用方，没有证据要求继续扩展通用框架。

建议：优先减少每次调用必须重新装配的知识，把固定协作在创建时绑定，或将高度耦合部分保留在同一个 Module 内；以调用方只需表达“加载这个 key”的效果衡量，而不是以类长度衡量。保留写回失败仍返回业务值这一已有行为。

### D3 · P1：验证更擅长证明局部行为，尚未锁住生产路径的组合契约

本轮重跑 `SecureJacksonRedisSerializerTest` 与 `DefaultEarlyExpirationPolicyTest`：**24 个测试通过，0 失败、0 错误、0 跳过**。但独立探针仍复现 C1、C2、C4。C3 的版本为 `2L` 的测试数据还与错误读取的字段发生巧合。

问题不是测试数量不足，而是验证对象与调用方实际跨越的 Seam 不一致。单独证明 policy 计算正确，不等于 handler 遵循 policy；证明字符串业务值可往返，不等于缓存元数据契约正确。

建议优先补 3 组有区分力的回归检查：

1. 生产 serializer 的缓存对象往返，以及不在允许列表中的不同类型编码。
2. 真实写入格式配合 Lua：原值、并发覆盖值、非 `2` 值版本。
3. 完整刷新读取路径：多组 TTL/threshold，包含刷新窗口超过 60 秒的情形。

这些测试应与调用方跨越相同 Seam，内部替身只控制必要的不确定性。无需通过大量镜像 Implementation 的测试来补数量。

## 建议处理顺序

| 顺序 | 工作 | 完成标准 |
|---|---|---|
| 1 | C1 安全类型解析 | 不允许的类型在实例化前被拒绝 |
| 2 | C2 + C3 存储与刷新协议 | 生产对象往返后元数据正确，CAS 区分实际写入版本 |
| 3 | C4 刷新时机 | handler 与配置比例一致 |
| 贯穿 | D3 回归验证 | 新测试能在修复前失败、修复后通过 |
| 后续 | D1 + D2 Interface 收缩 | 调用方需要知道的协议减少，稳定扩展保持兼容 |

## 验证范围与可复现材料

- 使用 Java 21 对当前工作区执行离线编译；上述两组定向测试最终输出 `BUILD SUCCESS`，24 个用例通过。
- 独立探针使用生产 `JacksonConfig`、`SecureJacksonRedisSerializer`、`CachedValue`、`DefaultEarlyExpirationPolicy`；类型绕过只实例化本地无害类，没有外部副作用。
- 探针：[AuditProbe.java](/tmp/resicache-audit/AuditProbe.java)、[Forbidden.java](/tmp/resicache-audit/Forbidden.java)、[运行脚本](/tmp/resicache-audit/run.py)。执行 `rtk proxy python3 /tmp/resicache-audit/run.py`；依赖当前工作区已编译类与 Surefire 记录的本机依赖路径。这是本轮复现工具，断言确认缺陷仍存在，修复后需要改为正式预期断言；`/tmp` 文件不是永久仓库资产。
- 图索引项目为 ResiCache，Tier 2 定向验证，generation `2026-09-04T17:29:55Z`。依赖的文件 coverage 均为 `no_recorded_issue / metadata_match`。该信号不证明整个项目无遗漏；部分动态派发图关系不能作为精确调用证据，关键结论以源码与执行结果为准。
- Hindsight 的相关知识页没有提供可用的项目历史事实，本报告没有依据历史记忆判定缺陷。
- 本轮没有执行全量 verify、真实 Redis 集成测试、集群故障测试或发布兼容性矩阵。因此这是有范围的风险审查，不是完整安全审计，也不声称列尽全部缺陷。
- 没有修改业务源码、提交或撤销已有工作区改动；仓库内仅新增本报告。
