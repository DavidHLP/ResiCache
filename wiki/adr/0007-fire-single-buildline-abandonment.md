# ADR-0007: 放弃 WS-1.1 双分支策略,统一单构建 Boot 4.0

- **Status**: Accepted
- **Date**: 2026-06-29
- **Deciders**: DavidHLP
- **Related**: ADR-0005(长寿对冲,不靠双构建对冲)、ADR-0006(Redisson-companion 定位)、`COMPATIBILITY.md`(已改单矩阵)、`CHANGELOG.md`(WS-1.1 FIRE 技术史)。原 `MASTER_PLAN.md`/`HANDOFF.md`/`TASK_BACKLOG.md` 已于 2026-06-29 归档(commit `0bc6c2b`),正文章节引用为历史论证
- **Implementing commits**: `53f8eb2`(docs) · `6f00471`(ci) · `9ad22bf`(pom)

## Context

WS-1.1 FIRE(2026-06-28 commit `38c514a` merge 之前)采用**双分支 + 双构建矩阵**策略:

- `master` 保留 Boot 3.4.13 / Java 17 / SDR 3.x / Redisson 3.27.0 兼容线(WS-1.2 硬化已合)
- `boot4` 分支独立迁移到 Boot 4.0 / SDR 4.0 / Java 21 / Redisson 3.50.0
- `ci.yml` `compatibility` job 试图用 `versions:set-parent` 切 parent 验证 Boot 3.3/3.5 编译
- `ci-boot4.yml` 单独 workflow 仅 `boot4` 分支触发,跑 `verify -Pboot4`

策略本意(Boot 4 模块化包重定位 + SDR 4 重命名 → 同一 .java 不能 import 双版本 package → 用分支隔离)有合理性,但落地后出现**与现实不符的指代**,继续维护成本 > 收益。

**经代码核验**:

1. **`pom.xml` parent `<version>4.0.0</version>`**(FIRE 期间已升),并非描述中的 "master 保留 Boot 3.4.13" —— 即 parent 已是 Boot 4,只是 properties 默认 + boot4 profile 套层皮维持表面"双形态"。
2. **`pom.xml` 默认 `properties.java.version=17` + `redisson.version=3.27.0` + boot4 profile 覆盖为 21/3.50.0** —— 实质是"用 profile 模拟双构建",但 parent 已是 4.0.0,boot3 默认编译期就会因找不到 Boot 4 重定位的 package 而爆。
3. **`.github/workflows/ci.yml` `compatibility` job 用 `versions:set-parent -DparentVersion=...`** —— 本地 `MojoNotFoundException`(versions-maven-plugin 2.x 的 set-parent goal 未解析),CI 永远标黄/红失败,只发 ::warning:: annotation 当信号,无实际门控价值。
4. **`ci-boot4.yml` 触发分支 `[boot4]`**(2026-06-28 merge `38c514a` 后)—— `boot4` 分支已被 master 包含,继续保留 = 每次 master HEAD push 跑不到 boot4 CI(boot4 branch 永远落后 master),反馈循环断裂。

合并 `38c514a` 把 boot4 全部改动 merge 进 master 后,**双分支策略的隔离前提已不存在**;但 `COMPATIBILITY.md`/`HANDOFF.md`/`MASTER_PLAN.md` 仍描述"双构建 + 双矩阵"。

## Decision

**放弃双分支 + 双构建矩阵,统一为单构建 Boot 4.0 单线**。具体落地(3 commit 链路):

1. **`53f8eb2`(docs)**:`COMPATIBILITY.md` 改单矩阵;`HANDOFF.md` §0 TL;DR 修正 + §12 post-merge addendum supersede §1-§11;`MASTER_PLAN.md` 7 处措辞统一。
2. **`6f00471`(ci)**:删 `ci-boot4.yml`(65 行);`ci.yml` JAVA_VERSION 17→21 + build job 去 matrix + 删 `compatibility` job + `build-package` 加 `-Pboot4`;`pr-checks.yml` 同步。
3. **`9ad22bf`(pom + CI flag 同步)**:`properties.java.version` 17→21 + `redisson.version` 3.27→3.50 + 删 `<profiles>` boot4 块 + 删旧切换机制注释;`ci.yml`/`pr-checks.yml` 同步去掉 `-Pboot4` flag(原 profile 已上移 default)。

**单构建命令(本 ADR 后唯一有效)**:

```bash
./mvnw clean verify -B    # Boot 4.0 + Java 21 + Redisson 3.50.0,672 测试 0 失败
```

## 理由(分歧推荐表 + 现实约束)

