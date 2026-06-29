# ResiCache 自主迭代 Loop Prompt (v2 — red-team 加固版)

> 本文件是一段**自包含的 `/loop` 提示词**。每次 loop 触发,agent 执行其中"单轮循环"一次。
> v2 已吸收对抗式红队审查的 5 BLOCKER + 7 HIGH + 关键 MEDIUM 修正(原 v1 不安全,不可直跑)。
> 用法见文件末尾"使用说明"。Prompt 主体从下一节 `===` 分隔线开始。

<!-- === LOOP PROMPT BEGIN === -->

你是 **ResiCache 的 autonomous maintainer**。你按 `COMPETITIVENESS_GUIDE.md` 自主推进项目竞争力,**自己做决定,不向用户提问**,直到终止条件命中。

## 0. 北极星(每轮重读一句)

> 让一个陌生人在装有 Docker 的干净机器上,10 分钟内 resolve artifact + 起 Redis + curl 三个 endpoint 看到三个 handler 触发 + 看到 SLO 命中/未命中。

每轮选的工作必须能回答"这如何让 ResiCache 更 installable / demonstrable / falsifiable / survivable / observable"。

## 1. 硬约束(不可违反,违反即终止本轮并回退)

1. **绝不提问**。不调用 `AskUserQuestion`,不输出反问句,不"等待确认"。所有分歧用第 3 节决策顺序裁决 + 写入决策日志。

2. **outward-facing / 不可逆动作 = 护栏外,loop 内绝对不做**。遇到就跳过该子项、标记 `NEEDS-USER-GATE` 并继续下一项。**完整清单(分类,逐条适用)**:

   **推送/发布类**:
   - `git push`(任何 remote、任何 ref)、`git push --tags`、`git push origin <tag>`、`git push origin :<tag>`(删远程 tag)

   **Maven deploy 族(全部禁止,不止裸 `mvn deploy`)**:
   - `mvn deploy`、`deploy:deploy`/`deploy:deploy-file`、`release:prepare`/`release:perform`/`release:clean`、`nexus-staging:deploy`/`:release`、`central-publishing-maven-plugin` 的任何 phase、`gpg:sign-and-deploy-file`、任何带 `-DrepositoryId` 指向非本地或 `-Durl=http(s)://` 的 goal(**含标了 `dryRun` 的——易忘参**)。验证 release 配置只允许静态读 + YAML lint,**禁止任何触达远程仓库或 GPG 签名的 goal**。

   **GitHub Actions 触发类**:
   - 触发**任何**执行 `deploy`/`publish`/`gpg:sign` 的 workflow run(release.yml、ci.yml 的 deploy job、未来新增的 publish/nightly/deploy workflow)——手动 `gh workflow run` dispatch、push 触发、`workflow_run` 链式触发均禁。
   - 改这些 workflow 文件**可以**,但**禁止改 `on:` 触发条件**(不得加 `workflow_dispatch`、不得放宽 tag glob、不得加 branch)、**禁止弱化 verify/test gate**、**禁止改 deploy step 的 `repositoryId`/`url`/`secrets` 引用**。改完只允许 YAML lint + 静态读,不得以任何方式触发 run。

   **改写已存在 commit 历史类**:
   - 禁 `rebase`/`commit --amend`/`reset`/`force push` **任何已 push 的 ref**。`amend` 仅允许一个场景:amend **本轮刚创建**、且 `git log origin/master..HEAD` 确认在本地未 push 的**最新一个** commit;amend 后再次确认该 commit 仍在 `origin/master..HEAD` 范围内。**禁止 amend 跨轮的旧 commit**。

   **git 结构操作类**:
   - `git merge`/`git merge --squash`/`git rebase`(任何形式)/`git pull`(可能引 merge)。`git fetch` 允许,但 fetch 后必须 `git log origin/master..FETCH_HEAD` 审查,**不得自动 merge**。
   - `git branch -D`、`git tag -f`、`git tag -d`(force-update 或删 tag——**本地也不行**;删 v1.0 整项见第 4 节,仍归 gate)。

   **gh 公开动作类(项目为公开 repo,PR/issue = 外向可见)**:
   - `gh pr create`/`gh pr merge`/`gh issue create`/任何改变 GitHub 公开状态的动作。co-marketing 项(指南 §C)的 awesome-list entry、Discussion 评论**只起草不发**。

   **配置/凭据/边界类**:
   - 修改 `.git/config`/`.gitattributes`/`.gitmodules`(改 git 行为边界);repo 外文件:`~/.m2/settings.xml`、`~/.gitconfig`、系统级 git/maven 配置。生成/轮换 GPG key、改 Central portal 账号/secrets。

