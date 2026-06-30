# ResiCache 竞争力与专业化指南

> **范围**:本文档回答「ResiCache 作为 solo 维护、v0.0.x 阶段、面向 Redisson 用户的薄封装库,如何让它更专业、更有竞争力」。
> **不重复**:定位叙事见 `wiki/adr/0006-redisson-companion-positioning.md`;架构与机制细节见 `wiki/`;版本史见 `CHANGELOG.md`。
> **关系**:本文是**执行清单**(checklist + 度量),不是定位文档;定位变化应改 ADR,本文只跟随 ADR。
> **维护**:每季度复盘一次;新增/废弃条目留 commit 痕迹,不改写历史章节。

---

## 0. 快速导航

| 我想…… | 看…… |
|---|---|
| 知道当前定位怎么讲故事 | §1 |
| 看现在真正做对了什么 / 还差什么 | §2 |
| 提一个工程质量改进项 | §3 |
| 让新用户 5 分钟跑起来 | §4 |
| 让企业能采购(可观测性 + 故障证据) | §5 |
| 用 JMH 数据而非形容词证明性能 | §6 |
| 让代码经得起安全审查 | §7 |
| 让文档体系分层清晰 | §8 |
| 上 Maven Central | §9 |
| 让 Redisson 社区认识我们 | §10 |
| 招募共同维护者 | §11 |
| 6 / 12 个月该干什么 | §12 |

---

## 1. 战略锚定(锚点引用,不重新发明)

> 本节是**复述**而非**再决策**。任何与 ADR-0006 冲突的微调,**先改 ADR,再回来同步本文**。

### 1.1 一句话定位(钉死的)

> ResiCache is **the declarative cache-protection chain that Redisson forgot to build**.
>
> 不与 JetCache 正面竞争;不和 Spring Cache 替换;不和 Caffeine 重做。
> 只做一件事:**把 `@RedisCacheable` 一行注解顶三十个手写 `RLock` / `RBloomFilter` / 手算 TTL**,且每条策略可关、可观测、可插拔。

### 1.2 信任算术(给销售 / 给 PR / 给博客作者讲时都要原文用)

- 用户采用 ResiCache 时,**不需要信任一个新 solo 核心基建库**;
- 用户采用 ResiCache 时,**只是在已有的 Redisson 之上加一层薄封装**;
- 这把风险厌恶型企业(采购委员会、红蓝对抗)的采用门槛**从「信任个人」降到「信任厂商 + 薄封装」**。

### 1.3 永远让给 JetCache 的能力(不要花精力做)

- 多级缓存(L1 Caffeine + L2 Redis)—— 留给 JetCache / Caffeine 官方;
- 广播失效(Redis Pub/Sub fan-out)—— 留给 JetCache;
- API 级 `Cache.get/put` 调用风格(`jetcache-core` 零 Spring 依赖那条线)—— 留给 JetCache。

**反例警示**:任何 PR 想给 ResiCache 加「L1+L2 多级」能力,**先停一停**;它会污染 ADR-0006 的信任算术(让 ResiCache 从「薄封装」变成「又一个缓存框架」)。

### 1.4 成功标尺(12 个月)

> 来自 ADR-0006 §「成功标尺」:~50–200 star + 2–3 个已知生产用户。

不重新定义。每月评估一次是否在轨道上。**不在轨道上 ≠ 失败**,但要主动重新评估 wedge(见 §13.2)。

### 1.5 阅读路径

- 新人读 ADR-0006 → 本指南 §2(竞争力盘点)→ §4(DX)→ §12(路线图);
- 已熟悉者直接跳到对应章节。

---

## 2. 当前竞争力盘点(快照:2026-06-30)

### 2.1 真正做对了的事(写作时不要贬低,不要重新做)

| 维度 | 已落地 | 引用 |
|---|---|---|
| 战略叙事 | ADR-0006 Wedge 2 定位已审完 | `wiki/adr/0006` |
| 构建口径 | 单构建 Boot 4.0 / Java 21 / Redisson 3.50 | `wiki/adr/0007` |
| 真 seam | `LockManager` / `BloomIFilter` / `TtlPolicy` / `NullValuePolicy` 均可 `@ConditionalOnMissingBean` 顶替 | `wiki/log` 2026-06-30 |
| 责任链 | `HandlerOrder` 单源 + `@HandlerPriority` 注解反查 disableName,无类名耦合 | `wiki/log` 2026-06-27 |
| 故障硬行为 | SyncLock fail-fast(无 RLock → IllegalStateException,非静默降级);Cluster hash-tag;Bloom rebuilding window | `wiki/log` 2026-06-28 |
| 异步透传 | Path C 7 步收官,`supportsAsyncRetrieve=true` 恢复 | `wiki/log` 2026-06-29 |
| 可观测性 | 链级 `resicache.chain.execute` Timer + per-handler `fired` counter | `wiki/log` 2026-06-30 |
| 质量门禁 | 694 测试 0 失败 / JaCoCo 70%·40% / checkstyle 0 / `./mvnw clean verify` 38s | `wiki/log` 2026-06-30 |
| 治理文档 | `CHANGELOG` / `CONTRIBUTING` / `SECURITY` / `COMPATIBILITY` / `CODEOWNERS` / ADR-0001~0007 | 仓库根目录 |
| 文档护栏 | `docs-link-check` 黑名单防已删特性复发 + 白名单校验关键类存在 | `.github/workflows/` |
| i18n | 英文 README canonical + 中文 README.zh-CN.md(明确"可能滞后") | `README.md` / `README.zh-CN.md` |
| 知识库 | 39 页 wiki,Dataview 仪表盘,Obsidian 画布 3 张 | `wiki/index.md` |

