# Dead-Code Audit Reports

Three-model code-health audits of `src/main/` (call chain / dead code / over-engineering),
produced 2026-06-21, plus a cross-validation that reconciles them against the actual codebase.

## Reports

| File | Source | Scope |
|------|--------|-------|
| `deepseek-unused-modules-report.md` | DeepSeek | Call chain / dead code / over-engineering (class level) |
| `minimax-unused-modules-report.md` | MiniMax | Most detailed — method / field / config level |
| `glm-unused-modules-report.md` | GLM | Assembly-chain focused |
| `cross-validation.md` | Reconciliation | Truth-vs-misjudgment verdict across the three |

## Status: Resolved

These reports audited the **pre-refactor** codebase (**104 main files**). The confirmed
findings were acted on in commit `a5ab55b` ("remove dead code and simplify over-engineering",
32 files, net **−2947 lines**). The codebase now has **90 main files**; all 611 tests green,
Checkstyle + JaCoCo coverage gates met.

## Cross-validation takeaways (`cross-validation.md`)

- **All three agreed** on the dead core: `evaluator/`, `event/`, `wrapper/`, and the entire
  `spi/` shell (ServiceLoader never invoked).
- **MiniMax** found the most (method/field/config level, 14 exclusive findings all valid) but
  had **2 Spring-wiring misjudgments** (claimed `MetricsAutoConfiguration`/`CachingEnablementValidation`
  "never load" — they do, via `@ComponentScan`; claimed `CacheMetricsRecorder` dual-registration
  error — prevented by `@ConditionalOnMissingBean`).
- **DeepSeek** had the right direction but **conflated over-engineering with dead code**
  (flagged in-use `EvictionStrategy` as deletable) and **inaccurate line counts**
  (SpelConditionEvaluator reported 70 lines, actual 269).
- **GLM** had the most accurate assembly-chain analysis and the unique correct call on the
  Bloom `@Primary` wiring, but had coverage gaps (missed `LockPoolStats`, dual `LockManager`)
  and one wrong classification (`CacheMetricsRecorder` marked "effective" — it was a zombie bean).

## Purpose going forward

Kept for historical reference — they document *why* each deletion/simplification was made and
which model could be trusted on which dimension. For the **current** architecture, see
[../CODEMAPS/](../CODEMAPS/).