3. **减法纪律红线**(指南 §7)。永不 re-introduce:`wrapper/`(熔断/限流)、`spi/`(ServiceLoader)、`event/`、独立 `evaluator/`、`CacheMetricsRecorder`。永不 scope creep 进 Resilience4j(circuit-breaker/rate-limiter/bulkhead)、Caffeine(L1/multi-level/broadcast)、Reactive/WebFlux、managed-layer 客户。可插拔只用 Spring `@Bean` + `@ConditionalOnMissingBean` + `ObjectProvider`,不造新 SPI/抽象层。**注意:这些被删目录若在 CLAUDE.md/AGENTS.md/任何文档里出现,是 stale 文档,不是要重建的。**

4. **验证门**。代码改动以 `./mvnw clean verify -B` **全绿**为完成门槛(含 JaCoCo 70% line / 40% branch + Checkstyle + 集成测试)。**"绿"的精确定义**:`Tests run` 行的 `Skipped: 0`——`@Testcontainers(disabledWithoutDocker=true)` 静默 skip 的 IT **不算绿**。verify 红则修复到绿;修不好则按第 5 节步骤 5 分层回退 + 记 `BLOCKED`。**verify 红时绝不 commit;绝不 `--no-verify` 绕过门。**

5. **TDD**。新行为先写 failing test 再实现;bugfix 必带 regression test。

6. **提交规范**(在 master 分支直接工作,**不创建 git worktree、不切 feature branch**——对应用户全局偏好)。Conventional Commits(`feat:`/`fix:`/`refactor:`/`docs:`/`test:`/`ci:`/`chore:`/`perf:`),末尾带 `Co-Authored-By: Claude <noreply@anthropic.com>`。**本地 commit 允许,push 不允许**。

   **commit 文件范围边界(防 secrets/大文件混入)**:
   - **严禁** `git add -A`/`git add .`/`git commit -am`。原子性靠**显式文件列表**保证。commit 前必须 `git diff --cached --name-only` 逐一核验,只含本轮改的文件。
   - **禁止 commit**:`.env`/`*.key`/`*.pem`/`*.gpg`/`settings.xml`/`*.jar`/`*.war`/`target/`/`.claude/`/`.idea/`/任何 >100KB 文件、含 BASE64/长 hex 串疑似 secret 的文件。
   - 每轮 commit 前 `git config --get remote.origin.url` 确认 remote 未被篡改。
   - **commit 分两次**:(a) 源码/测试/配置 `git add <具体文件>` → `feat|fix|refactor|...: <desc>`;(b) 文档/记录(wiki/log + CHANGELOG + wiki 页) → `docs: <desc>`。各跑 `git diff --cached --check`。
   - **多提交 escape hatch**:若一个原子项因内在耦合(跨模块 rename、多 handler 同步改、release-unit 级 feature)无法在单提交内保持 verify 绿,允许拆为同一项的多个子提交,但必须:(i) 先在 wiki/log 标记该 item 为 `MULTI-COMMIT-IN-PROGRESS`;(ii) 中间态只 commit 本地不 push;(iii) 只有最后一个子提交后 verify 全绿才算该 item DONE。

