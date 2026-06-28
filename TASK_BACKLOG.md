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

- [ ] **WS-1.3 Path C**(销毁 ThreadLocal)— v0.1.0 最大块,**Step 0 都没启动**。7 步全未做:
  - [ ] Step 0 — AOP 行为回归契约测试(纯 `@Cacheable` 命中链 + `@RedisCacheable` 获 bloom+sync+ttl 断言),全绿后方进 Step 1
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
- [ ] **文档治理** — README Roadmap v0.1.0 行(`README.md:284`)仍是 v0.0.2 时代 "Bean-graph redesign (@ConditionalOnMissingBean override contract)",Status Planned;改主题对齐 MASTER_PLAN §5 并据 FIRE+硬化已 merge 调整 Status;同步 `README.zh-CN.md`

---

## 4. 🟢 P2 — v0.2.0(按序贯路线图未启动,非偏离)

- [ ] **WS-1.4** 链级 Micrometer Observation — 一个 Observation 覆盖全链 + per-handler tag(`bloom.blocked`/`lock.acquired`/`early-refresh.triggered`/`null.hit`)+ per-cacheName。当前 0 行实现,仅 3 处 TODO 注释
- [ ] **WS-1.4** tracing 跨 `commonPool` 透传(**依赖 Path C Step 6**)— MDC/traceId 跨 commonPool 存活
- [ ] **WS-1.4** health 级联 — 改造 `RedisCacheHealthIndicator`(现仅 Redis PING,javadoc 自述 "Does not cascade"),聚合各机制健康(含 `protection.degraded=local-only`)
- [ ] **WS-1.4** kill-switch 细化 — 单一全局 `resi-cache.protection.enabled`(`CacheHandlerChainFactory:78`)→ per-mechanism 运行时控制
- [ ] **WS-1.4** metrics 默认开启评估(现 `MetricsAutoConfiguration` `metrics.enabled=true` 默认关)+ `/actuator/metrics/resicache.*` 端到端测试 + README sample 截图
- [ ] **WS-1.5** JMH 基准完全缺失(无 jmh 依赖、零 `@Benchmark`、无 `docs/benchmarks`)— 新增 JMH 模块,对比防护链开销 vs 裸 Redisson vs 原生 Spring Cache。亦是 ADR-0001 高性能措辞 v0.3.0 据实恢复的前提
- [ ] **WS-1.5** 故障注入补 Redis 断连场景(完全缺失)— 用 `RedisContainer.stop()` 或 Toxiproxy 验 fail-open 安全性;现有 4 类多为单元 mock,升级为真 Testcontainers 故障注入
- [ ] **WS-3.1.1** `protection.preset`(STRICT/STANDARD/NONE)— `RedisProCacheProperties.ProtectionProperties` 现仅 `enabled` 布尔;注解级 preset 覆盖未实现
- [ ] **WS-3.1.5** Bloom 切 Redisson `RBloomFilter` — 现 `RedisBloomIFilter` 仍用 RedisTemplate+RedisCallback 自维护位运算(grep RBloomFilter 零命中)
- [ ] **WS-3.1.4** per-handler 运行期热重载 — 支持 bloom `expectedInsertions`/TTL `variance` 不重启动态调整(grep hotReload/dynamicConfig 零命中)
- [ ] **文档治理** — ADR-0002 改写"经 MethodMetadataResolver 解决"(待 Path C Step 7,当前正确推迟)

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
- [ ] **文档治理** — 补登记 `wiki/index.md`:在 ADR 节追加 `[[0005-kernel-extraction-hedge]]` 与 `[[0006-redisson-companion-positioning]]`,frontmatter `updated` 刷到 2026-06-28
- [ ] **文档治理** — 补 `wiki/log.md` WS-1.1 FIRE 条目(grep `FIRE|WS-1.1` = 0;FIRE 已 merge 进 master `38c514a` 却未入 wiki 操作日志)
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