### 2.2 已显式放弃的事(永远不捡回来)

| 已放弃 | 原因 | ADR |
|---|---|---|
| 双构建 / 多 Boot 兼容矩阵 | Boot 3 EOL,solo 维护成本 × 2 | 0007 |
| 装饰器替代 interceptor | Spring AOP 6.x 限制,不可达 | 0002 |
| 内核无关化(抽 3 端口) | 「便宜抽出」为伪命题;长寿对冲改由 Path C + Observation 兜 | 0005 |
| 多级缓存 / 广播失效 | 让给 JetCache | 0006 |
| 任何「默认全开防护」 | 风险不可控,preset 必须显式 | 0004 |
| 「高性能」措辞 | 无 JMH 实证前属虚假宣传 | 0001(被 0006 取代叙事,纪律保留) |

### 2.3 仍待补齐的短板(本指南其余章节的全部目的)

> 每条短板 → 对应章节给出可执行 checklist。

- ❌ 未发布到 Maven Central,无法 `./mvnw dependency:copy` 一行拉
- ❌ 未提供 `resicache-spring-boot-starter` 命名,Spring Initializr 不可选
- ❌ 没有独立的 `examples/` 仓库,「before/after runnable sample」靠 README 内联
- ❌ 没有 video / conference talk / 中文播客
- ❌ SBOM 未生成,Reproducible Builds 未声明
- ❌ 无独立对比页(ResiCache vs Spring Cache 默认、ResiCache vs 手写 Redisson)
- ❌ OpenSSF Scorecard 未跑(无法给采购委员会一个客观分)
- ❌ 真实生产案例为 0(ADR-0006 成功标尺里至少要 2-3 个)
- ❌ Co-maintainer 为 0(单点故障风险)

---

## 3. 工程质量纵深

> 在 v0.0.x → 1.0 路上,工程质量是**采纳前企业的硬性过滤项**;不是加分项,是通行证。

### 3.1 测试

| 项 | 现状 | 目标 | 行动 |
|---|---|---|---|
| 单元测试 | 694 通过 | 覆盖率守住 70% 行 / 40% 分支 | 不主动扩到 80%(追求 ROI);只在新机制 / 边界场景加 |
| 集成测试 | Testcontainers Redis 已有 | 覆盖 Cluster / Sentinel / 单点 3 形态 | 已有(2026-06-28 hash-tag 验证)。新增 TopologyTestParameters 三种 profile |
| 故障注入 | Redis 断连 3 路径已加(WS-1.5) | 加 cluster 拓扑震荡、TTL 漂移、布隆 Redis 节点失效 | §5.3 详述 |
| Mutation 测试 | **未做** | PIT 对核心 handler 跑一轮,目标 mutation score ≥ 60% | 列入 §12.1 6 个月 |
| Property-based | **未做** | jqwik 对 `BloomFilter` / `TwoListLRU` / `TtlHandler` 跑属性测试 | 收益有限,**低优先级** |
| Contract 测试(Path C) | `PathCAopContractIT` 4 tests | 不扩;Step 0 是硬契约 | 维护即可 |

### 3.2 静态质量

| 工具 | 状态 | 行动 |
|---|---|---|
| Checkstyle | 已跑,0 违规 | 守门即可,不主动加严规则 |
| SpotBugs / Error Prone | **未启用** | `pom.xml` 加 `errorprone` 插件(Google 风格),只启用 `NullAway` + `Immutable` checker。低噪音 |
| PMD | **未启用** | 不启用(与 Checkstyle 重叠),如要做 copy-paste 检测用 `pmd-cpd` 单跑 |
| Lombok | 全代码已用 | 不动;但禁止 `@SneakyThrows` / `@Cleanup` 这类魔法 |

### 3.3 构建可重现(企业级要求)

