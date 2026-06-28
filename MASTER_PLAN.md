# ResiCache Master Plan — 从 v0.0.3 到一个有竞争力的 v1.0

> **状态**: v1 战略与工程总纲(2026-06-28 制定)
> **制定方法**: 经多轮对抗式多 Agent 评审(20-agent 战略评估 + 12-agent 定位评审团 + Path-C 可行性验证)沉淀,定位决策由 3 评委独立打分(2:1)裁定。
> **一句话**: ResiCache 要成为 **"Redisson 忘了做的那条缓存防护链"** ——把散落的 `RLock`/`RBloomFilter`/手写 TTL 逻辑,收敛成一条可编排、可关断、可观测的声明式防护链(一个 `@RedisCacheable` 顶三十个手写原语)。

---

## 0. North Star(北极星,一切取舍的源头)

**定位**: `ResiCache for Redisson` —— Redisson 没有提供的、**可声明的缓存防护责任链**。

**为什么是这个定位(裁定理由:信任算术)**:
缓存中间件的采用是**信任决策**。一个 solo 维护、v0.0.x、白名单默认作者包、序列化不兼容的库,**对抗不了阿里背书的 JetCache**——无论技术多好。Wedge 2 是**唯一不要求采用者"信任一个 solo 库作为核心基建"**的定位:它只要求采用者**信任他们栈里已经有的 Redisson**,然后加一层薄封装。它向一个**有真实痛点空白**的具名在位者(Redisson)提出一个**可证伪**的问题:"Redisson 提供可声明的防护链吗?——没有。" 这个空白是可感知的。

**附带嫁接(graft,从落选 wedge 汲取的精华)**:
- **来自 Wedge 1**: `@Cacheable` 锁定用户是**次要受众/滩头阵地**——`jetcache-core` 零 Spring 依赖,意味着任何"已选用 `@Cacheable`+`@EnableCaching` 作为缓存契约"的团队,**结构上无法使用 JetCache 而不重写**。ResiCache 是这个滩头里唯一的纵深防护选项。
- **来自 Wedge 1**: **Path C 重构必须做**(wedge 无关)——销毁 `CacheOperationMetadataHolder` ThreadLocal 双向侧信道 + `CacheInterceptor` 继承。
- **来自 Wedge 3**: 内核无关化作为**长寿对冲**记入 **ADR-0005**,但**不是近期动作**(handlers 直接 import `RedisTemplate`/`CacheStatisticsCollector`/`NullValue`,抽出需 3 个端口而非 1 个,已验证"便宜抽出"为假)。价值:让 Spring/SDR 4 的 churn 局限在 adapter 层。
- **来自 Wedge 3**: **链级 Micrometer Observation**(一个 Observation 覆盖全部 5 机制 + per-handler tag)—— 企业可采购性的解锁键。
- **杀手 Demo**: "30 行加一个自定义 `RateLimitHandler` 插在 Bloom 和 SyncLock 之间" —— 让"可编排链"差异点一目了然。

**目标用户**:
1. 已用 Redis(尤其 Redisson / Redis Cluster)、把穿透/击穿/雪崩当**生产事故**而非理论的团队——他们要防护,不要第二个缓存抽象。
2. 已标准化在 `@Cacheable`+`@EnableCaching`、**不会为 JetCache 推翻缓存层**的中文 Spring Boot 团队(主滩头)。
3. 想在不 fork 的前提下**插入自定义防护策略**(熔断/限流/配额/布隆预热)的平台/中间件工程师。

**我们永远让给 JetCache 的(大声在 README 里说)**: 多级缓存、广播失效、API 级 `Cache.get/put`。**不与 JetCache 正面竞争**。

### 不可违背的原则(Non-Negotiable Principles)
1. **火是第一优先级,无例外**: 任何定位/特性都不得先于一条 Boot 3.5+/4.0 兼容构建(与 3.4.13 并行)落地。EOL 平台是采用者的自动否决项——**没有任何定位能在它之上存活**。
2. **永不静默降级**: 标榜"分布式"的防护,绝不允许静默退化为单 JVM。
3. **不近期做内核抽取**: 已验证为伪命题,只作 ADR-0005 对冲。
4. **Solo 节奏诚实**: 承诺"一个 Spring 主版本对应一次本项目主版本",否则项目腐烂。**成功标尺是 ~50–200 star + 2–3 个已知生产用户(12 个月内),不是 JetCache 量级**。不搞直接变现(付费 license/SaaS);只走 Sponsored dev / GitHub Sponsors / 咨询。

