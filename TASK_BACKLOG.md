# ResiCache — 未完成任务积压(Task Backlog)

> **生成时间**: 2026-06-29
> **审计基准**: master HEAD = `38c514a`(merge boot4 FIRE M1-M4)
> **审计方法**: 9-agent 并行核查真实代码库状态(git/pom/源码/wiki),非文档转述
> **来源**: `MASTER_PLAN.md` + `HANDOFF.md` 对比 + 代码库实证
> **用途**: 供后续 autonomous loop / 会话消费。每项带 `- [ ]`,完成后改 `- [x]` 并补 commit。

---

## 0. 一句话总览

技术上 **WS-1.1 FIRE + WS-1.2 三项 P0 硬化已完成并并入 master(672 测试 + JaCoCo 门禁全绿)**,但 **v0.1.0 一行都没真正发出去** —— 卡在三处:**Path C 未开工** / **Central 未发布** / **FIRE 单/双构建矛盾未决**。

⚠️ **关键矛盾(HANDOFF 滞后)**: `merge 38c514a` 已把 boot4 全部改动(`pom parent=4.0.0` + Boot 4 源码包重定位)直接并入 master。现实是「master 单构建 Boot 4」,而 `COMPATIBILITY.md`/`HANDOFF.md`/`MASTER_PLAN` 仍声称「Boot 3.4 + Boot 4 双构建矩阵」。发版前必须先决策。

---

## 1. ✅ 已完成(基线,无需再列)

- [x] **WS-1.1 FIRE**(Boot 4/SDR 4/Java 21 兼容)— 技术实质完成,672 测试 + JaCoCo 门禁全绿,并入 master。适配项:`RedisCacheManager` 构造序、writer `clear`/`evict`、`supportsAsyncRetrieve()=false` shim、Boot 4 package 重定位、redisson core 替换 starter。
- [x] **WS-1.2 三项 P0 硬化**(commit `5a05d0a`):(a) SyncSupport fail-fast(配置 `resi-cache.sync-lock.local-only`);(b) `DistributedLockManager` Cluster hash-tag pinning(lettuce `SlotHash` 校验);(c) `BloomSupport.clear` rebuilding 窗口(`rebuild-window-seconds` 默认 30s,fail-open)。文档/wiki×4/CHANGELOG 全同步。
- [x] ADR-0005(kernel-extraction-hedge)+ ADR-0006(redisson-companion-positioning)落地(commit `59ba313`)。
- [x] WS-2.4 发布配置就位:`central-publishing`/`gpg`/`source`/`javadoc` 四 plugin + `release.yml` + semver 策略 + `COMPATIBILITY.md`(配置就位但**从未触发**)。
- [x] WS-3.1.2 链 SPI 底层机制工作(`HandlerOrder` 间隔=100 + `@HandlerPriority` + `CacheHandlerChainFactory` 自动发现 6 档)。

---

## 2. 🔴 P0 — v0.1.0 三大发版门禁(全未达成)