| 项 | 行动 | 优先级 |
|---|---|---|
| SBOM(CycloneDX) | `maven cyclonedx-maven-plugin` 插件,`./mvnw verify` 出 `target/bom.xml` | P1 |
| Reproducible Builds | 启用 `preserved-classpath` / `outputTimestamp`,jar 内容跨机器一致 | P1 |
| Signature | Maven Central 上架前必须 GPG 签名(见 §9.1) | P0(上架前置) |
| Dependency Convergence | 当前 mvn dependency:tree 已收敛 | 守住 |

### 3.4 CI / CD

| 项 | 现状 | 行动 |
|---|---|---|
| GitHub Actions | `ci.yml` + `pr-checks.yml` + `docs-link-check` | §3.4.1 |
| Dependabot | **未启用** | 启用:`dependabot.yml` 周跑,group 升级(避免 1 PR 30 个 dep) |
| Release Drafter | **未启用** | 启用:根据 PR label 自动生成 `CHANGELOG.md` 草稿,发版时人工润色 |
| OpenSSF Scorecard | **未跑** | 加 `scorecard-action.yml` 周跑,目标 ≥ 6 分(P1) |
| SLSA L3 | **未声明** | 暂缓;solo 库达 L3 成本/收益比差 |

#### 3.4.1 必须守住的 CI 护栏(已存在的别退化)

- `./mvnw clean verify -B` 必须绿(38s 量级,超过 60s 视为回归);
- `docs-link-check` 守住 a5ab55b 类已删特性不在 README 复发;
- 黑名单规则只能加不能减,白名单要写明「为什么这是允许的 stale」(防漂移)。

---

## 4. 开发者体验(DX):让新用户 5 分钟跑起来

> 这是「采纳漏斗」最宽的瓶颈。技术再好,用户卡在「怎么把它跑起来」就流失。

### 4.1 Spring Boot Starter 命名与拆分

- **artifactId**: `resicache-spring-boot-starter`(Spring 官方 starter 命名规范)
- **主 artifact**: `resicache-core`(让高级用户只引核心,无需 starter)
- **Redisson 适配**: `resicache-redisson`(spring-boot-starter 自动传递依赖)
- 目标:`implementation("io.github.davidhlp.resicache:resicache-spring-boot-starter:1.0.0")` 一行启动

### 4.2 「5 分钟跑起来」路径(README 必须的前 30 行)

```
1. 加依赖(<dependency/> 块,完整 GAV)
2. 配置 Redis(application.yml 最小可运行 10 行)
3. 加 @EnableResiCache 注解(主类一行)
4. 在 service 方法上加 @RedisCacheable(一行)
5. 启动 → curl → 看到 cache miss → 看到 cache hit
```

**禁止**:任何 README 步骤要求用户「先读 wiki」「先理解 HandlerOrder」「先配置 Redisson 客户端」(这些是 later)。

### 4.3 IDE 友好

| 项 | 行动 |
|---|---|
| `@RedisCacheable` 注解 `@Attribute` Javadoc | 写清每个属性的语义、默认值、与 Spring Cache 原生属性的差异 |
| Spring Boot configuration metadata | `META-INF/spring-configuration-metadata.json` 由 `@ConfigurationProperties` annotation processor 自动生成,**确认生成且提交到仓库** |
| `spring-configuration-metadata.json` 不提交 | 这是常见反模式,会导致 IDE 联想失效 |
| 错误信息 | 启动期 fail-fast 的异常 message 给出**修复建议**而非栈 |

### 4.4 错误信息黄金标准

```java
// 反例(只说"出错")
throw new IllegalStateException("no LockManager bean");

// 正例(说"为什么错 + 怎么修")
throw new IllegalStateException(
    "No LockManager bean found. ResiCache requires Redisson on the classpath.\n"
    + "Fix: add 'org.redisson:redisson-spring-boot-starter:3.50.0' to your dependencies, "
    + "or set 'resi-cache.sync-lock.local-only=true' for single-instance/test use.");
```

**所有 fail-fast 异常**都要走这条标准。当前 `SyncSupport` 已部分做到(2026-06-28),其他 handler 复查。

### 4.5 Examples 仓库(独立仓库,不要塞在本仓库)

**反模式**:把 example 塞 `examples/` 子目录,导致 examples 跟着 release 版本走,无法独立迭代。

**正模式**:独立仓库 `davidhlp/resicache-examples`,包含:

| Example | 演示什么 |
|---|---|
| `01-quickstart` | 5 分钟跑起来的最短路径 |
| `02-protection-chain-config` | 4 种 protection preset 套餐对比 |
| `03-redisson-only-required` | ResiCache vs 手写 Redisson 30 行的 before/after |
| `04-cluster-topology` | Cluster + hash-tag 行为演示 |
| `05-multi-tenant` | 多 cacheName + per-cache 配置 |
| `06-observability` | Micrometer + Grafana dashboard JSON |

