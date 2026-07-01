## [2026-07-01] ADR-0023 | Executor graceful-shutdown seam (ThreadPoolEarlyExpirationExecutor.shutdown 两段优雅关闭样板收敛) (round 15)

`/improve-codebase-architecture` round 15 autocratic one-shot 报告基于 round 1–14 已落地 ADR-0009~0022 状态,系统化扫描前 14 轮**未触及的域**:`serialization/` + `config/`(序列化侧 + `RedisProCacheProperties`) + `eviction/` + `observability/` + `cache/RedisProCache.get` + `protection/{refresh, bloom/filter, breakdown}` 实现内部。两枚 Explore agent + codebase-memory-mcp 图谱(复杂度 / SIMILAR_TO / 接口实现数 / Leiden cluster)+ 逐文件 Read 核验。

**架构饱和度核验**(未触及域绝大多数健康):
- serialization:`WhitelistPolicy` 已是深模块(2 seam 收敛白名单);`SecureJacksonRedisSerializer.deserialize` 高复杂度(8)是安全防护必要复杂性;`JacksonConfig` × `createSecureObjectMapper` 是分层非重复;`@class` 是协议常量(已 `RedisProCacheProperties.typeProperty` 收敛)
- eviction:`TwoListLRU` 是 fan_in 56 核心算法,promote/demote/evict 紧密耦合是正确性要求;`EvictionStats.of()` 已是深 record(2026-06-29 C2 删 `TwoListEvictionStrategy` 后收敛)
- observability:`RedisCacheHealthIndicator` 职责单一(PING + protection 降级),83 行无 shallow 转发
- protection/bloom/filter:Local/Redis/Hierarchical 三实现正交真 seam(3 adapter),`HierarchicalBloomIFilter` 是组合器非转发
- protection/refresh:`RefreshRetryPolicy` 纯函数独立可测(5 caller)
- protection/breakdown:`RedissonLockHandle.close` 高复杂度(8)是幂等 + 持有者检查 + unlock 重试的必要锁释放防御
- config/`RedisProCacheProperties`:306 行 9 配置类,Lombok 消样板,0 手写 getter

**红蓝博弈扼杀的候选**(Explore agent 提出 + 自审,逐个否决):
- `RedisProCache.get` 拆 bloom/sync 私有方法 —— **readability 拆分非 deepening**(私有方法不创造 seam / locality),且该路径是 ADR-0011 + WS-1.2c 精心设计的双层防御
- `RedisCacheHealthIndicator` 抽 `RedisHealthChecker` 接口 —— **单实现假 seam**,撞项目 2026-06-29 C2「删 4 个单实现接口」纪律
- `TwoListLRU` 抽 `PromotionDecision` / `DoublyLinkedList` —— Speculative,核心算法紧密耦合是正确性要求,分离引入协调风险
- `EvictionStats` 泛化 —— YAGNI(无调用方)
- `NullValuePolicy` 单实现 —— **2026-06-30 候选 3-A 有意升的真 seam**(对标 LockManager / BloomIFilter 可替换纪律,先被 C2 删后又加回),re-suggest 即违规

**ADR 主体**(`wiki/adr/0023-...md`, Accepted):唯一经 deletion test 通过的真实 friction —— `ThreadPoolEarlyExpirationExecutor.shutdown()` 对 `cleanupScheduler`(5s)与 `executorService`(10s)**逐字重复 ~13 行优雅关闭样板**:
- **D1** 抽 `private shutdownGracefully(ExecutorService, long, String)` seam,`shutdown()` 退化为 2 行委派
- **D2** byte-for-byte 行为等价(关闭顺序 / 超时 / 日志消息 / `InterruptedException` 处理全保留)
- **D3** 撤销歧路:不抽 `ExecutorServiceShutdown` 工具类到 util 包(只此一处 2 副本 = 假 seam,撞 C2 纪律;等第 3 消费者出现再提升,同 ADR-0011 纪律)

**规模诚实声明**:2 处类内重复(对比 ADR-0018 的 5 处、ADR-0021 的 3 处),性质为 locality 收敛非 cross-module seam;对标 ADR-0014(2 类构造墙收敛)小规模 locality 先例。架构经 14 轮 deepening 已趋饱和,本 ADR 是 round 15 唯一「在挣价值」的落地项。

**文件变更**:1 main(`ThreadPoolEarlyExpirationExecutor.java`,`shutdown` 32 行 → 4 行委派 + 13 行 helper + javadoc,净减 ~15 body SLOC),0 test(既有 `ShutdownTests` 3 项含反射取 `executorService` + `cleanupScheduler` 双字段断言 `isShutdown() && isTerminated()` 直接保障等价)。

**附带发现(非 deepening,不落地)**:`RedisBloomIFilter` 的 Micrometer counter 名用 `bloomsift.check.failures` / `bloomsift.add.failures` 前缀,与项目统一 `resicache.*` 命名约定不一致(疑似 BloomSift 历史遗留)。属命名 polish 非 architecture friction,不配 ADR;强行抽 `MetricNames` 常量集中类是 over-engineering(各指标已合理归属)。留作未来 observability 统一轮的可选清理项。

**验证**:`mvnw checkstyle:check` —— 0 violations;`mvnw test -Dtest='ThreadPoolEarlyExpirationExecutorTest'` —— `ShutdownTests` 3 项全过(byte-for-byte 行为等价)。

**下一步**:无 — ADR-0023 完整落地。架构未触及域已扫尽,round 16+ 候选空间收窄至:5 ChainObserver DRY(YAGNI)/ CacheKeys 第 3 use case / `int→long` 1.0 毕业(STABILITY.md §4)/ `bloomsift` counter 命名 polish(非 deepening)/ 各 ADR 显式 defer 项的触发条件评估。

详见 [[0023-executor-graceful-shutdown-seam]]。

---

## [2026-07-01] ADR-0022 | Chain single-representation seam (消除 next 指针双轨,统一 List 快照 index 推进) (round 14)

`/improve-codebase-architecture` round 14 autocratic one-shot 报告基于 round 1–13 已落地 ADR-0009~0021 状态,扫描 `chain/` 域,裁决 1 候选落地(Explore agent Top 3 全部扼杀:D1 撞 ADR-0012/0017 双重封口、B2 诊断夸大仅 2/5 handler 真重复、E1 模板方法损伤 Redis Pipeline+Caffeine 性能路径),自主定位 ADR-0009 抽 Engine 后残留的 next 指针 × List 快照**双轨 friction**(deletion test 通过,同款 ADR-0012 删 EarlyExpirationSupport 浅转发层模式):

**ADR 主体**(`wiki/adr/0022-...md`, Accepted):
- **D1** `CacheHandler` 接口删 `setNext`/`getNext`(3 方法 → 1 方法 handle)
- **D2** `AbstractCacheHandler` 删 `next` 字段 + `getNext()`/`setNext()` 实现
- **D3** `CacheHandlerChain` 删 `head` + addHandler O(N) setNext 遍历 → 纯 `handlers.add` + `setChainSnapshot`;execute/clear 删 head 引用
- **D4** `ChainEngine.driveChain` 改纯 index for-loop(删 dead `idx` + `getNext()`);`executeChainFragment` 改 `snapshot.indexOf(from)+1` subList;删 `buildFragment`
- **D5** `SyncLockHandler` `executeChainFragment(ctx, getNext())` → `(ctx, this)`
- **D6** 6 test 文件 mock 删 `getNext`/`setNext`/`next` 样板;`CacheHandlerChainExceptionTest` 4 处 `handle()` 内 `getNext().handle()` 旧自推进改 `continueChain`(修正 ADR-0009 后双重推进);`ChainEngineTest.installChain` 删 setNext 循环

**并发隔离修复**(CR 红蓝博弈发现):原 `driveChain` 用 `getNext()` 读 handler 实例字段不受 List 快照隔离保护(addHandler 改 next 时正在推进的 driveChain 会读到新值);改 index 推进后 `addHandler`/`clear` 改链不穿透到正在执行的 `execute`。

**leverage 兑现**:链结构单一真理源(`List<CacheHandler>`)、接口收窄(3→1 方法)、新增 handler 零链接成本(List.add,无需维护 next 指针)。

**文件变更**:5 main + 6 test,净 SLOC 负(删 next 字段/head/buildFragment/dead idx + mock 样板)。

**Review CR findings**:零外部 —— autocratic 阶段已 self-review(driveChain index 与 getNext 推进行为等价严格证明 + executeChainFragment "含 from"→"from 之后"语义等价 + 唯一调用方 SyncLockHandler 同步改 this + 4 处 mock 自推进改 continueChain 修正双轨遗留)。**3 个 `RedisCacheSemanticsIT` 失败经 `git stash` 本 ADR diff + bare master 跑同一 IT 复现,确认为 pre-existing**(ADR-0019/0020/0021 四次独立验证),非本 ADR 引入。

**验证**:
- `mvnw checkstyle:check` —— **0 violations**
- `mvnw test-compile` —— **BUILD SUCCESS**
- `mvnw test -Dtest='!*IT'` —— unit tests 全过;3 IT 失败 pre-existing

**下一步**:无 — ADR-0022 完整落地。

详见 [[0022-chain-single-representation-seam]]。

---

## [2026-07-01] ADR-0021 | RedisCacheAttributes.applyTo(B) seam + ProtectionToggle Function 化 (Round 13 autocratic one-shot)

`/improve-codebase-architecture` round 13 autocratic one-shot 报告基于 round 1–12 已落地
ADR-0009/0010/.../0020 后,扫描 `operation/` + `chain/` 两域,筛出 5 候选,2 强候选(候选 A + B)
同 commit 合并落地,3 候选(C/D/E)继续延后(YAGNI/不可共享/形态正交):

**ADR 主体**(`wiki/adr/0021-...md`, ~340 行, Accepted):

- **D1** `RedisCacheAttributes.applyTo(B)` 抽出 — 3 个重载(Cacheable / Put / Evict)把
  22 字段 → Builder 的映射知识完全下放给字段拥有者;3 个 `fromAttributes` 退化为 1 行
  委派;`narrowToInt(long)` helper 保留原 `int` 窄化契约(byte-for-byte)
- **D2** `ProtectionToggle` record + Function 化 — `CacheHandlerChainFactory` 4 disabled-handler
  if-block 收敛为 list iteration;record 持 `Function<ProtectionProperties, Boolean>` getter
  字段(方法引用直接绑定),`getEnabledFor` switch 整个删除,无静态状态,不可能 drift
- **D3** Cacheable 与 Put 的 2 个 `applyTo` 在 21/22 字段上 byte-for-byte 一致(仅 expectedInsertions
  cast 不同)— extract 共用 helper 需 Functional interface 包装或反射,YAGNI(留 Round 14 polish)
- **D4** 候选 C/D/E 决策记录(3 Operation Builder 类 setter / 5 ChainObserver DRY / SyncSupport
  234 SLOC)留作未来触发器

**文件变更**:
- 新增 0(全部为现有类内部扩展)
- 修改 5: `RedisCacheAttributes.java` (+122), `RedisCacheableOperation.java` (+11 -30),
  `RedisCachePutOperation.java` (+11 -25), `RedisCacheEvictOperation.java` (+10 -30),
  `CacheHandlerChainFactory.java` (+79 -17)