---

## 1. 现状 → 目标(差距地图)

| 维度 | 现状(v0.0.2/0.0.3) | 目标(v1.0) | 差距性质 |
|---|---|---|---|
| 平台 | Boot 3.4.13(**EOL**),Java 17,SDR 3.x | Boot 4.0 + Java 21 + SDR 4.0(主线),3.4 兼容线并行到 EOL+6mo | **P0 火灾** |
| 正确性 | sync 静默单 JVM 退化;锁 key 无 hash-tag;CLEAN 非原子+清空布隆 | fail-fast;Cluster hash-tag pinning;原子/延迟 CLEAN+重建 | **P0 硬化** |
| 架构耦合 | ThreadLocal 双向全耦合 + 继承 ~8–10 个 SDR 内部类型 | Path C: 销毁 ThreadLocal,继承面降到 ~6–7,语义零回归 | **P1 重构** |
| 可观测性 | 无 per-handler 指标;health 不级联;无 tracing | 链级 Observation + per-handler/per-cache tag + tracing 透传异步边界 | P1 |
| 默认值 | 5 防护属性全 false;preset 仅路线图 | STRICT/STANDARD/NONE preset + 注解级覆盖 | P1(DX) |
| DX/样例 | 无样例、无对比页、无网站 | before/after sample + 诚实对比页 + getting-started 站点 | P2(采用) |
| 迁移 | 信封序列化不兼容 Spring 原生;无迁移工具 | shadow→dual-write→cutover 工具 + 白名单自动探测 | P2 |
| 质量 | 64 测试,JaCoCo 70/40;无 JMH;无故障注入 | + JMH 基准 + Testcontainers 故障注入 + 回归契约 | P1 |
| 发布 | Maven Central 已配,未发 | 已发 Central + semver 策略 + 兼容矩阵 + 定期 release | P2 |
| 治理 | solo,Non-SLA | + 贡献 onboarding + 商业支持/咨询 on-ramp + 诚实 SLA | P3 |
| 差异化 | 链可插拔(未强调);Bloom(JetCache 无) | 把"可编排链+Bloom+Redisson 原生"做成 Hero | P2(护城河) |

---

## 2. Pillar 1 —— 工程与架构

### WS-1.1 THE FIRE:Boot 4 / SDR 4 / Java 21 兼容线 `[L][P0]`
- **为何**: Boot 3.4.x 自 2025-12 起 OSS-EOL,无安全补丁;**对新采用者是当前否决项**。SDR 4.0 恰在 `RedisCache`/反序列化类有 breaking changes(正是 ResiCache subclass 的那 8–10 个);issue #3348 证实 4.x 把 `RedisCacheWriter` 默认 sync→async(恶化 ThreadLocal 异步隐患)。
- **具体任务**:
  1. 建 `boot4` 分支/Profile,**双构建矩阵**: CI 同时跑 Boot 3.4.x+Java17 与 Boot 4.0+Java21(扩展现有 17/21 matrix 为 Boot×Java 二维)。
  2. 审计 8–10 个 SDR 内部扩展点对 SDR 4.0 的破坏:`RedisCacheManager.createRedisCache/getMissingCache`、`RedisCache`(super.get/put/evict/clear)、`RedisCacheWriter`、`CacheInterceptor`、3 个 `CacheOperation+Builder` 子类、`AnnotationCacheOperationSource`、autoconfigure 模块化后的类重定位。
  3. 升 Redisson 到与 SDR 4/Spring 7 兼容版本(3.5x+);验证 `RLock`/`RBloomFilter` API。
  4. 把 `RedisProCacheWriter.retrieve()/store()` 异步默认改为**显式接管**(见 Path C Step 6),或临时 `supportsAsyncRetrieve()=false` 作 legacy 线 shim。
- **完成判据**: `./mvnw verify` 在 Boot 4.0+Java21 全绿(含 Testcontainers);Boot 3.4 兼容线保留至其 EOL+6mo;`COMPATIBILITY.md` 列双矩阵。
- **依赖**: 无(最先做)。**风险**: SDR 4 对 `RedisCache` 内部签名大改可能迫使 subclass 改写——这正是 Path C 降耦合的动机,但 FIRE 不得等 Path C。