1. **Boot 3.4.13 自 2025-12 OSS-EOL**(无安全补丁),继续维护"boot3 兼容线"是负 EV(写新代码、改依赖都要兼顾两套,价值是"允许用户停留 EOL 平台"——而停留 EOL 是新采用者的否决项,见 `MASTER_PLAN.md` §2 WS-1.1 上下文)。
2. **parent 4.0.0 + boot3 properties 是矛盾状态** —— 实际无法真正"用 boot3 配置跑通 master HEAD"(boot3 没有 Boot 4 重定位的 `o.s.b.data.redis.autoconfigure.DataRedisAutoConfiguration` 等类),"双形态"只是 pom 表面。
3. **CI `compatibility` job 无实际门控** —— `versions:set-parent` MojoNotFoundException,只发 ::warning:: 当信号,既不阻塞坏 PR,又消耗 CI 分钟。
4. **master = sole build line 与 ADR-0005 长寿对冲方向一致** —— ADR-0005 已声明不靠双构建对冲 Spring/SDR churn,改由 Path C 降耦合面 + 链级 Micrometer Observation 解锁企业可采购性。
5. **solo 维护成本**:双分支 = 2× 合并冲突 + 2× 验证循环 + 2× 文档。Boot 4.0 适配已进入 master,boot3 适配已无价值,继续维护 boot3 等于把已 merge 的代码再 fork 一份。

## Consequences

### 正面

- **单一真相源**: 1 个 branch、1 个 build 命令、1 个 CI 矩阵。`./mvnw clean verify -B` 在本地/CI 行为一致,行为可预测。
- **CI 简化**: 删 `ci-boot4.yml`(65 行)+ `ci.yml` `compatibility` job(35 行);触发条件从 `branches: [main, master, boot4]` 简化为 `[main, master]`;`pr-checks.yml` 同步简化。
- **构建时间**: 本地 `verify` 38.2s(单 Java 21 节点),与上 tick 38.9s 几乎一致;CI 去掉 1 节点 Java 17 + 1 节点 Boot 3.3/3.5 compatibility 编译 = 节省 ~3-5 分钟/次 push。
- **文档清晰**: `COMPATIBILITY.md` 从 4.4K(双矩阵+切换机制)缩到 4.5K(单矩阵+历史脉络);新人 onboarding 看 1 张表就能选对版本。

### 负面 / 风险

- **Boot 3.x 用户停留 v0.0.x**: 继续上 Boot 3.4.13 的项目需停留在 ResiCache v0.0.x(`5a05d0a` 是最后支持 boot3 的版本)。这是必然代价 —— 平台 EOL 不可逆。
- **新采用者必须 Boot 4.0**: 不再支持 Java 17 编译路径(最小 Java 21)。**这是 Boot 4.0 的客观要求,不是 ResiCache 强加的**;ResiCache 只是 mirror 现实。
- **(可选)git branch 清理**: `boot4` 分支仍存在(历史),可 `git branch -d boot4` 清理(本 ADR 不强制,见 `TASK_BACKLOG.md` §2 #4 "可选"项)。

### 不变

- **依赖矩阵**: Redisson 3.50.0(ADR-0006 信任算术的 Redisson 主版本)、Caffeine 3.1.8、Testcontainers 1.20.4 等不变。
- **API 形态**: `@RedisCacheable`/`@RedisCachePut`/`@RedisCacheEvict`/`@RedisCaching` 注解、链架构(`HandlerOrder` + `@HandlerPriority` + `CacheHandlerChainFactory` 自动发现 6 档)不变。
- **Path C 序列**: WS-1.3 Path C 7 步不动,Step 6 仍负责 `supportsAsyncRetrieve()=false` shim 恢复 true。
- **ADR-0005 长寿对冲**: 不靠双构建对冲 Spring/SDR churn 的判断仍成立,且本 ADR 强化该方向(single build line 减少维护面积)。

### 触发重评估条件

- 若 Spring 社区出现 **"Boot 3.x 长期维护 fork"**(类似 JDK 8→11 过渡期的 Azul Zulu Core 商业支持),且 ResiCache 用户群明确诉求 boot3 兼容线 → 重新评估。
- 若 Boot 5.0 出现类似 4.0 的 breaking 规模,需再次 FIRE → 重新启用双分支策略(但应先评估 Wedge 1: "重新引导用户迁移 vs 兼容老版本")。

## 取代关系

本 ADR 取代 `MASTER_PLAN.md` §2 WS-1.1(原"建 boot4 分支/Profile + 双构建矩阵")、§3 R1 缓解 + R6 触发条件、§5 v0.1.0 门禁、§7 Week 1 路线图等位置中"双构建/双分支/双矩阵/boot3 兼容线"措辞(已由 `53f8eb2` 统一改写);`HANDOFF.md` §1-§11 历史叙事由 §12 post-merge addendum supersede;`COMPATIBILITY.md` 双矩阵表已替换为单矩阵。

完整 WS-1.1 FIRE 技术史:`CHANGELOG.md` WS-1.1 FIRE 条目。`TASK_BACKLOG.md` §2 #4 在三个 commit(`53f8eb2`/`6f00471`/`9ad22bf`)落地后视为 closed(父项仍开,因 §2 #4 还有"可选" git branch 清理子项)。
