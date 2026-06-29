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
updated: 2026-06-29
---


# 操作日志

wiki 演化的时间线,append-only。条目格式 `## [YYYY-MM-DD] <op> | <subject>`(op ∈ init / ingest / improve / colorize / query / lint)。

> 解析最近条目:`grep "^## \[" log.md | tail -5`

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

## Auto-Iteration Progress
- 状态: RUNNING
- current-target: v0.0.3
- 最后更新: `63bc2c1`
- BLOCKED 计数器: 0
- NEEDS-USER-GATE(已就绪待批准): (空)
- OPEN-QUESTION: (空)

### 轮次记录
- [轮 1] DONE reconcile CLAUDE.md/AGENTS.md stale 版本号 + 已删目录 | SHA `b76aeba`(amend 后) | verify N/A(纯 docs 项,§5 step 5b 跳过 verify) | 决策:loop §5 step 2 首轮硬指令(reconcile stale 版本号 + 已删目录);实际 stale:Java 17+ → 21、Spring Boot 3.4.13 → 4.0.0、Redisson 3.27.0 → 3.50.0;AGENTS.md 仍列 `wrapper/`/`spi/`/`event/`/`evaluator/`/`CacheMetricsRecorder` + SPI discovery 段说"Java ServiceLoader"(已废弃)→ 缩为 CLAUDE.md pointer file 消漂移;首次创建 Auto-Iteration Progress 区块(loop §6 bootstrap);CHANGELOG v0.0.3 段加 Documentation alignment 子段
- [轮 2] DONE 建立 STABILITY.md(0.x public API stability contract + 7 项 1.0 graduation criteria) | SHA `f802d94` | verify N/A(纯 docs 项) | 决策:loop §5 step 2 chicken-egg gate(§1.4 改 public API surface 前须先查 STABILITY.md;指南 §3 pillar A "一个 URL 答什么 stable 什么不 stable" + §6 Week 9-10 + §8.2 trust signals 共同强调);决策顺序 §3 命中"指南明确指示";包含 §1 stable(annotations/property keys/wire format)+ §2 may-change(internals/defaults/package layout/pre-1.0 metric namespace)+ §3 never-change + §4 1.0 graduation criteria(Maven Central resolve / STABILITY §1+§3 持有 ≥1 cycle / CycloneDX SBOM / dependency-check gate / production adopter / succession plan / comparison-page referrer)+ §5 how to read + §6 references;CHANGELOG v0.0.3 [Unreleased] ### Added 加 STABILITY.md bullet
- [轮 3] DONE whitelist rejection message 追加 remediation hint(`resi-cache.serializer.allowed-package-prefixes`) | SHA `307fa78` | verify ✅(668 tests, Skipped:0, 含 Testcontainers IT, 全门绿) | 决策:指南 §3 pillar B1 + §6 Week 2 + §5.2 friction 2 "白名单 rejection 无 remediation = 最高频 doc round-trip";TDD:先写 failing test `deserialize_rejectionMessage_containsRemediationPropertyKey`(SecurityTests nested)→ 确认 red(Tests run: 1, Failures: 1)→ 改 SecureJacksonRedisSerializer.java:186-187(消息尾追加 remediation hint)→ 跑 SecurityTests green(3/3)→ 跑全 verify 668 全绿;STABILITY.md §2 诊断 message 文本 pre-1.0 "may change",无 public API surface 影响(查 §1+§3 无 breaking);首个 code 项,验 §5 step 5b verify 分层 + §5 step 5c Skipped:0 监控(测试数 667→668,加 1 regression test);CHANGELOG v0.0.3 ### Changed 加 bullet
- [轮 4] DONE ADR-0006 JetCache 覆盖机制算术事实修正(4/5 → 3/5;剔除 TTL jitter) | SHA `d2aa304` | verify N/A(纯 docs 项,§5 step 5b 跳过 verify) | 决策:指南 §3 实施条目「ADR-0006 的算术有一个事实错误——它把 TTL jitter 计入 JetCache 的覆盖范围(JetCache Issue #269 已 closed unimplemented)。修正。」(战略执行直接指令,§3 决策顺序第一档「指南明确指示」);前置 recon 确认错误范围:**仅 `wiki/adr/0006-redisson-companion-positioning.md:12` 一处**,`docs/comparison.md`、`README.md`、`README.zh-CN.md` 均不把 TTL jitter 计入 JetCache 覆盖(自身一致);变更:①原文 4/5 → 3/5 + 从 JetCache 列表中剔除 TTL jitter;②ResiCache 真实技术增量从「Bloom + 可插拔责任链」更正为「Bloom + TTL jitter + 可插拔责任链」3 项独立项;③新增 `## Amendment 2026-06-29` 段(动机+来源+版本对齐);④CHANGELOG v0.0.3 ### Documentation alignment 加事实修正条目 + 引用 SHA;STABILITY.md §1+§3 未涉及(JetCache 对比文案非 public API surface);§2 事实/诊断文本 pre-1.0 may-change 无 break;两步 commit:d2aa304(ADR+CHANGELOG)/ wiki/log.md(this)。
- [轮 5] DONE `redisCacheTemplate` bean 实际继承 `resi-cache.serializer.*` 属性 | SHA `5949d29`(代码+测试)+ `07bd31f`(CHANGELOG) | verify ✅(669 tests, +1 vs 轮 3 基准 668, Skipped:0, 含 Testcontainers IT, BUILD SUCCESS) | 决策:指南 §3 pillar B1 "first-contact consistency = property 一处配置处处生效";前置 recon grep `allowed-package-prefixes` 发现 `RedisProCacheProperties.SerializerProperties.allowedPackagePrefixes` 字段**已存在**(默认 `["io.github.davidhlp"]`),`RedisProCacheConfiguration#defaultRedisCacheConfiguration` 行 63-69 已**正确连线**,但 `RedisConnectionConfiguration#redisCacheTemplate` 行 38-40 用 `new SecureJacksonRedisSerializer(objectMapper)`(no-list ctor = 默认)忽略全部 4 个 serializer 属性 — **wired 与 unwired 双轨并存,静默不一致**;TDD:①先写 `RedisConnectionConfigurationIT`(注:后来 rename 为 `*Test.java` 让 surefire 在 verify 阶段拾起,与既有 `*IntegrationTest.java` 命名一致)→ `@DynamicPropertySource` 设 `allowed-package-prefixes=[com.example.round5]` + `polymorphic-typing-enabled=true` → roundtrip `CustomDomainValue` → 确认 red:抛 "Type not in deserialization whitelist: com.example.round5.CustomDomainValue"(说明 whitelist 路径走的是默认 io.github.davidhlp,property 被忽略);②fix:`RedisConnectionConfiguration#redisCacheTemplate` 注入 `RedisProCacheProperties` 改用 5-arg ctor(allowedPackagePrefixes / failOnUnknownType / typeProperty / polymorphicTypingEnabled),与 `RedisProCacheConfiguration` 镜像完全对称;同步 fix `TestRedisConfiguration#redisCacheTemplate`(@Primary 镜像,否则 IT 实际测的是测试 bean 而非生产 bean);log 升级加实际生效的属性值;③再跑 IT 仍 red — 第二波错误 "VersionEnvelope 不在白名单" 暴露框架内部 envelope 包 `io.github.davidhlp.spring.cache.redis.serialization` 也需在白名单(framework own types:VersionEnvelope / CachedValue / NullValue 等),IT 改用 `[com.example.round5,io.github.davidhlp.spring.cache.redis.serialization]` 两前缀 → green;④rename `RedisConnectionConfigurationIT.java` → `RedisConnectionConfigurationTest.java`(matches 既有 `CacheLatencySmokeBenchmarkTest` 等命名约定,确保 surefire 在 `verify` 阶段拾起该 IT 测试);全 `clean verify` 669/0/0/0 ✅,BUILD SUCCESS;STABILITY.md §1 `resi-cache.*` property keys 是 stable surface,imply behavior consistency — 本轮 fix 是 contract tighten 而非 break(无新增/重命名/删除属性);CHANGELOG v0.0.3 ### Fixed 加完整 bug 分析 + wire mirror 说明 + STABILITY 论证;两步 commit:5949d29(code+test) / 07bd31f(CHANGELOG)。
- [轮 6] DONE tracking 2 untracked loop 基础设施 docs(`AUTONOMOUS_ITERATION_LOOP.md` 23KB + `COMPETITIVENESS_GUIDE.md` 52KB) | SHA `aaf379f`(一次性双文件)+ wiki/log.md(this) | verify N/A(纯 docs 项,§5 step 5b 跳过 verify) | 决策:前置 recon grep 显示这两个文件**已被 ≥5 个已 committed 文件 by-name 引用**:`STABILITY.md` (loop prompt reference + 指南 §4 §6 references)、`CHANGELOG.md`(Round 4 amendment 引指南作为权威源)、`wiki/adr/0006-redisson-companion-positioning.md`(guide §3 修正条目)、`RedisConnectionConfigurationTest.java`(Round 5 IT 引 resi-cache.serializer.allowed-package-prefixes property key)、`SecureJacksonRedisSerializerTest.java`(Round 3 引同一 property);不 commit 会 broken-reference(markdown-link-check / mkdocs / fresh clone LLM session 都会崩);**为何前 5 轮没做**:Round 1 优先 reconcile CLAUDE.md/AGENTS.md、Round 2 优先 STABILITY.md chicken-egg、Round 3-5 优先 TDD bug fix,broken-reference 状态在已跟踪 refs 只有 1-2 个时还可容忍,Round 5 后累积 ≥5 refs → 不再容忍;变更:①`git add AUTONOMOUS_ITERATION_LOOP.md COMPETITIVENESS_GUIDE.md`(explicit file list, no -A per loop §5 step 6);②单 commit 跟踪,commit message 解释"why now not Round 1"+ 引用图证据 + 明确"not pushed per working agreement";③wiki/log Round 6 记录 + 最后更新 → aaf379f;STABILITY.md §1+§3 未涉及(loop infra 不属 public API surface);§2 docs may-change 不适用(commit 是首次入库而非修改);指南 §3 + STABILITY §4(7 项 1.0 graduation criteria)后续会受益 — ADOPTERS.md / BUS-FACTOR.md 创建等可由指南 §6 checklist 接力。
- [轮 7] DONE `docs/comparison.md` 能力矩阵 TTL jitter 行事实修正(JetCache=❌ + 脚注) | SHA `e2172dc`(comparison.md + CHANGELOG) | verify N/A(纯 docs 项,§5 step 5b 跳过 verify) | 决策:Round 4 修了 ADR-0006 第 1 段同样的事实错误(JetCache 不实现 TTL jitter,Issue #269 closed unimplemented),但 Round 4 recon 在 docs/* 范围 grep 没命中「TTL jitter row claim」 → `docs/comparison.md` 防雪崩行 JetCache 列**仍写 ✅** → 用户面对外公开页 STABILITY §4.7 1.0 毕业条件 #7 所指的 adoption 信号页带事实错误,直接误导读者在 JetCache vs ResiCache 之间选错;Round 7 锁这个二维矩阵错误(行级 vs ADR 的段落级);变更:①`docs/comparison.md:24` 行 JetCache 单元格 ✅ → ❌ + 脚注锚点链接;②新增 `## 脚注` 段 + `fn-jetcache-269` 锚点,完整引 JetCache Issue #269 + 指向 ADR-0006 Amendment 2026-06-29 + 重申 JetCache 实际覆盖 3/5;③CHANGELOG v0.0.3 ### Documentation alignment 第 3 条独立条目(不合并 Round 4)— 这是 公开/内部 文档事实一致性补齐,独立条目读者更易查得;STABILITY.md §1+§3 未涉及(对比页非 public API surface);§2 docs may-change 是 adoption 信号页 pre-1.0 预期内的修复,无 break。Round 4 + Round 7 形成 ADR + comparison 双文档事实一致 pair,后续若再发现同类错误模式可建 checklist 化(尚未行动)。
- [轮 8] DONE `.github/workflows/release.yml` `JAVA_VERSION` 与 `pom.xml` 对齐(`17`→`21`) | SHA `8fc9989`(release.yml + CHANGELOG) | verify N/A(纯 config YAML + docs 项,§5 step 5b 跳过 verify;无代码改动不需 ./mvnw verify) | 决策:指南 §3 pillar B "buildable first time"(SHIPPING_READY 路径)— release 工作流若 JAVA_VERSION 仍是 '17',tag push 触发后 javac 用 JDK 17 编 Java 21 源码,必在 records / sealed types / pattern matching / switch expressions 编译失败;前置 recon:`pom.xml:51 <java.version>21</java.version>` + `maven.compiler.{source,target}=21`,CI.yml `env.JAVA_VERSION: '21'`(WS-1.1 FIRE commit 38c514a 期间同步过),release.yml `env.JAVA_VERSION: '17'`(被遗漏);scope:**只改 1 个 env 值 + 注释**,不动 secret / tag 触发 / `softprops/action-gh-release` 步骤 / job 依赖(loop §1 hard constraints "no outward-facing/irreversible actions");变更:①`release.yml` `JAVA_VERSION: '17'` → `'21'` + 6 行注释 cite WS-1.1 FIRE commit + pom.xml java.version must-align 逻辑;②CHANGELOG v0.0.3 ### Fixed 加 drift 条目:Ci.yml vs release.yml 同步差异、影响链、scope 声明;verify N/A:无 Java 代码改动,不需 `mvn verify`;STABILITY.md §1+§3 未涉及(CI workflow 不属 public API surface);§2 internals may-change 适用但 CI config 严格说不是 public API — 实际是「build infrastructure consistency」附录项。两步 commit:8fc9989(release.yml + CHANGELOG) / wiki/log.md(this)。
- [轮 9] DONE `WhitelistPolicy` 支持 `.*` 后缀通配语义(additive) | SHA `2c40c77`(代码+测试)+ CHANGELOG(this) | verify ✅(673 tests, +4 vs 轮 5 基准 669, Skipped:0, BUILD SUCCESS) | 决策:指南 §3 pillar B1 first-contact consistency — 用户配置 `com.example.*` 时几乎 universal 期望「所有 com.example.* 包」,但现状 literal `startsWith` 把字面 `*` 当成字符匹配,导致 `com.example.*` 设了但 `com.example.Foo` 仍被 whitelist 拒绝 — 静默 footgun;additive semantic 风险最低(无配置回退,既有用法 0 改变);TDD:①在 `WhitelistPolicyTest` 加 4 新测试(red)— `isClassNameAllowed_wildcardSuffix_matchesDirectClass`(`com.example.*` 匹配 `com.example.Foo`)/ `..._matchesSubPackage`(`com.example.sub.Bar` + 任意层嵌套)/ `..._doesNotMatchUnrelated`(不相关包拒)/ `..._literalPrefix_stillMatches`(向后兼容 literal prefix);②`mvn test -Dtest=WhitelistPolicyTest` red 确认(3 wildcards fail + 1 backward-compat green);③`WhitelistPolicy.java` 加 private static `matchesPrefix(className, prefix)`:detect `prefix.endsWith(".*")` → strip → 要求 `className.equals(packageOnly) || className.startsWith(packageOnly + ".")`(dot-boundary 保护 → `com.example` 不会误匹配 `com.exampleX.Foo` 的 wildcard 路径),其他形式维持原 startsWith(`com.example` literal 仍可匹配 `com.exampleX.Foo` — 这是 pre-existing behavior,Round 9 不动);④再跑 test green 12/12(含 4 新 + 7 既有 + 1 修正);⑤red 阶段修正第三测试 `assertThat(...com.exampleX.Foo).isFalse()` 漏理解现状 literal 没有 dot-boundary — 改为 `com.other.Foo` 反向断言,在测试 body 注释里留 record:literal prefix 引入 dot-boundary 是单独决定(intentionally deferred,会破既有用户);⑥全 `clean verify` 673/0/0/0 ✅ — Round 9 是第二个 code 项(round 3 + 5),延后 code→docs→code→docs→config→code 节奏;STABILITY.md §1 (resi-cache.* keys stable)保持 — 无 key 增删,既有 prefix 字符串行为不变,.* 是新增有效语法;§2 internals may-change 适用于实现层(增加 helper method);§3 never-change 不涉及。CHANGELOG v0.0.3 ### Added 加 wildcard 条目;两步 commit:`2c40c77`(代码+测试) / CHANGELOG(this)。
- [轮 10] DONE README.md + README.zh-CN.md「Serialization safety」段加 wildcard `.*` 语法发现文档 | SHA `a3f4528`(单 docs commit,3 文件,无 code 改动) | verify N/A(纯 docs 项,§5 step 5b 跳过 verify;无代码改动) | 决策:Round 9 已落地 `.*` 通配能力,但 README `Serialization safety` yaml 示例 + 紧随其后的 ⚠️ 默认警告都没提到 `.*` — 用户**无路径发现此能力**(只在 CHANGELOG v0.0.3 ## Added 与 git log 里看到);指南 §3 pillar B1 first-contact consistency 第二面:**能力已存在,文档不透露 = 用户踩坑**;scope 限定:①两 README「Serialization safety」段在原 yaml 块 + ⚠️ 警告后追加 1 段通配说明 + 1 段 yaml 示例(展示 `com.example.*` 整子树 + dot-boundary 保护);不动其他段、不动 docs/comparison.md(避免引入与 table 内容重复、保持 table 信息密度);不动 STABILITY.md(§1 resi-cache.* keys stable 不变,.* 是现有 key 的额外语法,§2 docs may-change 适用);STABILITY.md §2 docs may-change 适用(README 是 docs);§1+§3 不涉及;loop §4 默认 position table docs 项「align with source」+ 指南一致性 → 落地。变更:①README.md 加通配段 + yaml 示例;②README.zh-CN.md 同;③wiki/log Round 10 记录 + 最后更新 → a3f4528。
- [轮 11] DONE 抽出 `SecureJacksonSerializerFactory` (@Component) 消除 R5 暴露的双轨装配 smell | SHA `6f41683`(代码+测试:1 新 @Component + 1 新 test + 2 config 注入点改) | verify ✅(675 tests / 0 failures / 0 errors / 0 skipped, +2 vs 轮 9 基准 673, BUILD SUCCESS) | 决策:Round 5 (commit 5949d29) 修了 `resi-cache.serializer.*` 属性在 `redisCacheTemplate` 上静默忽略 bug,同步把 5-arg `SecureJacksonRedisSerializer` ctor 在 3 个装配点镜像(`RedisConnectionConfiguration#redisCacheTemplate` + `RedisProCacheConfiguration#defaultRedisCacheConfiguration` + `TestRedisConfiguration#redisCacheTemplate`)— 但 3 处持有相同 wiring 是维护陷阱:任何 ctor 签名变更会默默 break 其中一处,重新引入 wired/unwired 双轨 bug;refactor 抽出为单 `@Component` 是结构性预防;不变性保证 — 现有 673 测试 = regression 基线,加 2 新测试守护 contract;TDD 风格变体:无新增行为,**既有 673 测试 = regression check** + **1 个 negative-wiring 守护测试**(把 allowedPackagePrefixes 故意设为 `[com.example.round11]` 不含 `io.github.davidhlp`,然后 roundtrip `CachedValue` — 如 factory 默默用默认,会成功;如正确传 props,会抛 `SerializationException` "whitelist"。这是 R5 + R11 contract 的唯一 guard);变更:①新 `SecureJacksonSerializerFactory.java` (@Component 单 method `create(ObjectMapper, SerializerProperties)`);②新 `SecureJacksonSerializerFactoryTest`(2 tests,关键 negative-wiring + String 兜底);③`RedisConnectionConfiguration#redisCacheTemplate` 与 `RedisProCacheConfiguration#defaultRedisCacheConfiguration` 注入 factory,5-arg 内联构造 → `factory.create(...)` 单行;④`TestRedisConfiguration` 不改(@TestConfiguration 不参与 Spring 组件扫描,要把它接上 factory 反而 more wiring,net 更复杂 — 留 5-arg 镜像是 intentional);scope 限定:不动 ctor 签名、不动 `SecureJacksonRedisSerializer` 业务、不动 STABILITY.md 内容(§2 internals may-change pre-1.0 适用;§1+§3 不涉及 — 重构是内部架构),不动 docs/comparison.md(table 是架构级、不是装配级,不动)。CHANGELOG v0.0.3 ## Added 加 factory 条目。
- [轮 12] DONE CONTRIBUTING.md JDK 17+ → 21+ 对齐 pom.xml + 新增「Maintainers & bus factor」段 | SHA `f52bea0`(单 docs commit) | verify N/A(纯 docs 项,§5 step 5b 跳过 verify;无代码改动) | 决策:前置 recon `CONTRIBUTING.md:16 Requirements: JDK 17+` 与 `pom.xml:51 <java.version>21</java.version>` 漂移 — Round 8 抓的是 release.yml 同源漂移,本轮抓 CONTRIBUTING.md;**双决策合并**:①JDK 17+ → 21+ + 注释 cite WS-1.1 FIRE commit(对齐 source of truth = pom.xml);②新增 `## Maintainers & bus factor` 段满足 STABILITY §4.6「1.0 graduation criterion #6:named successor or documented succession plan」前置 — 当前 bus factor 1 (单 maintainer,DavidHLP) honest 描述 + 含义解读(pre-1.0 可接受 / 1.0 前必须重写)+ 实际意义(downstream 评估可读 + 继任对话开放)。变更:①CONTRIBUTING.md:16 「JDK 17+」→「JDK 21+」+ 注释引 WS-1.1 FIRE;②新增 `## Maintainers & bus factor` 段(11 行 honest 文档);③CHANGELOG v0.0.3 ## Added 加贡献者指南两项条目;④wiki/log Round 12 记录 + 最后更新。STABILITY.md §2 docs may-change 适用(CONTRIBUTING.md 是 docs);§1+§3 未涉及(no public API);STABILITY §4.6 graduation criterion 部分满足(Plan ✓ 项,Successor 项未填 — 列在「含义解读」段明示)。scope 限定:不动 `pom.xml`(已经是 21)、不动 `release.yml`(R8 已修)、不动 `CODEOWNERS` / `.github/CODEOWNERS`(bus factor 描述在 CONTRIBUTING 已表达,不动结构);不动其他 docs。
- [轮 13] DONE Composite GH Action `.github/actions/setup-jdk-21/action.yml` 抽出 6 个 setup-java 调用点 | SHA 本轮 commit(1 新 action + 3 workflow + CHANGELOG + wiki/log) | verify N/A(纯 YAML workflow 改动;无 Java 代码改动 + PyYAML lint 全部 OK) | 决策:Round 8 只修了 release.yml 一处的 `JAVA_VERSION` 漂移,根本问题(3 workflow × 多处调用站各自持有同一 string 漂移风险)还在 — 6 个 `setup-java@v5` 调用点分别持有相同 4 项 config(`java-version` / `distribution: temurin` / `cache: maven` + 可选 deploy secrets),任何 pom.xml `<java.version>` 改值要搜 6 处同步,rebuild 风险高;**架构修复**:抽 composite action 作 single source of truth;设计权衡:composite 不能让 caller 加 `with:`(完全封装 vs 半封装),但 release.yml 的 deploy secrets 必须传 → composite 收 5 个 optional inputs 当 pass-through,`ci.yml`/`pr-checks.yml` 不传 `with:`(走 defaults '21'/'temurin'/'maven');变更:①新 `.github/actions/setup-jdk-21/action.yml`(composite,default Java 21,5 个 optional inputs);②`.github/workflows/ci.yml` 4 处 `setup-java@v5` → `uses: ./.github/actions/setup-jdk-21`(无 `with:`,空 with 块以 python `re.sub` 删除干净);③`.github/workflows/pr-checks.yml` 1 处同上;④`.github/workflows/release.yml` 1 处同上 + 5 项 deploy secrets 传 `with:`;⑤PyYAML 4 文件校验全 OK;⑥CHANGELOG v0.0.3 ## Added 加 composite action 条目;⑦wiki/log Round 13 记录 + 最后更新指针。STABILITY.md §2 internals may-change pre-1.0 适用(CI infra 是 internals);§1+§3 未涉及(no public API);loop §1 hard constraints 满足 — 不动 secret 值、不动 tag 触发器、不动 workflow 文件结构或 job 依赖,纯静态 YAML edit + local commit,never push 不触发 gh actions。scope 限定:不动 3 workflow 的 jobs / trigger / cache 路径,不动 secret env values,不动 server-* token 名(后续单独 R 候选)。