### WS-1.2 P0 企业硬化(三个承重正确性隐患) `[M][P0]`
- **1.2a `SyncSupport` fail-fast(不再静默单 JVM)**: 当 `distributedManagers.isEmpty()`(Redisson 不在 classpath,由 `@ConditionalOnClass` 结构性保证)且用户声明 `sync=true` 时,**启动期 fail-fast** 抛清晰错误(而非运行期静默走 `synchronized` 单 JVM)。配套链级 Observation 事件(`protection.degraded=local-only`)使安全属性**可观测**。保留 `synchronized` 作为"显式声明 `localOnly=true`"的合法降级,但**绝不静默**。
- **1.2b Cluster hash-tag pinning**: `DistributedLockManager.tryAcquire()` 的 `lockKey = prefix + key` 改为与缓存 key **同 slot**(hash-tag `{}`),或启动期探测 Cluster 并校验/自动加 tag;文档明确单机/哨兵/集群下锁行为。锁失效比"没锁"更糟。
- **1.2c 原子/延迟 CLEAN + 布隆重建**: `ActualCacheHandler` 的 `allEntries` CLEAN(非原子 SCAN+DEL+`bloomSupport.clear()` 整体清空 → 确定性穿透窗口)改为**原子化(Lua)**或**延迟重建**(evict 后异步重建布隆,期间临时放大 null-value 防护;或先重建再切换)。
- **完成判据**: 三项各有 Testcontainers 故障注入测试;Known Limitations 更新为"已修复"。

### WS-1.3 Path C 重构(销毁 ThreadLocal) `[L][P1]`
- 详见 **§6(已验证 7 步序列)**。一句话: 用 `ResiCacheMethodInterceptor`(implements `MethodInterceptor`,**不继承** `CacheInterceptor`)+ 显式生命周期 context carrier,替换 `CacheOperationMetadataHolder` ThreadLocal 侧信道;**保留** `RedisCacheWriter` 扩展缝;**顺带消灭** `retrieve()`/`store()` 在 `commonPool` 丢 ThreadLocal 的潜伏 bug。
- **完成判据**: 行为零回归(Step 0 契约测试前/后均绿);`CacheOperationMetadataHolder.java` 删除;ADR-0002 改写为"已解决";继承的 SDR 内部类型从 ~8–10 降到 ~6–7。
- **依赖**: WS-1.1(FIRE)完成后或并行;**不得先于 FIRE**。

### WS-1.4 可观测性(企业可采购性解锁) `[M][P1]`
- **链级 Micrometer Observation**: 一个 Observation 覆盖整条链,per-handler tag(`bloom.blocked`/`lock.acquired`/`early-refresh.triggered`/`null.hit`)+ per-cacheName tag。比 per-handler 单独埋点便宜,是三 wedge 一致认同的企业解锁键。
- **tracing 透传异步边界**: Path C 的 snapshot/restore 让 context carrier 跨 `commonPool` 存活,MDC/traceId 一并透传。
- **health 级联 + kill-switch 细化**: actuator health 反映防护链健康(而非当前不级联);单一全局 kill-switch 细化为 per-mechanism 运行时控制。
- **完成判据**: `/actuator/metrics/resicache.*` 暴露 hit/miss/load-time/各防护触发计数;sample 截图入 README。

### WS-1.5 质量:基准 + 可靠性测试 `[M][P1]`
- **JMH 基准**(填 v0.3.0 路线图): 链开销 vs 裸 Redisson vs 原生 Spring Cache;证明"防护代价可接受"。是 README 里"高性能"措辞**据实恢复**的前提(ADR-0001 已删该措辞)。
- **故障注入**: Testcontainers 场景——Redis 断连(降级 fail-open 安全性)、Redisson 缺失(fail-fast)、Cluster slot 不一致、并发击穿(锁串行化验证)、bulk evict 后穿透窗口(WS-1.2c 验证)。
- **完成判据**: JMH 报告入 `docs/benchmarks`;故障注入覆盖 5 类场景。

---

## 3. Pillar 2 —— 产品化与开源运营