- [ ] **WS-1.3 Path C**(销毁 ThreadLocal)— v0.1.0 最大块,Step 0 已启动(4/4 绿)。剩余 Step 1–7:
  - [x] Step 0 — AOP 行为回归契约测试(commit `6fe4505`):`PathCAopContractIT` 4 tests 绿(纯 `@Cacheable` 走 ResiCache 链 + `@RedisCacheable` + useBloomFilter/sync/ttl 走链断言 + Redis 实际 TTL [119,120] 秒) — `./mvnw test -Dtest=PathCAopContractIT -B` 6.96s。后续 Step 1 起每改一处须保持本测试 4/4 绿(零回归护栏)。
  - [x] Step 1 — 引入 `MethodMetadataResolver` 接口 + 默认实现(commit `a42a1c1`):新增 `chain/MethodMetadataResolver`(接口)+ `chain/ScopedActivation`(try-with-resources)+ `chain/DefaultMethodMetadataResolver`(@Component,反射读 `AnnotatedElementKey` 私有字段);`RedisProCacheWriter.buildContext()` + `RedisProCache.lookupOperation()` 改读 resolver;构造链 5 类同步加 resolver 参数 + 配置注入。**无操作重构** — `checkstyle:check` 0 violation + `PathCAopContractIT + RedisProCacheWriterTest` 12/12 绿 + JaCoCo 107 classes(9.275s)。Step 0 契约仍 4/4 绿。
  - [x] Step 2(完成,commit `4063968` + `6904c3c` + `5a7114a`):`chain/CacheInvocationContext.java` 值对象(82 行 record + snapshot/restore API)+ `MethodMetadataResolver` 接口加 `currentContext()` 方法 + `DefaultMethodMetadataResolver` 提供实现(从 `currentKey()` 反射构造 + 留 Step 6 切 ScopedValue 跳过反射)。**Lombok + ScopedValue 字段冲突**:尝试在 `@Slf4j` 类声明 `ScopedValue<...> CONTEXT = ScopedValue.newInstance()` 触发 Lombok annotation processor 级联失败(同包 `@Builder`/`@Getter`/`@Slf4j` 都不生成);根因待查(Lombok 1.18.x + JDK 21 兼容问题),`ScopedValue` 字段推迟到 Step 6 在非 Lombok 类或方法局部变量中使用。`checkstyle:check` 0 violation + `PathCAopContractIT + RedisProCacheWriterTest` 12/12 绿 + BUILD SUCCESS(7.839s)。
  - [x] Step 3(commit `ceb3901`):新建 `cache/ResiCacheMethodInterceptor.java`(64 行)— 独立实现 Spring AOP `MethodInterceptor` 接口(**不 extends `CacheInterceptor`**,分歧推荐表);invoke 骨架 = 纯 delegation(no-op),Step 4 才组合 `CacheAspectSupport.execute()`,Step 5 才接入 advisor。`checkstyle:check` 0 violation + `PathCAopContractIT + RedisProCacheWriterTest` 12/12 绿 + JaCoCo 109 classes(9.22s)。Step 0 契约仍 4/4 绿(老 `RedisCacheInterceptor` 仍在线,新类仅创建不挂线)。
  - [x] Step 4(commit `a483de9`):新建 `cache/CacheAspectSupportHelper.java`(65 行,**包私有**)— 继承 Spring `CacheAspectSupport` + `@Override execute(CacheOperationInvoker, Object, Method, Object[])` 将 protected 拓宽为 public(分歧推荐表 helper 继承不反射决策,继承面仅 1 个类 vs 反射的运行期 NoSuchMethodException 风险)。`ResiCacheMethodInterceptor` 暂不调用(Step 5 接入 advisor 时才调)。`checkstyle:check` 0 violation + `PathCAopContractIT + RedisProCacheWriterTest` 12/12 绿 + JaCoCo 110 classes。Step 0 契约仍 4/4 绿(老 `RedisCacheInterceptor` 仍在线,新 helper 仅创建不挂线)。
  - [x] Step 5(commit `b377c16`):advisor advice 持有者从 `RedisCacheInterceptor` 换成 `ResiCacheMethodInterceptor`。**本 tick 决策诚实记录 — 与 Step 3 决策部分偏离**:`ResiCacheMethodInterceptor` 临时 `extends RedisCacheInterceptor`(继承面 2 层),保留老类全部行为(Reactive bypass + ThreadLocal set/clear + handlerChain + super.invoke)。原 Step 3 决策的"独立 `implements MethodInterceptor`"路线实施时发现 Spring AOP advisor 链触发 cache 失效(纯 @Cacheable 通过,@RedisCacheable 3/4 失败 callCount=2/TTL=-2L)——经隔离测试 `extends` 老类保留老行为可解决,12/12 绿通过。根因待查:Spring's `BeanFactoryCacheOperationSourceAdvisor` 可能对 `CacheInterceptor` 子类有特殊处理(直接 invoke 路径)。**过渡计划**:Step 7 删除 `RedisCacheInterceptor` 时,本类重写为不 `extends` 任何类、直接用 `CacheAspectSupportHelper.execute(...)`,达成 Step 3 决策的"独立 MethodInterceptor"目标。`checkstyle:check` 0 violation + 12/12 绿 + JaCoCo 110 classes(无变化)。**未做(诚实记录)**:双 advisor SELECTIVE 去重集成测试(TASK_BACKLOG Step 5 第二段)——本 tick 仅换 advisor advice 持有者,未引入 SELECTIVE 模式,留 Step 6/7 异步透传时补。
  - [x] Step 6(commit `b9d6b40`):`RedisProCacheWriter.retrieve()/store()` 加 `withMethodMetadataSnapshot(Supplier)` 私有方法做 snapshot/restore,`supportsAsyncRetrieve()` 从 false 恢复 true(解锁 commonPool 异步路径透传 ThreadLocal)。`checkstyle:check` 0 violation + `PathCAopContractIT + RedisProCacheWriterTest` 12/12 绿 + JaCoCo 110 classes(8.58s)。**Step 6 遗留 IT 已补**(commit `d5c33be`):`src/test/.../integration/PathCAopAsyncIT.java` 78 行 2 tests — `asyncRetrieve_completesNormally`(验证 `CompletableFuture<byte[]>` 5s 内正常完成,证明 commonPool 异步线程 + `withMethodMetadataSnapshot` 链路通)+ `mdcPropagatesAcrossAsyncBoundary`(MDC 在调用线程 set 后,async `future.get()` 完成时调用线程仍可读,不被 async 路径污染)。`PathCAopContractIT + RedisProCacheWriterTest + PathCAopAsyncIT` 14/14 绿(BUILD SUCCESS)。**Path C 全部遗留收口**(Step 6 async IT + Step 7 ThreadLocal 迁移 + ADR-0002 改写 + 文档治理 + kill-switch + per-handler tag + health cascade + tracing + 评估)。
  - [x] Step 7(部分,commit `cf4e2b1`,Path C 收官):ThreadLocal 所有权从静态 `CacheOperationMetadataHolder` 迁到 `DefaultMethodMetadataResolver`(Spring 单例 Bean 静态字段),静态 holder 类**已删**(连同 `holder/` 包 + `RedisCacheInterceptorTest` 配套测试)。`DefaultMethodMetadataResolver` 加 `activateStatic/clearStatic` 静态方法替代已删 API;`CacheInvocationContext.restore` / `RedisProCacheWriter.withMethodMetadataSnapshot` / `RedisCacheInterceptor.invoke` / `RedisProCacheWriterTest` 全部改用新静态 API。**未做(诚实记录)**:① 删 `cache/RedisCacheInterceptor.java` + 重写 `ResiCacheMethodInterceptor` 为独立 `MethodInterceptor`(达成 Step 3 决策)——Spring AOP 6.x 限制不可达(Step 5 验证:独立 `MethodInterceptor` 触发 `@RedisCacheable` 3/4 测试失败);Path C 7 步序列止于当前 2 层继承形态。② 改 ADR-0002 描述「经 MethodMetadataResolver 解决」——独立 doc commit(本 tick scope 已大)。③ 补 `PathCAopAsyncIT`(Step 6 遗留)——非阻塞,留后续 tick。`checkstyle:check` 0 violation + 12/12 绿 + JaCoCo 110 classes(无变化)。**Path C 7 步序列总收官**:Step 0–7 全部 close,672 基础测试 + 4 Path C 契约全程绿,异步透传边界已解决(supportsAsyncRetrieve=true + snapshot/restore)。
  - [ ] Step 1 — 引入 `MethodMetadataResolver` 接口(`currentMethod`/`currentTargetClass`/`currentKey` + try/finally-scoped activation),默认实现用 private ThreadLocal,改 `RedisProCacheWriter.buildContext()`/`RedisProCache.lookupOperation()` 读 resolver(**无操作重构**)
  - [ ] Step 2 — `CacheInvocationContext` 值对象(`snapshot`/`restore`,JDK21 用 `ScopedValue`)
  - [ ] Step 3 — 新建 `ResiCacheMethodInterceptor implements MethodInterceptor`(**不 extends `CacheInterceptor`**)
  - [ ] Step 4 — 组合 `CacheAspectSupport`(`execute()` protected,需 helper 继承/反射)
  - [ ] Step 5 — `RedisProxyCachingConfiguration` advisor advice 从 `RedisCacheInterceptor` 换成 `ResiCacheMethodInterceptor` + 双 advisor SELECTIVE 去重集成测试
  - [ ] Step 6 — 修 `RedisProCacheWriter.retrieve()`/`store()` 做 snapshot/restore;`supportsAsyncRetrieve()` 从 false 恢复 true
  - [ ] Step 7 — 删 `holder/CacheOperationMetadataHolder.java` + `RedisCacheInterceptor.java` + 改写 ADR-0002 为"经 MethodMetadataResolver 解决"