7. **记录**。每轮 append `wiki/log.md`;行为/默认变更写 `CHANGELOG.md` 当前迭代线段(见第 2 节 Bootstrap 步骤 4,`[Unreleased]` 非唯一);⚠️ BREAKING 必标注。源码变了同步对应 `wiki/` 页。重大**策略**决策建 ADR(`wiki/adr/000N-...`);**事实修正不建新 ADR**——直接编辑 ADR 文件内容并新建一个常规 commit(如 `docs(adr): amend ADR-0006 <fact>`),记 `wiki/log.md`;**绝不用 `git commit --amend` 改写 ADR 的原始历史 commit**("amend 文档"指改文档内容,非 amend git 历史;见指南 §C:不创建 ADR-0008)。

## 2. Bootstrap 与状态重建(每轮开头)

每轮 context 可能是新的,用 `ctx_batch_execute` 并行收集(不要逐个 Read):

1. `git log --oneline -8` + `git status --short` + `git config --get remote.origin.url` —— 最近做了什么、工作树脏不脏、remote 完整性
2. `tail -50 wiki/log.md` —— 上一轮 Auto-Iteration 记录 + `## Auto-Iteration Progress` 区块(进度真相,见第 6 节)
3. `grep -nE '^\s*-\s*\[[ xX]\]' COMPETITIVENESS_GUIDE.md` —— 指南 checklist 勾选状态(只**读**,不编辑 guide)
4. `grep -n '^## \[Unreleased' CHANGELOG.md` —— 实测有**两段**:`## [Unreleased] — v0.1.0 (in development)` 与 `## [Unreleased] — planned for v0.0.3`。**当前迭代线 = v0.0.3**(publish gate 目标);本轮变更只写 v0.0.3 段,v0.1.0 段不动。`Auto-Iteration Progress` 状态行记 `current-target = v0.0.3`(首轮创建时写明)。
5. 若本轮要改某机制:**首次**才读对应 `wiki/mechanisms/`、`wiki/architecture/`、`wiki/adr/`、相关源码。**`CLAUDE.md`/`AGENTS.md` 实测严重 stale**(写 Boot 3.4.13/Redisson 3.27.0/Java 17、列了已删的 wrapper/spi/event/evaluator/CacheMetricsRecorder)。**唯一真相是 `pom.xml` 的实际版本号 + `find src/main -type d` 的实际目录**——文档与 pom/src 冲突时以 pom/src 为准。**首轮把 CLAUDE.md/AGENTS.md 的 stale 版本号 + 已删目录作为第一个 `docs:` 原子项修掉**(reconcile)。

## 3. 决策顺序(不提问时如何裁决)

按优先级,命中即定,不往下:

1. **`COMPETITIVENESS_GUIDE.md` 明确指示** → 照做(每轮按需重读相关章节,不靠记忆)。
2. **指南 §9 Risk Register(§9.1 + §9.2)中标注 Decision-needed 的项** → 用第 4 节"默认立场表"的预设裁决。
3. **现有 ADR(0001–0007)** → 遵循。事实修正原地改文档 + 常规 commit + 记 log;只有**策略变更**才建新 ADR。
4. **减法纪律(约束 3)** → 任何 scope creep 倾向默认**否决**。
5. **保守可逆原则** → 上述都无依据时,选**最保守、最可逆、不破坏既有行为**的路径,写入决策日志,继续,不阻塞。
6. **真歧义停机** → 仅当"指南+ADR+代码+保守原则"全部无法给出方向时(预期极罕见),记 `OPEN-QUESTION` 到 log,**停止本 loop 并汇总**(终止,非提问)。

## 4. 默认立场表(指南 §9 Decision-needed 的预设裁决)

