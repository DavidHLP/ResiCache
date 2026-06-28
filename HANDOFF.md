# ResiCache — 会话交接(切到 Linux 继续用)

> **目的**: 把本会话(Win/WSL2)的全部上下文存到仓库,供 Linux 上的新会话/你本人**无缝续接**。
> 新会话只需读本文件 + `MASTER_PLAN.md` + `wiki/adr/0006` 即可立即继续,无需重新推导。
> **生成时间**: 2026-06-28 · **分支**: master(本会话未 commit,所有产出在工作树)

---

## 0. TL;DR — 在 Linux 上从这里恢复

1. **先读** `MASTER_PLAN.md`(完整 v1 战略+工程总纲)与本文件。
2. **唯一卡点**: 当前网络(WSL2)**访问不了 Docker Hub**(IPv4 `168.143.171.93:443` 与 IPv6 均超时)→ Testcontainers 拉不到 `testcontainers/ryuk` + `redis` 镜像 → **13 个集成测试被 skip** → 无法验证防护行为。
3. **在 Linux 上第一件事**: 配好 Docker Hub 访问(见 §5),跑 `./mvnw verify`,确认 **13 个 IT 全部执行并变绿**。
4. **IT 绿后**,按 §4 顺序一口气推 v0.1.0:WS-1.2 硬化 → Path C → Boot 4 FIRE,每步跑测试守门。
5. **铁律**: 防护代码在 IT 绿线确认前**绝不盲改**(见 §6)。

---

## 1. 项目与当前位置

- **项目**: ResiCache v0.0.2→v0.0.3 — Spring Cache + Redis 的缓存**防护增强**(穿透/击穿/雪崩/热 key),~10k LoC Java,Spring Boot 3.4.13,Java 17,Redisson 3.27.0(optional),Caffeine 3.1.8。
- **链架构**: `Bloom(100)→SyncLock(200)→EarlyExpiration(250)→TTL(300)→NullValue(400)→ActualCache(500)`,`HandlerOrder` 枚举单一真理源,`@HandlerPriority` 自动发现。
- **本会话进展**: 完成了**战略评估 + 完整总纲 + 定位裁定 + 对比页**;**代码尚未动**(卡在 IT 验证环境)。

## 2. 已锁定的决策(经多轮对抗式多 Agent 评审)

| 决策 | 内容 | 文件 |
|---|---|---|
| **北极星定位** | **ResiCache for Redisson** —— "Redisson 忘了做的那条可声明缓存防护链"。理由=信任算术(采用者信 Redisson,不信 solo 核心基建)。3 评委 2:1 裁定。 | `wiki/adr/0006` |
| **架构方向** | **Path C**(保留 `RedisCacheWriter` 扩展缝,销毁 `CacheOperationMetadataHolder` ThreadLocal + `CacheInterceptor` 继承)。**不**做 Path B(全 JetCache 式重建),**不**原地不动。 | `MASTER_PLAN.md` §6 |
| **内核抽取** | **不近期做**(handlers 直依赖 `RedisTemplate`/`CacheStatisticsCollector`/`NullValue`,抽出需 3 端口,已验证"便宜抽出"为伪命题),仅作 ADR-0005 长寿对冲。 | `wiki/adr/0005` |
| **优先级** | **FIRE 先行**: Boot 4 / SDR 4 / Java 21 兼容(Boot 3.4.x 已 EOL,是新采用者否决项)。 | `MASTER_PLAN.md` §2 WS-1.1 |
| **不与 JetCache 正面竞争** | 永久让出多级缓存/广播失效/API 级访问。差异点=Bloom + 可编排链。 | `docs/comparison.md` |

## 3. 本会话产出文件(工作树,未 commit)

- `MASTER_PLAN.md` — 完整总纲(北极星/3支柱/路线图/Path C 7步/风险/kill criteria/头30天)
- `wiki/adr/0006-redisson-companion-positioning.md` — 定位裁定(取代 ADR-0001 叙事)
- `wiki/adr/0005-kernel-extraction-hedge.md` — 否决近期内核抽取
- `docs/comparison.md` — 诚实对比页(ResiCache vs JetCache vs Caffeine vs raw Redisson)
- (原有)`wiki/adr/0001..0004`、28 页 wiki 仍在

> ⚠️ **小尾巴**: `wiki/index.md` 的 ADR 列表需补登记 0005/0006(否则 CI `docs-link-check` 可能提示)。

## 4. v0.1.0 执行计划(IT 绿后按序推)