每个 example 目录独立 `pom.xml` / `build.gradle`,可单独 `mvn spring-boot:run`。

---

## 5. 可观测性:企业可采购性的真开关

> ADR-0006 已点出「链级 Micrometer Observation」是企业可采购性解锁键。WS-1.4 已部分落地,但**还有距离**。

### 5.1 链级 Micrometer Observation(从 Timer 升级)

| 当前(WS-1.4) | 目标(1.0) |
|---|---|
| `resicache.chain.execute` Timer | `resicache.cache.operation` Observation(tag: cacheName, op, hit/miss/skip/error) |
| per-handler `fired` Counter | per-handler Observation span(child span,便于 trace) |
| 无 trace | OpenTelemetry / Brave 自动桥接(Micrometer Observation 自带) |

**关键决策**:Observation tag 要**白名单枚举**,不允许动态字符串(避免 cardinality 爆炸)。`cacheName` 是受控集,可以;`key` 不是,**绝不进 tag**。

### 5.2 健康检查深度

当前 `RedisCacheHealthIndicator` 已有,深度不够:

- ❌ 未检查 Redisson 连接;
- ❌ 未检查保护链装配(handlers 是否齐全);
- ❌ 未暴露「degraded」状态(SyncLock 无锁 → degraded 但不是 down,2026-06-28 已加)。

**目标**:`UP / DEGRADED / DOWN` 三态,每态具体说明哪个组件异常。

### 5.3 故障注入 / Chaos(WS-1.5 已起步)

已有:Redis 断连 3 路径(GET/PUT/CLEAN)。

要加的:

| 场景 | 验证什么 |
|---|---|
| Cluster slot migration | `@RedisCacheable` 在 hash-tag 下不跨 slot 失败 |
| Redisson 节点临时失效 | DistributedLockManager 行为符合预期 |
| Bloom Redis 节点失效 | rebuilding window 启用 vs 禁用行为差异 |
| TTL 漂移(机器时钟差) | 抖动算法在时钟不准情况下不雪崩 |

**chaos 测试不写到 main suite**,放 `*-chaos` tag,Jenkins/Nightly 跑,CI 默认跳。

### 5.4 Logging / MDC

- 已有:MDC 跨 commonPool 透传(WS-1.4);
- 待加:cacheName / key(截断,前 32 字符)/ handler-results 的结构化字段;
- 守门:**绝不把整个 key 进 log**(PII / 密钥泄漏风险);
- 守门:**绝不把 value 进 log**(同上)。

---

## 6. 性能与基准(JMH 实证,不是形容词)

> ADR-0001 已删除「高性能」措辞,ADR-0006 复述该纪律。**重新启用性能叙事的前提是 JMH 数据**。

### 6.1 当前数据(WS-1.5 smoke)

| 场景 | 延迟 |
|---|---|
| hit(全链路,Redis 返回 value) | 210 µs |
| miss(Redis null → loader → 写回) | 365 µs |
| async 路径 | 423 µs |

**问题**:这些数字**没标明吞吐**、**没标明 Redis 拓扑**、**没标明机器规格**、**没标明 Redis payload 大小**。

### 6.2 升级 JMH 框架(1.0 前必须)

```
benchmarks/
├── jmh/
│   ├── src/main/java/.../bench/
│   │   ├── CacheHitBench.java          # hit 场景
│   │   ├── CacheMissBench.java         # miss 场景
│   │   ├── AsyncCacheBench.java        # async 路径
│   │   ├── ProtectionDisabledBench.java# @Cacheable 对照组
│   │   └── BloomOnlyBench.java         # 单 handler 隔离
│   └── pom.xml
```

每个 bench 必须输出 `BenchmarkResult.csv` 含:score / score-error / unit / param:cacheName / param:payloadSize / param:redisTopology。

### 6.3 对比页(ResiCache vs Spring Cache 默认 / vs 手写 Redisson)

独立页面 `BENCHMARKS.md`,包含:

1. **配置**:Redis 版本、机器规格、payload 字节、JVM 参数(全部公开);
2. **方法**:warmup 5s × 3,measurement 10s × 5,fork 2;
3. **图表**:hit / miss / async 三场景 ResiCache vs Spring Cache @Cacheable 默认;
4. **诚实陈述**:ResiCache 慢于原生 @Cacheable(`+X µs`,链长是客观成本);**但**ResiCache 防穿透 / 防击穿 / 防雪崩 是 Spring Cache 原生**完全不做**的;
5. **不要做**的对比:ResiCache vs Caffeine(不同问题域,无意义)。

### 6.4 「不要」说的性能话术

