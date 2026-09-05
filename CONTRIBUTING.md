# Contributing to ResiCache

Thanks for your interest in improving ResiCache! This is a small,
single-maintainer, **Non-SLA best-effort** project — PRs of all sizes are
welcome, and the bar below keeps the project healthy.

## Before you start

- ResiCache is **pre-1.0**: APIs may change. If your change alters a public API,
  please open an issue to discuss it first.
- Read [README.md](README.md) (Known Limitations + Not in Scope) and
  [CLAUDE.md](CLAUDE.md) (Project Structure + Key Architecture) so your change
  fits the architecture.

## Development setup

Requirements: **JDK 21** (matches `pom.xml` `<java.version>21</java.version>`),
**Maven 3.x** (the wrapper `./mvnw` is bundled),
**Docker** (for Testcontainers-based integration tests).

```bash
./mvnw clean verify -B
./mvnw -Punit test -B
./mvnw checkstyle:check -B
./mvnw clean package -DskipTests -B
bash scripts/ci/check-test-names.sh
```

`./mvnw -Punit test -B` is the no-Docker daily path. It does not verify real
Redis/Cluster behavior and is not a release gate; `./mvnw clean verify -B`
remains the full Redis proof.

The `verify` goal enforces a JaCoCo coverage gate:

- **70% line coverage**
- **40% branch coverage**

A PR that drops below these thresholds will fail CI. **If you add code, add
tests.**

- [ ] `./mvnw clean verify -B` passes locally (including the coverage gate).
- [ ] `./mvnw checkstyle:check -B` passes locally.
- [ ] New behavior has tests; bug fixes have a regression test.
- [ ] Integration tests touching Redis extend `AbstractRedisIntegrationTest`
      (Testcontainers — Docker must be running).
- [ ] No over-engineering: features that belong in
      [Resilience4j](https://resilience4j.readthedocs.io/) (circuit breaking /
      rate limiting) or [Caffeine](https://github.com/ben-manes/caffeine)
      (multi-level caching) are **out of scope** — see README "Not in Scope".
- [ ] Javadoc on public API; Chinese rationale comments are welcome for design
      decisions (matching the existing codebase style).
- [ ] Commit messages follow
      [Conventional Commits](https://www.conventionalcommits.org/):
      `feat:`, `fix:`, `refactor:`, `docs:`, `test:`, `ci:`, `chore:`.

## Architecture pointers

| You're touching... | Start here |
|---|---|
| A protection mechanism | internal `cache/` runtime + `chain/CacheHandlerChainFactory` |
| Annotation handling | internal `cache/` annotation pipeline |
| Auto-configuration | `config/RedisCacheAutoConfiguration` + `RedisProCacheProperties` |
| Serialization | `serialization/SecureJackson*` |
| Cache core | `cache/RedisProCache`, `RedisProCacheManager`, `RedisProCacheWriter` |

See [CLAUDE.md](CLAUDE.md) (Key Architecture + Where to Look) for the design
rationale and source pointers.

## Adding a protection handler

1. Create a class in the internal `cache/` runtime implementing `CacheHandler`
   (extend `AbstractCacheHandler`).
2. Annotate it `@HandlerPriority(HandlerOrder.YOUR_ORDER)` — `HandlerOrder` is
   the single source of truth for ordering (gap = 100, extend the enum to insert).
3. Annotate it `@Component` — the internal `cache/` runtime package is the
   only package `RedisCacheAutoConfiguration` scans (test classes excluded);
   no root-package scan is used. Internal `@Configuration` classes inside
   `cache/` are picked up by the same internal-only scan.
4. Add tests; document the mechanism's design rationale in Javadoc on the
   handler class (matching the existing codebase style).

The full extension protocol (non-null `HandlerResult`, `FlowControl`
semantics, post-process isolation, observer hook order, scope tokens,
thread safety, nested public type classification) is normative in
[`STABILITY.md`](./STABILITY.md) §4; the nested public type list is pinned
by `src/test/resources/allowlist/public-surface-nested.txt` and
`PublicSurfaceContractTest`.

See [CLAUDE.md](CLAUDE.md) § Key Architecture: Chain of Responsibility for the
handler-ordering model, and the `protection/` packages for worked examples of
existing handlers.

## Code of conduct

Be respectful and constructive. This is a best-effort project; assume good
intent and keep discussions focused on the code.

## Maintainers & bus factor

ResiCache is currently a **single-maintainer project** — all merges, releases,
and architectural decisions flow through `DavidHLP` (the only committer with
`CODEOWNERS` write access on `main`; `master` is retained only where legacy
workflow references still exist).

**Bus factor: 1** (current). This is honest, not aspirational.

We publicly track this because it matters for downstream evaluation. See
[`STABILITY.md`](STABILITY.md) §4 1.0 graduation criterion #6 — graduation
requires either a **named successor** (someone who can carry the project
forward if the maintainer disappears) **or** a **documented succession plan**
(e.g. an org transfer, a publisher hard-takeover clause, or a fork governance
agreement).

What this means in practice today:

- **Pre-1.0**: bus factor 1 is acceptable. The project is explicitly
  best-effort, no SLA, no production adopters are pinned to it.
- **At 1.0 graduation**: this section must be rewritten to document either a
  successor or a plan before the `1.0.0` tag is cut. The graduation criteria
  are an explicit pre-flight checklist for this kind of risk.

If a serious downstream evaluation finds bus factor 1 unacceptable, file an
issue — the maintainer is open to succession conversations and to a publisher
hand-off, not to abandoning the project.

## Releases & CI infrastructure

CI runs on every push to `main` or `master` and every PR via
[`.github/workflows/ci.yml`](.github/workflows/ci.yml) and
[`.github/workflows/pr.yml`](.github/workflows/pr.yml). The composite action
[`.github/actions/setup-jdk-21/action.yml`](.github/actions/setup-jdk-21/action.yml)
centralizes the JDK distribution and Maven cache configuration. The POM's
`<java.version>21</java.version>` remains the compiler and Enforcer source of
truth; CI must keep the action input aligned.

Release-time secrets (`OSSRH_*`, `GPG_*`) are configured at the repository /
environment level out of band by the maintainer. Do not edit `release.yml`
to add secrets — open an issue first.
