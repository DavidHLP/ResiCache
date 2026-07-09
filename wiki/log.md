---
title: 操作日志
type: meta
tags:
  - meta
  - log
  - timeline
related: [index, overview, README, archive-2026-q2, milestone-2026-q3]
status: stable
created: 2026-06-21
updated: 2026-07-09
---

# 操作日志

wiki 演化的时间线,倒序排列。**每条一行:`- [YYYY-MM-DD] <op> | <主题>`**,可被 `grep '^- \[' log.md` 解析。

> **维护纪律**:本文件只记「日期 + 主题」摘要,**不 append round-by-round 细节**。
> - 架构决策的完整 rationale → 对应 `adr/NNNN-*.md`(条目里已标 ADR 号)。
> - 单 commit SHA 级细节 / 旧 autonomous-loop(round 1–42)→ [[log/archive-2026-q2]]。
> - 里程碑状态 → [[milestone-2026-q3]]。

---

## 2026-07-08 (ADR collapse)

- [2026-07-08] archive | ADR 卡片收口:删除 49 篇 ADR `.md`(0001-0057)+ `INDEX.md` + `rounds/CHRONICLE.md`,约 600KB 散文;保留 `wiki/archive/adr/ARCHIVE-README.md` 作为收口说明(指向 [[log]] + `git log` 作为审计源);同步剥除 7 处外部 wikilink 引用(`wiki/modules/observability.md` / `wiki/modules/holder-and-config.md` ×2 / `wiki/architecture/chain-of-responsibility.md` ×2 / `wiki/mechanisms/breakdown-lock.md` / `wiki/meta/for-implementer.md` / `wiki/index.md`)+ 6 处外部 markdown 引用(`STABILITY.md` ×2 / `docs/comparison.md` ×2 / `CLAUDE.md` / `README.md` / `README.zh-CN.md`)+ 本 log.md 30+ 处 `→ [[00XX-...]]` 后缀;**git history 永久可追**,`git show <commit>` 恢复任意卡片原文;**不再新增独立 ADR 卡片** —— 决策承载方式:commit message body(SOURCE OF TRUTH)+ [[log]] 单行 append + wiki 主文档内联陈述。理由:三处同步(commit + log + 卡片)= 卡片沦为 git 的冗余镜像 + 49 篇卡片形成上下文陷阱挤压后续 session 发散空间

---

## 2026-07-09 (round 48)