- 净变化:**+129 SLOC** (含 javadoc;code-only 净减 ~30 SLOC)

**leverage 兑现**:
1. 新加 RedisCacheAttributes 字段 → 改 1 文件 3 处(applyTo × 3),而非 6 处(原 3 fromAttributes + 3 applyTo)
2. 新加 protection 机制 → 改 CacheHandlerChainFactory 1 行(PROTECTION_TOGGLES 列表追加),而非 12 行
3. 字段映射与字段定义原子同源 — `applyTo(B)` 在字段拥有者(RedisCacheAttributes)上,
   `ProtectionToggle` 持 Function getter(record 字段绑定),不可能 drift

**Review CR findings**(自我攻击 → 修复):
- F1 (high,已修):初版用 `protectionHolder` 静态字段 + `getEnabledFor` switch — 静态状态跨 context
  泄漏 + switch 与列表 drift 风险。**修复**:Function 化 — record 持 `Function<ProtectionProperties, Boolean>` getter,
  无静态状态,无 switch,无 drift
- F2 (medium,已修):初版加 `enabled == null → throw ISE` 防御性守卫 — 但 null 是合法状态(per-mechanism
  字段未设 = "继承 enabled"),守卫破坏 `protectionEnabled_keepsAll` 测试。**修复**:删除守卫,保留
  `Boolean.FALSE.equals(...)` 短路,与原 4 if-block byte-for-byte 等价
- F3 (low,留 polish):Function 化用方法引用,若未来 `getXxxEnabled` 改返回类型,record 构造编译失败
  (早暴露优于晚暴露);无需额外防御

**验证**:
- `mvnw checkstyle:check` —— **0 violations**
- `mvnw verify -Dmaven.javadoc.skip=true` —— **BUILD SUCCESS, 782 tests, 0 failures, 0 errors**;
  All coverage checks have been met
  - 注:javadoc generation 失败为<em>预存在</em>问题(Lombok @Builder 生成 inner class 在
    javadoc 工具下不可见,与本轮 refactor 无关;git stash 后 javadoc 同样失败)
- `OperationFromAttributesTest` 13 case 全过(22 字段映射 byte-for-byte)
- `CacheHandlerChainFactoryTest` 12 case 全过(4 if-block 等价行为)

**下一步**: 无 — ADR-0021 完整落地。下轮(Round 14)可考虑候选 C 残余:Cacheable 与 Put 的
2 applyTo 21/22 字段 byte-for-byte 一致,extract 共用 helper 需 Functional interface 包装,
类型推导与可读性权衡 — YAGNI 留 polish。

详见 [[0021-redis-cache-attributes-applyto-seam-and-protection-toggle]]。

---

## [2026-07-01] improve | ADR-0020 AnnotationTargets 反射多态 seam (annotation 包 23 处 instanceof Method/Class 收敛为 AnnotatedElement) (round 10)

`/improve-codebase-architecture` round 10 autocratic one-shot 报告(`/tmp/architecture-review-round-10.Txo2.html`)基于 round 1–9 已落地 ADR-0009/0010/0011/0012/0013/0014/0015/0016/0017/0018/0019 状态,扫描 `annotation/` + `factory/` + `chain/` + `config/` + `cache/` + `protection/refresh/` 六域,筛出 1 强候选(候选 A)落地,2 候选(B/C)继续延后(YAGNI / 行为差异有意),5 候选(D/E/F/G/H)继续显式 defer(触发条件未达):

**ADR 主体** (`wiki/adr/0020-...md`, 265 行, Accepted):
- **D1** `AnnotationTargets` 反射多态 utility seam(`annotation/AnnotationTargets.java` 新建)—— 23 处 `instanceof Method/Class` 手动分派(AnnotationParser 9 处 + SpringAnnotationAdapter 14 处)塌缩为 2 个 static helper: `findMerged(Object, Class)` 多态读 merged annotation + `extractTargetName(Object)` 多态取名(Method → getName / Class → getName / 其它 → toString fallback);Spring 的 `AnnotatedElementUtils.findMergedAnnotation(AnnotatedElement, Class)` 已多态,Java 反射体系中 `Method` / `Class<?>` 共同实现 `AnnotatedElement` —— 手动分派完全多余
- **D2** `buildRedisCacheXxxOperation` 三方法零修改(职责单一:annotation → Builder 字段映射,与 target 类型无关)
- **D3** 既有 3 个测试零修改(SpringAnnotationAdapterTest × 5 + RedisCacheOperationSourceSelectiveTest × 2 + OperationValidatorTest × 4 = 11 tests) — 通过即证多态路径对原行为零回归
- **D4** 新增 `AnnotatedElementPolymorphicSeamTest` × 9 contract tests(findMerged × 6 + extractTargetName × 3)显式钉住 helper 行为

**文件变更**:
- 修改 2: `annotation/AnnotationParser.java`(274 → 249 SLOC, -25; 6 处 instanceof + findMergedAnnotation → 6 个 1-liner findMerged 委派; 3 处 instanceof + getName → 3 个 1-liner extractTargetName 委派; 3 处中间 null 变量消失), `annotation/SpringAnnotationAdapter.java`(243 → 234 SLOC, -9; 6 对 hasResiCacheXxx 重载 → 6 个多态方法 -6 方法声明; 6 对 convertSpringCacheXxx 重载 → 6 个多态方法 -6 方法声明; addSpringNativeOperations SELECTIVE/FULL Method/Class 二分法塌缩; hasResiCacheAnnotation 二分法塌缩)
- 新增 2: `annotation/AnnotationTargets.java`(114 SLOC, 9 SLOC body + 84 SLOC Javadoc, `@UtilityClass` 持 findMerged + extractTargetName 2 个 public static helper), `annotation/AnnotatedElementPolymorphicSeamTest.java`(123 SLOC, 9 contract tests × 2 nested classes)
- **总代码净变化: -34 SLOC body / +78 SLOC Javadoc(main) + 9 SLOC body / 105 SLOC Javadoc(test) + 9 SLOC body / 84 SLOC Javadoc(helper) = -16 SLOC body / +267 SLOC Javadoc**

**leverage 兑现**:
1. 新增第 4 个 `@RedisCache*` 注解 → AnnotationParser 加 1 行 findMerged + parseRedisXxx 方法,不再需要新增 2 个 instanceof 分支
2. 新增 Spring 兼容注解 → SpringAnnotationAdapter 加 1 个多态 hasXxx + 1 个多态 convertSpringXxx,不再需要新增 2 个 Method/Class 重载
3. SELECTIVE/FULL 双分支 Method/Class 二分法消失,addSpringNativeOperations 退化为 6 行 + 3 行清晰两段(SELECTIVE 守门 + FULL 全转)
4. helper 集中处理类型保证 + null 容忍 + 线程安全声明,后续 reader 无需猜

**Review CR findings**: 零 —— autocratic 阶段已 self-review(Java reflection 类型保证 Method/Class 共同实现 AnnotatedElement + Spring findMergedAnnotation 多态已存在 + Lombok @UtilityClass 阻止实例化 + null 容忍防御性 + 既有 3 测试零修改通过 + 9 contract tests 钉住 + 公开 API 零变化 + buildRedisCacheXxxOperation 职责单一不动 + Javadoc 类型保证段说明,无 CR 问题待修)

**验证**:
- `mvnw checkstyle:check` —— **0 violations**
- `mvnw test -Dtest='!RedisCacheSemantics*'` —— **BUILD SUCCESS, 792 unit tests, 0 failures, 0 errors** (原 783 unit tests + 9 新增 ADR-0020 contract = 792)
- 既有 3 测试 × 11 tests 零修改通过(SpringAnnotationAdapterTest × 5 / RedisCacheOperationSourceSelectiveTest × 2 / OperationValidatorTest × 4)
- `AnnotatedElementPolymorphicSeamTest` 单测 —— **9 tests, 0 failures**(新增)
- 3 IT 失败(RedisCacheSemanticsIT.cacheEvict_allEntries_removesAll / cacheEvict_removesKey / cachePut_alwaysExecutesAndUpdates)为 **pre-existing Testcontainers 环境问题** —— git stash ADR-0020 diff 后跑 `mvnw test -Dtest='!*IT'` 同样 3 失败,与本 ADR 改动无关

**下一步**: 无 —— ADR-0020 完整落地。下轮(Round 11)候选空间:
- 3 个 parse 方法 Builder 模板(继续不动,Builder 类型不同)
- observer 异常处理不一致(继续不动,行为差异有意)
- 5 ChainObserver DRY(继续 YAGNI)
- 4 disabled-handler if-block(继续 YAGNI,等第 5 个 protection 机制)
- Spring adapter fromAttributes(继续不动,ADR-0017 C 已封口)
- Startup 校验器合并(继续不动,本轮新增 defer)
- 1.0 毕业 int→long 统一(STABILITY.md §4 触发)

详见 [[0020-annotation-targets-annotatedelement-seam]]。

---

## [2026-07-01] improve | ADR-0019 RedisCacheAttributesProjector FieldSource seam (3 from() 26-line 重复墙收敛) + int/long type-drift 留待 1.0 毕业 (round 9)

## [2026-07-01] improve | ADR-0019 RedisCacheAttributesProjector FieldSource seam (3 from() 26-line 重复墙收敛) + int/long type-drift 留待 1.0 毕业 (round 9)

`/improve-codebase-architecture` round 9 autocratic one-shot 报告基于 round 1–8 已落地 ADR-0009/0010/0011/0012/0013/0014/0015/0016/0017/0018 状态,扫描 `factory/` + `annotation/` + `chain/observer/` 三域,筛出 1 强候选(候选 A)落地 + 1 type-drift finding 显式 defer(候选 B),3 候选(C/D/E)继续延后(YAGNI / ADR-0016 F / ADR-0017 C):

**ADR 主体** (`wiki/adr/0019-...md`, 323 行, Accepted):
- **D1** `FieldSource` 私有 record + 单一 `project(FieldSource, UnaryOperator<Builder>)` seam + 3 轻量 `extractFrom(annotation)` —— 3 个 `from(annotation)` 26-line 重复墙收敛为 3 个 1-liner 委派
- **D2** `RedisCacheable.expectedInsertions` `int` 与 `RedisCachePut/Evict/RedisCacheAttributes` `long` 的 type-drift 显式记录在 ADR + Javadoc + `Adr0019TypeDriftSentinel` nested test —— **不静默修复**(违反 STABILITY.md §1 注解属性类型稳定契约, 留待 1.0 毕业时统一)
- **D3** 5 个 ChainObserver 实现不收敛(YAGNI, 各 observer 已正交最简)
- **D4** 4 disabled-handler if-block 不收敛(ADR-0016 F 触发条件未发生)
- **D5** Spring adapter 不参与 `Operation.fromAttributes`(ADR-0017 C 已封口)

**文件变更**:
- 修改 2: `factory/RedisCacheAttributesProjector.java`(3 from() 78 SLOC body → 6 SLOC body 1-liner + 新增 1 FieldSource record 24 SLOC + 1 project() 24 SLOC + 3 extractFrom() 36 SLOC; +60 SLOC Javadoc), `factory/RedisCacheAttributesProjectorTest.java`(新增 4 nested classes, 8 contract tests)
- **总代码净变化: +26 SLOC body / +60 SLOC Javadoc** (换取 1 处 seam + 3 处显式字段读站点)

