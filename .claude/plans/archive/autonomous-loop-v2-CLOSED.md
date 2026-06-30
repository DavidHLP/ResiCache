# Autonomous Loop v2 — Runbook **[CLOSED 2026-06-30]**

> **Status: CLOSED** — 2026-06-30 由项目优化新里程碑接管,见 `.claude/plans/2026-q3-optimization.md`
>
> **Closure reason**: v2 循环在 round 38–42 完成(v2 design 入库 + SyncSupport 边界测试 + ADR-0008 + wait-state cycle)。round 42 wait-state 因 ADR-0008 仍 Proposed,且 mini-candidate 反向勘察无真实工程价值项可推进,触发「重新评估定位」信号。按计划 §6 stop-condition 重启原则,转入新一轮里程碑。本文件归档保存,仅作历史追溯。

> **Loop 模式**: `sequential`(plan → working → code review → 更新文档,顺序不可换)
> **Mode**: `safe`(strict quality gates — verify 必跑、显式文件列表、armend 禁、不 push、不触 GPG)
> **Pattern 来源**: `AUTONOMOUS_ITERATION_LOOP.md`(项目内,根目录,**已删除**)
> **历史 loop 状态**: `STOPPED-PUBLISH-BLOCKED`(round 37),v2 重启就绪
> **创建时间**: 2026-06-30
> **首次运行**: 待用户触发

---

## 1. Repository State Snapshot(启动前必确认)

| 项 | 状态 | 备注 |
|---|---|---|
| Branch | `master` | 用户偏好:直接在 main 上做,无 worktree |
| HEAD | `0269dfc` | `docs(wiki): log Policy-seam promotion + review-candidate verification` |
| Working tree | `M AUTONOMOUS_ITERATION_LOOP.md`、`M COMPETITIVENESS_GUIDE.md` | 2 modified 未 commit(round 6 同模式) |
| Working tree other | clean | 无其他变更 |
| Verify baseline | N/A(无代码改动) | round 6 历史:`./mvnw clean verify -B` 38s / 694 tests / JaCoCo 70%/40% / checkstyle 0 |
| Local tags | `v0.0.3`、`v1.0` 残留 | **§4 armed-state 不可动**(等用户) |
| Remote tags | `v0.0.3`(待删) | 同上,等用户执行 release 命令链 |
| ECC_HOOK_PROFILE | unset(默认 = 全开) | GateGuard 已启用 |

## 2. Pattern & Mode 选择理由

| 维度 | 选择 | 理由 |
|---|---|---|
| Pattern | `sequential` | 用户明示「plan → working → code review → 更新文档」线性流 |
| Mode | `safe` | strict gates 必须:verify 绿 / 显式文件列表 / 不 amend / 不 push / 不触 GPG / 不动 tag |
| Model tier | opus 主调 + sonnet 兜底 | opus 写代码,sonnet 跑 ctx_search / 简单 grep |
| N commits/round | 默认 3(`AUTONOMOUS_ITERATION_LOOP.md` §1.2) | 可基于 token 余量自调 [1, 5] |
| Worktree | **不用** | 用户偏好:直接在 main 上做(CLAUDE.md workflow preferences) |

## 3. Hard Constraints(loop 启动时打印一次 + 每轮遵守)

**全部从既有 round 1-37 历史继承,新增约束置 §10.4**:

| ID | 约束 | 来源 |
|---|---|---|
| §1 | 不 push / 不 amend / 不打 v* tag / `git add` 显式文件列表禁 `-A`/`.` | round 8 / round 6 |
| §2 | 不触 GPG 签名相关 goal(`gpg:sign` 等)+ 不改 release.yml deploy 步骤 + 不动 secret env | round 37 |
| §4 | armed-state:本地残留 `v0.0.3` / `v1.0` tag **不可删不可建**,等用户执行 release 命令链 | round 36-37 |
| §5 step 5b | 纯 docs 改动 → verify N/A(跳过 `./mvnw clean verify -B`) | 多轮 |
| §5 step 6 | `git add` 必显式文件列表,**禁止** `-A` / `.` | round 6 |
| §6 | 首次创建 "Auto-Iteration Progress" 区块(已在 wiki/log.md 顶部) | round 1 |

**v2 独有约束**(本 runbook):
- §v2-1:不动 pom.xml `<release>` profile 内 sign-artifacts phase(归 §2);
- §v2-2:不动 CHANGELOG.md 版本号绑定段(用户已锁 v0.0.6);
- §v2-3:不动 v0.1.0 roadmap 段(round 37 已声明);
- §v2-4:不动 `wiki/adr/0001`–`0007`(沿用既有,除非 v2 派生新 ADR);
- §v2-5:不动 round 1-37 wiki/log 记录(append-only);
- §v2-6:Round 1 仅 commit 当前 2 modified 文档(§10.6),不做其他工作。