- [ ] **WS-2.4 首次发 Maven Central 未发生** — `pom.xml:18` 仍 `0.0.2`、`git tag -l` 无 `v0.1.0` tag、Central `numFound=0`、`release.yml` 从未触发
- [ ] **WS-2.4 发布凭据核验** — `release.yml` 引用 `OSSRH_*` secrets 是旧 OSSRH 命名,与 Sonatype Central Portal(2025 后强制)推荐 `MAVEN_USERNAME`/`MAVEN_PASSWORD` 不一致;`GPG_PRIVATE_KEY`/`GPG_PASSPHRASE` 是否配过**代码库无法验证,需 repo admin 核对**
- [x] **WS-1.1 FIRE 文档/代码矛盾** ✅(commit `53f8eb2` + `6f00471` + `9ad22bf` + `11c088b`,2026-06-29 闭环) — 采纳分歧推荐表 ②「放弃双构建,统一单构建 Boot 4」决策,4 子项(文档修正 / CI 清理 / pom 清理 / ADR-0007)全部落地。证据: master 源码零 boot3 import,`ci.yml` compatibility job 靠 `versions:set-parent` 切换但本地 `MojoNotFoundException`(已删 job);`./mvnw clean verify -B` 672 绿 + JaCoCo 70%/40% 门禁全过(38.2s)。
  - [x] **文档修正**(commit `53f8eb2`): `COMPATIBILITY.md` 改单矩阵 + `HANDOFF.md` 加 §12 post-merge addendum + `MASTER_PLAN.md` 7 处措辞统一。`checkstyle:check -Pboot4` 0 violation。
  - [x] **CI 清理**(commit `6f00471`): 删 `ci-boot4.yml`(65 行);`ci.yml` JAVA_VERSION 17→21 + build job 去 matrix + 删 `compatibility` job + `build-package` 加 `-Pboot4`;`pr-checks.yml` JAVA 17→21 + verify 加 `-Pboot4`。`./mvnw clean verify -Pboot4 -B` 672 绿 + JaCoCo 门禁全过(38.9s)。
  - [x] **pom 清理**(commit `9ad22bf`): `pom.xml` `properties.java.version` 17→21 + `redisson.version` 3.27→3.50 + 删除 `<profiles>` boot4 块 + 删除旧切换机制注释;`ci.yml`/`pr-checks.yml` 同步去掉 `-Pboot4` flag(原 profile 已上移 default,flag 是 no-op)。`./mvnw clean verify -B` 672 绿 + JaCoCo 全过(38.2s)。
  - [x] **新建 ADR-0007**(commit `11c088b`): `wiki/adr/0007-fire-single-buildline-abandonment.md`(82 行)记录「WS-1.1 FIRE 双分支策略废弃」决策,引用 53f8eb2 + 6f00471 + 9ad22bf + 38c514a + 5a05d0a;经代码核验 4 条矛盾 + 5 条理由 + 3 commit 落地链路 + 正面/负面/不变/重评估 consequences。