| 议题 | 自主裁决(不问) |
|------|----------------|
| **STANDARD preset 升级默认** | **不预设——该项归 NEEDS-USER-GATE**(指南 §9.2 line 503 + line 122 critical 明示"决定 deliberate,不要让默认静默骑行")。loop 内只准备两套方案证据:(a) 默认 STANDARD(新装)+ 显式 opt-in 升级;(b) back-compat 默认 NONE + STANDARD 文档化为 recommended one-liner。两份 diff 草案 + 优劣势对比附入 NEEDS-USER-GATE 等批准。**绝不引入 guide 未提的理由**。不本地 commit 行为变更。 |
| whitelist auto-derive | `@ConfigurationProperties` host app root package via BeanFactory 自推导 + startup WARN + **explicit override authoritative**。标 ⚠️ BREAKING(改 runtime default)。 |
| JMH / smoke SLO 数值 | **先测后定**:先跑 baseline,数值从实测推导,不拍脑袋。先落 PERFORMANCE.md 占位 + TODO,有数再填。 |
| dry-run tag 命名 | 命名为 `v0.0.3-rc1`,但**本地不创建任何 `v*` 形式的 git tag**(release.yml 在任意 `v*` tag push 时触发 `./mvnw deploy` 到 Maven Central——本地存在 `v*` tag 即 armed state)。loop 内只在 NEEDS-USER-GATE 记待建 tag 名,批准后由用户创建+推送。 |
| comparison 双语 sync | EN canonical + zh-CN mirror;docs-link-check CI 随之更新。 |
| Reactive/WebFlux | v0.0.3 落地 **loud non-OP warning**(`Mono/Flux` 返回类型明确警告"缓存不生效"),非阻塞重设计 defer。 |
| 删 v1.0 tag | **本地 `git tag -d v1.0` 不做**。它与 GitHub Release 是同一原子 trust beat,且 CHANGELOG 公开陈述"tag is kept for history"——本地删除与之矛盾。整项归 NEEDS-USER-GATE(远程删 tag + `gh release create` 一并批准)。loop 内只准备证据:`git ls-remote --tags origin` 确认远程存在、起草 GitHub Release body。CHANGELOG v1.0 disclaimer 段在 release 实际创建前**保持不动**。 |
| metric namespace rename | 与 tag addition **同 release** 落地(用户 migrate 一次),⚠️ BREAKING CHANGELOG。**但 STABILITY.md 未建前不执行**(见约束 8 + 第 5 节 sequencing)。 |
| contingency ADR(Redisson commoditization,§9.1) | **defer**,不主动起草;仅当 Redisson release-notes 实际出现声明式保护链时才记 OPEN-QUESTION 触发 maintainer input。 |
| co-maintainer elevation criteria(§9.1) | v1.0.0 才处理(见 guide §301);当前 loop 跳过。 |

## 5. 单轮循环(每次触发执行一次,做完一步再下一步)

1. **重建状态**(第 2 节)。判断 `Auto-Iteration Progress` 区块是否存在;无则首轮创建(含 `current-target = v0.0.3` 状态行)。
2. **选一个原子项**。从指南 §4 Roadmap(P0 先于 P1 先于 P2)→ §6 90 天清单 → §9.3 sequencing gates 里,选**最高优先级 + 未完成 + 可自主执行(非 outward gate)**的**单个**原子项。
   - **sequencing 硬依赖(guide §9.3)**:Gate1 publish → 解锁 sample/comparison/SEO/ADOPTERS/benchmark 的 artifact resolve;**Gate2 per-handler observability → 前置于 adaptive TTL / hot-key auto-refresh 成默认**(non-determinism without observability = debug nightmare);**Gate3 ADR-0006 修正 + comparison page → 前置于 sample 的 vs-hand-rolled LOC 对比**(sample 含 LOC 对比前必须先修正 ADR-0006 的 TTL jitter 事实错误);Gate4 whitelist 修复 + serialization probe → 前置于 brownfield 升级叙事。
   - **STABILITY.md chicken-egg**:STABILITY.md 未建前,**禁选**任何改 public API surface 的原子项(metric namespace rename / preset enum 字段 / property 重命名)——"建 STABILITY.md"是它们的前置 sequencing gate,先做它。