| 类别 | 反例(禁止) | 正例 |
|---|---|---|
| 形容词 | 「极致高性能」「毫秒级响应」「企业级性能」 | 「hit 场景 P50 = X µs,JMH fork=2 测得,机器 / 配置见 BENCHMARKS.md」 |
| 对比 | 「比 Caffeine 快 10x」「超越 JetCache」 | 「在 [场景 A] 下 P50 = X µs,vs Spring Cache 默认 = Y µs(多 Z µs / 多 N% 链长成本)」 |
| 大数 | 「支持百万 QPS」「千万级并发」 | 「单实例测得 X ops/s,fork=2 / 4 线程,机器 / Redis 拓扑见 §X」 |

---

## 7. 安全合规

### 7.1 已做的事(写作时不要重复「我们做过了」)

- 序列化白名单(信封模式,ADR-0003);
- SyncLock fail-fast(无 RLock 时 IllegalStateException + 显式降级开关);
- 路径泄漏修复(2026-06-27 RedissonConfiguration message 不含绝对路径);
- `SECURITY.md` 已写,声明漏洞报告邮箱 + 响应 SLA;
- 锁 key 加 hash-tag 防 cross-slot(WS-1.2b)。

### 7.2 还要做的事

| 项 | 优先级 | 行动 |
|---|---|---|
| OWASP Dependency-Check | P1 | `dependency-check-maven` 插件,High / Critical CVE fail build |
| Snyk 或 GitHub Dependabot security | P0 | GitHub 仓库启用(免费,Settings → Security → Dependabot security updates) |
| CVE 响应流程文档化 | P0 | `SECURITY.md` 已写,补:发现 CVE 后 7 天内发 patch,30 天内发 advisory 的承诺 |
| 序列化反序列化测试 | P1 | 显式跑「未在白名单的类尝试反序列化 → 拒绝」测试,不止信 ADR |
| 锁超时 / leaseTime 边界 | P1 | `SyncSupport` 已有 leaseTime 计算,补极端值(0 / 超大 / 负)测试 |

### 7.3 依赖治理

- 守住 mvn dependency:tree 无 SNAPSHOT / 无 alpha / 无 beta(已做到);
- Dependabot 启用 + group upgrade(避免 1 PR 30 个 dep);
- 锁版本策略:compile scope 锁 patch,optional / provided 锁 minor。

---

## 8. 文档与教育

### 8.1 三层文档模型(行业标准)

| 层 | 内容 | 在哪 |
|---|---|---|
| **Reference**(查 API) | javadoc + `@ConfigurationProperties` 表 | 源码 + `javadoc.jar` |
| **Guide**(讲设计 / 讲原理) | wiki(已有 39 页) | `wiki/` |
| **Tutorial**(做项目) | examples 仓库 + 5 分钟跑起来 | `davidhlp/resicache-examples` |

**禁止**:在 README 里塞 API 详表(应去 javadoc),在 wiki 里塞 step-by-step 操作(应去 examples)。

### 8.2 视频 / Conference / 博客

| 类型 | 行动 |
|---|---|
| 15 分钟技术演讲 | 在 Redisson 社区 meetup / Spring I/O 申请(2026 H2 目标) |
| 中文 B 站视频 | 「ResiCache 5 分钟上手」+ 「为什么不用 Spring Cache 默认」 |
| 英文博客(Medium / dev.to) | "Why we built ResiCache for Redisson" 系列(3 篇) |
| InfoQ / The New Stack 投稿 | 1 篇深度技术稿,目标 2026 Q4 |

**纪律**:每篇对外内容**走 ADR 引用纪律**,禁止复述未审过的定位;每篇发版前给社区 1 个 reviewer 看一遍(避免事实错误)。

### 8.3 i18n 现状与策略

- 英文 README canonical(已立);
- 中文 README.zh-CN.md「可能滞后,以英文为准」(已声明);
- wiki 全中文(内部 LLM 知识库,合理);
- 对外 PR 描述、commit message、CHANGELOG 用英文;
- 中文社区回复可用中文,但**关键文档引用一律给英文路径**。

### 8.4 迁移指南(Spring Cache 原生 → ResiCache)

独立文件 `MIGRATION.md`:

| 章节 | 内容 |
|---|---|
| 「为什么迁移」 | 不是「ResiCache 更好」,而是「你需要 [防护] 能力时,Sprint Cache 默认不提供」 |
| 「什么时候不要迁移」 | 单实例 + 无热点 key + 无穿透风险 → 不必迁 |
| 「5 步迁移」 | 替换 import + 加 Redisson + 改注解 + 加 @EnableResiCache + 验证 |
| 「回滚」 | 把注解换回即可,无 schema 变更 |
| 「风险」 | 仅 fail-fast 行为改变(WS-1.2a);其余向后兼容 |

---

## 9. 发布与分发

### 9.1 Maven Central 上架(1.0 前的硬关卡)