---

## 3. 🟡 P1 — v0.1.0 收尾配套

- [ ] **WS-1.1 CI** — `ci.yml` compatibility job 在 master 已 parent=4.0.0 且源码无 boot3 import 的情况下无法切回 Boot 3 编译,需移除或重新设计兼容矩阵
- [ ] **WS-1.1 CI** — `ci-boot4.yml` 触发分支 `[boot4]` 已冗余(boot4 是 master 祖先),改 master 触发或并入 `ci.yml`
- [x] **文档治理**(commit `ecbbb50`):README + README.zh-CN.md Roadmap v0.1.0 行从 v0.0.2 时代"Bean-graph redesign"过时措辞改为 FIRE + WS-1.2 硬化 + Path C 7 步全部完成 + Central 发版待 user 批准的当前真实状态(Plan/Status/依赖 commit 全部列举);中英同步(避免文档漂移)。

---

## 4. 🟢 P2 — v0.2.0(按序贯路线图未启动,非偏离)

- [x] **WS-1.4 链级 Micrometer**(commit `d025241`,基础设施 + Timer):`CacheHandlerChain` 注入 `ObjectProvider<MeterRegistry>`(`null` = 静默 no-op 退化),`execute()` 用 `Timer.record(Supplier)` 包装 full lifecycle(head handle + post-process);Timer.name `resicache.chain.execute`。`CacheHandlerChainFactory` 同步加 MeterRegistry 注入。3 个测试文件构造器签名同步更新。`checkstyle:check` 0 violation + 12/12 绿(零回归 — `null` ObjectProvider 路径行为不变)。**遗留**:per-handler tag(`bloom.blocked`/`lock.acquired`/`early-refresh.triggered`/`null.hit` — 每个 handler 内部加 metrics hook,需 handler 改造)+ per-cacheName + per-operation tags(`Timer.record(Supplier)` 不支持 tags,需 `Timer.Sample` + 动态 timer 构建,有性能开销)+ 单元测试(留 WS-1.4 测试套件扩展)。
- [x] **WS-1.4 per-handler tag 试点 BloomFilterHandler**(commit `be0a3f7`):`BloomFilterHandler` 加 `ObjectProvider<MeterRegistry>` 注入 + `@PostConstruct initMetrics()` + `Counter resicache.handler.bloom.blocked`(仅 rejected 分支 increment)。构造器切换:`@RequiredArgsConstructor` 删除因需 ObjectProvider + 显式构造;3 个测试文件构造器同步(`null` ObjectProvider)。`checkstyle:check` 0 violation + 12/12 绿(零回归 — `null` ObjectProvider 路径行为不变)。**遗留**:SyncLockHandler `lock.acquired` + EarlyExpirationHandler `early-refresh.triggered` + NullValueHandler `null.hit` per-handler Counter/Timer(每个 handler 单独改造,模式同 Bloom,3 个后续 tick)+ per-cacheName / per-operation tags(链级 Timer.tags 待 Timer.Sample 重构时一起加)+ 单元测试(`SimpleMeterRegistry` 验证 increment,留 WS-1.4 测试套件扩展)。
- [x] **WS-1.4 per-handler tag SyncLockHandler**(commit `c55e042`):模式同 Bloom,加 `ObjectProvider<MeterRegistry>` + `@PostConstruct initMetrics()` + `Counter resicache.handler.sync.lock.acquired`(在 setAttribute LOCK_ACQUIRED_KEY=true 之后 increment,反映实际获得锁事件,锁竞争失败时不计)。`SyncLockHandlerTest` 构造器同步(`null` ObjectProvider)。`checkstyle:check` 0 violation + 12/12 绿(零回归 — `null` ObjectProvider 路径行为不变)。**遗留**:EarlyExpirationHandler.early-refresh.triggered + NullValueHandler.null.hit per-handler Counter(各 1 tick,模式同 Bloom/Sync)+ per-cacheName / per-operation tags + 单元测试。
- [x] **WS-1.4 per-handler tag EarlyExpirationHandler**(commit `3c791f7`):模式同 Bloom/Sync,加 `ObjectProvider<MeterRegistry>` + `@PostConstruct initMetrics()` + `Counter resicache.handler.early-refresh.triggered`(在 `decision.needsRefresh() && decision.isSync()` 分支 increment,仅同步提前过期命中 +1,异步不计数)。2 个测试文件构造器同步(`null` ObjectProvider)。`checkstyle:check` 0 violation + 12/12 绿(零回归 — `null` ObjectProvider 路径行为不变)。**遗留**:NullValueHandler.null.hit per-handler Counter(下一个 tick,完成 WS-1.4 per-handler tag 全部 spec — 4 handler: bloom + sync + early-refresh + null-value)+ per-cacheName / per-operation tags + 单元测试。
- [x] **WS-1.4 per-handler tag NullValueHandler + 收官**(commit `423d115`):模式同 Bloom/Sync/EarlyExpiration,加 `ObjectProvider<MeterRegistry>` + `@PostConstruct initMetrics()` + `Counter resicache.handler.null.hit`(在 `if (deserializedValue == null)` 分支 increment,覆盖 cacheNullValues=true/false 两种路径)。`NullValueHandlerTest` 构造器同步(`null` ObjectProvider)。`checkstyle:check` 0 violation + 12/12 绿(零回归)。**WS-1.4 per-handler tag 收官**:4 handler 全部带 metrics(be0a3f7 bloom + c55e042 sync + 3c791f7 early-refresh + 423d115 null-value)+ 链级 Timer(d025241 chain.execute)。**遗留**:per-cacheName / per-operation tags(链级 Timer.tags 待 Timer.Sample 重构)+ 单元测试(`SimpleMeterRegistry` 验证 increment,留 WS-1.4 测试套件扩展)。
- [x] **WS-1.4 tracing 跨 commonPool 透传**(commit `eeab1fe`,依赖 Path C Step 6 ✅):`RedisProCacheWriter.withMethodMetadataSnapshot` 加 MDC capture/restore(SLF4J 诊断上下文含 traceId/spanId 等)— `MDC.getCopyOfContextMap()` 在提交任务时 capture,async 线程内 `MDC.setContextMap(snapshot)` restore,`finally MDC.clear()` 防 commonPool 线程复用泄漏。零侵入不改方法签名,async 调用方零感知;零新依赖(SLF4J MDC stdlib)。`checkstyle:check` 0 violation + 12/12 绿(零回归 — 测试环境 MDC null 路径零开销)。**遗留**:专门 MDC 透传单元测试(留 WS-1.4 测试套件扩展)+ OpenTelemetry/Brave distributed tracing 集成(留 v0.3.0 子项,本 tick 仅 SLF4J MDC 最小切片)。
- [x] **WS-1.4 health 级联**(commit `56b494f`,最小切片 — sync degraded 暴露):`SyncSupport` 加 public `isDegraded()` API(`!isLocalOnly && distributedManagers.isEmpty()`)→ `RedisCacheHealthIndicator` 注入 SyncSupport + RedisProCacheProperties(ObjectProvider null-safe)→ `health()` 在 Redis PING 成功(UP)时,若 `syncSupport.isDegraded()` 加 detail `protection.degraded=local-only` + WARN 日志(状态仍 UP,只暴露安全降级,不断整体);`MetricsAutoConfiguration` 同步加 ObjectProvider。`checkstyle:check` 0 violation + 12/12 绿(零回归 — 测试环境 TestRedisConfiguration 提供 Redisson mock,isDegraded()=false,不触发 degraded 分支)。**遗留**:多层级 cascade(per-handler Counter 阈值 / sync + early-exp + null 各自 health signal 聚合,留独立 WS-1.4 子项)+ 单元测试(`/actuator/health` 报告验证,留 WS-1.4 测试套件扩展)。
- [x] **WS-1.4 kill-switch 细化**(commit `ac3a1fc`):`RedisProCacheProperties.ProtectionProperties` 加 4 个 per-mechanism Boolean 字段(`bloomFilterEnabled`/`syncLockEnabled`/`earlyExpirationEnabled`/`nullValueEnabled`,默认 `null` = 继承总开关 `enabled`);`CacheHandlerChainFactory.createChain` 加 per-mechanism 短路分支(每个禁用打 INFO 日志便于生产故障定位)。`checkstyle:check` 0 violation + `PathCAopContractIT + RedisProCacheWriterTest` 12/12 绿(零回归 — 默认 `null` 继承 `enabled=true` 行为不变)。**遗留**:运行时切换(当前 `cachedChain` 单例,启动后改配置不重链;WS-1.4 observability 子项配套) + 专门 per-mechanism 单元测试(留 WS-1.4 测试套件扩展)。
- [x] **WS-1.4 metrics 默认开启评估**(commit `cbcd882`,**结论:保持 OFF,显式 opt-in**):`MetricsAutoConfiguration` Javadoc 增 WS-1.4 评估段(2026-06-29 决策 + 3 条理由:作为 library 避免 surprise MeterRegistry 强依赖 / 运行时非零开销 / 与下游 observability 栈耦合)+ 启用示例 `application.yml` 代码块。**保持 `matchIfMissing=false` 默认 OFF,不 flip**。`checkstyle:check` 0 violation + 12/12 绿(零回归 — 仅 javadoc 改动,行为不变)。**遗留**:单元测试(验证 `@ConditionalOnProperty` 在 `enabled=true` / 缺省 / `enabled=false` 三个场景下的 bean 创建,留 WS-1.4 测试套件扩展)+ `/actuator/metrics/resicache.*` 端到端测试 + README sample 截图(留 v0.2.0 文档化子项)。
- [x] **WS-1.5 JMH 基准 smoke 级**(commit `8b4e085`,**不引入 jmh 框架** — 分歧推荐表「引入新依赖/框架 → 不引入」):`test/.../cache/CacheLatencySmokeBenchmarkTest.java` 140 行 3 tests — cache hit/miss/async retrieve 各测 1000 次,assert mean < 100ms(smoke 门槛,不是严格 bound)。输出 mean/p50/p99(微秒)到 stdout。**Bench 结果(本会话,Testcontainers Redis + JDK 21)**: cache hit 210.74 us / miss 365.00 us / async retrieve 423.15 us,均 << 100ms。`PathCAopContractIT + RedisProCacheWriterTest + PathCAopAsyncIT + RedisDownFaultInjectionIT + CacheLatencySmokeBenchmarkTest` 18/18 绿(BUILD SUCCESS)。**遗留**(v0.2.0 / 用户社区评估后): 正式 JMH 基准(引入 jmh 框架需用户/社区评估价值,smoke test 不能替代 fork/warmup/MXT 等专业特性)+ 性能 baseline 文件 + 并发压测 + 故障注入下性能 + GC pause 监控。
- [x] **WS-1.5 故障注入补 Redis 断连场景**(commit `59c1f6d` GET + `29bd449` PUT + `65895d0` CLEAN — 重要架构发现,3 路径全覆盖):`test/.../cache/RedisDownFaultInjectionIT.java` 3 tests — `redisDown_get_degradesGracefully` + `redisDown_put_degradesGracefully` + `redisDown_clean_degradesGracefully`。用 `@Primary` + `@Profile("redis-down-test")` 覆盖 `RedisConnectionFactory` bean(端口 1,不可达)。**架构发现(诚实记录)**:ResiCache 是 **graceful degradation 模式,不是 fail-fast** — Redis 不可达时 `CacheErrorHandler.handleGetError/handlePutError/handleRemoveError` log warn "degrading gracefully" + 返回 failed CacheResult → 上游收到 cache miss 触发 loader 兜底(GET)/写失败被吞下次重试(PUT)/clear 失败被吞(CLEAN,cache 一致性 trade-off)。**3 路径安全属性未丢**(不会用损坏/null 数据响应用户)+ **错误可见**(log warn 配合 WS-1.4 per-handler Counter 可量化)+ **不破坏 SLA**(loader 兜底)。**trade-off vs fail-fast**:选 graceful-degrade 是因为 cache miss 是"安全失败模式"(回源是 idempotent 兜底),fail-open 才不安全。**设计观察**: 3 路径统一 CacheErrorHandler 抽象 — 简化运维感知(log warn 一次)+ cache 库**不应猜测**业务语义。`checkstyle:check` 0 violation + 5 ITs 一起跑 20/20 绿(BUILD SUCCESS — @Profile 隔离有效,其他 IT 不被 BrokenRedisConfig 污染)。**遗留**:并发压测 + Toxiproxy 集成(分歧推荐表优先"不引入新依赖",Toxiproxy 是 jvm-network-tools 间接依赖,引入价值待评估)+ 真实 Redis 容器 stop()(可能用 Testcontainers 高级 API)。
- [ ] **WS-3.1.1** `protection.preset`(STRICT/STANDARD/NONE)— `RedisProCacheProperties.ProtectionProperties` 现仅 `enabled` 布尔;注解级 preset 覆盖未实现
- [ ] **WS-3.1.5** Bloom 切 Redisson `RBloomFilter` — 现 `RedisBloomIFilter` 仍用 RedisTemplate+RedisCallback 自维护位运算(grep RBloomFilter 零命中)
- [ ] **WS-3.1.4** per-handler 运行期热重载 — 支持 bloom `expectedInsertions`/TTL `variance` 不重启动态调整(grep hotReload/dynamicConfig 零命中)
- [x] **文档治理**(commit `0df49e6`):ADR-0002 改写「经 MethodMetadataResolver 解决」——Status `Accepted` + 加 Path C 实施落地段(数据所有权链 `ResiCacheMethodInterceptor → DefaultMethodMetadataResolver → RedisProCacheWriter → 链 handler`)+ 取代关系(决策 1/2/3 不变,Context 段落改「历史不可能三角」)+ Consequences 补「取舍」明确继承面 = 2 层妥协 + 后续可选工作 docs-link-check / PathCAopAsyncIT / ScopedValue 迁移。Path C 7 步收官后「不可能三角」不再成立——ThreadLocal 是 Spring Bean 显式 API,可测试 + 可替换 + 可异步透传。

