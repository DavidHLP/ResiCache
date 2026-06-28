# ResiCache — 会话交接(FIRE 进行中)

> **目的**: 把本会话全部上下文存到仓库,供下次会话无缝续接。
> **生成时间**: 2026-06-28(关机前)· **当前分支**: `boot4`
> **新会话恢复**: 读本文件 + `MASTER_PLAN.md` + `~/.claude/plans/stateful-crafting-bubble.md`(FIRE 完整方案)。

---

## 0. TL;DR — 下次从这里恢复

1. **当前在 `boot4` 分支**。两条分支:
   - `master` (`5a05d0a`): Boot 3.4.13 + **WS-1.2 硬化已完成**(672 测试绿)—— 默认兼容线,不受 boot4 影响。
   - `boot4` (`fd55bd4`): Boot 4.0/SDR 4.0/Spring 7/Java 21/Redisson 3.50 —— **FIRE M1 已完成**(`compile -Pboot4` 绿)。
2. **FIRE 进度**: M1(boot4 编译绿)✅ → **M2(`test -Pboot4` 672 绿)✅ [2026-06-28]** → **M3(`verify -Pboot4` + async shim)待续** → M4(CI + 文档)。
3. **下次第一件事**: `./mvnw verify -Pboot4 -B`(M3:13 Testcontainers IT + JaCoCo 70%/40% 门 + `supportsAsyncRetrieve()=false` shim)。M2 改动见 §4a。
4. **铁律**: 永不静默降级;IT 绿线前不盲改防护代码;commit/push/merge 需显式批准;默认 master(FIRE 已授权用 boot4 分支)。

---

## 1. 项目与北极星

- **项目**: ResiCache — Spring Cache + Redis 的缓存**防护增强**(穿透/击穿/雪崩/热 key),~10k LoC Java。
- **链架构**: `Bloom(100)→SyncLock(200)→EarlyExpiration(250)→TTL(300)→NullValue(400)→ActualCache(500)`,`HandlerOrder` 枚举单一真理源,`@HandlerPriority` 自动发现,`AbstractCacheHandler.handle` 单一引擎(CONTINUE 推进 / TERMINATE 短路 / SKIP_ALL 物化 skipRemaining)。
- **北极星**(ADR-0006): **ResiCache for Redisson** — "Redisson 忘了做的那条可声明缓存防护链"。
- **技术栈**: Boot 3.4.13(EOL)→ 4.0(迁移中);Java 17/21;SDR 3.x/4.0;Redisson 3.27.0/3.50.0;Caffeine 3.1.8;Testcontainers(redis:7-alpine, ryuk:0.11.0);JaCoCo 70%/40% 门禁;checkstyle 强制。

## 2. 已完成(本会话 + 之前)

### ✅ WS-1.2 P0 硬化(master `5a05d0a`,672 测试绿)
- **1.2a SyncSupport fail-fast**(⚠️ BREAKING): 无分布式锁后端时不再静默降级单 JVM,改启动 WARN + 运行 fail-fast;新增 `resi-cache.sync-lock.local-only`。
- **1.2b Cluster hash-tag pinning**: `DistributedLockManager.buildLockKey` 给锁 key 加 `{...}`,与缓存 key 同 slot。
- **1.2c 布隆 CLEAR rebuilding 窗口**: CLEAN 后 bloom 空导致 `RedisProCache.get:157` 静默 return null(违反 @Cacheable 契约);`BloomSupport.clear` 开 Redis-backed rebuilding 窗口(TTL=`rebuild-window-seconds`=30s),期间 `mightContain` fail-open。新增 `resi-cache.bloom-filter.rebuild-window-seconds`。经 8-agent Workflow 评审(2:1 否决"不清 bloom"方案)。
- 文档全同步: CHANGELOG / README×2 / configuration.md / bloom-filter.md / breakdown-lock.md / log.md。

### ✅ FIRE M1 — boot4 编译绿(boot4 `fd55bd4`)
见 §4。

## 3. 双分支状态

| 分支 | HEAD | 内容 | 测试 |
|---|---|---|---|
| `master` | `5a05d0a` | Boot 3.4.13 + WS-1.2 硬化 | `./mvnw verify` 672 绿 |
| `boot4` | `fd55bd4` | Boot 4.0 + SDR 4.0 + Spring 7 + Java 21 + Redisson 3.50 | `compile -Pboot4` 绿(M1);test/verify 待续 |