### WS-2.1 命名/品牌/模块(对齐 North Star) `[S][P2]`
- **artifact/package 不大改**(solo 节奏诚实,避免无谓破坏):保留 `io.github.davidhlp:ResiCache`、包 `io.github.davidhlp.spring.cache.redis`。**信息层**重新定位:README/标题/tagline 从"Spring Cache 防护增强"→ **"ResiCache — Redisson 忘了做的那条缓存防护链"**(副标保留 @Cacheable 兼容)。
- **可选(后期 v1.0)**: 拆 `resicache-core`(零 Spring,留 ADR-0005 对冲缝)+ `resicache-spring-boot-starter`;**近期不做**。
- **Hero 化 `HandlerOrder`**: 把"链可插拔"从隐藏特性变成**首屏卖点**——ASCII 链图 + "30 行加 RateLimitHandler"demo。

### WS-2.2 文档与网站 `[L][P2]`
- **诚实对比页**(`docs/comparison.md` + README 内联表): ResiCache vs JetCache vs Caffeine vs 裸 Redisson,**明确 owns ResiCache 输在哪**(多级/广播/API 级),赢在哪(可编排链+Bloom+声明式)。**solo 库的通货是可信度**——Redisson-companion 定位下必须**先发制人**回答"为什么不直接调 `redissonClient.getBloomFilter()`?"。
- **before/after runnable sample**(独立 repo `resicache-examples` 或 `/examples`): **同一业务**两版——(A)手写 RLock+RBloomFilter+TTL 散落各处 vs (B)一个 `@RedisCacheable`。**这是这个定位的生存机制**,不是附属交付物。链价值只在"~30 个缓存用一套一致策略"时显现,sample 要展示规模感。
- **getting-started 站点**: 5 分钟"它跑起来了"体验;starter 模块 + sensible preset。可选 MkDocs/Material 发布 GitHub Pages。
- **完成判据**: 对比页 + sample + 5-min 指南上线;i18n(中英)。

### WS-2.3 迁移工具 `[L][P2]`
- **序列化迁移**: shadow(双读)→ dual-write(双写)→ cutover(切流)三阶段工具(填 v0.2.0 路线图);解决信封与 Spring 原生 `GenericJackson2JsonRedisSerializer`/`JdkSerializer` 不兼容导致"切流即全 miss"。
- **白名单自动探测**: 启动期扫描被缓存类型,提示/自动填充 `allowed-package-prefixes`(消除"白名单默认作者包"信任税)。
- **注解迁移指南**: `@Cacheable`→`@RedisCacheable`(SELECTIVE 已共存,指南聚焦"哪些方法该升级获得防护")。
- **完成判据**: 迁移工具 + 可照抄 runbook。

### WS-2.4 发布与版本治理 `[M][P2]`
- **激活已配置的 Maven Central**: `central-publishing-maven-plugin`+`maven-gpg-plugin`+source+javadoc 已就位——**做第一次 release**(v0.1.0)。补 CI secret(portal token/gpg)。
- **semver 策略**(显式): `<1.0` 期间 minor 可破坏(CHANGELOG 标 ⚠️);v1.0 后 API freeze。Path C 作为 v0.1.0 minor(二进制兼容,仅 CHANGELOG 注明 `RedisCacheInterceptor` 子类化的 niche 破坏)。
- **兼容矩阵**(`COMPATIBILITY.md`): Boot 3.4/4.0 × Java 17/21 × Redisson 有/无 × Redis 单机/哨兵/集群。
- **release cadence**: 与 Spring 主版本对齐。

### WS-2.5 社区与可持续性 `[M][P3]`
- **贡献 onboarding**: `CONTRIBUTING.md` 补"如何加一个防护 handler"(4 步,链 wiki 已有)作为**最低门槛贡献入口**——外部贡献者无需碰核心即可加 handler(链架构的 force-multiplier)。
- **诚实的 issue SLA**: Non-SLA best-effort 如实写,但承诺"活跃回应"。
- **商业支持/咨询 on-ramp**: GitHub Sponsors + "生产落地咨询/付费支持"——**唯一可行可持续路径**。protection-audit CLI(见 WS-3.2)是天然咨询 lead-gen 工具。

---

## 4. Pillar 3 —— 差异化、路线图、度量、风险