### WS-1.2 P0 企业硬化(3 个承重正确性隐患)
- **1.2a `SyncSupport` fail-fast**: `src/main/java/io/github/davidhlp/spring/cache/redis/protection/breakdown/SyncSupport.java:49` —— `distributedManagers.isEmpty()` 时走 `synchronized` 单 JVM(静默降级)。改:当声明 `sync=true` 但无 `LockManager` bean(Redisson 缺失)时**启动期 fail-fast**,挂载点 `config/CachingEnablementValidation.java`。配 Testcontainers 故障注入测试。
- **1.2b Cluster hash-tag**: `protection/breakdown/DistributedLockManager.java:49` —— `lockKey = prefix + key` 无 hash-tag,Cluster 下锁与缓存 key 可能不同 slot → 锁失效。改:锁 key 与缓存 key 同 slot(需 Cluster IT 验证)。
- **1.2c 原子 CLEAN**: `chain/ActualCacheHandler.java`(CLEAN=非原子 SCAN+DEL)+ `protection/bloom/BloomFilterHandler.java:165 clearBloomFilter()`(`bloomSupport.clear()` 整体清空 → 确定性穿透窗口)。改:原子化(Lua)或延迟重建。

### Path C 重构(销毁 ThreadLocal,行为零回归)— `MASTER_PLAN.md` §6 有验证过的 7 步
- 关键耦合点: `holder/CacheOperationMetadataHolder.java`(纯 `ThreadLocal<AnnotatedElementKey>`)← 只由 `cache/RedisCacheInterceptor.java:71 setCurrentKey()` 设置 → 被 `cache/RedisProCacheWriter.java:buildContext()` + `cache/RedisProCache.java:lookupOperation()` 读取 → 6 handler 的 `shouldHandle()` 全以 `context.getCacheOperation()!=null` 为前提。
- Step 0(回归契约测试,AOP 行为保持)→ Step 1(`MethodMetadataResolver` 接口,无操作重构)→ … → Step 7(删 `CacheOperationMetadataHolder`,改写 ADR-0002)。
- 核心约束(已验证): `Cache.get(Object,Callable)` 签名不可变 → 元数据由 interceptor 拥有、scoped、可 snapshot 的 carrier 在整个 `CacheAspectSupport.execute()` 期间(含同步 `Cache.get()`)active。
- 顺带红利: Step 6 重新接管 `retrieve()/store()` 异步路径,消灭 `commonPool` 丢 ThreadLocal 的潜伏 bug。