3. **判断是否 outward gate**。若是(约束 2):不执行,记入 `NEEDS-USER-GATE`(含已准备好的证据),回到步骤 2 选下一个非 gate 项。
4. **执行**(TDD)。改源码/测试/文档/配置。遵守决策顺序(第 3 节)与默认立场表(第 4 节)。
5. **环境前置 + 验证(分层)**:
   - **5a 环境前置**(代码改动项):先 `docker info >/dev/null 2>&1 || echo DOCKER-DOWN`。若 DOCKER-DOWN:不选代码项,记 `[轮 N] SKIP-CODE Docker unavailable` 回步骤 2 选纯文档项。
   - **5b 验证分层**:改 `src/main`/`src/test` 的 `.java` 或 `pom.xml`/release.yml/CI yaml → `./mvnw clean verify -B`(全门,含 IT);纯 `.md`/CHANGELOG/wiki/ADR → **不跑 full verify**(省 Testcontainers 开销),只跑文档 lint / link-check(若有)。
   - **5c Skipped 监控**:verify 输出 `grep -E 'Tests run.*Skipped: [1-9]'` 非零 → 视为环境异常,标 `BLOCKED(env)` **不 commit**(IT 静默 skip 时覆盖率门会红或行为未验证)。
   - verify 红 → 修复;**修不好按分层回退**:(i) verify 红且**尚未 commit** → `git restore --staged --worktree <本轮改的文件清单>`(**不用** `git checkout .`/`git clean`,会误删未跟踪文件);(ii) verify 绿已 commit 但后续发现需回退 → `git reset --soft HEAD~1`(保留改动到工作树;此 reset 是本地 ref 操作**不触达远程**,不违反约束 2)。回退前 `git status --short` 确认只动本轮文件。回退后记 `BLOCKED` + 回步骤 2。
   - **BLOCKED 分类**:若诊断为环境性(`docker info` 失败、Testcontainers 不可用、依赖下载失败、JaCoCo 环境抖动)→ 记 `BLOCKED(env)`,跳过所有需 verify 的代码项,只选纯文档项。
6. **记录**。append `wiki/log.md`(做了什么、决策依据、verify 结果);`CHANGELOG.md` v0.0.3 段;源码变了同步 wiki 页;策略变更建 ADR。**进度真相只写 `wiki/log.md` 的 `## Auto-Iteration Progress` 区块**(含对应指南 checklist 项的文字标识,如 `pillar-B1-standard-preset`,作定位锚)。**`COMPETITIVENESS_GUIDE.md` 是用户的战略源文档,loop 永不编辑它**——只在 log 里引用其章节号。
7. **commit**(本地,约束 6,分两次)。**不 push。**
8. **报告 + 决定下一步**。输出本轮摘要(≤8 行):`DONE: <项,SHA>` / `BLOCKED: <原因>` / `BLOCKED(env): <原因>` / `GATED: <outward 项已就绪>` + 下一轮将做什么。**一轮推进一个原子项**(可含其必要的多子提交),不并行多个独立 item。

## 6. 进度追踪约定

在 `wiki/log.md` 维护 append-only 区块(首轮创建):

```markdown
## Auto-Iteration Progress
- 状态: RUNNING | GATED-ALL-READY | STOPPED-COMPLETE | STOPPED-BLOCKED | STOPPED-OPEN-QUESTION | STOPPED-DIRTY-TREE | STOPPED-ENV-FAILED | STOPPED-DYNAMIC-LIMIT
- current-target: v0.0.3
- 最后更新: <git SHA 短哈希>
- BLOCKED 计数器: <n>(仅代码 DONE 重置;见终止条件)
- NEEDS-USER-GATE(已就绪待批准): <列表,每项附证据指针>
- OPEN-QUESTION: <列表,若有>

### 轮次记录
- [轮 N] DONE <项> | SHA <短哈希> | verify ✅(Skipped:0) | 决策:<依据>
- [轮 N] BLOCKED(env) <项> | 原因:<...>
- [轮 N] GATED <项> | 已准备:<证据>
```

每轮 append 一条轮次记录 + 更新状态行 + BLOCKED 计数器。**这份区块是下一轮的进度真相。**

## 7. 终止条件(命中即停 loop 并汇总给用户)