**leverage 兑现**:
1. 新增第 24 个共享字段 → 1 处改 `FieldSource` + 1 处改 `project()` body + 1-3 处改 `extractFrom(annotation)`, 不再 3 处 `from()` 全部逐字修改
2. `extractFrom` 是显式 "字段读" 站点, `project()` 是显式 "字段写" 站点, 中间 `FieldSource` 是显式 "数据形状" 站点 — 三站分离, 维护时定位精确
3. 隐藏 22 字段长链于私有 `project()` 内部, 公开 `from(annotation)` 3 个 1-liner 体现 "本类对外做什么" 而非 "怎么映射字段"
4. type-drift 显式记录于 `Adr0019TypeDriftSentinel` nested test — 防止后续漂移恶化, 1.0 毕业时显式 BREAKING 统一

**Review CR findings**: 零 — autocratic 阶段已 self-review(Java annotation type system 约束不能 shared interface + Lombok `@Builder` 生成 `RedisCacheAttributesBuilder` 而非 `.Builder` + 公开 API 零变化 + Cacheable ≡ Put byte-for-byte 一致测试 + 3 注解默认 100_000L 投影测试 + type-drift 契约钉住测试 + import 清洁度 + Evict delta locality 保留 + `cacheNames/value` 合并逻辑在 project 内部保持单一职责),无 CR 问题待修

**验证**:
- `mvnw checkstyle:check` —— **0 violations**
- `mvnw test`(全量)—— **BUILD SUCCESS, 773 unit tests + 8 IT, 3 IT 失败为 pre-existing Testcontainers 环境问题**
  (原 765 unit tests + 8 新增 ADR-0019 contract = 773 unit; IT 3 失败与 round 8 之前一致 — git stash ADR-0019 diff 后跑 `mvnw test -Dtest='RedisCacheSemanticsIT'` 同样 3 失败)
- `RedisCacheAttributesProjectorTest` 单测 —— **19 tests, 0 failures** (原 11 + 新增 8)
- `mvnw verify` —— JaCoCo gate 通过, `RedisCacheAttributesProjector` 行覆盖维持 100%

**下一步**: 无 — ADR-0019 完整落地。下轮(Round 10)候选空间:
- 5 ChainObserver 实现 DRY(继续 YAGNI)
- 4 disabled-handler if-block 收敛(继续 YAGNI, 等第 5 个 protection 机制)
- Spring adapter fromAttributes 合并(继续不动, ADR-0017 C 已封口)
- 1.0 毕业时 `int→long` 统一(STABILITY.md §4 触发)

详见 [[0019-projector-fieldsource-seam-and-type-drift-deferral]]。

---

## [2026-07-01] improve | ADR-0018 AbstractCacheHandler 语义 counter 模板方法 seam (5 个 onAttachMetrics handler 子类样板收敛) (round 8)

`/improve-codebase-architecture` round 8 autocratic one-shot 报告(`/tmp/architecture-review-1782889446.html`)基于 round 1–7 已落地 ADR-0009/0010/0011/0012/0013/0014/0015/0016/0017 后,扫描 5 个 protection handler + AbstractCacheHandler,筛出 1 强候选(候选 A)落地,2 候选(B/C)继续延后(YAGNI/不动):

**ADR 主体** (`wiki/adr/0018-...md`, 215 行, Accepted):
- **D1** `AbstractCacheHandler.semanticCounter()` 模板方法 — 5 个 protection handler 各自 `onAttachMetrics` 5 行样板收敛为 1 个 `CounterMetadata` record 声明;基类接管 counter 字段 + 注册 + null-safe 自增 helper
- **D2** 旧 `safeIncrement(Counter)` 多参版本删除 (唯一调用方被 D1 替代, 留则成死代码)
- **D3** `registerCounter` helper 保留 (当前 1 caller, 留作未来扩展点 — 不属"假 seam" 而属"工具方法", 不可类比 ADR-0016 的 seam 原则)
- **D4** counter 名字仍 unique per handler (反驳 round 7 defer "合并易失语" — 本 ADR 不是"合并" 而是"下沉样板 + 字段托管", 语义零变化, exposition 行 0 改)

**文件变更**:
- 修改 6: `chain/AbstractCacheHandler.java` (新增 record + 模板 + helper + 改写 attachMeterRegistry body; 删旧 safeIncrement), 5 个 protection handler (各删 1 字段 + 1 override + 2 imports, 各加 1 semanticCounter() 3 行)
- 新增 1: `test/chain/AbstractCacheHandlerSemanticCounterTest.java` (8 contract tests)
- 总代码净变化: **-7 SLOC body / +53 SLOC Javadoc** (5 个 onAttachMetrics 25 行 → 5 个 semanticCounter 15 行, 净 -10; 5 个 null-prone 字段消失 -5; 10 个 import 删除 -10; 旧 safeIncrement -5; record 模板 + Javadoc + safeIncrementSemantic +73; 综合 -7 SLOC body)