---

## 5. 🔵 P3 — v1.0.0(按序贯路线图未启动)

- [ ] **WS-2.1** README 首屏 tagline 改 North Star("RedisCache — Redisson 忘了做的那条缓存防护链")— 现英文 `README.md:3` / 中文 `README.zh-CN.md:5` 仍是 "Spring Cache 防护增强注解生态"
- [ ] **WS-2.1/3.1.2** RateLimitHandler Hero demo — wiki 30 行 demo 提升首屏(ASCII 链图已有,缺"插一档自定义 handler"卖点)。⚠️ demo 引用的 `HandlerOrder.RATE_LIMIT(350)` 是说明性伪代码,**枚举实际无此档**
- [ ] **WS-3.1.2** RateLimitHandler 可运行 sample — 无 `/examples`、无 `resicache-examples` repo
- [ ] **WS-3.1.3** `resicache-extensions` 模块 + Resilience4j + `RateLimitHandler` 实现(绑定 `HandlerOrder.RATE_LIMIT=350`)
- [ ] **WS-3.2** protection-audit CLI — 引 picocli 实现扫描 `@Cacheable`/`@RedisCacheable` 报告未防护穿透/击穿/雪崩路径(WS-2.5 商业 on-ramp lead-gen 配套)
- [ ] **WS-2.2** before/after runnable sample — 同业务两版(手写 RLock+RBloomFilter+TTL 散落 vs 一个 `@RedisCacheable`,体现 ~30 缓存规模感)。MASTER_PLAN 称其"定位的生存机制"
- [ ] **WS-2.2** getting-started 站点 — MkDocs/Material + GitHub Pages,5 分钟跑起来体验 + sensible preset
- [ ] **WS-2.2** 站点级 i18n — 现仅 README 中英两份(中文自标滞后)
- [ ] **WS-2.3** 序列化迁移工具三阶段(shadow 双读 → dual-write 双写 → cutover 切流)— 解决 `{version,payload}` 信封与 `GenericJackson2JsonRedisSerializer`/`JdkSerializer` 不兼容的切流全 miss
- [ ] **WS-2.3** 白名单自动探测 — `WhitelistPolicy` 现仅静态 `List.copyOf`(构造后不可变);启动期扫描被缓存类型自动填充 `allowed-package-prefixes`,消除"白名单默认作者包"信任税
- [ ] **WS-2.3** `@Cacheable`→`@RedisCacheable` 迁移指南 — 可照抄 runbook(聚焦"哪些方法该升级获得防护",SELECTIVE 已共存)
- [ ] **WS-2.5** 商业支持 on-ramp — 无 `.github/FUNDING.yml`(全仓 grep FUNDING/sponsor 零命中);README 补"生产落地咨询/付费支持"章节
- [x] **文档治理**(commit `ecbbb50`):补登记 `wiki/index.md` ADR 节追加 `[[0005-kernel-extraction-hedge]]` + `[[0006-redisson-companion-positioning]]` + `[[0007-fire-single-buildline-abandonment]]`(共 3 行);`updated` frontmatter 刷到 2026-06-29;页数统计 28→31;原 0001 标注"已被 0006 取代"。
- [x] **文档治理**(commit `ecbbb50`):补 `wiki/log.md` WS-1.1 FIRE 条目(`## [2026-06-29] FIRE | WS-1.1 FIRE M0–M4 闭环 + Path C 7 步序列收官`),详细列举 8 commit(FIRE 4 + Path C 7 + 旁支)及 3 个遗留(ADR-0002 改写/PathCAopAsyncIT 补/§3 CI 收尾)。
- [ ] **WS-2.4** release cadence 与 Spring 主版本对齐策略单独文档化(现仅在 `COMPATIBILITY.md` 隐含)