| 步骤 | 行动 | 工时 |
|---|---|---|
| Sonatype 账号注册 | `issues.sonatype.org`,创建 OSSRH ticket | 0.5d |
| GPG 签名密钥生成 | `gpg --gen-key`,发布到 keyserver | 0.5d |
| pom 改 `groupId=io.github.davidhlp` | 已经在 pom 里,确认 | 0d |
| 加 `maven-gpg-plugin` + `maven-source-plugin` + `maven-javadoc-plugin` | `release` profile | 1d |
| 加 `nexus-staging-maven-plugin` | `release` profile | 0.5d |
| `mvn clean deploy -P release` | dry-run 1 次 + 正式 1 次 | 1d |
| 关闭 OSSRH ticket | 等 Sonatype 审核(通常 2-5 天) | 等待 |

**总工时**:~3 天 + 等待。**目标**:v0.3.0 或 v1.0.0 时上架。

### 9.2 SemVer 与稳定承诺

- v1.0 之前:任何 minor 版本都可能 break,**用户锁定 minor 版本**;
- v1.0.0 之后:`@RedisCacheable` / `@RedisCachePut` / `@RedisCacheEvict` / `@RedisCaching` 公开 API 稳定;
- **breaking change**:必须 major bump,CHANGELOG 必须写 `⚠️ BREAKING`;
- `STABILITY.md` 标注哪些类属 internal(可变),哪些属 stable(锁定)。

### 9.3 升级指南

- `CHANGELOG.md` 每版本必须有「Upgrading」小节;
- v0.0.x → v1.0 单独写一份 `UPGRADE_1_0.md`,列出所有 breaking 与自动迁移建议。

### 9.4 Spring Boot 版本兼容矩阵

**单线**(ADR-0007)下,**不再承诺多 Boot 兼容**,只在 README / `COMPATIBILITY.md` 写:

```
ResiCache 1.x  → Spring Boot 4.0 / Java 21 / Redisson 3.50+
ResiCache 0.0.x → Spring Boot 3.x / Java 17(已 EOL,仅留作迁移路径)
```

---

## 10. 社区与采用

### 10.1 Star 增长路径(不刷、不买)

- ✅ 在 Redisson 官方仓库 issue / discussion 里**回答相关问题**(不推销,只回答),挂签名 + 1 行「如果你需要 `@RedisCacheable` 一行注解即用,我做了 ResiCache」;
- ✅ Reddit r/java / r/SpringBoot / Hacker News Show HN(1.0 后);
- ✅ Java 公众号 / 知乎专栏(中文社区);
- ❌ 任何「star-for-star」交易、刷星平台;
- ❌ 任何付费推广。

### 10.2 「Redisson 社区」曝光(主战场)

| 渠道 | 行动 |
|---|---|
| Redisson 官方 GitHub | 提 1 个 discussion(非 issue):「如果你想做 declared cache protection,看 ResiCache」 |
| Redisson 群(Telegram / Gitter) | 进群潜水,只在被问相关问题时回答 |
| Redisson 中文群 | 同上,中文回复 |
| Spring 中文社区 | 「在 Spring Cache 之上做防护的另一个选择」 |

### 10.3 中文社区策略

- 不做单独的「中文 README + 英文 README 双 canonical」,英文永远是 canonical(已立);
- 中文社区回复可以,但**关键 PR / wiki 链接给英文路径**;
- B 站视频可用中文,但视频脚本里引用的所有 spec / ADR 链接用英文 wiki 路径。

### 10.4 真实生产案例(成功标尺的核心)

- 0 → 1:找 1 个愿意在生产用 ResiCache 的项目(solo 自己的项目或朋友的项目即可);
- 1 → 3:让 3 个用户同意在仓库首页 / 博客里写「我们在 X 环境用 ResiCache 处理 Y」;
- **不要求**用户公开敏感信息,允许「某电商,日均 2 亿 QPS,ResiCache 防 [具体场景]」匿名化表述;
- **必须**诚实:不能说「30+ 公司生产在用」如果实际是 3 个。

---

## 11. 治理与可持续性(对抗 solo 风险)

> 任何 solo 库的**最大风险**不是技术,是维护者 bus factor = 1。1.0 前必须启动这个维度。

### 11.1 Co-maintainer 招募条件

**不要**等「有大神主动加入」,**主动**做:

| 触发条件 | 行动 |
|---|---|
| GitHub star > 100 | 在 README 加「Looking for co-maintainers」小节 |
| 有 2+ 个外部 PR 贡献 | 在贡献者中邀请 1 个 review 质量最高的 |
| v1.0 之前 | 不招「co-maintainer」,招「reviewer」,给 triage / label 权限即可 |
| v1.0 之后 | 给 1-2 个 reviewer 升 co-maintainer,要求签署 CLA(用 GitHub 集成) |

