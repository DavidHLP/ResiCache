# ResiCache 竞争力战略终稿：在 Redisson 阴影下做出不可被忽视的 wedge

> 文档定位：这是写给 maintainer 的**最终、可执行、有取舍**的竞争力指南。它**建立在 ADR-0001..0007、v0.1→v1.0 Roadmap、CHANGELOG、CI/governance 已有沉淀之上**，绝不重新发明已决策之事。所有结论均经源码、配置、git 历史、竞品 issue 实证校验。
> 时代锚点：2026-06。竞品数据 fetch 自 github.com/alibaba/jetcache、redisson.pro、docs.spring.io 等，截至 2026-06-29。

---

## 1. 执行摘要：唯一的核心判断 + 北极星指标

### 1.1 一句话核心判断

> **ResiCache 的真实技术增量不是 ADR-0006 所述的"Bloom + 可插拔链"（1 个 gap），而是"JetCache 缺失的 3 个防护、打包进一个 Redisson 原生的责任链"（3 个 gap）。但这个 wedge 本身无法赢得信任——赢得信任的唯一路径是把 library 做成 installable、demonstrable、falsifiable,让自管 Redis/Redisson 那个尚未被任何 managed layer 覆盖的细分市场能基于事实评估它。**

ADR-0006 是对的：缓存采用是信任决策，solo v0.0.x 在 Alibaba 量级的信任战里赢不了。但 ADR-0006 的算术有一个**事实错误**——它把 TTL jitter 计入 JetCache 的覆盖范围（JetCache Issue #269 已 closed unimplemented）。修正这个错误**强化而非削弱**竞争力：真正的 gap 从 1 变成 3。

### 1.2 三条不可妥协的事实

| # | 事实 | 证据 | 战略含义 |
|---|------|------|----------|
| 1 | **artifact 无法被解析** | `pom.xml` 版本 0.0.2；`release.yml` 用 `JAVA_VERSION '17'` + `OSSRH_*` secrets；`gh repo view` 显示 `latestRelease: null`；0 stars/forks | 企业无法采用一个无法 resolve 的依赖——**所有其它信任投入都是理论上的，直到 artifact 从 Maven Central 解析出来** |
| 2 | **wedge 被严重低估** | JetCache Issue #269 closed unimplemented（TTL jitter 0%）；`@CachePenetrationProtect` 是 JVM-only 不是分布式（breakdown partial）；`docs/comparison.md` line 22 错误标 ✅ | 真实 gap = 3（bloom + jitter + distributed breakdown lock），不是 1。重述 wedge 让它**可被验证** |
| 3 | **信任无法被制造,只能被工程化拆除** | 4 个 friction：solo / v0.0.x / 序列化不兼容 / 白名单默认作者包。其中 3 个是机械问题（artifact、rejection message、whitelist default），1 个是结构性问题（bus factor=1） | 把 trust 当作"可逐项拆除的工程目标",而非"静态局限" |

### 1.3 北极星指标（单一指标）

> **"一个陌生人在装有 Docker 的干净机器上,10 分钟内 `git clone && ./mvnw dependency:get` 解析 artifact、`docker-compose up` 起到 Redis、curl 三个 endpoint 看到三个不同的 handler 触发,且能看到每个 handler 的 SLO 是否命中。"**

这一个指标折叠了 4 个 gate：installable（artifact resolves）、demonstrable（sample 跑起来）、falsifiable（SLO 命中/未命中）、trustworthy（每个 handler 行为可观测）。**达成它,ResiCache 就从"内部 memo"变成"可被采购评审评估的 artifact"。**

### 1.4 不追求的目标（诚实声明）

- **不追求**在信任规模上打败 JetCache（Alibaba 5.6k stars vs solo v0.0.x——数学上不可能）。
- **不追求** multi-level cache / L1 / broadcast invalidation（ADR-0006 已明确放弃,所有竞品 lens 都验证这是正确的）。
- **不追求** Reactive/WebFlux（real open gap vs hanqunfeng 的 11-star reactive starter,但需要非阻塞 interceptor 重设计——deferred,not dropped）。
- **不追求** managed-layer 客户（Momento / Redis Cloud 已 server-side 吸收 breakdown + hot-key）——定位为 out-of-scope,不是 lost。

---

## 2. 竞品矩阵：5 大防护问题 + 关键信任轴

### 2.1 防护覆盖矩阵（已逐项实证校验）