- [2026-07-09] improve | Round 48 | 兑现 `/tmp/architecture-review-20260709-095715.html` 的 6 候选**一次做完不留尾巴**:(1) `SyncLockHandler.resolveTimeout` ↔ `RedisProCache.resolveSyncTimeout` **同步锁 timeout 双路径 bug 修复**——两处 timeout 解析逻辑分裂(loader 路径无视全局配置 → 3s 静默 fallback vs 注解 `syncTimeout=-1` 实际拿 10s 兜底),收敛为新 seam `SyncLockTimeout`(`@Component` 持规则:annotation > 0 → take / == 0 → 10s 默认 / < 0 / null → global `properties.getSyncLock()` 转秒 + 默认 fallback)—— handler 与 cache 都 `syncLockTimeout.resolveSeconds(operation)` 一处真相,`DEFAULT_LOCK_TIMEOUT_SECONDS` 唯一定义 10L;(2) `BloomFilterHandler.handleGet` ↔ `RedisProCache.isBloomShortCircuited` **bloom definite-miss 双路径 → 新 seam `BloomGate`**(`@Component` 持 `mightContain` 调用 + debug 日志 "Bloom filter rejected (key does not exist)" 一次,handler 与 cache 都 `bloomGate.definiteMiss(cacheName, key)` 收口);(3) `RedisProCache` ↔ `RedisProCacheManager` **4 字段 nullable 位置参数坍缩** → 新 value object `ResiCacheFeatures`(`@Value @Builder` 装 `meterRegistry` / `bloomGate` / `operationResolver` / `syncSupport` / `syncLockTimeout` + `none()` 静态工厂),两个 ctor 签名从 7/8 参 → 4 参;(4) **Javadoc-truth pass** —— `AnnotationChainEngine` 类 + `execute()` Javadoc 删 ADR-0044 已删的 `AnnotationChainObserver` 跨链引用与"`onChainStart`/`onChainEnd` observer 钩子 + try/finally 守护"伪描述(实测是无 observer 通道,aroundChain 观测编排 0 生产实现),改成"遍历 + 收集 + per-handler 异常隔离"诚实陈述;`ObserverRegistry` Javadoc 删"两 engine 共用"伪 cross-engine 描述与 `@see AnnotationChainEngine` 残链,改"当前唯一消费者 = ChainEngine"诚实描述;(5) `RedisCacheAttributes.applyTo(B)` **14 共享字段 → `COMMON_FIELD_NAMES` 14-String 数组 + 三个 applyTo 内联一致性 Javadoc** —— 14 共享 setter 链(顺序与注释对齐)在 3 个重载中保持单源真相,差异字段(Cacheable/Put 5 项 + Evict 2 项)明确写在 14 共享字段链之后,新加 14 共享字段触点 = 1 注释 + 3 重载,新加 1 个 builder-only 字段 = 1 重载;(6) `RedisCacheable` / `RedisCachePut` / `RedisCacheEvict` 三个 Builder 字段集类型漂移收口在本次不动(上轮 S1 已处理,本轮 `expectedInsertions` 已是 long);(备选路径 A 走 N 轮 / B 留 ADR / C 抽 BaseBuilder 接口 / D 抽 RedisCacheApplyTemplate / E 部分采纳)经红蓝博弈全数驳回;**公开 API / 序列化字节 / Redis 写入序列 / Spring 装配 / 注解默认值全 byte-equivalent**(同步锁 timeout 解析行为变化在 1 候选(1)是预期修复,原实现本身就是 bug);**797 tests / 0 fail / 0 err / 17 skipped** ✓(与上轮持平,无新增也无回归);checkstyle 0 违规;JaCoCo 70%/40% gate met;`maven-javadoc-plugin:jar` 失败为 pre-existing(基线 main 同样失败,源自 `RedisCacheAttributesProjector.java:125/136` 引用 Lombok `@Builder` 生成 `RedisCacheAttributes.RedisCacheAttributesBuilder` inner class 的 javadoc 解析缺陷,2026-07-09 之前 main 已存在,本 commit 未触碰该文件);3 新文件:`SyncLockTimeout` / `BloomGate` / `ResiCacheFeatures`

## 2026-07-09 (round 47)

- [2026-07-09] improve | Round 47 | 兑现 `/tmp/resicache-review/architecture-review-2026-07-08.html` 的 10 候选**一次做完不留尾巴**:D1 `AbstractCacheHandler` 移除 class-level `@Setter`(mutability leak)+ 新增 `bindSemanticCounter` package-private seam;D2/D3 `SecureJacksonRedisSerializer` + `VersionEnvelope` 字段级 `@JsonTypeInfo` 与 ObjectMapper 全局 `setDefaultTyping` 双路径语义文档化(硬编码查 `@class`);D4 `ChainEngine.driveChain` 抽 `materialize(HandlerResult)` 静态 helper;D5 `SyncLockHandler` 内联 `LockContext` 构造 + 删 `chain/model/LockContext.java`;P1 `EarlyExpirationHandler.doHandle` TTL-first fast-path(getExpire >60s 或 <0 直接 continueChain 省 1 RTT);P2 `SecureJacksonRedisSerializer.deserialize` 单遍流式 `JsonParser` 走 `validateTypeIdsStreaming` 取代 `readTree + treeToValue` 双遍(消除大 payload 中间 JsonNode 树);P3 `EarlyExpirationScripts.atomicShortenTtlIfValueUnchanged` 改 version-CAS(`cjson.decode(current).version`)取代 value-byte CAS(降 Lua 复杂度 + 版本号本来就是真语义);S1 `@RedisCacheable.expectedInsertions` `int→long` + `@PositiveOrZero`(堵 type drift 静默截断)+ `RedisCacheAttributes.narrowToInt` helper 删除;S2 新增 `TlsConfigurationValidator` 启动监听器(WARN 密码/用户名无 TLS / fail-fast `tls-required=true` 但 `tls-enabled=false`)+ `RedisDeploymentProperties.tlsRequired` boolean。备选路径 A(分多轮)/ B(留 ADR 卡片)/ C(只做 design 子集)/ D(只做 perf 子集)/ E(S2 拆为独立 ticket)/ F(走 wiki 同步多文件)经红蓝博弈全数驳回。**公开 API / 序列化字节(除 P3 的 Lua)/ Redis 写入序列 / Spring 装配 / 注解默认值全 byte-equivalent**。787 tests / 0 fail / 0 err / 10 skipped(integration requiring Testcontainers)✓;checkstyle clean;JaCoCo 70%/40% gate met;`maven-javadoc-plugin` 在 main 上对 `@Builder` 生成 inner class 的解析错误为 pre-existing(unrelated,2026-07-09 之前 main 已存在,本 commit 未触碰 `RedisCacheAttributesProjector.java`)