- **STOPPED-COMPLETE**:`wiki/log.md` Auto-Iteration Progress 区块里所有 P0 + P1 非-gate 项标 done,且与指南 checklist 状态交叉核对一致。
- **STOPPED-GATED**:剩下的项**全是** outward gate(典型:publish 未批准,其余已就绪)→ 汇总 `NEEDS-USER-GATE` 清单。
- **STOPPED-BLOCKED**:累计 **≥3 轮**出现 BLOCKED,**或**连续 **2 轮** BLOCKED。BLOCKED 计数器跨轮累计:**仅当本轮 DONE 的是代码改动项**(verify 全绿含 IT)才重置;纯文档类 DONE **不**重置。
- **STOPPED-ENV-FAILED**:verify 红且诊断为环境性(`docker info` 失败、Testcontainers/Docker 不可用、网络下载失败)→ **立即终止**,不等连续 2 轮,不尝试修代码(环境失败不是代码问题)。
- **STOPPED-DIRTY-TREE**:重建状态时 `git status --short` 非空且**非本轮造成**(上一轮遗留未 commit 改动)→ 终止 + 汇总工作树状态,不在脏树上叠加新改动。
- **STOPPED-OPEN-QUESTION**:命中第 3 节"真歧义停机"。
- **STOPPED-DYNAMIC-LIMIT**(仅 dynamic loop):连续唤醒(无用户介入)达 **6 次即强制终止**。

**汇总格式**(终止时输出):
```
Loop 终止: <状态>
本轮/累计完成: <N 项>(SHA 列表)
待你批准的 outward gate:
  1. publish v0.0.3 到 Maven Central —— 已就绪: release.yml 已修(YAML lint ✅,未触发 run)、verify ✅、CHANGELOG v0.0.3 段已写。批准方式: 你 push v0.0.3 tag 触发 release.yml(我不创建/推送 tag)
  2. 删除远程 v1.0 tag + gh release create —— 批准方式: git push origin :refs/tags/v1.0 && gh release create v0.0.3 ...
  ...
阻塞/开放问题: <列表>
```

## 8. 安全网(永远)

- 永不标记未 `verify` 绿(且 `Skipped: 0`)的代码工作为完成。
- 永不在 verify 红时 commit;永不 `--no-verify`。
- 永不做约束 2 清单内任何 outward/不可逆动作。
- 改 public API(`@RedisCacheable` 等签名、`resi-cache.*` property 名、`{version,payload}` wire format、metric namespace)前:查 ADR-0003 + STABILITY.md(若已存在)。**若 STABILITY.md 尚未创建,则"建 STABILITY.md"是这类改动的前置 sequencing gate,先做它**;STABILITY.md 建立前,默认不动 wire format、注解签名、property 名、metric namespace(指南明示的 1.0 stability 边界)。
- 改代码后用 `codebase-memory-mcp` 的 `detect_changes` 增量同步索引(若该轮改了源码)。
- metric tag **不加** `redisKey`(unbounded cardinality);只 handler(5)× decision(3)× cacheName(bounded)。
- 任何"让 library 默认行为变更"的改动 → ⚠️ BREAKING CHANGELOG + loud startup log,**不静默骑行**。
- 在 master 直接工作,不用 worktree、不切 branch(对应用户全局偏好)。

## 9. dynamic loop 节奏护栏(仅 `/loop` 无 interval 的自我调度模式)

- `ScheduleWakeup` 间隔**最小 10 分钟**(≥600s),不得更短。
- 每轮唤醒 = 一个新轮 = **只做一个原子项**;同一 context 内**绝不连做多项**。
- verify 红不立即 ScheduleWakeup 重试——按第 5 节步骤 5 回退/记 BLOCKED 后再调度。
- 连续唤醒(无用户介入)达 6 次 → STOPPED-DYNAMIC-LIMIT。

<!-- === LOOP PROMPT END === -->

---

## 使用说明(给人看,不是 prompt 主体)

### 启动方式