**FIRE 策略(用户选定)**: boot4 独立 git 分支迁移,master 保留 boot3。CI 双分支矩阵。符合 MASTER_PLAN「boot4 分支」本意 —— 自然处理 Boot 4 模块化 package 重定位(同一 .java 无法 import boot3/boot4 不同 package,故用分支隔离)。

## 4. FIRE M1 适配详情(boot4 分支,7 文件)

`compile -Pboot4` BUILD SUCCESS(99 源文件)。适配项:

1. **pom.xml**: 加 `boot4` profile(`redisson.version=3.50.0` / `java.version=21`);parent 改 `4.0.0`;移除冗余 `spring-boot-starter-aop`(Boot 4 改名 `aspectj`,ResiCache 不用 AspectJ,grep 确认无 `@Aspect`)。
2. **Boot 4 模块化 package 重定位**(规则: 每模块根 package = `org.springframework.boot.<technology>`):
   - `o.s.b.actuate.health.{Health,HealthIndicator}` → `o.s.b.health.contributor.{Health,HealthIndicator}`
   - `o.s.b.autoconfigure.data.redis.RedisAutoConfiguration` → `o.s.b.data.redis.autoconfigure.DataRedisAutoConfiguration`(**改名 Redis→DataRedis**)
   - `o.s.b.autoconfigure.data.redis.RedisProperties` → `o.s.b.data.redis.autoconfigure.DataRedisProperties`(**改名**)
   - 注: `o.s.b.autoconfigure.condition.*` + `AutoConfiguration` 在 boot4 **保留**(未重定位)。
   - 受影响文件: `observability/RedisCacheHealthIndicator.java`、`config/MetricsAutoConfiguration.java`、`config/RedisCacheAutoConfiguration.java`、`config/RedissonConfiguration.java`。
3. **SDR 4 API**:
   - `RedisProCacheManager:67` super 构造参数序: boot3 `(writer,config,Map,boolean)` → boot4 `(writer,config,boolean,Map)`。
   - `RedisProCacheWriter` SDR 4 新增抽象方法 `clear(String,byte[])` + `evict(String,byte[])`(SDR 4 把 `clean`/`remove` 重命名对齐 Spring Cache 术语);加 `clear`/`evict` 委托现有 `clean`/`remove` 实现。

## 4a. M2 适配详情(boot4 分支,`test -Pboot4` 672 绿)

1. **RedissonConfigurationTest**:`RedisProperties`(import/类型/new)→ `DataRedisProperties`(`o.s.b.data.redis.autoconfigure`),与主代码 RedissonConfiguration 对齐。
2. **RedisProCacheManagerTest** ×4:`.getCacheConfiguration().getTtl()` → `.getTtlFunction().getTimeToLive(null, null)`(SDR 4 把固定 `Duration` TTL 重构为 `RedisCacheWriter.TtlFunction`,`getTtl()` 移除;`entryTtl(Duration)` 仍存)。
3. **pom.xml**:`redisson-spring-boot-starter` → `redisson`(core)。根因:starter 3.x 的 `RedissonAutoConfigurationV2` 硬引用 Boot 3 `RedisAutoConfiguration.class`,Boot 4(已重定位 `DataRedisAutoConfiguration`)context 加载爆炸(ClassNotFoundException)。ResiCache 自带 `RedissonConfiguration`(`@ConditionalOnClass`+`@ConditionalOnMissingBean RedissonClient`),只需 core API,无需 starter auto-config。master(boot3)零影响(改动仅在 boot4 分支)。
4. **RedisProCacheTest** evict 测试 ×4:stub/verify `cacheWriter.remove` → `cacheWriter.evict`(SDR 4 `RedisCache.evict`(super)改调 `writer.evict`;`RedisProCacheWriter.evict` 委托 `remove`)。clear 测试仍用 `clean`(SDR 4 `RedisCache.clear` 未改名)。

## 5. M3-M4 待续(FIRE 剩余)