### WS-3.1 差异化特性(把护城河做实) `[按 defensibility/effort 排序]`
1. **`protection.preset` 套餐**(STRICT/STANDARD/NONE, v0.2.0 拉前): 一键安全默认 + 注解级覆盖。"诚实 safe-by-default"是定位的中名,不能停在路线图。
2. **可编排链 SPI 作为一等公民**: `HandlerOrder` 间隔=100 单一真理源(已有);补"自定义 handler 30 行接入"官方 demo + 文档。
3. **`RateLimitHandler` 官方扩展**(可选模块 `resicache-extensions`): 把 Resilience4j 限流作为链上一档,证明链能容纳"非防护"语义——**让链成为通用缓存策略管线**,而不只是 5 个固定防护。
4. **per-handler 热重载**: 运行期调整某档参数(bloom `expectedInsertions`、TTL `variance`)无需重启(注解静态化的运行时覆盖层,补 INHERENT 短板)。
5. **Redisson 原生深化**: BloomFilterHandler 的 `RedisBloomIFilter` 切到 Redisson `RBloomFilter`(对齐"for Redisson"定位,减少自维护实现);锁已用 Redisson。

### WS-3.2 protection-audit CLI(高价值低体量产物) `[S][P2-P3]`
- 扫描代码库 `@Cacheable`/`@RedisCacheable` 使用,**报告未防护的穿透/击穿/雪崩路径**。solo 维护者**最佳投入产出比**产物:可经 Sponsors/咨询变现、任何 wedge 下适用、咨询 on-ramp 的直接 lead-gen。

### WS-3.3 度量(成功标尺)
- **北极星(代理)**: Central 月下载量增长(代理"生产中被保护的缓存操作")。
- **支撑领先指标**: GitHub star(12mo 目标 50–200)、sample 克隆、issue/PR 活跃度、**已知生产用户数(目标 2–3)**、对比页访问。
- **不做**: 追逐 JetCache 量级 star。

### WS-3.4 风险登记 + 终止/转向判据

| # | 风险 | 概率 | 影响 | 缓解 | 触发转向 |
|---|---|---|---|---|---|
| R1 | SDR 4.0 破坏 subclass 内部签名 | 高 | 高 | WS-1.1 双构建 + Path C 降耦合面 | — |
| R2 | JetCache 补 Bloom | 中 | 高 | 差异点上移到"可编排链"(更难抄) | 若 JetCache 出可插拔链 → 转 Wedge 1 |
| R3 | solo 维护不可持续 | 中 | 致命 | "一 Spring 主版本一主版本"承诺;压窄 scope;找 co-maintainer | 12mo 内 0 生产用户 + 0 star 增长 → maintenance-only 或并 JetCache |
| R4 | "为何不直接用 Redisson 原语?"质疑 | 高 | 中 | before/after sample + 对比页先发制人;价值在 ~30 缓存规模显现 | — |
| R5 | Redisson 破坏 `RLock`/`RBloomFilter` API | 低 | 中 | 锁定 Redisson 版本矩阵;兼容层 | — |
| R6 | Boot 4 迁移超 solo 能力 | 中 | 高 | 优先 FIRE;必要时延长 3.4 兼容线 + 明确 EOL 沟通 | — |

**终止/转向判据(kill criteria)**: 12 个月内若 ① 0 已知生产用户 且 ② star 增长 <20 且 ③ 无 co-maintainer 加入 → **转 maintenance-only**,或**贡献进 JetCache 生态**(补 Bloom + 可插拔链)。诚实接受"高质量个人项目/教学项目"作为下限结局。

---

## 5. 序贯路线图(分阶段,带门禁)

| 版本 | 主题 | 门禁(可发布的判据) | 推进 North Star |
|---|---|---|---|
| **v0.1.0** ★FIRE+硬化 | Boot 4/SDR4/Java21 兼容线 + 3 个 P0 硬化(fail-fast/hash-tag/原子 CLEAN) + Path C 重构(销毁 ThreadLocal) + 第一次发 Maven Central | 双构建 verify 全绿;3 硬化各有故障注入测试;Path C 零回归;Central 可拉取 | 止血:移除 EOL 否决项 + 消灭静默降级;降耦合面 |
| **v0.2.0** 定位落地 | `protection.preset` + 链级 Micrometer Observation + before/after sample + 诚实对比页 + Redisson 原生 Bloom(`RBloomFilter`) | preset 文档齐;`/actuator/metrics/resicache.*` 可见;sample+对比页上线 | 把"Redisson-companion + 可编排链"做成可感知卖点 |
| **v0.3.0** 可信度 | per-handler observability 深度 + tracing 异步透传 + JMH 基准 + 序列化迁移工具 + 白名单自动探测 | JMH 报告入 docs;迁移 runbook 可照抄;tracing 跨 commonPool 验证 | 据实恢复性能陈述;消除序列化信任税 |
| **v1.0.0** 发布 | API freeze + getting-started 网站 + RateLimitHandler composability demo + protection-audit CLI + 治理(Sponsors/咨询 on-ramp) | API 稳定性测试;网站上线;2–3 种部署矩阵验证;launch 文章 | 正式发布:护城河实化 + 可持续路径就位 |