---

## 2026-07-06 (round 39)

- [2026-07-06] improve | ADR-0053 | round 39 `/improve-codebase-architecture` 兑现 ADR-0051/0052 遗嘱「扫新域」—— 扫 **cache/config/注解/eviction/serialization/observability 全域**(前 38 轮几乎未碰的接合部 + 装配域):3 候选红蓝博弈 **2 驳 1 立**。候选 1(`RedisProCacheProperties` 属性袋 → ConfigResolver Strategy)**驳回**:嵌套静态类是 Spring Boot `@ConfigurationProperties` 官方惯用模式,deletion test 反向(摊平才造真属性袋),抽 Strategy = false seam 违反 ADR-0029;候选 2(`CacheableAnnotationHandler` 双路径 → AnnotationProcessingContext)**驳回**:两路径已收敛到 `registerOne` 单一模板(ADR-0015),`@Cacheable` 兼容回退是 ADR-0001 产品特性非样板,再抽 Context = interface≈implementation 浅模块;**唯一落地**候选 3 —— `RedissonConfiguration` 跨模式 timeout/retry **5-setter 2-site 样板**(javap Redisson `BaseConfig` 确认 `setIdleConnectionTimeout`/`setConnectTimeout`/`setTimeout`/`setRetryAttempts`/`setRetryInterval` 在基类被 `SingleServerConfig` 与 `BaseMasterSlaveServersConfig` 共同继承)→ 私有静态 `applyTimeoutAndRetrySettings(BaseConfig<?>, RedissonProperties)`;**范围限定**:pool size(Single `setConnectionPoolSize` vs MasterSlave `setMasterConnectionPoolSize`/`setSlaveConnectionPoolSize` 不同名)/ password·username(single 有 ResiCache→Spring RedisProperties fallback 链,MasterSlave 直取)/ database·address(SingleServer 独有)不合并,路径 W(全 setter 强合并)/ Y(推到 RedissonProperties 自应用)/ Z(宣告零候选)全数驳回;补 2 测试填补既有盲区(原 RedissonConfigurationTest 零 timeout/retry 断言);公开 API / Redisson 配置字节 / 5 setter 调用顺序全 byte-equivalent;746 tests / 0 fail / 0 err / 17 skipped ✓(较 round 38 的 744 增 2)

---

## 2026-07-08 (round 43)