## 4. Iteration Plan:Round 1(立即执行)

| # | Commit | 文件 | 约束 | 预期 verify |
|---|---|---|---|---|
| 1 | `docs(loop): v2 design — bridge 37-round history + STOPPED-PUBLISH-BLOCKED` | `AUTONOMOUS_ITERATION_LOOP.md`、`COMPETITIVENESS_GUIDE.md` | §1/§5 step 5b/§5 step 6/§v2-1/§v2-6 | N/A(纯 docs) |

**Round 1 完成后**,loop 转标准 sequential 循环,开始 Round 2。

## 5. Iteration Plan:Round 2+ 候选(按优先级)

> 来源:`AUTONOMOUS_ITERATION_LOOP.md` §10.5。Round 2 起每轮 N=3 个 commit,可基于 token 余量调 [1, 5]。

| 优先级 | 候选 | 外向依赖 | 文档同步 |
|---|---|---|---|
| P0 | (e) Observation spans 校准(guide 矛盾 + ADR-0005 归因修正) | 无 | ADR-0005 修订草案 + wiki/log 条目 |
| P1 | `docs/serialization` 反序列化测试(未白名单类 → 拒绝 显式测试) | 无 | 新增 integration test + wiki/log |
| P1 | `SyncSupport` 锁超时 / leaseTime 边界测试(0/超大/负) | 无 | 新增 unit test + wiki/log |
| P1 | `BloomSupport` rebuilding window 集成测试(default vs disabled 差异) | 无 | 扩展 integration test + wiki/log |
| P1 | chain-level `resicache.cache.operation` Observation 升级(WS-1.4 续) | 无 | `AbstractCacheHandler` + 5 handler + wiki/observability |
| P1 | Micrometer tag 白名单枚举护栏(MeterFilter 自动 reject 未知 tag) | 无 | `MeterFilter` config + unit test + wiki/log |
| P2 | JMH 框架骨架(`benchmarks/jmh/` 模块) | 无 | 新模块 + pom + wiki/benchmarks |
| P2 | `BUS_FACTOR.md`(对抗 solo 风险) | 无 | 新文档 + wiki/log |
| P2 | `MIGRATION.md`(Spring Cache → ResiCache) | 无 | 新文档 + wiki/log |
| P3 | Maven Central 上架筹备 | **GATE**(v0.0.6 release 未跑) | 冻结,等用户 |

## 6. Stop Conditions

满足任一即收尾本轮:

| 触发 | 行动 |
|---|---|
| 本轮 N commit 完成 + verify 绿(若适用)+ log 更新 + retro 写完 | 写 retro → 下一轮(回到 §1.5 resume 仪式) |
| 任何"重新评估定位"信号(见 `COMPETITIVENESS_GUIDE.md` §13.2) | **暂停**,写 ADR 草案;不继续推进不相关 commit |
| token 余量 < 30%(自估) | 提前收尾,把"未完成候选"写进 retro,下轮接着推 |
| 自检 ≥ 3 轮无新产出 | 暂停,写"卡点分析"到 retro |
| verify 连续 2 次失败 | 暂停,回滚最近 commit,写"环境/契约问题"到 retro |
| 用户中断 | 立刻收尾,写 retro |

## 7. Hook Profile & GateGuard

| 项 | 状态 | 备注 |
|---|---|---|
| `ECC_HOOK_PROFILE` | unset(默认 = 全开) | 安全 |
| `ECC_GATEGUARD` | enabled | 每次 Write / Bash 前 fact-forcing;loop 设计已通过(本 runbook) |
| `pre:edit-write:gateguard-fact-force` | 未禁用 | 后续 Write / Edit 仍触发,正常回应即可 |

**新增 hook 配置**(可选,提升 loop 安全性):

```yaml
# .claude/settings.local.json (本地,不入仓)
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Bash",
        "hooks": [
          {
            "type": "command",
            "command": "echo '[LOOP-RUNBOOK] safe-mode active; verify will refuse: git push, git reset --hard, mvn deploy, gpg:sign, tag creation' >&2"
          }
        ]
      }
    ]
  }
}
```

## 8. 启动命令(给用户复制)

### 8.1 一次性:确认 Round 1 commit 内容

```bash
cd /home/davidhlp/project/ResiCache
git diff --stat AUTONOMOUS_ITERATION_LOOP.md COMPETITIVENESS_GUIDE.md
# 应显示 ~2 file changed, ~14KB+ insertions (新写 + v2 bridge §10)
```