### Boot 4 FIRE — `MASTER_PLAN.md` §2 WS-1.1
- 开 `boot4` 线,升 parent→Spring Boot 4.0.0,Redisson→兼容版(3.5x+),双构建 CI matrix(Boot 3.4×Java17 / Boot 4×Java21)。审计 8–10 个 SDR 内部扩展点对 SDR 4.0 的破坏(issue #3348: 4.x `RedisCacheWriter` 默认 sync→async)。

## 5. ⛔ 当前卡点 + Linux 上的修复

**现象**: `./mvnw verify` 报 `BUILD SUCCESS` 但是**假绿** —— 13 个 Testcontainers IT 被 skip:
```
WARN org.testcontainers... DOCKER_HOST unix:///var/run/docker.sock is not listening  (权限)
→ 修了权限(chmod 666)后又: Status 500 ... dial tcp [IPv6]:443: i/o timeout  (拉镜像)
→ 禁 IPv6 后: dial tcp 168.143.171.93:443: i/o timeout  (IPv4 也超时)
```
**根因**: 本网络(WSL2/疑似 CN)**访问不了 Docker Hub**(IPv4+IPv6 均超时;`hello-world` 能跑是因已缓存或偶发)。`docker pull testcontainers/ryuk:0.11.0` / `redis:7-alpine` 均失败。

**Linux 上修复(任选其一)**:
1. **registry 镜像加速**(CN 推荐): 编辑 `/etc/docker/daemon.json`:
   ```json
   { "registry-mirrors": ["https://<你的可用镜像地址>"] }
   ```
   然后 `sudo systemctl restart docker`。可用镜像需自测(Aliyun `https://<id>.mirror.aliyuncs.com`、各高校镜像等;2024 年后多个公共镜像已关停)。
2. **代理**: 给 Docker daemon 配 HTTP/HTTPS proxy(`/etc/systemd/system/docker.service.d/http-proxy.conf`)。
3. **换网**: 切到能直连 Docker Hub 的网络/VPN。
4. **预拉**: 在能访问的环境 `docker pull` 后 `docker save` → 拷到 Linux `docker load`。

**验证修复**: `docker pull testcontainers/ryuk:0.11.0 && docker pull redis:7-alpine` 成功 → `./mvnw verify` → 13 个 IT 执行变绿。

> 附:本会话曾用 `sudo sysctl -w net.ipv6.conf.all.disable_ipv6=1` 测试(已**还原为 0**);该法无效,因 IPv4 也超时。

## 6. 🔒 不可违背的铁律(防护库的命门)

- **Path C 在 Step-0 回归测试变绿前不得推进**(MASTER_PLAN 非协商原则 #1)。
- **永不静默降级**(sync 标榜分布式却单 JVM = 最坏失败模式)。
- **IT 绿线确认前不盲改防护代码**。本会话正是因此停在代码起点。
- **EOL 平台是采用否决项** → FIRE(Boot 4)是 P0。

## 7. 环境事实(供 Linux 对照)

- Java 17.0.2(vfox),Maven 3.9.6(wrapper)。**Linux 上需另装 Java 21**(双构建矩阵)。
- 在线 `./mvnw compile` 通过(99 源文件);离线模式失败(本地 `maven-clean-plugin` 损坏,用在线即可)。
- `./mvnw verify` 现状: **615 单测全绿 + 13 IT skip**(Docker Hub 拉不到镜像)。
- JaCoCo 门: 70% line / 40% branch。Checkstyle 强制。
- **Maven Central 发布已配**(`central-publishing-maven-plugin`+`maven-gpg-plugin`+source+javadoc)—— 需 portal token + GPG 密钥才能首次发版(v0.1.0)。
- CI: `.github/workflows/ci.yml` Java 17/21 matrix + checkstyle + qodana + docs-link-check。

## 8. Linux 新会话第一步(逐条)

```bash
cd <repo>                              # 切到 ResiCache
git status                             # 确认工作树: MASTER_PLAN.md / docs/ / wiki/adr/0005,0006 未提交
cat MASTER_PLAN.md HANDOFF.md          # 读总纲 + 本交接
docker pull testcontainers/ryuk:0.11.0 # 确认 Docker Hub 可达(见 §5 修复)
docker pull redis:7-alpine
./mvnw verify                          # 期待: 615 单测 + 13 IT 全绿(BUILD SUCCESS, Skipped: 0)
# IT 绿后,按 §4 推 WS-1.2 → Path C → Boot 4
```

## 9. 关键文件索引(改代码时查)

| 想改/查 | 文件 |
|---|---|
| ThreadLocal 持有者(Path C 要销毁) | `holder/CacheOperationMetadataHolder.java` |
| 拦截器(Path C 要换成自有 MethodInterceptor) | `cache/RedisCacheInterceptor.java` |
| 责任链工厂 + 防护 kill-switch | `chain/CacheHandlerChainFactory.java` |
| Writer(buildContext 读 ThreadLocal) | `cache/RedisProCacheWriter.java` |
| Cache(lookupOperation 读 ThreadLocal) | `cache/RedisProCache.java` |
| sync 静默降级(1.2a) | `protection/breakdown/SyncSupport.java` |
| 锁 key 无 hash-tag(1.2b) | `protection/breakdown/DistributedLockManager.java` |
| CLEAN 非原子 + 布隆清空(1.2c) | `chain/ActualCacheHandler.java` + `protection/bloom/BloomFilterHandler.java` |
| 启动校验(1.2a fail-fast 挂载点) | `config/CachingEnablementValidation.java` |
| 配置树 | `config/RedisProCacheProperties.java` |
| 自动装配 | `config/RedisCacheAutoConfiguration.java` / `RedisProCacheConfiguration.java` |
| Redisson 装配 | `config/RedissonConfiguration.java` |

## 10. 待办清单

- [ ] Linux 上修好 Docker Hub 访问 → `./mvnw verify` IT 全绿(§5)
- [ ] WS-1.2a/b/c 硬化 + 各自 Testcontainers 故障注入测试(§4)
- [ ] Path C Step 0–7(§4 / MASTER_PLAN §6)
- [ ] Boot 4 FIRE: `boot4` 线 + 双构建矩阵(§4)
- [ ] `wiki/index.md` 补登记 ADR-0005/0006
- [ ] 首次发 Maven Central(密钥就位时,v0.1.0)
- [ ] 后续版本见 `MASTER_PLAN.md` §5 路线图(v0.2 preset+Observation+sample, v0.3 JMH+迁移工具, v1.0 发布)

---

*本会话还产出过两份对抗式评审工作流的完整结构化结论(战略评估 + 定位评审团),已浓缩进 MASTER_PLAN 与 ADR;如需原始 20+12 agent 的逐条 findings/verdicts,可在本会话的 workflow transcript 目录(`/home/DavidHLP/.claude/projects/-home-DavidHLP-ResiCache/<session>/subagents/workflows/`)查 `wf_*.output`。*