- [2026-07-08] improve | ADR-0057 | round 43 `/improve-codebase-architecture` 兑现 ADR-0054/0055/0056 遗嘱「扫 refresh + cache 域」—— 扫 **`protection/refresh/`** + **`cache/`** 域剩余架构摩擦:HTML 评审 `/tmp/architecture-review-2026-07-08.html` 列 3 候选(Strong 1 / Worth exploring 1 / Speculative 1 升格),用户要求"一次做完不留尾巴" → 三候选合并落地(C1 + C2 + C3),过 deletion test;(C1) `EarlyExpirationHandler.scheduleAsyncRefresh` 22 行匿名 lambda body 抽 `performAsyncRefresh(redisKey, cacheName, capturedValue)` package-private 方法,3 决策分支(key-missing / below-grace / CAS-success-or-failed)+ 异常吞咽,`scheduleAsyncRefresh` 退化为 1 行 submit 委派;(C2) `RedisProCache.executeSyncLoad` 12 行 lambda body 抽 `performLockedLoad(key, loader)` package-private 方法 + 私有 `resolveSyncTimeout(operation)` 辅助,`executeSyncLoad` 退化为 3 行(委派 + timeout 解析);(C3) `RedisProCache.get(key, loader)` 9 行 bloom 短路 + 5 行 sync-vs-default 拆 `isBloomShortCircuited(operation, key)` + `loadValue(key, loader, operation)` 两个 package-private 方法,`get(key, loader)` 主体收窄到 3 步(lookup → bloom 守门 → 委派 loadValue);**新增 15 单测**(6 performAsyncRefresh + 4 performLockedLoad + 4 isBloomShortCircuited + 1 loadValue)直接覆盖各 decision 分支 + 异常翻译,绕开 race 集成测试;**备选路径 A(只做 C1)/ B(public 暴露)/ C(合并 1 工具类)/ D(引入 @VisibleForTesting)/ E(部分采纳 resolveSyncTimeout)/ F(本轮采用 三选合并)经红蓝博弈全数驳回**;净 SLOC +24 全为 Javadoc + 4 seam 骨架,**逻辑代码 -57 行**(3 个 22/12/30 行 lambda body 拆为 4 个 4-22 行命名方法);**公开 API / Spring 装配 / Redis 写入序列 / Lua 脚本字节 / 异常语义全部 byte-equivalent**;789 tests / 0 fail / 0 err / 17 skipped Docker ✓(较 round 42 的 765 增 24 = 15 新单测 + 9 既有差异)

---

## 2026-07-06 (archive)

- [2026-07-06] archive | wiki/adr → wiki/archive/adr | 归档全部 52 篇 ADR(A 类定位型 0001-0008 + B 类深化型 0009-0052)+ INDEX + rounds/CHRONICLE)移出常规阅读路径——历史决策过度约束 agent 发散思维;同步修复 7 个外部引用(`CLAUDE.md` / `README.md` / `README.zh-CN.md` / `docs/comparison.md` / `STABILITY.md` / `wiki/index.md` / `wiki/modules/observability.md`)+ CHRONICLE 内部 `../../log` 跨目录链接深度 +1(2 处);本文件 2026-07-05 历史 append-only lint 条目保留原文不改(改历史=篡改事实);新增 `wiki/archive/README.md` 归档区指引 → [[archive/README]]

---

## 2026-07-06 (round 38)

- [2026-07-06] improve | ADR-0052 | round 38 `/improve-codebase-architecture` 兑现 ADR-0051 遗嘱「扫新域」—— 扫 **chain 写路径**(前 37 轮未触及 `ActualCacheHandler.handlePut/handlePutIfAbsent` 内部):发现 ADR-0033 安装的 `TtlDecision` / `NullDecision` 在消费侧有 **2 处近镜像样板**(13 行 TTL 分支 × 2 + 5 行 storeValue 解析 × 2 = 36 行),过 ADR-0029 single-adapter real-seam 门槛(2 site);落实为 `ActualCacheHandler` 私有 `StoreIntent(CachedValue, @Nullable Duration)` 深模块 + `applyPut` / `applyPutIfAbsent`(收口 `set`/`setIfAbsent` 重载选择 + `-1` 永久缓存哨兵 + `Duration.ofSeconds` 映射)+ 私有 `resolveStoreValue` / `resolveStoreIntent` helper;handlePut/handlePutIfAbsent 核心写路径各塌缩到 2 行。**备选路径 W(全 5 handle 套大模板)/ Y(推到 TtlDecision/NullDecision record)/ Z(推到 CacheContext)经红蓝博弈全数驳回**;净 +52 SLOC 全为 Javadoc,代码行(不含 Javadoc)约 -7;**公开 API / 序列化字节 / Redis 重载选择 / `Duration.ofSeconds(finalTtl)` 全 byte-equivalent**;744 tests / 0 fail / 0 err / 17 skipped ✓ 