### 11.2 模块所有权分割(降低单点风险)

把仓库按 `protection/<mechanism>/` 拆开,每个机制**独立可理解**:

- 「布隆过滤器的实现细节我不需要懂整体就能改」→ 这是设计目标;
- 当前已部分做到(2026-06-30 候选 5 误判否决保留这个纪律);
- **未来**:把每个机制提到 README 「模块速查」表里,降低入口门槛。

### 11.3 CODEOWNERS 落地

- 仓库根 `CODEOWNERS` 已写,确认每个核心目录至少 1 个 owner(目前是 solo,占位);
- 1.0 前:**故意**把 `/protection/*/filter/` 标为「need 1 reviewer」,强求外部贡献;
- 1.0 后:把每个 mechanism 分配给一个具体 co-maintainer。

### 11.4 「如果我明天消失」清单(必须可执行)

```
1. 仓库设为 archived(read-only),附 README 指向替代方案
2. 给所有已开放 issue / discussion 写一条「仓库将 archived,推荐 fork」
3. GPG 密钥公开,任何人都可签发 patch release
4. nexus maven central 留 contact email,允许转移 owner
```

**当前状态**:未做。**行动**:写一份 `BUS_FACTOR.md`(具体步骤,不解释为什么)。

---

## 12. 6 / 12 个月执行路线图

> 不承诺 deadline 给外部,只对内参考。每个季度末复盘一次是否还按这条路走。

### 12.1 6 个月(目标:v0.3.0 ~ v1.0.0)

| 工作线 | 交付物 | 不做什么 |
|---|---|---|
| Maven Central 上架 | `io.github.davidhlp.resicache:resicache-core` / `-spring-boot-starter` 可被 `dependency:copy` | 不等审核完才发 1.0(可以先 v0.3.0 上架) |
| Spring Boot Starter | `resicache-spring-boot-starter` artifact,5 分钟跑起来 | 不做 fat jar |
| Examples 仓库 | `davidhlp/resicache-examples` 6 个 example | 不做 Docker compose(留 example 自带 application.yml) |
| Observation 升级 | 链级 Observation,trace 集成 | 不做 OpenTelemetry SDK 集成(走 Micrometer 桥) |
| JMH 框架 | `benchmarks/` 模块 + `BENCHMARKS.md` 对比页 | 不与 Caffeine / JetCache 比 |
| 对比页 | `BENCHMARKS.md` + 「before/after 手写 Redisson」对照 | 不放性能营销话术 |
| 故障注入 | chaos 测试集 | 不进 main CI |

### 12.2 12 个月(目标:v1.1.x ~ v1.2.x)

| 工作线 | 交付物 | 不做什么 |
|---|---|---|
| Co-maintainer | 至少 1 个 reviewer | 不招技术合伙人级别 |
| 真实生产案例 | 至少 3 个公开(可匿名) | 不公开客户名(无授权) |
| Conference talk | 1 场技术演讲 | 不做付费 booth |
| 中文视频 | 1-2 个 B 站视频 | 不做课程(精力不在) |
| Reproducible Builds | SBOM + reproducible jar | 不做 SLSA L3 |
| OpenSSF Scorecard | ≥ 6 分 | 不强求 9+ |
| Bloom / Lock 接口扩展(谨慎) | `BloomFilterInterface` / `LockManagerInterface` 已存在,不动 | 不动内核 |

### 12.3 不做的事(anti-roadmap)

| 不做 | 理由 |
|---|---|
| L1 Caffeine + L2 Redis 多级 | 让给 JetCache |
| 广播失效 | 让给 JetCache |
| 反应式 Redis 路径 | SDR 4 已 async;非阻塞够用,不全反应式 |
| Spring Cloud 集成 | 不绑 Spring Cloud,Spring Cache 够用 |
| Kotlin DSL | `@RedisCacheable` 已可读;不写 `cacheable<String> { }` |
| GraalVM native image 适配 | Postpone until 真实用户诉求出现 |
| 中文 README canonical | 英文永远 canonical |

---

## 13. 度量与检查表

### 13.1 季度复盘维度(每月轻量,季度深度)

| 维度 | 指标 | 数据来源 |
|---|---|---|
| 战略一致 | ADR-0006 §1.4 成功标尺是否在轨道 | git star / issue / discussion 数 |
| 工程质量 | 测试数 / JaCoCo / checkstyle / CI 绿时间 | `./mvnw clean verify -B` 输出 |
| DX | 「5 分钟跑起来」README 是否能跑 | 实际跑一遍 |
| 可观测性 | Timer / Counter 数量 / 是否暴露 / OTel 集成 | Micrometer + Grafana |
| 性能 | JMH 数字有无漂移 | `benchmarks/` 输出 |
| 安全 | CVE 数量 / 修复时长 / OWASP 报告 | `dependency-check` 输出 |
| 文档 | wiki 页数 / orphan 数 / 英文 / 中文差异 | `wiki/lint` 输出 |
| 社区 | star / issue 平均响应 / PR 平均合入 / production user 数 | GitHub API |
| 治理 | co-maintainer 数 / BUS_FACTOR.md 是否更新 | 仓库 |