> 序列约束: **FIRE(v0.1)先于一切**;Path C 与硬化在 v0.1 内并行或紧随;**不在 v1.0 前做内核抽取**(ADR-0005 对冲)。

---

## 6. Path C —— 已验证的 7 步重构序列(Feasibility: **YES**)

> 来源: Path-C 可行性验证 agent(对实际代码 39 次工具调用核验)。核心约束: `Cache.get(Object key, Callable valueLoader)` 签名不可变,故方法元数据**不能**穿签名传递——必须由 interceptor 拥有、scoped、可 snapshot 的 carrier 在调用线程整个 `CacheAspectSupport.execute()` 期间(含同步 `Cache.get()`)保持 active。

- **STEP 0 — 冻结公共面为回归契约**: `@RedisCacheable/@Put/@Evict/@Caching` 语义、`RedisProCacheManager` bean、`RedisCacheRegister`、5 个 `@HandlerPriority` handler、`nativeAnnotationMode`。加一个 **AOP 行为保持测试**:断言纯 `@Cacheable` 仍正确命中/未命中链 **且** `@RedisCacheable` 仍获得 bloom+sync+ttl。**Step 0 测试不绿,不开始 Step 1。**
- **STEP 1 — 引入 `MethodMetadataResolver` 接口(缝)**: `currentMethod()`/`currentTargetClass()`/`currentKey()` + try/finally-scoped activation。默认实现**内部仍**用 ThreadLocal(private,**非** public `CacheOperationMetadataHolder`)。`RedisProCacheWriter.buildContext()` 与 `RedisProCache.lookupOperation()` 改读 resolver。**无操作重构**(行为不变),一刀切断双向静态耦合。Step 0 测试须仍绿。
- **STEP 2 — 设计跨异步存活的 per-invocation context carrier**: `CacheInvocationContext` 值对象(method/targetClass/key/resolved CacheOperation)+ `snapshot()`/`restore(snapshot)` 对。JDK21+ 用 `ScopedValue`,否则 managed ThreadLocal + 显式 `close()`。**尚无行为变化**。
- **STEP 3 — 用 ResiCache 自有 `MethodInterceptor` 替换 `CacheInterceptor` 继承**: `ResiCacheMethodInterceptor implements MethodInterceptor`(**不 extends `CacheInterceptor`**)。`invoke()`: (a) 在 joinpoint 从 `invocation.getMethod()/getThis()` 解析 method/target;(b) 跑现有 `AnnotationHandler` 链填充 `RedisCacheRegister`(即当前 `handlerChain.handle(method,target,args)`,只是搬迁);(c) 激活 `CacheInvocationContext`;(d) try/finally 委托 Spring `CacheAspectSupport`(见 Step 4);(e) finally 反激活。Steps 4–5 落地后**删除** `RedisCacheInterceptor` 与 `CacheOperationMetadataHolder`。
- **STEP 4 — `CacheAspectSupport` 作组合协作者(非基类)**: 在 `ResiCacheMethodInterceptor` 内组合 `CacheAspectSupport`,wire `setCacheOperationSource`/`setCacheManager`/`setKeyGenerator`,调 `aspectSupport.execute(...)`。Spring 原生 condition/unless/sync/key-resolution 与对 `Cache.get(Object,Callable)` 的调用完整保留;per-method 元数据因 resolver context 在**本线程整个 execute() 期间**(含同步 `Cache.get()`)active 而到达。**这是绕开 Cache.get 不可变签名、又不靠裸 ThreadLocal 的关键。**
- **STEP 5 — 重接 advisor 与 operation source**: `RedisProxyCachingConfiguration` 把 `redisCacheAdvisor` 的 advice 从 `RedisCacheInterceptor` 换成 `ResiCacheMethodInterceptor`;**保留** `BeanFactoryCacheOperationSourceAdvisor` + order=50;**保留** `RedisCacheOperationSource extends AnnotationCacheOperationSource`。**注意**: 双 advisor 此处**变微妙**(两个 advice 在重叠 pointcut)——SELECTIVE 在 OperationSource 层去重,需验证 ResiCache advisor 先运行填 register。**需专门多注解集成测试。**
- **STEP 6 — 重新接管异步路径(强制,非可选)**: `retrieve()`/`store()` 当前在 `commonPool` 上 `supplyAsync/runAsync` 会丢 resolver context(潜伏 bug)。修:调用线程 `resolver.snapshot()`,异步体内 `restore(snapshot)`…finally `deactivate()`。**使 per-method 元数据跨异步边界存活——当前 ThreadLocal 做不到。这是 Path C 的正确性红利**。legacy 线备选 `supportsAsyncRetrieve()=false` shim。
- **STEP 7 — 清理死耦合**: 全部消费者改读 resolver 后**删除** `holder/CacheOperationMetadataHolder.java`;ADR-0002 从"不可能三角"改写为"经 MethodMetadataResolver 解决";跑 checkstyle + 全 `./mvnw verify` + 集成测试。