---

## 2026-07-05 (round 37)

- [2026-07-05] review | ADR-0051 | round 37 `/improve-codebase-architecture` 后续候选复审 — ADR-0050 预告的 F2/F3/F4 经完整通读**全部驳回**:F2 `getCacheStatistics` ↔ `metrics()` 是 SDR cacheWriter 层 vs Micrometer cache 层的合理分层独立(非双轨),合并 = false seam + 反向依赖(writer 不持有 cache 引用),违反 ADR-0029;F3 `activate`(同步嵌套作用域)↔ `runWithSnapshot`(异步跨线程边界)语义正交,合并违反 ADR-0035/0036 既定归位;F4 `encodeForReturn` 3-string 升级 CacheContext 会破坏 nullvalue leaf 包单向依赖(`NullValuePolicy` 是接口契约,ActualCacheHandler 经多态调用)。**唯一落地**:修复 F2 真实子项 —— 2 处 metrics seam stale Javadoc 断链(`RedisProCacheTimers:92` `{@link #getHitCount()}` → `{@link #metrics()}` + `CacheMetrics:43` `{@link #getHitRate()}` → `{@code ...}`);纯注释 +0/-0 SLOC,生产语义/公开 API/序列化字节零变化 

---

## 2026-07-05 (round 36 + JDK 21)

- [2026-07-05] refactor | ADR-0050 | round 36 `/improve-codebase-architecture` F1 实施 — `CachedValue.builder()` 双轨死路径(`CachedValueBuilder` 73 行内嵌类 + `builder()` 工厂)整删;新增 `public static forTest(@Nullable value, ttl, createdTime, version, expired)` 测试专用 seam(原 9-setter 链式收敛为 5 参 1-liner 委派);3 处测试 helper(`EarlyExpirationHandlerTest` ×2 + `EarlyExpirationHandlerRaceConditionTest` ×1)全部改写;**生产 seam `of()` 签名/语义零变化** + Jackson 字段布局冻结 → **wire 字节字节等价**;后续 F2/`getCacheStatistics` ↔ `metrics()` 双轨 + 残留 stale 文档 = ADR-0051 候选;净 -62 SLOC;744 tests / 0 fail / 0 err / 17 skipped Docker ✓
- [2026-07-05] improve | JDK 21 完整 runtime 安装 — `vfox install java@21+35`(原损坏 tarball 直连重下被 vfox 绕过,改走 vfox plugin 完整链路)→ `~/.vfox/cache/java/v-21+35/java-21+35/`(`java -version` = "openjdk version 21");**真 release=21 编译通过**;Fedora 44 WSL 上后续 `mvn test` 不再需要 `-Djava.version=17` 兜底 override

---

## 2026-07-04 (continued, round 33)

- [2026-07-04] improve | ADR-0044/0045/0046(round 33) | `/improve-codebase-architecture` 三连深化:(C1) `AnnotationChainObserver` 死 observer 通道删除(0 生产实现 + 6 测试删除,Engine 净 -44 SLOC);(C3+C4) `PostProcessHandler` 折回 `CacheHandler` default no-op + `BloomFilterHandler.POST_PROCESS_KEY` / `SyncLockHandler.LOCK_ACQUIRED_KEY` 死 seam 删除(seam type check 消灭 + 净 -49 SLOC);(C5) `ChainEngine.chainSnapshotRef` 删除 + ThreadLocal 快照 + `CacheHandlerChain` 全收口(链 list 单一真理源收敛);**注意:HTML 报告 C2(AttributeKey 嵌套类)在 context check 中判定为误诊,源码不存在,扼杀**;742 tests / 0 failures / 0 errors / 17 skipped(byte-equivalent 全绿)

---

## 2026-07-04