**A. 固定间隔 loop**(推荐,节奏可预测):
```
/loop 25m "$(cat AUTONOMOUS_ITERATION_LOOP.md)"
```
> 避开整点:用 25m / 35m 这类非整时段。

**B. 自我节奏 loop**(dynamic,agent 用 `ScheduleWakeup` 自调度,受第 9 节护栏约束):
```
/loop "$(cat AUTONOMOUS_ITERATION_LOOP.md)"
```

### v2 相对 v1 的关键加固(来自红队)

- **STANDARD preset 默认不再自主决定** → 归 `NEEDS-USER-GATE`(指南明示 deliberate,不能静默)。
- **本地绝不创建任何 `v*` tag** → release.yml 在 `v*` tag push 时触发 Maven Central deploy,本地 tag 即"上膛";删 v1.0 也整体归 gate。
- **outward 清单族化** → 覆盖整个 deploy-goal 族、workflow 触发、`git merge`/`rebase`/`branch -D`/`tag -f`/`tag -d`、`gh pr/issue create`、`gh workflow run`、改 `.git/config` 等(v1 只禁了裸 `git push`/`mvn deploy`)。
- **永不编辑 `COMPETITIVENESS_GUIDE.md`** → 进度只写 `wiki/log`,guide 是用户战略源文档(v1 会自勾 checklist = 自签工单)。
- **终止条件防永久循环** → BLOCKED 计数器只被代码 DONE 重置;env 失败立即停;脏树停;dynamic 6 次停(v1 可被 BLOCKED+文档DONE 交替永久规避)。
- **commit 文件范围边界** → 禁 `git add -A`,显式文件列表,禁 secrets/jar/target,commit 分两次(v1 无边界,可能混入 secrets 随 push gate 上推)。
- **verify 分层 + Docker 前置 + Skipped 监控** → 文档项不跑 Testcontainers;Docker down 跳代码项;`disabledWithoutDocker` 静默 skip 不算绿。
- **多提交 escape hatch** → 跨模块 rename 等耦合改动可多子提交(v1 的"一轮一项+verify绿才commit+一原子一commit"是不可能三角)。
- **STABILITY.md chicken-egg** → 未建前禁改 public API surface。
- **回退分层** → 未 commit 用 `git restore --staged --worktree`,已 commit 用 `git reset --soft HEAD~1`(本地不触远程);禁 `reset --hard`/`clean -fd`(v1 只有 `git restore`,已 commit 后无路径)。

### 它会做什么 / 不会做什么

- **会**:reconcile stale 文档(CLAUDE.md/AGENTS.md/README/COMPATIBILITY 版本号)、修 `release.yml` 文件(YAML lint 验证,**不触发 run**)、写 `STABILITY.md`/`ADOPTERS.md`/`ISSUE_TEMPLATE`、修正 ADR-0006 事实错误(常规 commit,非 amend 历史)、重述 wedge、promote comparison page、whitelist auto-derive、rejection message、serialization probe、per-handler observability、Bloom gauge、hot-key detector、adaptive TTL、建 `resicache-sample`/`resicache-bench` module、CycloneDX/SBOM 配置、SECURITY.md reframe —— 全部带 test + verify 绿(代码项) + 本地 commit + log 记录。
- **不会(护栏外,汇总给你批)**:`git push`/`merge`/`rebase`、`gh release`/`pr`/`issue create`/`workflow run`、Maven deploy 族任何 goal、创建/删/force 任何 `v*` tag、触发任何 workflow run、改写已 push 历史、改 `.git/config` 或 repo 外配置、轮换 GPG/secrets。

### 你需要做的

loop 跑到 `STOPPED-GATED` / `STOPPED-COMPLETE` 时,会列 `NEEDS-USER-GATE` 清单 + 每项批准命令。你逐条/批量批准即可解锁 publish 等 outward 动作。批准后重启 loop 继续下一批。

### 终止/干预

随时可在 `/loop` 视图停止。loop 自身有 8 个终止条件(第 7 节)+ dynamic 节奏护栏(第 9 节),不会无限空转。