### 13.2 「再决定一次定位」信号

当以下信号 ≥ 2 项出现时,开 ADR 重新评估定位:

- 6 个月过去,star < 50 且 production user < 2 → **Wedge 是否错了**(不是 Wedge 1 vs Wedge 2,而是「到底是不是 Redisson companion」)
- Redisson 官方开始自己实现 declarative cache protection → **重新评估是否仍是空白**
- Spring Cache 原生引入类似防护注解 → **Wedge 失效,转「深耕 ResiCache 长尾场景」或直接 sunset**
- Solo 维护超过 12 个月且 burn-out 信号明显 → **转向 maintenance mode,不强求增长**

### 13.3 强制周期

- **每月**:看 1 次 §13.1 数据表(15 分钟)
- **每季度**:对照 §12 路线图,看是否偏航,如有偏航改路线图(commit 留痕)
- **每年**:对照 ADR-0006 成功标尺,看是否需要重新评估定位;是 → 开新 ADR;否 → 维持

---

## 附录 A:与现有 ADR/wiki 的关系(无重复,只引用)

| 本指南章节 | 引用 | 不重复 |
|---|---|---|
| §1 战略 | ADR-0006 全文 | 不重新写定位叙事 |
| §2.1 已落地 | wiki/log.md 2026-06-30 起 5 条 | 不复述 commit 详情 |
| §3.1 测试 | `PathCAopContractIT`(4 tests 绿,Step 0 契约) | 不重写测试设计 |
| §3.4.1 CI 护栏 | `.github/workflows/docs-link-check` | 不复述黑名单规则 |
| §5 可观测性 | WS-1.4(链级 Timer + per-handler counter) | 不复述实现 |
| §6.1 当前 JMH | WS-1.5(hit 210µs / miss 365µs / async 423µs) | 不重跑基准 |
| §7.1 已做安全 | ADR-0003 + 2026-06-27 多 AI CR | 不复述修复 commit |

## 附录 B:不要做的事清单(每次发版前扫一遍)

| ❌ 不要做 | 原因 |
|---|---|
| 写「极致性能」「企业级」「毫秒级」等形容词 | ADR-0001 纪律 |
| 与 JetCache / Caffeine 正面比较(能力而非性能) | ADR-0006 红线 |
| 给 ResiCache 加多级缓存 / 广播失效 | ADR-0006 让出 |
| 把 example 塞本仓库 `examples/` 子目录 | §4.5 反模式 |
| 默认全开所有 protection | ADR-0004 必须 preset |
| 在 README 里塞 API 详表 | §8.1 三层模型 |
| 任何「star-for-star」交易 | §10.1 |
| 任何「我们在 X 家生产在用」无据陈述 | §10.4 |
| 把 fail-fast 异常 message 简化成栈 | §4.4 标准 |
| 把动态字符串(尤其 key)放进 Micrometer tag | §5.1 cardinality |
| 在 log 里打整个 cache key 或 value | §5.4 PII / 密钥 |
| 在没 JMH 数据时重启「高性能」叙事 | §6.4 |

## 附录 C:术语对齐(避免一个词两种意思)

| 术语 | 含义 | 反例 |
|---|---|---|
| **seam**(真 seam) | interface + `@ConditionalOnMissingBean` + 默认实现 = `@Component`,可被 `@Bean` 顶替 | 单纯 `@Component` 没接口 = 假 seam |
| **protection** | 防穿透 / 防击穿 / 防雪崩 / 防热 key 4 类防护(不是熔断 / 限流) | 「ResiCache 提供熔断」错误 |
| **chain**(责任链) | `CacheHandler.handle()` 单向推进,5 档由 `HandlerOrder` 决定 | `AnnotationHandler`(模板方法基类)不是 chain |
| **preset** | 一组 protection handler 的批量启用配置(`protection.preset=ecommerce`) | 「默认全开」不是 preset |
| **fail-fast** | 启动期 / 运行期显式抛异常 + 修复建议,而非静默降级 | 「静默降级为 synchronized」反例(WS-1.2a 已修) |
| **companion**(Redisson companion) | 站在 Redisson 之上加薄封装,不替换 Redisson | 「ResiCache 是 Redisson 替代」错误 |

---

**最后更新**:2026-06-30
**下次复盘**:2026-09-30
**维护者**:@DavidHLP(沿用 ADR 决策者署名纪律)