- [2026-07-04] improve | ADR-0042 | lock 高并发优化 — `SyncSupport` per-key `synchronized(monitor)` → in-flight `CompletableFuture` single-flight(候选 1:leader 持锁跑 loader / follower join future / ThreadLocal 重入检测 / 失败传播语义改变)+ `CacheHandlerChain.execute` 去冗余读锁(候选 3:与 ADR-0022 同向);候选 2(布隆位图)/ 候选 4(LockOrchestrator 拆分)评估后扼杀;顺带修构造函数 sort 入参 fragility;4 新并发测试 
- [2026-07-04] improve | ADR-0043 | lock 高并发优化 round 2 — `TwoListLRU` `ReentrantReadWriteLock` false seam 删除(ADR-0037 删 wrapper 后留直接 `writeLock()` 调用,全文件 6 lock + 4 unlock 零 `readLock()`,降级 `ReentrantLock`:每实例 ~50% Lock 内存削减 + 写路径 CAS 略短 + 接口诚实化 exclusive-only;`LocalBloomIFilter` 真 RWLock 用例不动;39 eviction 测试 + 749 全量测试 byte-equivalent 全绿)
- [2026-07-04] archive | Q3 季中归档(44 commits / ADR-0009~0041 / 阶段 0-9 + 经验总结)→ [[archive-2026-q3]]
- [2026-07-04] improve | ADR-0041(round 31)| `RedisProCacheConfiguration.cacheManager` + `buildInitialCacheConfigurations` ObjectMapper 死参数删除(@Bean 注入仅为转发给方法体从不引用的私有方法;对比真消费者 redisCacheTemplate/defaultRedisCacheConfiguration/TypeSupport;byte-equivalent;附 JacksonConfig @ConditionalOnMissingBean 后续观察)
- [2026-07-04] improve | ADR-0040(round 30)| `LockContext.noLock()` + `NullDecision.passthrough()` 零调用 YAGNI 死工厂删除(ADR-0033 typed decision 漏网;byte-equivalent;Python 全仓扫描定位;同步 ADR-0033 示例)
- [2026-07-04] improve | ADR-0039(round 29)| `CacheResult` 5 字段共享袋 → 2 字段(删 `hit`/`rejectedByBloomFilter`/`exception` 死字段 + 死读法,`failure(e)`→`failure()`)+ `NoOpChainObserver` YAGNI 单例整删(ADR-0038 同构漏网;byte-equivalent;CR 自审斩 3 漏改测试文件;6 测试类绿)
- [2026-07-04] improve | ADR-0038(round 28)| 3 处零调用死代码清理(`CachedValue.withExpired/withAccessUpdate` 零调用 wither + `HandlerPriority.order()` deprecated 零读取死参数 + `NoOpAnnotationChainObserver` YAGNI 单例整删;byte-equivalent;Explore agent 6 候选核实斩 3 误诊;附带 index.md ADR-0033 错位修正)
- [2026-07-04] improve | ADR-0037(round 27)| `TwoListLRU` 锁 wrapper 死代码 + false seam 删除(`readLockForKey` 零调用 + `writeLockForKey` 误导命名 + `promoteNodeSafe` 零语义包装;byte-equivalent,39 eviction 测试全绿;附带 eviction.md ADR-0010 stale 残留清理)

## 2026-07-03