### M2 — `test -Pboot4` 全绿(下次起点)
- `git checkout boot4 && ./mvnw test -Pboot4 -B` → 收集测试侧 SDR 4 breaking。
- **预期错误模式**(基于 M1 经验):
  - 测试代码 import 旧 package(`actuate.health` / `autoconfigure.data.redis`)→ 换 import(同 §4 规则)。
  - 测试可能直接调 `clean`/`remove`(SDR 4 重命名)→ 改 `clear`/`evict` 或保留(若 deprecated 仍在)。
  - Redisson 3.50 API 变化(ResiCache 调用面窄: `RLock` getLock/tryLock/unlock + `Config`;`RLock` public 契约 3.27→3.50 稳定)。
- 迭代: 每批错误 → 适配 → recompile,直到 test 绿。

### M3 — `verify -Pboot4` 全绿 + async shim(FIRE 完成判据)
- **shim**: `RedisProCacheWriter.supportsAsyncRetrieve()`(`cache/RedisProCacheWriter.java:90`)从 `return RedisCacheWriter.super.supportsAsyncRetrieve()` 改 `return false`(注释标 Path C Step 6 恢复)。理由: SDR 4 #3348 默认 async 会暴露 commonPool 丢 ThreadLocal 潜伏 bug;FIRE 不越界做 Path C 的 snapshot/restore。
- `./mvnw verify -Pboot4 -B` 全绿(含 13 Testcontainers IT)+ JaCoCo 门。
- lock-free writer 默认**不受影响**(已验证: `RedisProCacheManager.createRedisCache`/`getMissingCache` 直接 `new RedisProCache(name, redisProCacheWriter, ...)`,绕过 super writer 选择)。

### M4 — CI boot4 job + 文档
- `.github/workflows/ci.yml` 加 `boot4` job(仿现有 `compatibility` job: `versions:set-parent 4.0` + JDK 21 + `verify -Pboot4`,`continue-on-error: true` 起步,M3 绿后转 `false`)。
- **注意**: CI 的 `compatibility` job 用 `versions:set-parent` 切 parent,但**本地 `versions:set-parent` 报 MojoNotFoundException**(versions 插件 goal 未解析)。boot4 分支已用**手动 Edit pom parent** 到 4.0.0(commit fd55bd4)。CI 若用 versions:set-parent 需验证(goal 可能需 full coordinate `org.codehaus.mojo:versions-maven-plugin:2.x:set-parent`)。
- `COMPATIBILITY.md` 双矩阵;CHANGELOG 记 FIRE。

## 6. 关键技术发现(Boot 4 模块化)

- **模块化 blog**: 2025-10-28 "Modularizing Spring Boot"。Boot 4 拆小模块,每模块 `spring-boot-<technology>` + 根 package `o.s.b.<technology>` + starter `spring-boot-starter-<technology>`。
- **package 重定位确认**(从本地 `~/.m2` Boot 4 jar `jar tf` 查实):
  - `HealthIndicator`/`Health` → `o.s.b.health.contributor`(spring-boot-health-4.0.0.jar)
  - `RedisAutoConfiguration` → `DataRedisAutoConfiguration` @ `o.s.b.data.redis.autoconfigure`
  - `RedisProperties` → `DataRedisProperties` @ `o.s.b.data.redis.autoconfigure`
  - 其他已知重定位: `BootstrapRegistry` → `o.s.b.bootstrap`;`@EntityScan` → `o.s.b.persistence.autoconfigure`;`@PropertyMapping` → `o.s.b.test.context`。
- **SDR 4 RedisCacheWriter 重命名**: `clean`→`clear`、`remove`→`evict`(对齐 Spring Cache 标准术语);#3348 `put` 默认 async。
- **SDR 4 RedisCacheManager**: 构造参数序变(boolean 与 Map 交换)。
- **Migration guide**: https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide(3.x→4.x 部分不完整,签名级 breaking 靠编译驱动)。
- **Boot 4.0 GA**: 2025-11-20,Spring Framework 7,Java 17 最低/21 推荐 LTS/25 支持;Jakarta EE 11;Jackson 2→3(ResiCache 用 SecureJackson,需留意)。

## 7. 环境与命令注意事项