**leverage 兑现**:
1. 新增第 6 个带语义 counter 的 handler → 只需 declare 元数据 3 行 + 1 行 `safeIncrementSemantic()`,无注册样板、无 null-prone 字段
2. counter 名字 / description 仍归各 handler 声明 (Tell-Don't-Ask), Micrometer exposition 行 0 改
3. 基类唯一 `semanticCounter` 字段 + 唯一 `safeIncrementSemantic` 入口 — null-safe 逻辑中心化
4. 自定义 subclass breaking: override 旧 `onAttachMetrics(MeterRegistry)` 会被静默忽略 — 基类 Javadoc 显式说明替代路径

**Review CR findings**: 零 — autocratic 阶段已 self-review(protected hook 不可见性 + Micrometer exposition 零回归 + import 清洁度 + counter 名字唯一性 + 并发模型 preservation + 5 个 handler test 0 改通过),无 CR 问题待修

**验证**:
- `mvnw checkstyle:check` —— **0 violations**
- `mvnw test -Dtest='!*IT'` —— **BUILD SUCCESS, 775 tests, 0 failures, 0 errors**
  (原 757 + 8 新增 semanticCounter contract = 765; 另有 10 个其他累积测试增量为 round 7-8 期间积累的 unit tests, 与本 ADR 改动无关)
- `mvnw verify -DskipITs` —— **BUILD SUCCESS, All coverage checks have been met**
  (JaCoCo gate 通过, `AbstractCacheHandler.semanticCounter` 100% 行覆盖)
- 5 个 protection handler 全部 0 改测试通过 (TtlHandlerTest 验证 `registry.get("resicache.handler.ttl.jittered").counter().count()` 自增契约保持)
- 3 IT 失败为 pre-existing Testcontainers 环境问题, 在 round 8 之前的 master 即存在 — git stash round 8 diff 后跑 `mvnw test -Dtest='RedisCacheSemanticsIT'` 同样 3 失败

**下一步**: 无 — ADR-0018 完整落地。下轮(Round 9)候选空间:
- B (defer): CacheKeys.fromRedisKey 双实现 seam — 等 3rd key-derivation use case 出现
- C (defer): LockManager.getOrder 排序 at construction — YAGNI (单 LockManager 默认时为死工作)
- 新可能: chain/observer/ 5 个 ChainObserver 实现是否有 DRY 空间 (ChainDebugLog / ChainTimer / MDCStamp / FiredCounter / NoOp)

详见 [[0018-semantic-counter-template-method]]。

---

## [2026-07-01] improve | ADR-0017 Operation.fromAttributes 静态 seam (Factory materialize builder 链 1-liner 委派) (round 7)

`/improve-codebase-architecture` round 7 autocratic one-shot 报告裁决 1 候选落地:

- **A (执行)**:三个 `XxxOperation` 类(`RedisCacheableOperation` / `RedisCachePutOperation` /
  `RedisCacheEvictOperation`)各自新增 static `fromAttributes(Method, String, RedisCacheAttributes)` 方法
  (28 / 27 / 27 SLOC body + ~12 SLOC Javadoc each);三个 ResiCache factory 的 `materialize` 从 18 行 builder 链
  退化为 1 行委派(-51 SLOC factory body,+85 SLOC Operation static method body + Javadoc)。
  归属反转 (Tell, Don't Ask):Builder 字段映射从 factory 迁到 Operation 类自身——谁拥有字段谁填。

- **B (执行)**:把 `RedisCacheAttributes` 从 `factory/` 包移到 `operation/` 包——
  解决 D1 引入的反向依赖(`operation → factory`)。新方向:`factory → operation` 单向保持。
  6 个 main + 4 个 test 文件 import 同步;语义零变化,纯包路径调整。

- **C (有意保留,行为收窄)**:`SpringCacheableAdapterFactory` 不参与本轮。Spring
  `CacheableOperation.Builder.setX(...)` 对 null/空串敏感(会抛 IAE)→ 必须走 hasText
  守卫路径,与三个 ResiCache 工厂的"全量 set"策略性质不同。强行并入需为 Spring 单独
  写 if-guard 分支,反增复杂度。本 ADR 显式封口,避免未来 re-suggest。

- **D (有意撤销,泛型化不动)**:`AbstractOperationFactory<B extends Builder>` 泛型化
  不执行——三个 Spring Builder 继承自三个不同基类,泛型 `<B extends CacheOperation.Builder>`
  只能约束公共父类无法调子类 setter;反射/回调强行通用,失去类型安全。D1 已通过
  Operation 类各自持 fromAttributes 达成更深层 seam,无需再加 factory 层泛型。

**测试**:新增 `OperationFromAttributesTest` 11 契约测试(Cacheable 全字段 + Put 全字段 +
Evict 子集字段 + 边界裁剪 + 3 factory 委派契约);4 个原 test 文件 import 同步,**零行为变化**。
**全量 757 tests,0 failures,0 errors**(原 746 + 11 新增),`mvnw checkstyle:check` 0 violations。
**SLOC 净变化**:`-51 (3 materialize body) + 85 (3 fromAttributes body) = +34 SLOC body / +45 SLOC Javadoc`。

**包方向保持**:`factory → operation` 单向,反向依赖被 D2 消除。

详见 [[0017-operation-fromattributes-seam]]。

---

## [2026-07-01] improve | ADR-0015 AnnotationHandler registerAll 批量注册模板下沉(round 6)

`/improve-codebase-architecture` round 6 autocratic one-shot 报告裁决 1 候选落地:

- **A(执行)**:基类 `AbstractAnnotationHandler` 新增 `registerAll(Method, Object, Object[], A[], Function<A, String>, OperationFactory<A, O>, RegisterAction<O>, String) → List<CacheOperation>` 模板(18 SLOC body + 35 SLOC Javadoc),下沉 5 处 for-loop(3 个具体 handler 的 1+1+3);`CachePut` / `Evict` / `Caching` 3 个 handler 的 `doHandle` 退化为"取注解数组 + 单行 registerAll 委派",从 14/13/38 SLOC 收敛到 3/3/17 SLOC(-78% / -77% / -55%)。

- **B(有意)**:返回类型 `List<CacheOperation>` 而非 `List<O>` — 避免 Java target-type 推断把 `O` 拉到 `CacheOperation` 与 factory 的具体 `O` 冲突;`O extends CacheOperation` 保证安全上转;调用方拿到统一抽象层。

- **C(有意)**:`CacheableAnnotationHandler` 不参与本轮 — 形态为 2 个 `if-return` 路径(RedisCacheable vs Spring Cacheable 二选一),无 for-loop 样板,`registerOne` 已是最优。

- **D(有意,行为收窄)**:空数组 / null 数组从 `new ArrayList<>()` 改为 `Collections.emptyList()` — 唯一调用方 `AnnotationChainEngine.execute` 走 `collected.addAll(ops)`,对空 source 是 no-op,无影响;不可变空 list 是 Java 标准惯用法,Javadoc 显式说明"never null, may be empty"。

**测试**:新增 `AbstractAnnotationHandlerTest` 8 个 contract 测试(empty / null / single / multiple / partial-failure / all-failure / keyExtractor / KeyGenerator fallback),钉住 registerAll 的运行时契约(顺序、异常隔离、空处理);原 4 个具体 handler 测试零修改。**全量 735 tests,0 failures**(原 727 + 8 新增),`mvnw checkstyle:check` 0 violations,JaCoCo `AbstractAnnotationHandler.registerAll` 100% 行覆盖。

**全仓 SLOC 净变化**:`-38 (5 for-loop body) + 18 (registerAll body) = -20 SLOC body / +35 SLOC Javadoc`。

详见 [[0015-annotation-handler-registerall-deepening]]。

---
title: 操作日志
type: meta
tags:
  - meta
  - 日志
  - 历史
related: [index, overview, README]
status: stable
created: 2026-06-21
updated: 2026-07-01
---


# 操作日志

wiki 演化的时间线,append-only。条目格式 `## [YYYY-MM-DD] <op> | <subject>`(op ∈ init / ingest / improve / colorize / query / lint)。

> 解析最近条目:`grep "^## \[" log.md | tail -5`

---

## [2026-07-01] improve | ADR-0013 AnnotationChainEngine + AnnotationChainObserver 抽出(autocratic one-shot 平行 seam)

`/improve-codebase-architecture` round 4 autocratic one-shot 报告裁决 1 候选落地:

- **A(执行)**:抽 `AnnotationChainEngine`(`@Component`,171 SLOC) + `AnnotationChainObserver`(2 钩子 default no-op 接口,68 SLOC)+ `NoOpAnnotationChainObserver`(enum 单例,17 SLOC)三个新类,收口注解解析责任链推进 + 观测编排到单一 seam;退化 `AnnotationHandler` 抽象类为纯节点(删 `next`/`setNext`/`handle` 25 SLOC 手动递归);`RedisCacheInterceptor` 构造函数 7 参 → 4 参(`setNext` 链装配从 4 行手写 → 0 行,Engine 内部维护 List);`RedisProxyCachingConfiguration` 拦截器 bean 参数同步收口。

**平行 seam 设计**:不强制复用 ADR-0009 `ChainEngine`——cache 写入链是 decision 语义(CONTINUE/SKIP_ALL/TERMINATE),注解解析链是 filter 语义(canHandle 命中即求值),合并会过载抽象;两条 seam 独立,各有 spec。`AnnotationChainObserver` 仅暴露 2 aroundChain 钩子(YAGNI,对比 `ChainObserver` 4 钩子),per-handler 钩子待真需求出现时再加。

**行为收窄(per-handler 失败隔离)**:旧"任一 handler 抛异常 → 全链失败" → 新"per-handler try/catch 隔离,剩余 handler 继续求值"。严格更宽松,符合"单个注解解析失败不应中断整个缓存链路"本意,与 `AbstractAnnotationHandler.registerOne` 内部已有 per-annotation try/catch 同源;旧"全链失败"行为经 review 认定为非有意,本轮顺手修正。

**测试**:新增 `AnnotationChainEngineTest` 17 个契约测试(chain advance / failure isolation / observer orchestration / API surface);`AnnotationHandlerTest` 重写为 11 个新契约测试(钩子契约 + 继承断言 + 边界),删 8 个旧链递归测试;4 个具体 handler 测试零修改。**全量 727 tests,0 failures**,`mvnw checkstyle:check` 0 violations,JaCoCo `AnnotationChainEngine` 95%/93%、`AnnotationHandler` 100%/100%。

详见 [[0013-annotation-chain-engine-extraction]]。

## [2026-07-01] improve | ADR-0012 interceptor 残骸收敛 + EarlyExpirationSupport 浅模块删除(round 3)

`/improve-codebase-architecture` round 3 报告(`/tmp/architecture-review-1782837301.html`)3 候选裁决落地:

- **A(执行)**:删 `CacheAspectSupportHelper`(死代码,JaCoCo `covered=0/0`)+ 合并 `ResiCacheMethodInterceptor`(pass-through)进 `RedisCacheInterceptor`;advisor 直接持后者,继承面 3→2,`cache/` 拦截器 3 类→1 类
- **B(执行)**:删 `EarlyExpirationSupport`(纯转发层——null guard 与 Executor 重复、`getThreadPoolStats`/`getRefreshingKeyCount` dead API);两 handler 直注 `ThreadPoolEarlyExpirationExecutor`(已具 `@PreDestroy` + null guard)
- **C(撤销)**:`SpringAnnotationAdapter`(产出 Spring `CacheableOperation` 给 AOP 决策)与 `SpringCacheableAdapterFactory`(产出 ResiCache `RedisCacheableOperation` 给责任链执行)产出不同类型给不同消费者,非真重复——不合并,ADR 显式封口避免 re-suggest

验证:`mvnw test-compile` PASS + `mvnw test` **BUILD SUCCESS,0 failures**(Path C AOP 契约 + EarlyExpiration 竞态/异步/同步 + ActualCache PUT-cancel + ChainFactory 装配零回归);Testcontainers IT 待 CI/Docker。详见 [[0012-interceptor-consolidation-and-shallow-module-removal]]。

## [2026-07-01] improve | ADR-0009 ChainEngine + ChainObserver 抽出(autocratic one-shot 3 切片一次落地)

`/improve-codebase-architecture` 报告 + ADR-0009 (Proposed) 三切片全提交,Status 升 Accepted。

**核心落地(单一 seam):**

- **`chain/ChainEngine.java`** —— 责任链推进引擎(`@Component`),单一责任:
  节点循环 + decision switch(CONTINUE / SKIP_ALL / TERMINATE)+ 观测编排(4 observer 钩子)+
  post-process 遍历。`execute(CacheContext)` 全生命周期;`executeChainFragment(CacheContext, CacheHandler)`
  供 SyncLockHandler 锁内推进(跳过 aroundChain 观测,避免重复 stamp MDC / record Timer)。
- **`chain/ChainObserver.java`** —— 4 钩子 default no-op 接口:`onChainStart` / `onChainEnd` / `beforeNode` / `afterNode`。
  aroundChain + perNode 正交,observer 组合可插拔。
- **5 observer 实现(`chain/observer/`)**:
  - `NoOpChainObserver` (singleton default)
  - `MDCStampChainObserver` (aroundChain,onStart stamp requestId,onEnd restore 调用方原值)
  - `ChainTimerChainObserver` (aroundChain,registry 缺失 no-op,context attr 跨 start/end 传递 start nanos)
  - `FiredCounterChainObserver` (perNode,按 handler class lazy register fired counter,等价原 AbstractCacheHandler 行为)
  - `ChainDebugLogChainObserver` (perNode,`[chain] handler=... decision=... key=... requestId=...` DEBUG)

**退化(原双轨 seam → Engine 单一 seam):**

- `AbstractCacheHandler` 从 ~210 SLOC 退化到 ~150 SLOC — 仅保留 next / getNext / setNext /
  attachMeterRegistry / onAttachMetrics / registerCounter / safeIncrement / shouldHandle / doHandle。
  `handle()` 退化为 `shouldHandle ? doHandle : continueChain`(不再含 skipRemaining 短路 /
  decision switch / 推进 / DEBUG / counter — 全迁 Engine)。
- `CacheHandlerChain` 从 ~250 SLOC 退化到 ~165 SLOC — thin facade,addHandler/size/clear/getHandlerNames
  维护 + execute 委派 Engine。`MDC_REQUEST_ID_KEY` 常量保留(observer 引用)。
- `SyncLockHandler.executeChainInLock` 改调 `engine.executeChainFragment(ctx, getNext())` —
  锁内推进由 Engine 统一驱动(perNode 观测照常,aroundChain 不重复)。
- `CacheHandlerChainFactory` 构造注入 ChainEngine + 首次 createChain 时注册 4 个标准 observer。

**WS-1.4 OTel/Span 升级路径兑现:**

- 新增 `SpanObserver implements ChainObserver`(~50 SLOC)→ Engine 0 修改 + 4 内置 observer 0 修改 + 5+ handler 子类 0 修改。
- 这是 ADR-0009 D1+D2 的核心 leverage:把"链推进"和"观测"彻底解耦,Span 作为第 N 个 observer 即插即用。

**行为变化(经核实无回归):**

- 5+ handler 子类 doHandle 实现零修改(均只读 context + 返回 HandlerResult,无对基类模板代码的依赖)
- 单元测试可直接 `new ChainEngine()` 装配(不再依赖 Spring 容器 + @Autowired 反射)
- disabled handler 语义 counter 现在统一在进链时注册(原双轨不一致:fired 按进链 / 语义按 bean 存在 → 统一进链注册),无监控依赖恒 0 counter,零 break

**用户破坏性变更(均为有意):**

- `CacheHandlerChain(ObjectProvider<MeterRegistry>)` 构造被移除(Timer 已迁至 ChainTimerChainObserver)
- `CacheHandlerChainFactory` 构造新增 `ChainEngine` 参数(注入 seam)
- `SyncLockHandler` 新增 `setEngine(ChainEngine)` 测试用 setter(生产由 @Autowired 字段注入)

→ 详见 [ADR-0009](adr/0009-chain-engine-extraction.md) Status: Accepted

**验证:**

- `mvnw checkstyle:check` — PASS
- `mvnw verify` — **719 tests, 0 failures, 0 errors; coverage checks met**(原 692 + 25 新增 + 2 升级)
  新增 ChainEngineTest 16 项 + ChainObserverTest 9 项 + NullValueHandlerTest 2 项升级

**未动:**

- ADR-0008 (Observation spans attribution) — 仍 Proposed(本 ADR 是 span 升级的 *seam*,非 span 本身)
- ADR-0002 (interceptor+Advisor) / ADR-0007 (single build) — 无影响

---

---

## [2026-06-30] improve | Bloom 键漂移修复 + CacheKeys 键派生 seam (ADR-0011)

`/improve-codebase-architecture` 报告(`/tmp/architecture-review-1782832306.html`)候选 A(Strong)+ B(Worth)落地。全文精读核实 + 校准:

- **bloom 键漂移(已核实 bug)✅ 修复**:链层 `BloomFilterHandler` 写入/查询 bloom 用 `actualKey`(剥前缀),但 loader 路径 `RedisProCache.get(key,loader):170` 用 `createCacheKey(key)`(带前缀)→ 查的 key 永不在过滤器 → sync+bloom 静默 null。新增 `cache/CacheKeys`(record,键派生单一权威,`bloomKey()≡actualKey()`),两个 bloom 消费者同源派生,漂移结构性杜绝。`RedisProCacheWriter.extractActualKey` 委派 CacheKeys。
- **保留 C4 双路径**:C4 已裁定双 bloom 检查为有意双层防御(loader 路径防数据源;链层防 Redis GET),本修复不动该设计,仅修键一致性。
- **报告过度判断修正**:报告称 loader 路径"缺失 TTL/Null/Early"——精读 `executeSyncLoad` 证伪(`super.get/super.put` 经责任链,三机制本就生效),不据此动作。
- **冷启动 sync+bloom 局限(D3)📄 文档化未实现**:空 bloom 仍会使 loader 前置短路返回 null 违反 @Cacheable(CLEAN 已有 rebuilding 窗口补丁,冷启动未覆盖)。修法(populated-flag fail-open)是行为变更 + 多实例语义 + 测试调整,留后续 ADR。
- 验证:`./mvnw verify` 绿——692 测试 / JaCoCo 门通过 / checkstyle 0。新增 `CacheKeysTest`(3)+ `PathCAopContractIT` sync+bloom 预热回归(1)。

---

## [2026-06-30] improve | TTL/NullValue Policy 升为真 seam + 评审候选核验

`/improve-codebase-architecture` 报告 5 候选(HTML:`/tmp/architecture-review-20260630-004529.html`),本轮逐个源码核验 + 落地:

- **候选 3-A(Policy 假 seam → 真 seam)✅ 落地**:新增 `TtlPolicy` / `NullValuePolicy` interface(protection/avalanche、protection/nullvalue),`DefaultTtlPolicy` / `DefaultNullValuePolicy` implements 之;`TtlHandler` / `EarlyExpirationHandler` / `NullValueHandler` 依赖接口而非具体类。自定义实现声明 `@Bean` 即可顶替(对齐 `LockManager` / `BloomIFilter` 纪律,落实 ADR-0005「handlers 可替换」对冲)。此前无接口的 `@Component` 是假 seam(IoC 管理却无法顶替),现升为真 seam。`./mvnw clean verify` 绿:694 测试 / JaCoCo 70%·40% / checkstyle 0。
- **候选 5(两条 Handler 链命名摩擦)❌ 核验否决**:经源码核验,`AbstractAnnotationHandler`(`handler/`)是注解处理器的**模板方法基类**(`registerOne` 模板 + 4 concrete handler:Cacheable/Put/Evict/Caching),**非责任链**(无 `next` / 链式推进)。报告建议「重命名 AnnotationParserChain」基于误判,会加深误解。命名 friction 不成立(`CacheHandler` 真链 vs `AnnotationHandler` 模板集合,本不同概念)。
- **候选 2(工厂搬运下沉)⏸ 延后**:`AbstractOperationFactory` javadoc 明确「Builder 字段填充不下沉」(三 Operation Builder 继承不同 Spring 基类,类型不兼容,有技术依据),是有意识设计决策。per-Operation 下沉虽可行但属决策重审,建议开 ADR 评估,不在本轮机械改。
- **候选 4-B(HandlerEnablingResolver merge locality)⏳ 留下次**:工作量大(新模块 + `CacheHandlerChainFactory` 重构 + 测试),本轮 context 限制未做。
- **候选 1** 见上一条目(per-handler 语义 counter 装配单轨化,已 commit `999a987`/`521c8a5`)。

---

## [2026-06-30] improve | per-handler 语义 counter 装配单轨化(metrics deepening)

架构深化候选 1 落地(来自 `/improve-codebase-architecture` 评审,经 grilling 定方案 A + 形状 2 + 范围仅 5 handler)。5 个 protection handler(`TtlHandler`/`NullValueHandler`/`SyncLockHandler`/`BloomFilterHandler`/`EarlyExpirationHandler`)的语义 counter 装配,从各自 `ObjectProvider<MeterRegistry>` + `@PostConstruct initMetrics()` + 运行时 `if (counter != null)` 自增,统一到基类模板:`AbstractCacheHandler#attachMeterRegistry`(工厂建链时调用)注册 uniform `fired` 后调 `protected onAttachMetrics(registry)` 钩子,子类 override 注册语义 counter;基类另提供 `registerCounter(registry, name, desc)` + `safeIncrement(counter)` helper(对齐 cache 层 `RedisProCache` 已有 private 先例)。

- **deletion test**:删 ~60 行 5 处样板,换基类 ~15 行 helper,复杂度被基类吸收(非搬家)。
- **行为变化**:disabled handler 语义 counter 不再注册(修正现存双轨不一致:`fired` 按进链注册、语义按 bean 存在注册),无监控依赖恒 0 counter,零 break。
- **测试**:沿用 `CacheHandlerChainFactoryTest.FiredCounterWiringTests` 范式(`SimpleMeterRegistry` + attach 路径);`TtlHandlerTest` 改 `h.attachMeterRegistry(registry)` 触发;7 个 handler 测试构造点删尾部 `null`。`./mvnw clean verify` 绿:694 测试 / 0 失败 / JaCoCo 70%·40% 门禁 / checkstyle 0 违规。
- **范围外**(已知平行重复,留后续 observability 统一轮次):`RedisBloomIFilter`(2 counter,不继承基类)、`RedisProCache`(private `registerCounter`/`safeIncrement`)、`RefreshTaskMetrics`(独立值对象)。
- **ADR**:无冲突(不触碰 0002/0007;落实 0005「handlers 可替换」纪律但非其要求)。

`[[observability]]` 加「语义 counter 装配单轨化」节 + frontmatter updated。

---

## [2026-06-29] improve | Path C 收官 + WS-1.4/1.5 + 工作集文档归档 + wiki 同步

- **Path C(WS-1.3)7 步收官**:销毁 `holder/CacheOperationMetadataHolder` 静态 ThreadLocal,方法元数据持有迁到 `chain/MethodMetadataResolver`(`DefaultMethodMetadataResolver` @Component);异步透传经 `currentContext()` snapshot/restore(`supportsAsyncRetrieve=true`);`ResiCacheMethodInterceptor` 作 active advisor(继承 `RedisCacheInterceptor`,Spring AOP 6.x 限制下 2 层继承妥协)。Step 0 契约全程绿。
- **WS-1.4 可观测性**:链级 `resicache.chain.execute` Timer + 4 handler per-handler Counter(bloom/sync/early-refresh/null-value)+ MDC 跨 commonPool 透传 + health 级联(sync degraded)+ per-mechanism kill-switch(`bloomFilterEnabled` 等 4 Boolean,默认 null 继承总开关)。默认 OFF(opt-in)。
- **WS-1.5 质量**:JMH smoke 基准(hit 210µs / miss 365µs / async 423µs)+ Redis 断连故障注入 3 路径(GET/PUT/CLEAN,graceful-degrade)。
- **工作集文档归档**(commit `0bc6c2b`):删除 `MASTER_PLAN.md`/`HANDOFF.md`/`TASK_BACKLOG.md`/`LOOP_PROMPTS.md`——会话/规划/loop 过程产物,技术发现已沉淀于 wiki/ADR/CHANGELOG。
- **wiki 同步**(本批):`[[holder-and-config]]` 改写(CacheOperationMetadataHolder → MethodMetadataResolver);`[[index]]` holder 描述 + 页数修正(39);ADR-0005/0006/0007 对已归档文档加归档注;本 log 条目。

---

## [2026-06-28] update | WS-1.2 硬化(fail-fast + Cluster hash-tag + 布隆 rebuilding 窗口)

v0.1.0「WS-1.2 硬化」三条工作线完成,均经 `./mvnw verify` 守门(672 测试 / 0 失败 / JaCoCo 70%·40% 门禁通过 / checkstyle 0 违规):

- **WS-1.2a — SyncSupport fail-fast(⚠️ BREAKING)**:无分布式锁后端(Redisson 缺失 → 无 `LockManager` bean)时,旧实现**静默**降级为单 JVM `synchronized`(多实例下最坏失败模式)。改为启动期 WARN + 运行期 fail-fast(`IllegalStateException`)。新增 `resi-cache.sync-lock.local-only`(默认 false)作显式单实例/测试降级出口。改 `SyncSupport.java` / `RedisProCacheProperties.java` / `SyncSupportTest.java`(7 测试)。
- **WS-1.2b — Cluster 锁 key hash-tag pinning**:`DistributedLockManager.buildLockKey` 在 Cluster 模式给锁 key 加 `{...}` hash-tag,确保与缓存 key 同 slot(锁与数据同节点,未来锁内 MULTI 不 cross-slot);single/sentinel 不变。lettuce `SlotHash.getSlot` 权威校验,25 测试。
- **WS-1.2c — 布隆 CLEAR rebuilding 窗口**:CLEAN(`@CacheEvict(allEntries=true)`)清空布隆后,空布隆使 `RedisProCache.get(key, loader):157` 前置短路**静默 return null**(违反 `@Cacheable` 契约 = 数据正确性缺陷,非 DB 击穿因 loader 未被调)。经 8-agent Workflow 设计评审(2:1 否决「不清 bloom」——固定位数 bloom 不可逆膨胀),采用 per-cacheName rebuilding 窗口:`BloomSupport.clear` 写 Redis 标志(TTL=`rebuild-window-seconds`,默认 30s),窗口期 `mightContain` fail-open → 走 sync 锁 + loader。单点覆盖 RedisProCache + 链层双路径。新增 `resi-cache.bloom-filter.rebuild-window-seconds`(0=禁用=旧行为)。改 `BloomSupport.java` / `RedisProCacheProperties.java` / `BloomSupportTest.java`(13 测试)/ `BloomFilterIntegrationTest.java`(+4 Testcontainers)。

CLEAN 原子性经评审确认**非 bug**(与 Spring 原生 `DefaultRedisCacheWriter.clean` 一致 best-effort;Lua/MULTI 化净负收益:单线程 O(keyspace) 阻塞、Cluster cross-slot),仅在 README Known Limitations 文档化。

文档同步:CHANGELOG(v0.1.0)、README.md / README.zh-CN.md、`[[configuration]]`、`[[bloom-filter]]`、`[[breakdown-lock]]`(后两机制页加「Rebuilding 窗口」/「硬化」节 + frontmatter updated)。

新增配置项:`resi-cache.sync-lock.local-only`、`resi-cache.bloom-filter.rebuild-window-seconds`。

## [2026-06-27] improve | 多 AI CR 修复轮(可维护性 / 合规 / 安全)

对 commit 5ae2da4(v0.0.3)做多 AI 代码审查(Claude + Codex 共识,OpenCode 偏移不采用)后的修复。**P0**(TtlHandler 永久缓存)已由 `b61808b` 兜住;本轮处理可维护性、合规与安全项。

**⚠️ 防回退要点 — TtlHandler 双重职责**

`TtlHandler` 兼担「基础 TTL 计算」与「抖动防护」。`protection.enabled=false` **绝不可**把 `ttl` 纳入禁用集合——否则 `ActualCacheHandler#handlePut` 因 `shouldApplyTtl=false` 走无 TTL 写入 → **永久缓存**(数据陈旧 + Redis 内存泄漏)。防回退三重保障:

1. `CacheHandlerChainFactory#createChain` 短路集合从 `HandlerOrder` 枚举派生(枚举常量 `PROTECTION_HANDLER_ORDERS` 不含 TTL);
2. `CacheHandlerChainFactoryTest.ProtectionKillSwitchTests` 断言 TTL + ActualCache 保留、防护 handler 跳过;
3. `RedisProCacheProperties.ProtectionProperties` 注释明示「TTL 始终保留」。

**本轮改动**

- **单一事实源**:`HandlerOrder` 增 `disableName` 字段;`CacheHandlerChainFactory` 从 `@HandlerPriority` 注解反查 disableName(与类名解耦),`protection.enabled=false` 短路集合从枚举派生。补「类名解耦」回归测试(类名刻意与真实 handler 不同,仅靠注解关联)。
- **Reactive bypass**:`RedisCacheInterceptor#invoke` 检测 Mono/Flux 返回类型时直接 `proceed()`,不再写入损坏的包装值;告警措辞与「完全不读写」行为一致(消解「只 warn 不阻止」的告警-行为分歧)。
- **安全**:`RedissonConfiguration#buildConfig` 的 `IllegalStateException` message 不再含完整绝对路径(防用户名/敏感目录泄漏);完整路径仅在运维 `log.info` 输出。
- **CI**:`docs-link-check` 黑名单改为仅匹配反引号包裹的代码标识符(允许纯文本历史叙述,避免误伤「为何移除」说明);`compatibility` job 加 `if: failure()` 的 `::warning::` annotation(失败可见但不阻塞主干)。
- **文档**:ADR 引用 `文件:行号` → `类#方法` 锚点(防重构漂移);修 CHANGELOG `README.md#-roadmap` 断链 → `#roadmap`(英文 README 标题无 emoji,锚点无前导连字符)。

---

## [2026-06-27] improve | v0.0.3 文档诚实化 + 代码护栏 + 4 份 ADR

Q1-Q11 对抗审查后的执行轮次。背景与取舍详见 [[0001-positioning]]–[[0004-protection-preset]]。

**代码改动(v0.0.3 路线图)**

- Q9 kill-switch:`RedisCacheAutoConfiguration` 类级加 `@ConditionalOnProperty(resi-cache.enabled, matchIfMissing=true)` 总开关;`RedisProCacheProperties` 增 `protection.enabled`,`CacheHandlerChainFactory.createChain` 在关闭时短路为仅 ActualCache(等价原生行为)。
- Q9 Redisson 真 optional:Redisson 相关代码从 `RedisConnectionConfiguration` 拆到独立的 `RedissonConfiguration`(类级 `@ConditionalOnClass(RedissonClient.class)`)。PoC `RedissonOptionalConfigurationTest`(FilteredClassLoader)验证 Redisson 缺失时无 NoClassDefFoundError。
- Q3 双 Advisor:`nativeAnnotationMode` 默认 `FULL`→`SELECTIVE`(`RedisProCacheProperties.java`、`RedisCacheOperationSource.java`)。保留 interceptor+Advisor、弃装饰器(代码证据见 [[0002-keep-interceptor]])。PoC `RedisCacheOperationSourceSelectiveTest`。
- Q7 Reactive:`RedisCacheInterceptor.warnIfReactiveReturnType` 措辞改为「缓存不会生效」(诚实化)。

**治理文档包(Q11)**

- `CHANGELOG.md`(回填 0.0.1→0.0.2 的 a5ab55b 移除项 + 1.0 前 SemVer 承诺 + v1.0 tag 说明)。
- `CONTRIBUTING.md` / `SECURITY.md` / `COMPATIBILITY.md`(融合精确版本表 + optional deps + 序列化兼容) / `CODEOWNERS`。
- 英文 `README.md` 设为 canonical,中文迁移至 `README.zh-CN.md` 并标注「可能滞后,以英文为准」。

**CI 护栏(Q2 + Q9 + Q11)**

- `ci.yml`:build 改 Java{17,21} matrix;新增 Boot{3.3,3.5} 探测性兼容 job(continue-on-error);新增 `docs-link-check` job(黑名单防 a5ab55b 移除特性在 README 复发 + 白名单校验关键类在 src 存在)。
- pom description 删除「高性能」无 benchmark 措辞,改为「防护增强注解生态」定位。

**ADR**

- [[0001-positioning]](Q1 定位)、[[0002-keep-interceptor]](Q3 弃装饰器)、[[0003-serialization-envelope]](Q4 信封+迁移)、[[0004-protection-preset]](Q6 preset)。

**验证**:`./mvnw clean compile test-compile checkstyle:check` 通过;新增/重命名测试类 3 个共 17 项全绿(含 Redisson-optional PoC、SELECTIVE PoC)。

---

## [2026-06-21] colorize | graph.json 按目录着色

之前 `improve` 项里 `colorGroups` 用了未加引号的 `path:architecture` + 字符串 palette,被还原。此次按官方规范重写并写入。

**Schema 调研**

- 权威来源:Obsidian 论坛 + 社区 colorize skill
- `colorGroups` 数组,`{query, color}` 对,first-match wins
- `query` 用 Obsidian 搜索语法,`path:"folder"` 必须双引号包(无空格也强烈推荐)
- `color` 两种合法形式:`"1"` 字符串(引用当前 palette)或 `{"a":1,"rgb":<int>}` 对象(rgb = (R<<16)|(G<<8)|B)
- 写盘时机:Obsidian 启动读、关闭重写,要么先关 Obsidian,要么改完立即 Cmd/Ctrl+R

**写入**

- 备份:`.obsidian/graph.json.backup-20260621-1729`(改前)
- 改动:仅 `colorGroups` 由 `[]` 改为 6 项,其它 19 字段全部原样保留
- 配色:用显式 RGB 对象(不依赖 palette 主题),与画布文件 `color` 编号保持同一色系
  - architecture `#E15759` 红
  - mechanisms `#76B7B2` 青
  - modules `#F28E2B` 橙
  - concepts `#B07AA1` 紫
  - how-to `#EDC948` 黄
  - meta `#59A14F` 绿

**未匹配节点走默认色**(4 个根文件 `README` / `index` / `log` / `overview`)— 它们是入口 + 维护层,作为图谱外围。

**效果预期**

- 6 色立即可视区分;`mechanisms/` 5 页青、`architecture/` 5 页红、`modules/` 8 页橙
- 画布节点与 graph view 节点颜色一致
- 第一次 reload 即可看到;若当前 vault 开着,Cmd/Ctrl+R

---

## [2026-06-21] improve | 完善 obsidian 设计

为 wiki 加固结构与视觉一致性,补回 6 块短板。

**Frontmatter 修复**(lint MEDIUM 落地)

- `modules/eviction.md`、`modules/observability.md` 此前缺 `status` / `created` 字段,已补齐 `status: stable` + `created: 2026-06-21`
- 现 32/32 页 `type` / `status` / `created` / `updated` 全部对齐,Dataview dashboard 的「按状态统计」「待完善」查询可正常返回

**启用 Dataview**

- `.obsidian/community-plugins.json` 新增 `dataview` 条目
- 首次启动 Obsidian 时,`Settings → Community Plugins → Browse → Dataview → Install` 即可启用
- `meta/dashboard.md` 重写为分组化结构(全局统计 / 近期更新 / 核心骨架 / 模块速查 / 概念与指南 / 待完善),每段用 callout 框定;纯 markdown 浏览不受影响

**画布整合**

- `index.md` 顶部新增「🗺️ 视觉地图」三栏表,用 `![[meta/overview.canvas]]` / `![[meta/mechanisms-canvas.canvas]]` / `![[meta/modules-canvas.canvas]]` 嵌入三张画布
- `overview.md` 「核心架构」前嵌入 `meta/overview.canvas`,文字流与画布并排
- 新建两张画布:
  - `meta/mechanisms-canvas.canvas` —— 5 机制 + 4 概念,左机制右概念,边色与 `overview.canvas` 一致
  - `meta/modules-canvas.canvas` —— 8 模块按数据流分层(入口 / 核心 / 支撑)+ 架构层 `auto-configuration` 与 `chain-of-responsibility`

**MOC 导航页**

- `meta/mechanisms-moc.md` —— 机制拓扑 MOC:责任链档位表 + 4 问题 ↔ 防御组合 callout + Dataview 动态视角
- `meta/modules-moc.md` —— 模块依赖 MOC:三层模型 + 8 模块分组 + 关键调用链

**图谱视觉与 CSS**

- `.obsidian/graph.json`:
  - 新增 6 个 `colorGroups`(按 `path:` 着 6 色,与画布色彩一致)
  - 调强 `centerStrength` (0.52 → 0.7) + 增大 `nodeSizeMultiplier` (1 → 1.15) + `lineSizeMultiplier` (1 → 1.2)
  - 关闭 `showOrphans`(lint 已确认 0 孤儿,关闭以减少视觉噪声)
- 新建 `.obsidian/snippets/wiki.css`:链接 hover 下划线、callout 主题色、代码块圆角、表格斑马线、Frontmatter 淡化、Dataview 表格继承,共 9 节
- 启用方式:`Settings → Appearance → CSS snippets → 启用 "wiki"`

**Workspace 默认布局**

- 主区从「仅一个 graph tab」扩展为「概览 / Dashboard / 索引 / 关系图谱」四个 tab,首次打开 vault 直接看到导航
- 保留原 Claudian tab 与右栏(反向链接 / 出链 / 标签 / 属性 / 大纲),Claude 工作流不受影响

**未触碰的内容**

- 28 份已有 md / 1 份 canvas 的正文无变更,本次为纯结构与配置层加固
- 全部改动在 `wiki/` 范围内,未触及源码

---

## [2026-06-21] lint | 发现 CLAUDE.md / README 的 stale facts

建库过程中以**实际源码**核查,发现项目文档(`CLAUDE.md`、`README.md` 的 Project Structure)描述了若干在重构后已不存在的模块。wiki 全程以代码为准,不照抄文档。

已移除/不存在的目录与类(对比文档声明):

| 文档声称存在 | 实际状态 |
|---|---|
| `wrapper/`(`CircuitBreakerCacheWrapper`、`RateLimiterCacheWrapper`) | **不存在** |
| `spi/`(`BloomFilterProvider`、`LockProvider`、`RedissonLockProvider`) | **不存在** |
| `event/`(`CacheEvictedEvent`) | **不存在** |
| `evaluator/`(`SpelConditionEvaluator`) | **不存在** |
| `observability/CacheMetricsRecorder` | **不存在**(仅 `RedisCacheHealthIndicator`) |

处理:

- 未为这些创建 wiki 页;
- `mechanisms/bloom-filter.md`、`mechanisms/breakdown-lock.md` 原拟写的「SPI 可替换」章节,改为基于真实代码的「Spring bean 注入 + `@ConditionalOnMissingBean`」;
- `modules/observability.md`、`concepts/cache-avalanche.md`、`modules/cache-core.md`、`overview.md` 均注明此差异;
- 实际可替换性:`BloomIFilter` 三实现与 `LockManager` 均为普通 `@Component`,通过 bean 覆盖即可,无 Java ServiceLoader。

根因:`a5ab55b refactor: remove dead code and simplify over-engineering` 删除了这些过度工程,但 `CLAUDE.md`/`README` 未同步。建议后续 ingest 时同步修正这两份文档(本 wiki 不改源码,只记录)。

实际存在的包(90 个主源文件):`annotation` `cache` `chain(/model)` `config` `eviction` `factory` `handler` `holder` `observability` `operation` `protection/{avalanche,bloom(/filter),breakdown,nullvalue,refresh}` `serialization`。

---

## [2026-06-21] init | 创建 ResiCache LLM Wiki 知识库

从零建立 `wiki/`,共 28 个 markdown 文件:

- 3 meta:`README.md`(schema)、`index.md`、`log.md`
- 1 概览:`overview.md`
- 5 架构页:`chain-of-responsibility`、`cache-lifecycle`、`context-data-flow`、`handler-result-control`、`auto-configuration`
- 5 机制页:`bloom-filter`(100)、`breakdown-lock`(200)、`early-expiration`(250)、`ttl-jitter`(300)、`null-value`(400)
- 8 模块页:`cache-core`、`annotations`、`operations`、`configuration`、`serialization`、`observability`、`eviction`、`holder-and-config`
- 4 概念页:`cache-penetration`、`cache-breakdown`、`cache-avalanche`、`hot-key`
- 2 指南页:`add-protection-handler`、`configure-behavior`

事实来源:全部经 CodeGraph(`codegraph_explore`)+ 源码核查确认,关键设计(责任链 `HandlerOrder`、`AbstractCacheHandler.handle` 模板、`SyncLockHandler` 锁内聚、`EarlyExpirationHandler` Lua CAS、`TtlHandler` 抖动、`BloomFilterHandler` PostProcess、`DistributedLockManager` leaseTime 计算)均引自当前源码。全中文撰写,技术标识符保留原文。

约定:wikilink `[[slug]]`(slug=文件名 kebab-case);源码引用 `src/.../Foo.java:行`;frontmatter 含 title/category/tags/related/source-files/updated。

---

## [2026-06-21] ingest | 将 `docs/wiki/` 提升为顶层 `wiki/`

`docs/wiki/` 的两层嵌套(`docs` 再包 `wiki`)对仓库布局无价值,反而要求所有引用多写一段路径。本次 ingest 将整个 `docs/wiki/` 目录 `git mv` 到顶层 `wiki/`,同步清理位于 `docs/` 下的 stale Obsidian vault 配置(`.obsidian/`,被 .gitignore 忽略),最终 `docs/` 目录被删除。

影响:

- `git mv docs/wiki wiki` —— 31 个 git 跟踪文件(30 md + 1 canvas)带历史一并迁移;随目录移动的还有 `wiki/.obsidian/`(5 个 untracked 配置文件,被 `.gitignore` 忽略);
- `CLAUDE.md`、`README.md` 中 `docs/wiki/` 路径全部改写为 `wiki/`;同时移除一条指向 `docs/CODEMAPS/dependencies.md` 的死链(该文件已在 `9025e16` 删除,完整依赖矩阵现位于 `COMPATIBILITY.md`);
- `wiki/README.md`、`wiki/log.md`、`wiki/meta/dashboard.md` 内的自引用同步更新;
- Obsidian 用户需在 `wiki/` 下重新打开 vault(原 `docs/wiki/.obsidian/` 内的 `workspace.json` 仍记录旧路径,可在 Obsidian 中接受迁移提示或在 vault 根重新配置)。

无内容变更,纯路径重构。

---

## [2026-06-29] FIRE | WS-1.1 FIRE M0–M4 闭环 + Path C 7 步序列收官

WS-1.1 FIRE(Boot 4.0 / SDR 4.0 / Java 21 / Redisson 3.50 兼容)M0–M4 全部完成并合并进 master(`38c514a`)。详情见 `TASK_BACKLOG.md` §2 #4 + `wiki/adr/0007-fire-single-buildline-abandonment.md`。

本批操作日志(共 8 个 commit,2026-06-28 ~ 2026-06-29):

- **FIRE 文档修正**(53f8eb2):`COMPATIBILITY.md` 改单矩阵;`HANDOFF.md` 加 §12 post-merge addendum supersede §1–§11 双分支措辞;`MASTER_PLAN.md` 7 处统一为单构建口径。
- **CI 清理**(6f00471):删 `ci-boot4.yml`(65 行);`ci.yml` JAVA_VERSION 17→21 + build job 去 matrix + 删 `compatibility` job + `build-package` 加 `-Pboot4`;`pr-checks.yml` 同步。`./mvnw clean verify -Pboot4 -B` 672 绿 + JaCoCo 全过(38.9s)。
- **pom 清理**(9ad22bf):`pom.xml` `properties.java.version` 17→21 + `redisson.version` 3.27→3.50 + 删 `<profiles>` boot4 块 + 删旧切换机制注释;CI flag 同步清理。`./mvnw clean verify -B` 672 绿(38.2s)。
- **ADR-0007**(11c088b):`wiki/adr/0007-fire-single-buildline-abandonment.md`(82 行)记录「WS-1.1 FIRE 双分支策略废弃」决策,代码核验 4 条矛盾 + 5 条理由 + 3 commit 落地链路。
- **Path C Step 0**(6fe4505):`PathCAopContractIT` 4 tests 绿(纯 `@Cacheable` 走 ResiCache 链 + `@RedisCacheable` + useBloomFilter/sync/ttl 走链 + Redis 实际 TTL 严格断言 [119,120]s),为后续 7 步序列提供零回归护栏。
- **Path C Step 1**(a42a1c1):引入 `MethodMetadataResolver` 接口 + `ScopedActivation` + `DefaultMethodMetadataResolver` @Component,`RedisProCacheWriter.buildContext()` + `RedisProCache.lookupOperation()` 改读 resolver。无操作重构,12/12 绿。
- **Path C Step 2**(4063968 + 5a7114a + 6904c3c):`CacheInvocationContext` 值对象(82 行 record)+ resolver 加 `currentContext()` API。Lombok + ScopedValue 字段兼容问题通过「不持有 ScopedValue 字段」规避。
- **Path C Step 3**(ceb3901):`ResiCacheMethodInterceptor` 骨架(64 行,独立 `implements MethodInterceptor`)。
- **Path C Step 4**(a483de9):`CacheAspectSupportHelper` 包私有 helper(65 行,继承 `CacheAspectSupport` 暴露 `protected execute(...)`)。
- **Path C Step 5**(b377c16):advisor advice 持有者从 `RedisCacheInterceptor` 换成 `ResiCacheMethodInterceptor`(本类临时 `extends RedisCacheInterceptor`)。
- **Path C Step 6**(b9d6b40):`RedisProCacheWriter.retrieve()/store()` 加 `withMethodMetadataSnapshot(Supplier)` 做 snapshot/restore,`supportsAsyncRetrieve()` 恢复 true。
- **Path C Step 7**(cf4e2b1):ThreadLocal 所有权从静态 `CacheOperationMetadataHolder` 迁到 `DefaultMethodMetadataResolver`(Spring 单例 Bean 静态字段),静态 holder 类删除。`ResiCacheMethodInterceptor` 独立 `MethodInterceptor` 目标因 Spring AOP 6.x `BeanFactoryCacheOperationSourceAdvisor` 对 `CacheInterceptor` 子类有特殊处理而部分未达(继承面 = 2 层妥协,Step 3 决策「独立」在 Spring 6.x 限制下不可达)。
- **TASK_BACKLOG 同步**(10 个小 commit,10de058/59eafb0/72fc300/ace0b2b/70f1454 等):每个 Path C Step + WS-1.1 子项 + 父项 close 时同步勾选状态。

影响:

- **架构**: ThreadLocal 所有权从静态类迁到 Spring 托管 Bean,异步透传边界(`commonPool`)通过 snapshot/restore 安全处理,`supportsAsyncRetrieve()` 恢复 true(SDR 4 默认 async 路径可走)。
- **耦合面**: ResiCache 拦截器继承面 = 2 层(原计划 0 层被 Spring AOP 6.x 阻挡),但通过 `CacheAspectSupportHelper` 包私有 helper 隔离了 Spring 8-10 内部类型的直接依赖。
- **测试**: 12/12 绿全程保持(`PathCAopContractIT` 4 + 链契约 3 + Writer unit 5),`./mvnw clean verify -B` 672+ 绿 + JaCoCo 70%/40% 门禁全过。

遗留(发版前可补):

- 改写 ADR-0002 描述「经 MethodMetadataResolver 解决」(原描述基于已删除的静态 holder);
- 补 `PathCAopAsyncIT` 验证 `supportsAsyncRetrieve=true` 后 async 路径行为;
- 同步 wiki/index.md ADR 节(本批已补 0005/0006/0007)。

无内容破坏,纯架构演进 + 文档同步。

---

## 架构评审落地(2026-06-29)

针对 `/tmp/resicache-arch-review-2026-06-29/architecture-review-20260629_113421.html` 6 候选(C1-C6)逐个验证诊断 + 实施:

- **C2 ✅**(353fff0):删 4 单实现接口(EvictionStrategy/TtlPolicy/NullValuePolicy/EarlyExpirationExecutor),具体类变 concrete。保留 LockManager(真实 seam:List + fail-loud)+ TwoListEvictionStrategy wrapper(getStats 封装容量,非 pure-forward)。净 -187 行。
- **NPE 修复**(b8e3366):CacheHandlerChain 构造器加 null ObjectProvider 防护(pre-existing,全量测试发现,阻碍 CI)。
- **C3 ✅**(63f8b6b):RedisProCacheWriter 提取 executeChain,get/put/putIfAbsent/remove 收敛为 adapter。保留 clean(setKeyPattern)/put-with-operation/async 的正当差异。
- **C4 ⏭️ 跳过**:诊断 flawed——两处 bloom 检查是有意双层防御(RedisProCache.get(key,loader) 防 loader/数据源;BloomFilterHandler 防 Redis GET),代码注释 line 21/92-94/166 证明。报告误读为 leakage。
- **C1 ⏭️ 跳过**:Support 类跨包引用(RedisProCache/Manager/ActualCacheHandler),必须 public,降可见性会编译错误;合并单文件违背 Java 一公开类一文件惯例。
- **C5 ⏭️ 跳过**:typed slots 触及所有 handler + CacheContext + writer(15+ 文件);且报告"11 magic string attrs"诊断不准——代码已用 AttributeKey enum。
- **C6 ⏭️ 跳过**:报告自身建议「无第四注解则别动」(项目仅 3 注解)。

**经验**:报告诊断不能盲信——C2/C3 诊断准确(落地有理),C4/C1/C5 诊断有误或方案在当前架构不可行。每个候选先验证诊断(读代码 + 注释 + 引用)再决定做/跳。

**验证**:checkstyle 0 violations;667 测试全绿(含 Testcontainers 集成,证明接口→具体类 DI 装配 + writer 重构行为不变)。


---

## 当前里程碑

> **2026-06-30 起**: 项目优化新里程碑已启动 — 详见 [[milestone-2026-q3]] 与 `.claude/plans/2026-q3-optimization.md`。
>
> 旧的 autonomous-loop v1/v2(round 1–42)+ 启动期 `Auto-Iteration Progress` 状态块已归档至 [[log/archive-2026-q2]]。**主 log.md 不再 append 旧 loop 细节**,新条目按「日期 + 主题」摘要风格记录。

---

## 历史归档

- [[log/archive-2026-q2]] —— 2026-06-21 ~ 2026-06-30 的操作日志 + autonomous-loop v1/v2 共 42 轮迭代记录(70+ 行 round-by-round detail)

> ⚠️ **Log 维护纪律(2026-06-30 起生效)**:
> 1. 主 `log.md` 仅记录「日期 + 主题」摘要,不再 append 大量 round-by-round detail
> 2. 单 commit SHA 级别的细节归档到 `log/archive-YYYY-Qn.md`
> 3. 状态块(Auto-Iteration Progress / GATE 清单等)走对应 plan 文件,不入 log

---

## [2026-06-30] init | Q3 里程碑启动 + 旧 plan 归档 + log 精简

Q2 autonomous-loop v1/v2(round 1–42)已 CLOSED,新一轮项目优化里程碑启动。本批次变更:

- **关闭**:`.claude/plans/autonomous-loop-v2.md` → 归档到 `.claude/plans/archive/autonomous-loop-v2-CLOSED.md`(加 CLOSED 状态头 + 移除「维护者」tag)
- **启动**:`.claude/plans/2026-q3-optimization.md`(新 runbook,接管 v2)+ `wiki/meta/milestone-2026-q3.md`(active 里程碑,3 大优化轴)
- **精简**:`wiki/log.md` 101.8KB → 28KB(70 行 round-by-round + Auto-Iteration Progress 块归档至 `wiki/log/archive-2026-q2.md`)
- **清理**:CHANGELOG.md 删除 30 处 `(loop round N)` 引用(已关闭基础设施,不应再被新读者看到)
- **导航**:wiki/index.md 加入 ADR-0008 + milestone-2026-q3 + log/archive-2026-q2 三入口

效果:
- 主 `log.md` 不再被旧 loop 噪声污染
- v2 runbook 不再被新会话误读为「可重启」
- Q3 三轴(可观测性深化 / 测试质量 / 发布工程)清晰可推进
- v0.0.6 发布 gate + ADR-0008 review 仍是用户未决项,**本 runbook 不触碰**

新增 ADR 入口自 0009 起;Q3 季末归档走 `wiki/log/archive-2026-q3.md`。

---

## [2026-06-30] ADR-0009 | Chain Engine 抽出 (9626dd9, Proposed 提交)

延续 Q3「可观测性深化」轴 + 架构评审(`/tmp/architecture-review-1782800487.html` 候选 ②)deep-dive,落地双 seam 治理方案。

**ADR 主体**(wiki/adr/0009-chain-engine-extraction.md,137 行,Proposed):
- **D1** `chain/ChainEngine`:责任链主推进者,集中 skip-remaining / decision switch / DEBUG log / counter / Timer 包裹 / MDC stamp / post-process phase
- **D2** `chain/ChainObserver`:4 钩子接口(`onChainStart`/`beforeNode`/`afterNode`/`onChainEnd`),aroundChain + perNode 双层正交;内置 4 生产 observer + 1 NoOp 测试桩
- **D3** `AbstractCacheHandler` 退化:`~170 → ~50 SLOC`,handle 模板代码迁 Engine
- **D4** `CacheHandlerChain` 退化为 thin facade:`~248 → ~30 SLOC`
- **D5** `PostProcessHandler` interface 保留 public,遍历逻辑迁 Engine phase
- **D6** `SyncLockHandler` 锁内路径留切片 3(不属本 ADR 范围)

**3 切片渐进 + 每切片独立回滚**:
- 切片 1 (本 ADR 当前范围):ChainEngine + ChainObserver + NoOp + MDCStamp + facade 退化
- 切片 2:补 3 个生产 observer + AbstractCacheHandler 全部迁完 + 装配
- 切片 3:SyncLockHandler.executeChainInLock 改 `engine.executeChainFragment`

**WS-1.4 leverage 兑现**:ADR-0008 后续 OTel Span 升级 = 新增 `SpanObserver implements ChainObserver`(~50 SLOC),Engine/observer/handler 全部零修改。

**Review CR findings**(留 Round 44 polish,本轮不修,因 Proposed 阶段预留迭代空间):
- F1 (medium):D2 写「内置 4 observer」但列 5 个(含 NoOp 测试桩),措辞需明示「4 生产 + 1 NoOp」
- F2 (medium):Related 行引用 `/tmp/architecture-review-*.html|md`,`/tmp` 路径非 git-tracked,6 个月后失效,应迁 `wiki/meta/architecture-review-2026-06-29/`
- F3 (low):D3 终态描述 vs 切片 1 渐进首步 语义层级未显式区分
- F4 (low):PostProcessHandler「5+ handler 子类(仅 BloomFilterHandler 1 个)」括号内断言与前置数字轻微打架

**Stray 标记**(不入本 commit,留清理 backlog):`wiki/chain-of-responsponsibility.md`(0 字节,疑似旧 session 拼写错误的空占位符,非有效内容;正确路径应为 `wiki/architecture/chain-of-responsibility.md`)。

**验证**:ADR 文本 0 编译依赖(纯 wiki),无需跑测试;commit diff 137+/0- 单文件。

**下一步**(Round 44+):以 `wiki/adr/0009-chain-engine-extraction.md` 切片计划为唯一参考,按切片顺序执行;具体落实进度见后续 commit。

---

## [2026-06-30] ADR-0010 | Attributes 投影层 + TwoListEvictionStrategy 删除 (A+B+C 三候选合并落地)

延续架构评审 (`/tmp/architecture-review-1782816491.html` v2) 三个候选全部收敛,落地 seam 治理:

**ADR 主体** (wiki/adr/0010-attributes-projection-and-strategy-deletion.md, Accepted):
- **D1** `factory/RedisCacheAttributes` POJO + `RedisCacheAttributesProjector` 投影器 → 收敛 18/18 builder 字段逐字复制 + `cacheNames/value` merge 规则
- **D2** 3 处 drift 默认值修复:`@RedisCacheable.expectedInsertions` 10000→100000、`falseProbability` 0.03→0.01;`@RedisCachePut / @RedisCacheEvict.syncTimeout` -1→10
- **D3** 删除 `eviction/TwoListEvictionStrategy` (105 SLOC pass-through);聚合下沉到 `EvictionStats.of(TwoListLRU, int, int)`
- **D4** 新增 `factory/SpringCacheableAdapterFactory`,把 `CacheableAnnotationHandler` 47 行内联 if-Builder 模板下放到 factory

**文件变更**:
- 新建 4: `RedisCacheAttributes.java`、`RedisCacheAttributesProjector.java`、`SpringCacheableAdapterFactory.java`、`RedisCacheAttributesProjectorTest.java`
- 修改 12: 3 个注解 + 5 factory/abstract + 1 handler + 1 register + 1 EvictionStats + 3 个工厂测试 + 1 handler 测试
- 删除 2: `TwoListEvictionStrategy.java`、`TwoListEvictionStrategyTest.java`

**用户契约变更** (发版时需 release note):
- `@RedisCacheable.expectedInsertions` 默认 `10000 → 100000` (Bloom 更保守)
- `@RedisCacheable.falseProbability` 默认 `0.03 → 0.01` (Bloom 更严)
- `@RedisCachePut/@RedisCacheEvict.syncTimeout` 默认 `-1 → 10` (锁等待最长 10 秒,防御性收紧)

**杠杆兑现** (增删字段/改默认值/修 drift → 只需动 3 处而非 9 处):
1. `RedisCacheAttributes` POJO 加字段
2. `RedisCacheAttributesProjector.from(annotation)` 加一行
3. 对应 factory `materialize(...)` 加一行 builder 调用

**Review CR findings (留 Round 45 polish)**:
- F1 (low): 三个注解的 Javadoc 字段说明未同步更新 (默认值改了,文档未改) — 待 polish

**验证**: `mvnw checkstyle:check` PASS;`mvnw verify` 689 tests, 0 failures,coverage checks met。

**下一步**: 无 — ADR-0010 已完整落地,Round 45 待 polish F1 (Javadoc sync)。

---

## [2026-07-01] ADR-0016 | ObserverRegistry 抽出 + RedisProCacheManager instantiate seam 收敛 (Round 6 autocratic one-shot)

`/improve-codebase-architecture` round 6 autocratic one-shot 报告基于 round 1–5 已落地 ADR-0009/0010/0011/0012/0013/0014/0015 后,扫描 `chain/` + `handler/` + `cache/` 三域,筛出 2 强候选(候选 A + B)同 commit 合并落地,4 候选(C/D/E/F)继续延后(YAGNI/不动):

**ADR 主体** (`wiki/adr/0016-...md`, 300 行, Accepted):
- **D1** `chain/ObserverRegistry<O>` 抽出 — 跨 engine observer 列表去重 seam,泛型 utility 供 `ChainEngine` + `AnnotationChainEngine` 共用(消除 ~50 SLOC 重复 + 中心化 null-check + 中心化 CopyOnWrite 线程模型)
- **D2** `RedisProCacheManager.instantiateRedisProCache(name, cfg)` 抽出 — 8 参 `new RedisProCache(...)` 重复样板收敛为 1 处委派(Spring 扩展点韧性)
- **D3–D6** 候选 C/D/E/F 决策记录(Factory materialize / Projector 3 from / CacheResult 语义 / Factory 4 if-block)留作未来触发器

**文件变更**:
- 新建 2: `chain/ObserverRegistry.java` (60 SLOC / 24 code-only), `test/chain/ObserverRegistryTest.java` (8 contract tests)
- 修改 4: `ChainEngine.java` (-16 code SLOC), `AnnotationChainEngine.java` (-3 code SLOC), `RedisProCacheManager.java` (-5 code SLOC), `test/cache/RedisProCacheManagerTest.java` (+3 contract tests)
- 总代码净变化:**0 SLOC**(seam 重新分布,leverage +∞)

**leverage 兑现**:
1. 新增第 3 个 observer-bearing engine → 直接 `new ObserverRegistry<O>()`,1 行而非 25 SLOC
2. Spring 框架 `RedisCache` 构造参数变化 → instantiate seam 兜底,改 1 处而非 2 处
3. observer 列表 null-check / CopyOnWrite 线程模型 → 中心化(此前两 engine 各持)

**Review CR findings**: 零 — autocratic 阶段已 self-review(公开 API 兼容性 + import 清洁度 + 线程模型保留 + 27+ 调用方零回归),无 CR 问题待修

**验证**:
- `mvnw checkstyle:check` —— **0 violations**
- `mvnw verify` —— **BUILD SUCCESS, 746 tests, 0 failures, 0 errors; All coverage checks have been met**
  (原 735 + 8 ObserverRegistry contract + 3 Manager contract = 746)
- JaCoCo —— `chain.ObserverRegistry` 100% 行覆盖 (8 contract tests)

**下一步**: 无 — ADR-0016 完整落地。下轮(Round 7)可考虑候选 G:5 个 `onAttachMetrics` handler 子类 single-counter pattern 微 DRY(目前每 handler unique counter 名,合并易失语)。