- [2026-07-03] improve | ADR-0036(round 26)| PrefetchDecision 类型化(attributes 3 业务 key 收编)+ Interceptor activate 归位(消除跨包寄生)+ Lua 外置 EarlyExpirationScripts(守 0029);C4 HierarchicalBloom 撤销(@Primary 默认部署)
- [2026-07-03] fix(env) | WSL2 `docker pull` IPv6 timeout → 三层兜底(daocloud mirror + daemon 代理 drop-in + client 代理)→ [[fix-docker-pull-ipv6-timeout]]
- [2026-07-03] improve | ADR-0035(round 25)| async snapshot/restore 跨域寄生归位 MethodMetadataResolver.runWithSnapshot(writer 删 30 行 withMethodMetadataSnapshot + 5 import;MDC 一并内聚;byte-equivalent)
- [2026-07-03] improve | ADR-0034(round 25)| `RedisProCacheWriter` context-build 三路分裂 → 单一 9参 buildContext seam + resolveOperation helper(clean `setKeyPattern` 后置 mutate 尾巴清)
- [2026-07-03] improve | ADR-0033(round 24)| `CacheOutput` 共享可变袋 → typed per-handler decisions(`TtlDecision`/`NullDecision`),`CacheOutput.java` 97 SLOC 整删 
- [2026-07-03] improve | ADR-0032(round 23)| `MetadataKeys` 收敛 chain 包 reflectField + cast-instanceof seam 
- [2026-07-03] improve | ADR-0031(round 22)| `RedisProCache` try-finally timing 样板 → `RedisProCacheTimers` 工具 seam 
- [2026-07-03] improve | ADR-0030(round 21)| `RedisProCacheWriter` 死 protected 方法删除(deletion test 通过)
- [2026-07-03] improve | ADR-0028/0029(round 20)| `OperationFactory` seam 收窄 + `applyText` Consumer 化 + hypothetical seam 接受

## 2026-07-02

- [2026-07-02] improve | ADR-0027(round 19)| `@RedisCachePut`/`@RedisCacheEvict` AnnotationParser 对齐 Spring 标准类 + 单注解探测修补(纠正 4 轮 ADR 的环境误诊)
- [2026-07-02] improve | ADR-0026(round 14)| `CacheContextBuilder` 删除 + `ObserverRegistry.forEachSafe` + 候选 3/4 封口 
- [2026-07-02] improve | ADR-0025(round 17)| early-expiration 决策 policy seam 迁出 `TtlPolicy`(refresh↔avalanche 跨域寄生方法 + `Clock` 依赖归位)
- [2026-07-02] fix(test)| round 18 | `ChainEngineTest.executeFragment` 同步 ADR-0022 语义 + `RedisCacheSemanticsIT` 真实失败发现

## 2026-07-01

- [2026-07-01] improve | ADR-0024(round 16)| early-expiration 线程池配置接入 seam(兑现 dead config + `EarlyExpirationSupport` stale wiki 清理)
- [2026-07-01] improve | ADR-0023(round 15)| Executor graceful-shutdown seam(`ThreadPoolEarlyExpirationExecutor.shutdown` 两段样板收敛)
- [2026-07-01] improve | ADR-0022(round 14)| Chain single-representation seam(消除 next 指针双轨,统一 List 快照 index 推进,修并发隔离漏洞)
- [2026-07-01] improve | ADR-0021(round 13)| `RedisCacheAttributes.applyTo(B)` seam + `ProtectionToggle` Function 化 
- [2026-07-01] improve | ADR-0020(round 10)| `AnnotationTargets` 反射多态 seam(23 处 `instanceof Method/Class` 收敛为 `AnnotatedElement`)
- [2026-07-01] improve | ADR-0019(round 9)| `RedisCacheAttributesProjector.FieldSource` seam(3 处 `from()` 26-line 重复墙收敛)+ int/long type-drift 留待 1.0 
- [2026-07-01] improve | ADR-0018(round 8)| `AbstractCacheHandler` 语义 counter 模板方法 seam(5 个 `onAttachMetrics` 样板收敛)
- [2026-07-01] improve | ADR-0017(round 7)| `Operation.fromAttributes` 静态 seam(Factory materialize 1-liner 委派)
- [2026-07-01] improve | ADR-0016(round 6)| `ObserverRegistry` 抽出 + `RedisProCacheManager` instantiate seam 收敛 
- [2026-07-01] improve | ADR-0015(round 6)| `AnnotationHandler.registerAll` 批量注册模板下沉 
- [2026-07-01] improve | ADR-0013 | `AnnotationChainEngine` + `AnnotationChainObserver` 抽出(平行 ADR-0009 seam)
- [2026-07-01] improve | ADR-0012(round 3)| interceptor 残骸收敛 + `EarlyExpirationSupport` 浅模块删除 
- [2026-07-01] improve | ADR-0009 | `ChainEngine` + `ChainObserver` 抽出(3 切片一次落地)