- **context-mode hook 拦截 `mvn`/`mvnw` Bash**: 重定向到 `mcp__plugin_context-mode_context-mode__ctx_execute`。**关键**: ctx_execute 的文件写在项目目录(`/home/davidhlp/project/ResiCache`)是持久的(versions:set-parent 改 pom 可见);构建输出被过滤(只回 grep/tail)。
- **boot4 跑法**:
  - boot4 分支 pom 已是 parent 4.0.0 + profile(commit fd55bd4)。
  - `compile -Pboot4` / `test -Pboot4` / `verify -Pboot4`(`-Pboot4` 激活 redisson 3.50 / java 21 属性)。
  - 不需 `versions:set-parent`(已手动 Edit,parent 已 4.0)。
- **切分支**: `git checkout master`(boot3)/ `git checkout boot4`(FIRE)。boot4 工作树干净(M1 已 commit)。
- **Java**: 环境 Java 21 可用(boot4 用 release 21 编译)。
- **Docker**: Linux 上 Docker Hub 可达,Testcontainers IT 全绿(WSL2 阻塞已解除)。

## 8. MASTER_PLAN 序列约束(牢记)

- **"FIRE 先于一切"**: WS-1.1 是 P0 最高优先级。Path C 不得先于 FIRE。
- v0.1.0 scope = FIRE + 3 硬化(WS-1.2 ✅) + Path C 重构 + 首次发 Maven Central。
- v0.1.0 门禁: 双构建 verify 全绿;3 硬化各有故障注入测试(✅);Path C 零回归;Central 可拉取。
- 序列: FIRE(v0.1)→ Path C 与硬化并行/紧随 → **不在 v1.0 前做内核抽取**(ADR-0005 对冲)。

## 9. 超出 FIRE 的 v0.1.0 待办(M4 之后)

- **WS-1.3 Path C 重构**(销毁 ThreadLocal): 7 步序列,Step 0 回归契约测试先行。`~/.claude/plans/` 无 Path C plan(待规划)。FIRE M3 的 `supportsAsyncRetrieve=false` shim 由 Path C Step 6 恢复(snapshot/restore)。
- **WS-1.4 可观测性**: per-handler Micrometer tag + tracing 透传异步边界(依赖 Path C snapshot/restore)。
- **WS-1.5 质量**: JMH 基准 + 故障注入(部分已做: WS-1.2c 的 rebuilding Testcontainers 测试)。
- **WS-2.4 发布**: 激活 Maven Central(central-publishing + gpg + source/javadoc 已配置),做第一次 release(v0.1.0)。
- **wiki/index.md**: 补登记 ADR-0005/0006(CI docs-link-check 可能提示)。
- **README Roadmap**: 对齐 MASTER_PLAN。

## 10. 铁律与约束

1. **永不静默降级**(WS-1.2a 体现)。
2. **IT 绿线确认前不盲改防护代码**。
3. **commit/push/merge/publish/改写 git 历史** 需用户显式批准。Conventional commits `<type>: <description>`,结尾 `Co-Authored-By: Claude <noreply@anthropic.com>`。
4. **默认 master** 直接改(用户偏好);FIRE 已授权用 boot4 分支。
5. **序列化白名单**默认仅 `io.github.davidhlp.`,用户须加自己包。
6. **EOL 平台是采用否决项 → FIRE 是 P0**。

## 11. 关键文件索引

- `MASTER_PLAN.md` — v1 完整战略总纲(北极星/3支柱/路线图/Path C 7步/风险)。
- `~/.claude/plans/stateful-crafting-bubble.md` — FIRE 完整方案(M0-M4、双构建机制、shim 决策、风险)。
- `wiki/adr/0006-redisson-companion-positioning.md` — 北极星定位。
- `wiki/adr/0005-kernel-extraction-hedge.md` — 否决近期内核抽取。
- `CHANGELOG.md` — v0.1.0 [Unreleased](WS-1.2 全部 + 配置项)。
- `wiki/log.md` — 2026-06-28 WS-1.2 条目(逆序,最新在顶)。
- `CLAUDE.md` — 项目结构 + 约定(wiki 入口、HandlerOrder、策略替换)。

---

**下次起点**: `git checkout boot4` → `./mvnw test -Pboot4 -B`(用 ctx_execute)→ 按 §5 M2 迭代。FIRE 方案细节见 `~/.claude/plans/stateful-crafting-bubble.md`。