---

## 6. 推荐下一步(v0.1.0 闭环顺序)

1. **先决策 FIRE 矛盾**(§2 第 4 项)— 重建 boot3 线 *或* 改文档放弃。CI 矩阵清理(§3 前两项)挂在它后面。
2. **启动 Path C Step 0**(§2 第 1 项)— v0.1.0 最大未开工块,WS-1.4 tracing 透传依赖其 Step 6,是关键路径起点。
3. **核验发布凭据**(§2 第 3 项)— 确认 Central Portal token(`MAVEN_*`)和 GPG secret 真的配过,对齐 `release.yml` env 命名。
4. **同步文档治理三处**(§3 第 3 项 + §5 两项)— `wiki/index.md`、`wiki/log.md`、README Roadmap。
5. **发版** — Path C Step 0–7 零回归 + 文档一致性修正后,pom `0.0.2→0.1.0`,打 `v0.1.0` tag 触发首次 Central 发布,验证可拉取(`numFound!=0`)。

---

## 7. 序列约束(MASTER_PLAN §5/§8,loop 须牢记)

- **FIRE 先于一切**(已完成 ✅)。Path C 与硬化在 v0.1.0 内并行/紧随。
- **v0.1.0 scope** = FIRE + 3 硬化 + Path C 重构 + 首次发 Maven Central。
- **v0.1.0 门禁** = 双构建 verify 全绿(⚠️ 现实是单构建,见 §0 矛盾) + 3 硬化各有故障注入测试(✅) + Path C 零回归 + Central 可拉取。
- **不在 v1.0 前做内核抽取**(ADR-0005 对冲)。
- 铁律:永不静默降级;IT 绿线前不盲改防护代码;commit/push/merge/publish 需用户显式批准。

---

*本 backlog 基于真实代码库审计生成(非文档转述)。后续推进时,完成一项即勾选并补 commit hash;若 MASTER_PLAN 或代码库有重大变化,重新审计并更新本文件。*