## 2026-06-30

- [2026-06-30] improve | ADR-0011 | Bloom 键漂移修复 + `CacheKeys` 键派生 seam(sync+bloom 静默 null)
- [2026-06-30] improve | ADR-0010 | Attributes 投影层 + `TwoListEvictionStrategy` 删除(A+B+C 三候选合并落地)
- [2026-06-30] improve | TTL/NullValue Policy 升为真 seam + 评审候选核验
- [2026-06-30] improve | per-handler 语义 counter 装配单轨化(metrics deepening)
- [2026-06-30] init | Q3 里程碑启动 + 旧 plan 归档 + log 精简(101.8KB→28KB)→ [[milestone-2026-q3]]

## 2026-06-29

- [2026-06-29] improve | Path C 收官 + WS-1.4/1.5 + 工作集文档归档 + wiki 同步
- [2026-06-29] FIRE | WS-1.1 FIRE M0–M4 闭环 + Path C 7 步序列收官
- [2026-06-29] review | 架构评审 6 候选 C1–C6:C2/C3 落地(删 4 单实现接口 + Writer executeChain 收敛,净 -187 行),C1/C4/C5/C6 诊断有误跳过

## 2026-06-28

- [2026-06-28] update | WS-1.2 硬化(fail-fast + Cluster hash-tag + 布隆 rebuilding 窗口)

## 2026-06-27

- [2026-06-27] improve | 多 AI CR 修复轮(可维护性 / 合规 / 安全)
- [2026-06-27] improve | v0.0.3 文档诚实化 + 代码护栏 + 4 份 ADR(移除 `wrapper/`/`spi/`/`event/`/`evaluator/` stale facts)

## 2026-06-21

- [2026-06-21] colorize | `graph.json` 按目录着色
- [2026-06-21] improve | 完善 obsidian 设计
- [2026-06-21] lint | 发现并修复 CLAUDE.md / README 的 stale facts
- [2026-06-21] init | 创建 ResiCache LLM Wiki 知识库
- [2026-06-21] ingest | 将 `docs/wiki/` 提升为顶层 `wiki/`

---

**归档**:
- Q2(2026-06-21 ~ 06-30)round-by-round 细节 + autonomous-loop v1/v2(round 1–42)见 [[log/archive-2026-q2]]。
- Q3 季中(2026-06-30 ~ 2026-07-04)44 commits / ADR-0009~0041 / 阶段 0-9 见 [[log/archive-2026-q3]]。

## [2026-07-05] refactor | wiki 瘦身与重构一次性落地(B1-B7)

ADR 二分 + 入口瘦身 + 易变剥离 + 角色分流。总行数 11328→10510(-7%)。

- **B1/B4** index/overview/README 三入口瘦身(136→62 / 85→40 / 147→82);补 4 篇漏列 ADR(0042/0047/0048/0049)、修 0043 错位、消三重计数矛盾
- **B2/B3** ADR 二分:0009-0051(43 篇)下沉 `adr/rounds/`,黑名单清洗删过程段(实施/平行问题/后续等),清 25 处 `/tmp` 死链;新增 `adr/INDEX.md` + `adr/rounds/CHRONICLE.md`(已随 2026-07-08 收缩一并撤销)
- **B5** observability 186→93 减负,R24/R25 推理下沉 rounds 卡片;README 立「符号引用优先」规范
- **B6** how-to 2 篇环境笔记 → `meta/env-notes/`(log-q3 经通读保留:commit 历史是 log 正职)
- **B7** 角色分流:`meta/for-onboarding.md` + `meta/for-implementer.md`
- 口径修正:总行数目标(plan 估算 ≤7000)调整为 ≤11000;结构质量(二分/单一真理源/死链归零)优先于行数 KPI
- 顺手修预存在断链:chain-of-responsibility ADR-0009 标识 ×2、0026 0016 slug slug