### 8.2 Round 1:执行 commit

```bash
cd /home/davidhlp/project/ResiCache
git add AUTONOMOUS_ITERATION_LOOP.md COMPETITIVENESS_GUIDE.md
git commit -m "docs(loop): v2 design — bridge 37-round history + STOPPED-PUBLISH-BLOCKED"
```

(commit body 见 `AUTONOMOUS_ITERATION_LOOP.md` §10.6)

### 8.3 启动 loop 本身(后续 Round 用)

**方式 A:`/loop` 动态模式**(每轮手动重启,推荐):

```
在 Claude Code 输入:
> /loop 1h 按 /home/davidhlp/project/ResiCache/AUTONOMOUS_ITERATION_LOOP.md §1-§4 循环
```

**方式 B:`ScheduleWakeup` 长间隔**(无人值守,低频):

> 不推荐 — 现有 `/loop` 已能 track harness,无需 ScheduleWakeup 轮询。

### 8.4 Round 1 后,继续推进 Round 2 的入口

```
在 Claude Code 输入:
> 按 /home/davidhlp/project/ResiCache/.claude/plans/autonomous-loop-v2.md §5 Round 2+ 候选,挑 P0/P1 候选执行 1 轮(3 commit)
```

## 9. 监控命令(每轮收尾后)

### 9.1 本轮 commit 是否合规

```bash
cd /home/davidhlp/project/ResiCache
git log -1 --format="%H%n%s%n%b" | head -20
git show --stat HEAD
git status --short
# 期望:working tree clean,本地 tag 不变(v0.0.3/v1.0 仍在)
```

### 9.2 verify 状态(若本轮有代码改动)

```bash
./mvnw clean verify -B
# 期望:exit 0,JaCoCo 70%/40%,checkstyle 0
# 期望耗时:<60s(回归阈值)
```

### 9.3 wiki/log 是否同步

```bash
grep -c "^\- \[轮" /home/davidhlp/project/ResiCache/wiki/log.md
# 期望:每 commit 一行,轮号连续
```

### 9.4 GateGuard / ECC_HOOK 状态

```bash
echo "ECC_HOOK_PROFILE=${ECC_HOOK_PROFILE:-<unset>}"
echo "ECC_GATEGUARD=${ECC_GATEGUARD:-<unset>}"
# 期望:两者都 unset 或 enabled
```

### 9.5 当前 loop 状态(对照 round 37)

```bash
grep -A 3 "^## Auto-Iteration Progress" /home/davidhlp/project/ResiCache/wiki/log.md | head -5
# 期望看到 STOPPED-PUBLISH-BLOCKED 字样(直到用户跑 release 命令链)
```

## 10. 已知 Gates(等用户决策,loop 不会触碰)

来源:round 37 commit body + wiki/log.md "Auto-Iteration Progress"。

| # | Gate 项 | 用户需执行 | loop 是否触碰 |
|---|---|---|---|
| G1 | pom.xml `maven-gpg-plugin` sign-artifacts `<phase>deploy</phase>` → `<phase>verify</phase>` | 改 pom + commit | ❌(约束 §2) |
| G2 | `git tag -d v0.0.3`(本地) | `git tag -d v0.0.3` | ❌(约束 §4) |
| G3 | `git push origin :refs/tags/v0.0.3`(远程) | 同上 | ❌(约束 §1+§4) |
| G4 | `git tag v0.0.6`(本地建) | 同上 | ❌(约束 §4) |
| G5 | `git push origin v0.0.6` | 同上 | ❌(约束 §1+§4) |
| G6 | GitHub Release 自动建(softprops `if:success()`) | release.yml 自动 | N/A(loop 不动) |
| G7 | 本地 `git tag -d v1.0` 收尾 | `git tag -d v1.0` | ❌(约束 §4) |

## 11. Runbook 维护

- 本文件由 `/ecc:loop-start` 生成,人工维护;
- 每次 Round 收尾,在 retro 中引用本文件;
- 若 loop 模式 / 模式 / 模型 tier 变更,改本文件并提交,**不要**改 `AUTONOMOUS_ITERATION_LOOP.md`(那是 loop prompt 本身,与 runbook 解耦);
- runbook 与 prompt 的分工:prompt = loop 的"宪法",runbook = loop 的"实施细则"。

---

**Runbook 创建**:2026-06-30
**Runbook 版本**:v2(对应 `AUTONOMOUS_ITERATION_LOOP.md` v2 设计)
**下次维护触发**:已 CLOSED,不再维护。仅作历史归档参考。