**保留的风险(Path C 不解决)**:
- 仍 subclass ~6–7 个 SDR 内部类(从 ~8–10 降),SDR 4 churn 仍命中 Cache/Writer/Manager 轴(ADR-0005 内核对冲仍是长期答案)。
- `CacheAspectSupport.execute()` 是 protected → Step 4 组合需内部 helper 继承或反射/复制(往 spring-context 继承跳,严格比 SDR 稳定但非零耦合)。
- 双 advisor SELECTIVE 在生产未验证(Step 5 标记,需专门测试)。
- ScopedValue 的 Java 分裂(legacy JDK17 线仍需 managed ThreadLocal,营销说"销毁 ThreadLocal"仅 JDK21+ 线字面成立)。
- 嵌套/递归 `@RedisCacheable`(A 调 B 都缓存)→ carrier 必须是**栈**而非单值(需专门嵌套缓存集成测试)。

**迁移安全**: 对终端用户二进制兼容(Path C 只动内部机器);唯一可观察行为变化是若有用户 subclass 了 `RedisCacheInterceptor`(未文档化、罕见)→ CHANGELOG 标破坏,提供 `MethodMetadataResolver` SPI 作为支持扩展点。**作为 v0.1.0 minor 发布**,低风险,由 Step 0 回归测试守护。

---

## 7. 头 30 天(立即可执行)

> 目的: 灭火 + 给项目一个"今天就能被严肃评估"的基线。

1. **Week 1 — FIRE 启动**: 开 `boot4` 分支,升 parent 到 Spring Boot 4.0.0,跑 `verify`,**列出全部编译/运行期破坏点**(预计集中在 `RedisCache`/`RedisCacheWriter`/序列化)。同步升 Redisson 到兼容版。产出: SDR 4 破坏清单 + 双构建 CI matrix。
2. **Week 2 — P0 硬化**: 实现 `SyncSupport` fail-fast(WS-1.2a)+ Cluster hash-tag(WS-1.2b)。写 Testcontainers 故障注入测试。这两项**改动小、收益大、风险低**,先于 Path C 落地。
3. **Week 3 — 原子 CLEAN + Path C Step 0–1**: WS-1.2c 原子/延迟 CLEAN;然后 Path C Step 0(回归契约测试)+ Step 1(`MethodMetadataResolver` 无操作重构)。
4. **Week 4 — 发布 v0.1.0-alpha**: Boot 4 兼容线 + 3 硬化 + Path C Step 0–1,**发 Maven Central**(激活已配置发布插件)。README tagline 改 Redisson-companion 定位。开 GitHub Release + CHANGELOG。

---

## 附:关键 ADR 待办
- **ADR-0005**: 内核无关化作为长寿对冲(不近期执行)——记录 handlers 直接依赖 `RedisTemplate`/`CacheStatisticsCollector`/`NullValue` → 抽出需 3 端口,验证"便宜抽出"为假。
- **ADR-0002(改写)**: 从"不可能三角"→"经 MethodMetadataResolver 解决"。
- **ADR-0006(新)**: Redisson-companion 定位裁定(信任算术 + 2:1 评委裁定 + grafts),取代 ADR-0001 的叙事。

---

*本计划以实际源码、ADR、CI/发布配置与多轮对抗式评审结论为依据制定。任何后续重大偏离应更新本文件并记入 `wiki/log.md`。*