| 防护问题 | ResiCache (v0.0.2) | JetCache 2.7.x/2.8RC | Redisson-native | Spring-native | Caffeine |
|---------|-------------------|----------------------|-----------------|---------------|----------|
| **Penetration / Bloom** | ✅ `BloomFilterHandler`(100),分层 Local/Redis Bloom,**声明式注解自动 guard** | ❌ 无原生 Bloom,需外接 Redisson `RBloomFilter` | ⚠️ 有 `RBloomFilter`/`RBloomFilterNative` 但**未接 CacheManager/注解**,DIY | ❌ 无 | ⚠️ 仅 `caffeine-simulator` 研究包(曾有 100% FP bug #85) |
| **Penetration / Null-value** | ✅ `NullValueHandler`(400) | ✅ `@Cached(cacheNullValue=true)`(default false) | ✅ `NullValue.INSTANCE` via Spring Cache | ⚠️ 默认 ON 但**无 short TTL,无 null marker**——footgun | ❌ 无 first-class policy |
| **Breakdown / 分布式锁** | ✅ `SyncLockHandler`(200) Redisson `RLock`,**跨 JVM** | ⚠️ `@CachePenetrationProtect` **单 JVM only**(命名误导,实为 breakdown) | ⚠️ 有 `RLock` 但**无 single-flight wiring,无注解**,DIY | ❌ `sync=true` 在 cache-**region** 级锁(issue #1253),非 per-key,且 throughput collapse | ⚠️ 仅 JVM 内 `cache.get` atomic load |
| **Hot-key / Early refresh** | ✅ `EarlyExpirationHandler`(250) Redis Lua CAS,**fleet-wide 单次 refresh** | ✅ `@CacheRefresh`(refreshLockTimeout 分布式锁)+stopRefreshAfterLastAccess | ❌ 无 `getWithTTL`,无 refresh-ahead | ❌ 纯 reactive/expiry-driven | ⚠️ `refreshAfterWrite` **per-JVM**,无 cross-instance 去重 |
| **Avalanche / TTL jitter** | ✅ `TtlHandler`(300) 可配置 jitter per-write | ❌ **Issue #269 closed unimplemented**,Config.md 无 jitter 属性 | ❌ TTL 字面遵守(issue #4458 unresolved) | ❌ 仅 `TtlFunction` SPI,DIY jitter math | ⚠️ `expireAfterVariably` per-JVM,无 fleet-wide 协调 |
| **汇总(粗略)** | **5/5** | **~2.5/5**(refresh + null-value full;breakdown partial-JVM-only;bloom 0;jitter 0) | **1/5**(null-value) | **0.5/5**(null-value footgun 算 partial) | **1.5/5**(breakdown-JVM + refresh-JVM) |

**关键发现**:ResiCache 相对 JetCache 的真实增量是 **3 个 gap**(bloom + jitter + distributed breakdown lock),不是 ADR-0006 所述的 1 个。**相对 Redisson-native 的增量是 4 个 gap**(以上 3 个 + early-refresh,因为 Redisson 连 `getWithTTL` 都没有)。

### 2.2 信任轴矩阵

| 信任轴 | ResiCache | JetCache | Redisson | Spring-native | Caffeine |
|--------|----------|----------|----------|---------------|----------|
| **企业背书** | ❌ solo(DavidHLP) | ✅ Alibaba | ✅ Redisson PRO(商业)+ Apache 2.0 | ✅ VMware/Broadcom | ⚠️ 单人(Ben Manes)但社区信任强 |
| **GitHub stars** | 0 | 5.6k | ~23k+ | N/A(框架内) | ~17k+ |
| **Maven Central artifact** | ❌ **未发布** | ✅ `com.alicp.jetcache` | ✅ `org.redisson` | ✅(随 Boot) | ✅ |
| **序列化兼容性** | ❌ `{version,payload}` envelope 不兼容 Spring native | ✅ java/kryo5/fastjson2/jackson | ✅ 标准 | ✅ 可选 JDK/JSON | N/A(本地) |
| **白名单默认** | ❌ 默认 `io.github.davidhlp`(作者包) | ✅ 无 author-package lock | ✅ 无 | ✅ 无 | N/A |
| **API 稳定性承诺** | ❌ pre-1.0,API 可能在 patch 变 | ✅ 2.7.x stable | ✅ 多年 stable | ✅ 多年 stable | ✅ |
| **CVE posture / SBOM** | ❌ 无 codeql/dependency-check/SBOM | 未验证 | ✅ Apache 2.0 + PRO | ✅ CVE 主线修复 | ✅ |
| **Bootstrap 4 / Java 21** | ⚠️ pom 已 Boot 4,**但 release.yml 还用 Java 17** | ✅ 2.8.0.RC 已 Boot 4 | ✅ Boot 1.3–4.0 | ✅ Boot 4 GA | ✅ |
| **Reactive 支持** | ❌ blocking interceptor | ❌ 同 ResiCache | ⚠️ 部分 | ✅ | N/A |
| **Multi-level cache** | ❌ 明确放弃 | ✅ L1+L2 + broadcast | ✅ `RLocalCachedMap` | ⚠️ Boot 7 加原生两tier | ✅(L1) |
| **维护活跃度** | 50 commits/90 天(内部活跃,**外部不可见**) | 1-3 releases/年 | 频繁 release | 主线 | 慢但稳 |

**信任轴结论**:ResiCache 在**每一个外部可见的信任维度**都落后——不是技术不行,而是**内部 rigor 未被外部化**。677 tests、70%/40% JaCoCo gate、Boot 4 单 build line 这些真实存在的资产,因为 artifact 不 resolve 而全部失效。

---

## 3. 战略主题：四大支柱

### 支柱 A：Installability Floor(安装性地基)——不解锁这一层,其它全部无效

**Thesis**:一个企业无法采用一个无法 resolve 的依赖。当前 ResiCache 的 release pipeline 在三条独立路径上断裂(`JAVA_VERSION 17` vs Boot 4 的 Java 21 floor;`OSSRH_*` secrets vs Central 已迁的 `MAVEN_*`;`s01.oss.sonatype.org` 死链 vs `central.sonatype.com`)。`latestRelease: null`,**从未成功跑过一次 release**。这是**所有下游信任投入的前提 gate**。

**Concrete moves**:

- [ ] `release.yml`: `JAVA_VERSION '17'` → `'21'`;`OSSRH_USERNAME/OSSRH_PASSWORD/GPG_PRIVATE_KEY` → `MAVEN_USERNAME/MAVEN_PASSWORD/MAVEN_GPG_PRIVATE_KEY`;environment URL 死链 → `central.sonatype.com`
- [ ] **Dry-run 先行**:tag push `v0.0.3-rc1` 跑完整链路(staging repo)→ 确认绿 → 才做真实 publish。**这是唯一可接受的 acceptance gate**
- [ ] **删除误导性 `v1.0` git tag**(CHANGELOG 自己已 disclaim:"an early development milestone... does not denote an API-stable or publicly released version")。0-star never-released repo 上挂个 v1.0 = 采购评审视为 carelessness 或 deception
- [ ] **首个 GitHub Release** 配 v0.0.3 tag,带 CHANGELOG-derived body
- [ ] **STABILITY.md**:把埋在 CHANGELOG 的 pre-1.0 caveat 提升为一等公民契约——明确"0.x 什么能变(internal impl、default values、internals package layout)、什么不能变(`@RedisCacheable`/`@CachePut`/`@CacheEvict` 签名、`resi-cache.*` property 名、`{version,payload}` wire format)、1.0 graduation criteria"。**一个 URL 答"什么 stable 什么不 stable"——这就是 v1.0 promise 的具体内容**
- [ ] **reconcile 自相矛盾**:gh repo description "Spring Boot 3" → Boot 4;README 3.4.13/17/3.27.0 → 4.0.0/21/3.50;`COMPATIBILITY.md` 所有 `verify -Pboot4 -B` → `verify -B`(ADR-0007 commit `9ad22bf` 删了 profile);"672 tests" → "677 tests" 或 "700+"(不低于真相)

**Success metrics**:
- `mvn dependency:get -a io.github.davidhlp:ResiCache:0.0.3:pom` 在干净容器(非本地 cache)从 `repo1.maven.org/maven2` 解析成功
- `git tag -l | grep v1.0` 返回空
- `grep -riE 'spring boot 3|Pboot4'` across README*/COMPATIBILITY/.github 返回零
- STABILITY.md 被 README badge 引用,1.0 graduation criteria 被枚举(非 aspirational)

**Anti-patterns**:
- ❌ Pipeline 一动就 scope creep(重做 signing keys、迁移 release plugin)——**只修阻塞 v0.0.3 resolve 的东西,其它全 defer**
- ❌ 在 artifact resolve 之前做任何 SEO/benchmark/blog 推广——它们指向 Central 上的 404

**Risk register**:dry-run 失败的 root cause 可能是 Central portal 账号状态、GPG key 过期、或 Java 21 编译错误。每条都有独立 mitigation——不要在 dry-run 红之前对外承诺发布日期。

---

### 支柱 B：Trust Engineering(信任工程)——ADR-0006 的核心 thesis,最高杠杆

**Thesis**:信任不是被制造的,是被**逐项工程化拆除的**。4 个 friction 不等价——3 个是机械问题(artifact、rejection message、whitelist default),1 个是结构性问题(bus factor=1)。本支柱按"机械先行"排序,因为每项都是 1-2 小时修复,各自解锁一个完整的 procurement checkpoint。**非目标(明确声明,per ADR-0006):本程序不制造 Alibaba 量级信任。它让 ResiCache installable + demonstrable + falsifiable + survivable,使真实技术增量能被自管 Redis 细分市场基于事实评估。**

**Concrete moves**(按 friction 类型分组):

#### B1. 机械 friction(可逐项拆除,每项独立解锁一个 procurement gate)

- [ ] **白名单 first-contact 修复(双管)**:
  - `SecureJacksonRedisSerializer.java:186-188` 的 rejection message 追加 remediation:`"Type not in deserialization whitelist: <className>. Add its package to resi-cache.serializer.allowed-package-prefixes."`——一行编辑,消除最高频 doc round-trip
  - **greenfield 默认**:auto-derive `@ConfigurationProperties` host app 的 root package via BeanFactory introspection,startup WARN log。**尊重 ADR-0003**——envelope 不变,只修默认列表 UX
  - 当前 `RedisProCacheProperties.SerializerProperties` line 121-122 默认 `List.of("io.github.davidhlp")`——**library 信任作者自己的类型却不信任采用者的类型**,这是最 tone-deaf 的 first-contact failure
- [ ] **fail-fast 序列化 pre-flight probe**(v0.0.3 对 v0.2.0 migration tool 的 down-payment):
  - startup-time PROBE mode:采样 N keys,检测非 envelope payload,log prominent WARNING 链接 migration recipe
  - **不是** v0.2.0 的 shadow→dual-write→cutover CLI——只是把"生产惊喜"转成"startup 警告"
  - 尊重 subtraction discipline(诊断工具,非迁移工具)+ ADR-0003(envelope 不松)
- [ ] **STANDARD preset 回溯到 v0.0.3**(从 ADR-0004/v0.2.0 拉前):
  - 小 enum + default-attribute override,启用 3 个 infra-light 防护(TTL jitter + null-value + early-expiration,无需额外组件)
  - STRICT(加 bloom + distributed lock,需 Redisson)保持 opt-in
  - **critical**:STANDARD 是新装默认;**v0.0.2→v0.0.3 升级必须显式 opt-in**,CHANGELOG 标 ⚠️ BREAKING(行为默认变更)。决定 deliberate,不要让默认静默骑行
  - 当前 `@RedisCacheable` 5 个防护属性全 false——写 `@RedisCacheable(value="users", key="#id")` 得到和 plain `@Cacheable` 完全一样的行为(silent value-delivery failure)
- [ ] **security/compat posture 升级到 enterprise-procurement-grade**:
  - 加 CycloneDX `./mvnw org.cyclonedx:cyclonedx-maven-plugin:makeBom` 到 release workflow,publish SBOM 作为 release artifact
  - 把 `ci.yml` 的 dependency-check job 从 `continue-on-error` 提升为 gate(line 138/166 已有但 advisory)
  - **关键 reframe**:`{version,payload}` whitelist 在 SECURITY.md 重新定位为"deserialization-RCE 防御(CVE-2023-42809 Redisson / CVE-2025-49844 RediShell 证明你需要的)",SBOM 作为 evidence。当前采用者体验为 friction 的东西,在 security lens 里是 feature——CVE record 证明它是必要的

#### B2. 结构性 friction(bus factor=1)——从 named risk 转为 documented operational mitigation

- [ ] **contribution on-ramp**:
  - `.github/ISSUE_TEMPLATE/{bug_report,feature_request}.yml` + `config.yml`,`.github/PULL_REQUEST_TEMPLATE.md` 镜像 `CONTRIBUTING.md` 已有的 PR checklist(1-2 小时 copy job)
  - move `CODEOWNERS` 从 repo root → `.github/CODEOWNERS`(确保 auto-review-request 可靠触发)
  - label 5-10 `good-first-issue`/`help-wanted` tasks,来自 Known Limitations/Roadmap(JMH、per-handler Micrometer tag、Cluster IT、migration tool)——预拆解、scope-respecting
- [ ] **co-maintainer & succession plan**(CONTRIBUTING section 或 BUS-FACTOR.md):
  - contributor→co-maintainer elevation criteria
  - co-maintainer 拥有什么(如 `protection/` + its tests)
  - bus-factor mitigation:backup GPG key 注册 Central、documented publisher-transfer 到 named backup namespace 或 documented 'archive' state
  - **frame 为"维护者退后时会发生什么",不是"active co-maintenance 的承诺"**——避免 over-promise

**Success metrics**:
- greenfield app cache 自己的 `com.example.User` **零 whitelist 配置**工作(startup log 出现 derived prefix)
- rejection exception message 包含精确 property key `resi-cache.serializer.allowed-package-prefixes`(regression test 断言)
- 带 pre-existing Spring-native keys 的 brownfield 环境 startup WARN(而非 silent 100% miss 或 exception storm);IT seed 不兼容 keys 断言 warning 触发
- 每个 GitHub Release 带 CycloneDX `.bom` artifact;HIGH/CRITICAL CVE 依赖 fail build
- contributor 无 prior context 能 file correctly-templated bug report 并对 labeled good-first-issue 提 PR;CODEOWNERS 迁移经 test PR 验证触发 auto-review

**Anti-patterns**:
- ❌ 不要为"加强定位"re-introduce `wrapper/`(circuit-breaker/rate-limiter)、`spi/`(ServiceLoader)、`event/`、`evaluator/`、`CacheMetricsRecorder`——项目为 over-engineering 删了 ~2,989 行。可定制性 stays Spring `@Bean` + `@ConditionalOnMissingBean`
- ❌ 不要为追 brownfield friendliness 松 `{version,payload}` envelope——ADR-0003 决定 keep-envelope + offer-migration。probe 检测不兼容,不松 envelope
- ❌ 不要让 probe metastasize 成 full migration tool——v0.0.3 probe 是诊断,v0.2.0 dual-write CLI 是独立、更大的 effort。混它们 = re-invent `evaluator/` scope
- ❌ 不要把 SBOM/dependency-check 当 SECURITY.md Non-SLA framing 的替代——Non-SLA caveat 必须保留。SBOM 是让 Non-SLA 可被采购接受的 compensating control,不是把项目升级成 SLA-backed product
- ❌ 不要让 STANDARD preset 默默跨 v0.0.2→v0.0.3 升级——pre-1.0 的行为默认必须 ⚠️ BREAKING 显式 CHANGELOG

**Risk register**:
- whitelist auto-derive 是唯一**改 runtime default**的 pre-1.0 library 行为——可能 surprise 一个 v0.0.2 用户。Mitigation:loud startup WARN + explicit override authoritative + ⚠️ BREAKING CHANGELOG
- STANDARD preset 默认行为变更(TTL 被 jitter、null 被 cache)——strict TTL 合同或 null-sensitive 逻辑用户会 surprise。Mitigation:新用户 STANDARD 默认,升级路径显式 opt-in via migration note;**或** back-compat 默认 NONE,document STANDARD 为 recommended one-liner。**deliberate 决定,不要静默**
- co-maintainer succession doc 可能 over-promise——solo maintainer 无法 staff 的 elevation path 创造 credibility gap。Mitigation:frame 为 archival/publisher-transfer,非 active co-maintenance 承诺

---

### 支柱 C：Competitive Wedge Sharpening(竞争 wedge 锐化)——诚实是唯一可赢的轴

**Thesis**:ADR-0006 的 wedge("Bloom + 可插拔链")**事实错误 + 严重低估**。修正后 wedge 是"JetCache 缺失的 3 个防护、打包进一个 Redisson 原生责任链"——**更锐利、可验证、与项目诚实文化对齐**。但 wedge 只活在文档里——必须配合 runnable sample + honest comparison page 让 claim demonstrable,not just documented。

**Concrete moves**:

- [ ] **修正 ADR-0006 + `docs/comparison.md` 的事实错误**:
  - ADR-0006 line 12 的"JetCache 已覆盖 ResiCache 链中 4/5 机制(...TTL jitter...)"→ 修正为 ~2.5/5,footnote 引用 JetCache Issue #269
  - `comparison.md` line 22 `| 防**雪崩**(TTL 抖动) | ✅ |` → ❌ + footnote
  - breakdown 行加 partial/JVM-only 标记(`@CachePenetrationProtect` 单 JVM,非分布式)
  - **recording in `wiki/log.md`**:"verification strengthened the wedge"(事实确实如此),非 reversal。**不要创建 ADR-0008**——这是 Accepted 决策内的事实修正,非策略变更
- [ ] **重述 wedge headline**:`"the 3 protections JetCache is missing, in one Redisson-native chain"`(Bloom + TTL jitter + distributed breakdown lock)
- [ ] **promote `comparison.md` 到 README-visible**:
  - 翻译/sync EN 版本
  - README hero 下加一行 `→ How ResiCache compares to JetCache / Caffeine / raw Redisson (honest)`
  - 把修正后的"3 protections JetCache is missing"summary table 加进 README 本身,无需 click-through
  - **保留双语"输在哪"section**(multi-level、broadcast、vs Alibaba maturity)——**这是信任资产,不要软化**。comparison.md 开篇"诚实是 solo 库的通货"是 load-bearing
- [ ] **rewrite 三个 stale front doors**(一次性 pass,匹配 ADR-0006/0007):
  - gh repo description → `"ResiCache for Redisson — the declarative cache-protection chain Redisson forgot to build (Spring Cache + Redisson)"`
  - 加 repositoryTopics: `[redis, redisson, spring-cache, spring-boot, cache-protection]`
  - README hero block——替换 ADR-0001 framing 为 ADR-0006 Redisson-companion one-liner + 30 秒"Why ResiCache if you already use Redisson?"callout
  - `wiki/overview.md` "一句话" + tech-stack 表 → 同步
- [ ] **ship `resicache-sample` Spring Boot module**(ADR-0006 自己点名的 survival mechanism):
  - 一个 controller endpoint per protection:`/bloom`(reject non-existent key,log bloom short-circuit)、`/singleflight`(N concurrent miss 被 SyncLock 串行化)、`/jitter`(redis TTL 输出 randomized TTL)
  - docker-compose Redis + 5 分钟 README quickstart
  - **killer artifact**:side-by-side"Redisson + ResiCache vs hand-rolled"代码块——5 行 `@RedisCacheable` vs ~60 行 `redissonClient.getBloomFilter()/getLock()/try-finally/expire`
  - **必须包含诚实成本**(envelope config、whitelist),否则 sharp reviewer 抓到 + 诚实品牌反转
- [ ] **Redisson-ecosystem co-marketing surface**(让 named-incumbent tie 可被发现,不是 asserted):
  - README "Built on Redisson" section,link ResiCache 组合的 Redisson 原语(`RBloomFilter`、`RLock`)+ tested Redisson 版本
  - `COMPATIBILITY.md` 加 Redisson 版本范围行
  - prepare(don't spam)contributable items:awesome-list entry、drafted(未发)GitHub Discussion 评论
  - **co-marketing = discoverability(awesome-list entries、version-range tagging),never solicitation**

**Success metrics**:
- ADR-0006 + comparison.md wedge 表述为三个 named protections + footnote 引用 JetCache Issue #269;comparison.md JetCache 行 TTL jitter ❌、breakdown partial/JVM-only
- repo 无文档 claim JetCache 覆盖 TTL jitter
- README 渲染 EN comparison summary + 可点 link;≤2 clicks 从 repo root 到 comparison page
- stranger `git clone && docker-compose up && ./mvnw -pl resicache-sample spring-boot:run` + curl 三个 endpoint 各触发不同 handler(≤10 min,仅 Docker 依赖)
- ≥1 inbound referrer 来自 Redisson-ecosystem surface(3 个月内)
- comparison page visit count > 0(publish 后第一个月,GitHub traffic insight)

**Anti-patterns**:
- ❌ 不要把 ADR-0006 修正 reframe 成 positioning pivot(不创建 ADR-0008)——Accepted 决策内的事实修正,记 `wiki/log.md` + 原地 amend
- ❌ 不要软化/删除"输在哪"admissions 让定位更 punchy——诚实是信任资产
- ❌ 不要让"vs hand-rolled Redisson"demo 暗示 ResiCache 应长成通用 Redisson utility 层(multi-level、broadcast、API-level `Cache.get/put`)——ADR-0006 明确永久让位给 JetCache/Caffeine
- ❌ 不要用 Redisson co-marketing hooks astroturf——no unsolicited promotional posts。诚实文化是 moat,promotional spam 反转它
- ❌ 不要在 sample 演示 Reactive/WebFlux——deferred,加 demo 会违反 blocking-interceptor 契约

**Risk register**:
- positioning 是 doc/branding work,但 artifact 还不 resolve——positioning move 在 publish 前 convert 零采用。**Sequence gate**:front-door rewrite 同 release window 落地,不在 publish 之前
- 双语 sync debt:README(EN canonical)+ `README.zh-CN.md` + `docs/comparison.md` + wiki 是 4 个 surface;每次修正必须 propagate 到全部,否则 re-accumulate stale-fact 问题。docs-link-check CI blacklist/whitelist 必须随 ADR-0006 修正更新,否则 build 断
- Redisson co-marketing 是单向依赖 Redisson goodwill——Redisson 若自己 ship 声明式保护链,ADR-0006 thesis 一夜坍塌。加 periodic Redisson release-notes watch,非 code commitment

---

### 支柱 D：Technical Moat Deepening(技术护城河深化,IN SCOPE)——把"too thin"变成"唯一可被 SEE 和 TUNE 的链"

**Thesis**:ADR-0006 判 chain"too thin"——但这个 verdict 是对一个**确实 thin in 3 concrete fixable ways** 的 chain 做的:(1) Bloom 是固定位数组,无 FPP 仪表化、无 fill-ratio gauge、无 scalable/counting 变体,且 `BloomFilterProperties.expectedInsertions/falseProbability` 没接到数学(沉默 degraded);(2) chain handler 粒度不可观测——一个 flat timer、3 个不同 metric namespace、无 correlation id、无 Observation spans(ADR-0005 承诺但未交付);(3) early-refresh 静态阈值 + 全 manual(需 per-annotation opt-in)。**战略 move 不是加 creep 进 Resilience4j/Caffeine 领域的机制——是把 3 个真实增量(bloom + jitter + distributed lock)厚化到"最 observable、最 tunable、最 self-driving 的版本"。**

**Concrete moves**(brutal sequencing:instrument first → Bloom scalable+observable → chain self-driving → preset ON by default):

- [ ] **统一+完成 per-handler chain observability**(其它厚化的前置条件):
  - (a) namespace-rename `bloomsift.*/prerefresh.*` → `resicache.handler.*`(一个 namespace,breaking 但 pre-1.0 允许)
  - (b) 加缺失的 `TtlHandler` counter(`resicache.handler.ttl.jittered`)+ 每 handler uniform FIRED counter via `AbstractCacheHandler.handle()`
  - (c) **现在**就给 `resicache.chain.execute` timer 加 handler-name + decision(CONTINUE/TERMINATE/SKIP_ALL)tags——从 v0.3.0 拉前
  - (d) `AbstractCacheHandler` 单点 chain-advance: `log.debug("[chain] handler={} decision={} key={} requestId={}")` + MDC requestId stamped in `CacheHandlerChain.execute`
  - (e) **交付 ADR-0005 承诺**:wire `ObservationRegistry` around `CacheHandlerChain.execute`,每 handler 成 OTel-exportable span(handler.name、decision、cacheName、requestId as span attributes)
  - 关闭 ADR-0005"WS-1.4 链级 Observation"promise-vs-reality gap + 中心"why did it short-circuit"debuggability gap
- [ ] **Bloom observable AND scalable**(bloom 是"too thin"最大信号):
  - (a) `BloomFillGauge`:expose `resicache.bloom.bits.set` + `resicache.bloom.estimated.count`(count set bits / hashFunctions)per cacheName,scheduled refresh
  - (b) measured-FPP gauge(false-positives-seen / bloom-checks,bloom says `mightContain=true` 但 subsequent Redis GET miss——chain 已见两信号)
  - (c) wire dormant `BloomFilterProperties.expectedInsertions/falseProbability` 到 bitSize/hashFunctions math(今天它们存在但 `BloomFilterConfig` 读 fixed `@Value` defaults——config 是谎言)
  - (d) `ScalableBloom`(counting/segmented)变体 behind `BloomIFilter`,`resi-cache.bloom.strategy=NATIVE|SCALABLE` switch,默认 NATIVE backward compat
  - **counting/scalable bloom 是真实 moat——JetCache 完全没有**。observable auto-sizing bloom 是 risk-averse 企业能信任的(因为它告诉你它何时在撒谎)
- [ ] **automatic hot-key detection auto-enables early-refresh**(移除 per-annotation opt-in tax):
  - `HotKeyDetector`(sliding-window access-frequency per cacheName+key,bounded by small LRU/Caffeine——**detection bookkeeping,not cache**,尊重 no-L1 rule)
  - key 跨可配置 hot-key threshold → 设 `enableEarlyExpiration=true` for that key in context,**无需用户注解**
  - wire 为新低优先级 handler(`HandlerOrder.HOT_KEY_DETECTION`,e.g. 150,ahead of EarlyExpiration 250)或 pre-chain probe in `CacheHandlerChain.execute`
  - **self-driving chain 是"opt-in flags 工具集"(JetCache 也是)vs "by-default 防护链"的 qualitative difference**——最 defensible feature vs JetCache(其 `@CacheRefresh` 也 per-annotation)
  - **hard-cap detector LRU,assert bounded memory**——memory leak 直接违反 subtraction culture;无法证明 bounded 就 ship detect-and-log-only first
- [ ] **adaptive TTL**(厚化 jitter,3 个真实增量之一):
  - extend `DefaultTtlPolicy` with hit-rate-aware variance mode:`resi-cache.protection.ttl.adaptive=true` 时,高 hit-rate cacheName 增大 jitter window、cold 缩小
  - 复用现有 `resicache.cache.hit/miss` counter + `DefaultTtlPolicy.calculateFinalTtl` 签名,policy refinement,无新 handler
- [ ] **per-cache preset overlay**(`Map<String,CacheConfig>` caches overlay 已存在):STRICT overlay 高流量 cache、sibling 保持 STANDARD——**tunability story**

**Success metrics**:
- 所有 5 handler emit `resicache.handler.<name>.fired` counter;`resicache.chain.execute` 带 handler/decision tags 可在任何 Prometheus/Grafana 查询;一个 GET 的 DEBUG trace 显示单 requestId 串所有 handler + decision
- Grafana panel 显示 `resicache.bloom.estimated.count` 向 `expectedInsertions` 上升、`resicache.bloom.fpp.measured` 向配置 `falseProbability` 追踪;`ScalableBloomIT` 证明 SCALABLE 在 2x expectedInsertions 下 hold FPP 而 NATIVE degrade
- 零注解下,key accessed >N 次 window 自动 trigger EarlyExpirationHandler 异步 refresh(`HotKeyAutoRefreshIT` 断言 earlyRefreshTriggeredCounter 增,无 `@RedisCacheable(enableEarlyExpiration=true)`);detector LRU 被 cap(long-running test 断言 bounded memory)
- `AdaptiveTtlIT` 证明高 hit-rate cacheName 收到统计上更宽 TTL 分布 vs cold;`resicache.handler.ttl.adaptive.variance` gauge 反映 live computed variance
- `ChainObservationIT` 或 sample 中 manual verified trace 截图:parent `resicache.chain.execute` span + 一 child span per handler,各带 handler.name + decision,OTel exportable

**Anti-patterns**:
- ❌ 不要加 circuit-breaker/rate-limiter/bulkhead——Resilience4j 的活,项目为这删了 `wrapper/`。Bloom FPP degradation 必须 surface 为 metric + preset recommendation,非 in-library circuit breaker
- ❌ 不要加 L1 local cache / multi-level / broadcast——ADR-0006 forsworn,所有竞品 lens 验证正确。hot-key detector 的 bookkeeping LRU 是 detection-only(无 value 存储、无 GET-served-from-LRU),命名要明显
- ❌ 不要 revive `spi/` ServiceLoader 或 `event/` bus 来 plumb observability——用 Spring `@Bean` + `@ConditionalOnMissingBean` + `ObjectProvider<MeterRegistry>/ObservationRegistry`(已用 in `EarlyExpirationHandler`)
- ❌ 不要建自定义 metrics-recorder framework——项目删了 `CacheMetricsRecorder`。用 Micrometer 直接(Counter/Timer/Gauge 已用)+ Spring Observation,不包新抽象层
- ❌ 不要让 Bloom 的 `expectedInsertions/falseProbability` config 保持 decorative——要么接 math,要么删误导属性
- ❌ adaptive TTL / hot-key auto-refresh 不能在 observability(move #1)落地前成默认行为——non-determinism without observability = debugging nightmare,直接 contradict"most observable chain"thesis
- ❌ per-handler tag cardinality(handler × decision × cacheName)可能爆炸。**Bound it**:cacheName 已 per-cache(bounded by cache count)、handler 5、decision 3——acceptable,但 **DO NOT** 加 redisKey as tag(unbounded)

**Risk register**:
- Bloom scalability(SCALABLE/counting)是最高 effort + 最高 risk——buggy counting-bloom silent corrupt correctness。Mitigation:默认 NATIVE + SCALABLE 显式 opt-in + exhaustive `ScalableBloomIT` 在任何 preset 推荐前
- metric namespace rename 是 breaking change。Pre-1.0 合法但 CHANGELOG 标 breaking metric change,rename 与 tag addition **同 release** 落地(用户 migrate 一次,非两次)
- hot-key detector 引入 always-on state(frequency window)。Mitigation:hard-cap LRU + long-running test assert cap;无法 prove bounded 就 ship detect-and-log-only
- presets 改默认最可能 surprise v0.0.x 用户。STANDARD 是新装默认;提供 one-line `resi-cache.protection.preset=NONE` escape hatch;`v0.2.0` migration notes 突出。Pair with brownfield serialization probe(separate pillar)让升级 story survivable

---

## 4. Phased Roadmap(扩展现有 v0.1.0/v0.2.0/v0.3.0/v1.0.0,加竞争力工作)

| 版本 | 现有 Roadmap 项 | 竞争力扩展(P0/P1/P2) | Tier |
|------|----------------|----------------------|------|
| **v0.0.3**(立即) | — | **P0**:修 release pipeline + publish v0.0.3 到 Maven Central(JAVA 17→21、OSSRH→MAVEN secrets、dry-run)| P0 |
| v0.0.3 | — | **P0**:删误导 `v1.0` tag + 首个 GitHub Release | P0 |
| v0.0.3 | — | **P0**:ship `resicache-sample` module + comparison page(ADR-0006 survival mechanism)| P0 |
| v0.0.3 | — | **P0**:per-handler chain observability(correlated DEBUG + handler/decision tags + Observation spans)| P0 |
| v0.0.3 | — | **P0**:whitelist first-contact 修复(auto-derive greenfield default + rejection message remediation)| P0 |
| v0.0.3 | — | **P0**:reconcile 自相矛盾(Boot 3→4、`-Pboot4`、672→677 tests)| P0 |
| v0.0.3 | — | **P0**:STABILITY.md(API stability contract)| P0 |
| v0.0.3 | — | **P1**:修正 ADR-0006 TTL jitter 错误 + 重述 wedge 为 3 protections | P1 |
| v0.0.3 | — | **P1**:promote `comparison.md` 到 README-visible + EN 版 | P1 |
| v0.0.3 | — | **P1**:rewrite 三个 stale front doors(gh description、README hero、wiki overview)| P1 |
| v0.0.3 | — | **P1**:backport `resi-cache.protection.preset=STANDARD`(STRICT opt-in)| P1 |
| v0.0.3 | — | **P1**:fail-fast serialization pre-flight probe | P1 |
| v0.0.3 | — | **P1**:dependency-check gate + CycloneDX SBOM on release | P1 |
| v0.0.3 | — | **P1**:`.github/ISSUE_TEMPLATE` + PR template + move CODEOWNERS + label good-first-issues | P1 |
| v0.0.3 | — | **P1**:Redisson-ecosystem co-marketing surface(Built on Redisson section、version-range tagging)| P1 |
| v0.0.3 | — | **P1**:ADOPTERS.md(truthful at zero + add-yourself protocol)| P1 |
| **v0.1.0** | Bean-graph 重设计(`@ConditionalOnMissingBean` override 契约)+ Boot 4 单 build line | **P0**:per-handler Micrometer tags(move out of v0.3.0)| P0 |
| v0.1.0 | (Boot 4 已落,ADR-0007) | **P0**:Observation spans(交付 ADR-0005 承诺)| P0 |
| v0.1.0 | — | **P1**:minimal JMH module(3 benchmarks:chain pass-through vs Spring-native、per-handler additive cost、SyncLock throughput under concurrency)+ PERFORMANCE.md SLO 先行 | P1 |
| v0.1.0 | — | **P1**:tighten smoke benchmark gate(100ms → μs range)| P1 |
| **v0.2.0** | `protection.preset` + 序列化兼容 + 迁移工具(单 release unit) | **P1**:Testcontainers Redis Cluster IT(prove hash-tag slot co-location)+ Sentinel IT | P1 |
| v0.2.0 | — | **P1**:full migration tool(shadow→dual-write→cutover CLI)——probe 是 down-payment | P1 |
| v0.2.0 | — | **P2**:Bloom observable + scalable(FillGauge、FPP gauge、wire config、ScalableBloom strategy)| P2 |
| v0.2.0 | — | **P2**:automatic hot-key detection auto-enable early-refresh | P2 |
| **v0.3.0** | JMH benchmarks | **P1**:benchmark blog post("What does a composable cache-protection chain actually cost?")| P1 |
| v0.3.0 | — | **P2**:adaptive TTL(hit-rate-aware variance)| P2 |
| **v1.0.0** | API stability promise | **P2**:explicit co-maintainer & succession plan(BUS-FACTOR.md / CONTRIBUTING section)| P2 |
| v1.0.0 | — | **P2**:Spring Native / GraalVM reachability hints for SecureJackson whitelist | P2 |

**Roadmap alignment notes**:
- 大部分竞争力工作**加速**现有 roadmap 项(preset v0.2.0→v0.0.3、Cluster IT v0.2.0、JMH v0.3.0→v0.1.0、sample v1.0.0→v0.0.3),而非发明新 scope——**honoring subtraction-discipline culture**
- v0.0.3 是**承载最多 P0/P1 工作的 release window**——publish 必须先解锁,sample/observability/whitelist/preset/comparison 全部在它之后才能 convert adoptions
- 两个 recurring temptations **deliberately excluded**:multi-level cache(forsworn by ADR-0006,所有竞品 lens 验证正确)+ Reactive/WebFlux(real open gap vs hanqunfeng 11-star,但需 non-blocking interceptor 重设计——deferred,not dropped;loud non-OP warning on Mono/Flux 是 cheap interim,folds into DX theme)

---

## 5. Trust Engineering 深度章节(ADR-0006 核心 thesis,最高杠杆)

### 5.1 为什么这是最高杠杆

ADR-0006 的核心 thesis:**缓存采用是信任决策,solo v0.0.x 在 Alibaba 量级信任战里赢不了**。这是诚实的——但项目迄今把信任当**静态局限**,而非**可逐项拆除的工程目标**。本节是拆除序列。

**信任是"重复的、可验证的、低成本的诚实 beat"的输出**。每一个 P0 move(publish、删 v1.0 tag、whitelist message、preset STANDARD、sample、SBOM)都是一个诚实 beat。它们各自成本极低(S-M effort),各自解锁一个独立 procurement gate。**累积起来,它们让 ResiCache 从"无法被评估"变成"可被基于事实评估"。**

### 5.2 4 friction 拆解序列(机械先行)

```
Friction 1 (mechanical): artifact 无法 resolve
  ↓ 拆除: release.yml JAVA 17→21 + OSSRH→MAVEN + dry-run + publish v0.0.3
  ↓ 解锁 gate: 企业可以 add dependency
  
Friction 2 (mechanical): 白名单默认作者包 + rejection 无 remediation
  ↓ 拆除: auto-derive greenfield default + rejection message 追加 property key
  ↓ 解锁 gate: greenfield first-key 工作; brownfield first-GET 不 doc round-trip
  
Friction 3 (mechanical): protection 默认全 OFF
  ↓ 拆除: preset=STANDARD backport (TTL jitter + null-value + early-refresh)
  ↓ 解锁 gate: @RedisCacheable(value="users") visibly does something intelligent
  
Friction 4 (structural): bus factor = 1
  ↓ 拆除: ISSUE_TEMPLATE + good-first-issues + co-maintainer/succession doc
  ↓ 解锁 gate: 第二 contributor 可 file correct PR in 10 min; named risk 有 operational mitigation
```

### 5.3 SBOM 作为 RCE-defense reframe(关键的 trust-asset 转换)

当前采用者体验为 friction 的东西(whitelist + envelope),在 security lens 里是 **feature**——而 ecosystem CVE 证明了它是必要的:

- **CVE-2023-42809**(Redisson deserialization RCE,Kryo5Codec 仍 unsafe after patch)
- **CVE-2025-49844** "RediShell"(Redis server CVSS 10.0 Lua RCE)

这些让 enterprise security teams **default-skeptical of ANY new Redis-layer dependency**——尤其一个引入自己的 serialization envelope 的库。**Reframe**:`{version,payload}` whitelist 是"the deserialization-RCE defense the ecosystem CVEs proved you need",SBOM 作为 evidence。这把 friction 转成不同iator,且**完全不松 envelope**(尊重 ADR-0003)。

### 5.4 非目标(再次声明)

> **本 Trust Engineering 程序不制造 Alibaba 量级信任。** 它让 ResiCache **installable**(resolvable artifact)、**demonstrable**(sample 跑每个 handler)、**falsifiable**(API-stability contract 可 pin + SLO 可 disprove)、**survivable**(brownfield migration probe + succession doc)——使真实技术增量(bloom + jitter + distributed lock in one Redisson-native chain)能被**自管 Redis/Redisson 细分市场**评估,该细分市场是 substrate 留下全部 5 个防护未覆盖的地方。

---

## 6. 90 天 Quick Wins Checklist(solo maintainer 明天可起)

### Week 1(publish unblock——gate 一切)
- [ ] `release.yml`: `JAVA_VERSION '17'` → `'21'`;`OSSRH_*` secrets → `MAVEN_*`;environment URL 死链 → `central.sonatype.com`
- [ ] Dry-run: tag `v0.0.3-rc1` push 跑完整 staging 链路,确认绿
- [ ] 真实 publish v0.0.3 到 Maven Central
- [ ] `git tag -d v1.0` + push 删除误导 tag
- [ ] 首个 GitHub Release 配 v0.0.3 tag,带 CHANGELOG-derived body
- [ ] gh repo description: "Spring Boot 3" → Boot 4 Redisson-companion one-liner

### Week 2(first-contact 修复——最高频 DX 失败)
- [ ] `SecureJacksonRedisSerializer.java:186-188`: rejection message 追加 `Add its package to resi-cache.serializer.allowed-package-prefixes.`
- [ ] greenfield whitelist auto-derive(`@ConfigurationProperties` host app root package via BeanFactory,startup WARN log)
- [ ] reconcile 自相矛盾:README header Boot 3→4;`COMPATIBILITY.md` `-Pboot4`→`(无)`;"672 tests"→"677+"

### Week 3(preset + probe——让 library 显然有效)
- [ ] `resi-cache.protection.preset=STANDARD|STRICT|NONE` enum + default-attribute override(backport from ADR-0004)
- [ ] fail-fast serialization pre-flight probe(startup 采样 N keys,检测非 envelope,WARNING link migration recipe)
- [ ] CHANGELOG 标 ⚠️ BREAKING(STANDARD 默认行为变更)

### Week 4(governance + comparison)
- [ ] `.github/ISSUE_TEMPLATE/{bug_report,feature_request}.yml` + `config.yml`
- [ ] `.github/PULL_REQUEST_TEMPLATE.md` 镜像 `CONTRIBUTING.md` PR checklist
- [ ] move `CODEOWNERS` → `.github/CODEOWNERS`
- [ ] label 5-10 `good-first-issue`/`help-wanted`(JMH、Micrometer tag、Cluster IT、migration tool)
- [ ] 修正 ADR-0006 + `comparison.md` TTL jitter 错误,重述 wedge 为 3 protections
- [ ] README 加 EN comparison summary + link;promote `comparison.md` 到 README-visible

### Week 5-6(sample——trust-conversion artifact)
- [ ] `resicache-sample` module:docker-compose Redis + `/bloom` + `/singleflight` + `/jitter` endpoint
- [ ] sample README side-by-side"Redisson + ResiCache vs hand-rolled"(含诚实成本:envelope config、whitelist)
- [ ] 5 分钟 quickstart doc

### Week 7-8(observability + security posture)
- [ ] per-handler uniform `log.debug("[chain] handler={} decision={} key={}")` + MDC requestId in `AbstractCacheHandler`
- [ ] handler-name + decision tags on `resicache.chain.execute` timer(从 v0.3.0 拉前)
- [ ] CycloneDX `makeBom` 到 release workflow + SBOM 作为 release artifact
- [ ] dependency-check job 从 `continue-on-error` 提升 gate
- [ ] SECURITY.md reframe whitelist 为 RCE-defense(link SBOM)

### Week 9-10(STABILITY contract + ADOPTERS)
- [ ] STABILITY.md:0.x 什么能变/不能变、1.0 graduation criteria
- [ ] README badge link STABILITY.md
- [ ] ADOPTERS.md(truthful "No production adopters yet (pre-1.0)" + add-yourself protocol)

### Week 11-12(Observation spans + Redisson co-marketing)
- [ ] wire `ObservationRegistry` around `CacheHandlerChain.execute`(交付 ADR-0005 承诺)
- [ ] README "Built on Redisson" section(link `RBloomFilter`/`RLock` + tested Redisson version)
- [ ] `COMPATIBILITY.md` Redisson 版本范围行
- [ ] prepare(don't spam)awesome-list entry

---

## 7. What NOT to Do(尊重 subtraction discipline)

> v0.0.2 deliberately REMOVED ~2,989 lines as over-engineering:`wrapper/`(circuit-breaker/rate-limiter)、`spi/`(Java ServiceLoader)、`event/`(`CacheEvictedEvent`)、standalone `evaluator/`(`SpelConditionEvaluator`)、`observability/CacheMetricsRecorder`。项目有强"don't re-invent, defer to the right tool"文化——任何竞争力建议必须尊重这点。

### 7.1 绝对不做(scope creep red lines)

- ❌ **不加 circuit-breaker / rate-limiter / bulkhead**——Resilience4j 的活。Bloom FPP degradation surface 为 metric + preset recommendation,非 in-library breaker
- ❌ **不加 L1 local cache / multi-level / broadcast invalidation**——ADR-0006 forsworn,所有竞品 lens 验证正确。hot-key detector 的 bookkeeping LRU 是 detection-only,非 Caffeine-as-cache,命名要明显
- ❌ **不加 Reactive/WebFlux 支持**——需 non-blocking interceptor 重设计,deferred。comparison page 诚实 state gap,不 paper over with scope creep
- ❌ **不 re-introduce** `wrapper/`、`spi/`、`event/`、`evaluator/`、`CacheMetricsRecorder`——为 over-engineering 删的。"可插拔链"via `@ConditionalOnMissingBean` + `@Component`,**非新 extension SPI**
- ❌ **不松** `{version,payload}` serialization envelope——ADR-0003 决定 keep + offer migration。probe 检测不兼容,不松 envelope。任何"silent just work"的 whitelist/envelope 松动违反项目自己的 security red line
- ❌ **不让 probe metastasize 成 full migration tool**——v0.0.3 probe 是诊断,v0.2.0 dual-write CLI 是独立 effort
- ❌ **不建自定义 metrics-recorder / security scanner / observability framework**——用 Micrometer 直接 + Spring Observation + CycloneDX plugin + OWASP dependency-check + codeql,as-is。bespoke 工具是 `evaluator/`/`CacheMetricsRecorder` 被删的反模式
- ❌ **不追 managed-layer 客户**(Momento / Redis Cloud)——position 为 out-of-scope,非 lost,per ADR-0006
- ❌ **不在 sample 演示 Reactive 或 non-blocking interceptor 重设计**——违反 blocking-interceptor 契约

### 7.2 信任/诚实 red lines

- ❌ **不软化或删"输在哪"admissions** 让定位更 punchy——multi-level/broadcast/Alibaba-maturity 诚实是信任资产,删它反转品牌。`comparison.md` 开篇"诚实是 solo 库的通货"是 load-bearing
- ❌ **不 reframe ADR-0006 修正为 positioning pivot**(不创建 ADR-0008)——Accepted 决策内事实修正,记 `wiki/log.md` + 原地 amend
- ❌ **不 publish benchmark 无 committed SLO**——ADR-0001 scrub "高性能" 正为此。unbounded number 重受伤
- ❌ **不用 vanity metrics**(stars、watch、social impressions)作为 success criteria——每个 metric 必须 observable + falsifiable(artifact resolves、demo 5 min 跑、benchmark hits/misses SLO、grep stale 零)
- ❌ **不用 Redisson co-marketing hooks astroturf**——no unsolicited promotional posts、no star-buying。诚实文化是 moat
- ❌ **不把 SBOM/dependency-check 当 SECURITY.md Non-SLA framing 替代**——Non-SLA caveat 必须保留。SBOM 是 compensating control 让 Non-SLA 可被采购接受,非升级成 SLA-backed product

### 7.3 v0.0.3 升级 red lines

- ❌ **不让 STANDARD preset 默默跨 v0.0.2→v0.0.3 升级**——行为默认变更 pre-1.0 必须 ⚠️ BREAKING 显式 CHANGELOG(项目已用此 convention,见 CHANGELOG line 24、94),preset 必须遵循
- ❌ **不 market test count / coverage 为 headline credibility claim 在 v0.0.3 resolve 之前**——"677 tests" 在无法 install 的 library 上是 vapor;artifact resolve 是任何 rigor claim meaningful 的前置

---

## 8. 可测量的成功指标(adopt/trust/technical signals)

### 8.1 Adoption signals(具体,非 vanity)

| 指标 | 当前 | v0.0.3 后 3 月目标 | v1.0.0 后 12 月目标 |
|------|------|-------------------|---------------------|
| Maven Central artifact resolvable | ❌ | ✅ `mvn dependency:get` 在干净容器成功 | ✅ + 1.0 SemVer |
| GitHub stars | 0 | 5-20(SEO + sample + comparison 驱动)| 50-200(ADR-0006 自定 success metric) |
| GitHub forks | 0 | 1-5 | 5-15 |
| Known production adopters | 0 | 0(诚实,pre-1.0)| 2-3(ADR-0006 自定) |
| Inbound referrer from Redisson-ecosystem surface | 0 | ≥1 | ≥3 |
| comparison.md visit count(publish 后第一个月) | N/A | > 0 | trend upward |
| Issue/PR by external contributor | 0 | 0-1 | 3-8 |

### 8.2 Trust signals(可被采购评审验证)

| 指标 | 当前 | 目标 |
|------|------|------|
| GitHub Releases 显示 regular cadence(gap ≤ 60 天 through v1.0.0)| ❌ 从未 release | ✅ v0.0.3 后 ≤ 60 天 gap |
| 每 release 带 CycloneDX SBOM artifact | ❌ | ✅ v0.0.3 起 |
| dependency-check HIGH/CRITICAL CVE fail build | ❌(continue-on-error)| ✅ |
| STABILITY.md 存在 + 1.0 graduation criteria 枚举 | ❌(仅 CHANGELOG caveat)| ✅ |
| ADOPTERS.md 存在(诚实 at zero + add-yourself)| ❌ | ✅ |
| CODEOWNERS 在 `.github/` + auto-review 触发 | ❌(root)| ✅ |
| co-maintainer/succession plan 文档化 | ❌ | ✅(v1.0.0) |
| 首个外部 contributor 能 file templated PR ≤ 10 min | ❌ | ✅ |

### 8.3 Technical signals(falsifiable)

| 指标 | 当前 | 目标 |
|------|------|------|
| Sample 在 ≤ 10 min 跑(curl 3 endpoint 各触发不同 handler)| ❌ 无 sample | ✅ |
| Smoke benchmark gate | 100ms mean(passes 1000x regression)| μs range(fail on 10x regression) |
| PERFORMANCE.md SLO targets | ❌ | ✅(chain overhead ≤ X μs p99、SyncLock ≤ 1 RTT、Bloom sub-μs) |
| JMH module(3 benchmarks)| ❌ | ✅ resicache-bench separate module |
| Per-handler Micrometer tags(handler + decision)| ❌ flat timer | ✅ |
| Observation spans per handler(OTel-exportable)| ❌(ADR-0005 承诺未交付)| ✅ |
| Correlated DEBUG requestId across chain | ❌ scattered | ✅ single MDC requestId |
| Valkey tested CI row | ❌ 无 grep hit | ✅ |
| Redis Cluster IT(≥3 node,hash-tag slot co-location)| ❌ 仅 unit | ✅ |
| Sentinel IT | ❌ configured never IT'd | ✅ |
| Bloom FillGauge + measured-FPP gauge | ❌ | ✅(v0.2.0) |
| Bloom `expectedInsertions/falseProbability` wired to math | ❌ decorative | ✅(v0.2.0) |
| Hot-key auto-enable early-refresh(零注解)| ❌ manual opt-in | ✅(v0.2.0) |
| Adaptive TTL(hit-rate-aware variance)| ❌ fixed-config | ✅(v0.3.0) |

---

## 9. Risk Register + Decision Points(maintainer input needed)

### 9.1 Strategic risks(需持续监控)

| Risk | Severity | Likelihood | Mitigation | Decision needed |
|------|---------|-----------|-----------|----------------|
| **Redisson 自己 ship 声明式保护链**——ADR-0006 thesis 一夜坍塌 | 存在性 | 低-中(Redisson 多年未做)| periodic Redisson release-notes watch;moat 是 assembled chain + annotation ergonomics + migration tooling + preset,非 individual mechanism | 是否现在 draft 一个"如果 Redisson 加了 X,ResiCache 如何 differentiate"的 contingency ADR? |
| **JetCache 2.8.0 加 TTL jitter**——wedge 缩窄 | 高 | 低(Issue #269 closed 多年未做)| 监控 JetCache changelog;wedge 还有 bloom + distributed lock 2 个 gap | — |
| **managed layer(Momento/Redis Cloud)commoditize breakdown + hot-key**——价值侵蚀 | 中 | 高(已发生)| 不追 managed-layer 客户;定位 self-managed Redis/Valkey + Redisson 细分为 in-scope | comparison page 是否 explicit 列"managed-layer 客户 out-of-scope"? |
| **Spring Framework 7 / Boot 4 原生两tier cache abstraction** | 中 | 已发生 | differentiate on 5-protection CHAIN(bloom+lock+jitter+null+hot-refresh as composable unit),Spring 明确不建 | — |
| **bus factor = 1,maintainer 退后 project archive** | 高 | 中 | succession doc + backup GPG key + publisher-transfer plan | co-maintainer elevation criteria何时 activate? |

### 9.2 Tactical risks(需 deliberate decision)

| Risk | Decision needed |
|------|----------------|
| **STANDARD preset v0.0.2→v0.0.3 升级行为变更** | 默认 STANDARD(新装)+ 显式 opt-in 升级路径?**或** back-compat 默认 NONE + document STANDARD 为 recommended one-liner?**deliberate,不要静默** |
| **whitelist auto-derive 改 runtime default** | 是否 ⚠️ BREAKING CHANGELOG + loud startup WARN?explicit override authoritative? |
| **JMH SLO target 数值**(chain overhead ≤ X μs p99) | X 具体多少?需 smoke-test baseline 推导,非拍脑袋 |
| **dry-run tag 命名** | `v0.0.3-rc1`?还是 `v0.0.3-dryrun`?是否发布到 Central staging 即可? |
| **comparison page 双语 sync 策略** | EN canonical + zh-CN translation?或同步两版?docs-link-check CI 如何更新? |
| **Reactive/WebFlux deferred but real gap** | cheap interim(loud non-OP warning on Mono/Flux)是否 v0.0.3 落地?还是 defer 到 explicit roadmap version? |

### 9.3 sequencing gates(硬依赖)

```
Gate 1: v0.0.3 publish to Maven Central
  ├─ unblocks: sample、comparison page promotion、SEO、ADOPTERS、benchmark blog
  └─ 所有 positioning move 在此之前 convert 零 adoptions

Gate 2: per-handler observability (move #1 of pillar D)
  ├─ unblocks: adaptive TTL、hot-key auto-refresh 成默认(non-determinism without observability = debugging nightmare)
  └─ 是"most observable chain"thesis 的前置

Gate 3: ADR-0006 修正 + comparison page
  ├─ unblocks: sample 的"vs hand-rolled" LOC 对比(否则 sharp reviewer 抓到事实错误)
  └─ wedge 必须 demonstrable,not just documented

Gate 4: whitelist first-contact 修复 + serialization probe
  ├─ unblocks: brownfield 升级路径 survivable
  └─ v0.2.0 full migration tool 是更大独立 effort,probe 是 down-payment
```

---

## 10. 结语：现实主义 + 北极星

ResiCache 不会打败 JetCache 在信任规模上——这是 ADR-0006 的诚实算术,本指南不挑战它。但 ADR-0006 的算术**有一个事实错误**(TTL jitter),修正后真实 wedge 是 **3 个 gap,不是 1 个**。

这份指南的程序目标不是制造 Alibaba 量级信任。目标是让 ResiCache:

1. **Installable**(artifact resolves from Central)
2. **Demonstrable**(sample 在 10 min 内跑,每 handler 可见)
3. **Falsifiable**(SLO 先行,JMH hits/misses stated promise;API-stability contract 可 pin)
4. **Survivable**(brownfield probe + succession doc)
5. **Observable**(per-handler chain 是 product,可被 SEE 和 TUNE)

——使真实技术增量(bloom + jitter + distributed lock in one Redisson-native chain)能被**自管 Redis/Valkey + Redisson 细分市场**基于事实评估,该细分市场是 substrate 留下全部 5 个防护未覆盖的地方。Managed-layer 客户(Momento/Redis Cloud)是 out-of-scope,不是 lost。

**北极星指标再次**:一个陌生人在装有 Docker 的干净机器上,10 分钟内 resolve artifact + 起 Redis + curl 三个 endpoint 看到三个 handler 触发 + 看到 SLO 命中/未命中。**达成它,ResiCache 从内部 memo 变成可被采购评审评估的 artifact。**

诚实是 solo 库的通货。这份指南是诚实的工程化序列。
