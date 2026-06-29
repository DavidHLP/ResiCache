# Contributing to ResiCache

Thanks for your interest in improving ResiCache! This is a small,
single-maintainer, **Non-SLA best-effort** project — PRs of all sizes are
welcome, and the bar below keeps the project healthy.

## Before you start

- ResiCache is **pre-1.0**: APIs may change. If your change alters a public API,
  please open an issue to discuss it first.
- Read [README.md](README.md) (Known Limitations + Not in Scope) and the
  relevant [wiki](wiki/) pages so your change fits the architecture.

## Development setup

Requirements: **JDK 21+** (matches `pom.xml` `<java.version>21</java.version>`
after the WS-1.1 FIRE cut), **Maven 3.x** (the wrapper `./mvnw` is bundled),
**Docker** (for Testcontainers-based integration tests).

```bash
./mvnw clean verify -B      # build + tests + coverage gate + style
./mvnw checkstyle:check -B  # style only
./mvnw clean package -DskipTests -B   # quick package, no tests
```

The `verify` goal enforces a JaCoCo coverage gate:

- **70% line coverage**
- **40% branch coverage**

A PR that drops below these thresholds will fail CI. **If you add code, add
tests.**

## Pull request checklist

- [ ] `./mvnw clean verify -B` passes locally (including the coverage gate and
      Checkstyle).
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
| A protection mechanism | `protection/<mechanism>/` + `chain/CacheHandlerChainFactory` |
| Annotation handling | `handler/` + `annotation/RedisCacheOperationSource` |
| Auto-configuration | `config/RedisCacheAutoConfiguration` + `RedisProCacheProperties` |
| Serialization | `serialization/SecureJackson*` |
| Cache core | `cache/RedisProCache`, `RedisProCacheManager`, `RedisProCacheWriter` |

See `wiki/architecture/` and `wiki/mechanisms/` for the design rationale.

## Adding a protection handler

1. Create a class under `protection/<your-mechanism>/` implementing `CacheHandler`
   (extend `AbstractCacheHandler`).
2. Annotate it `@HandlerPriority(HandlerOrder.YOUR_ORDER)` — `HandlerOrder` is
   the single source of truth for ordering (gap = 100, extend the enum to insert).
3. Make it a `@Component` so `CacheHandlerChainFactory` auto-discovers it.
4. Add tests; document the mechanism in `wiki/mechanisms/`.

See `wiki/how-to/add-protection-handler.md` for a worked example.

## Maintaining the wiki

Source → wiki is a **one-way relationship**: when source changes, update the
relevant wiki page and append an entry to `wiki/log.md`. The wiki is the
compiled knowledge base — don't re-derive architecture from source in answers.

## Code of conduct

Be respectful and constructive. This is a best-effort project; assume good
intent and keep discussions focused on the code.

## Maintainers & bus factor

ResiCache is currently a **single-maintainer project** — all merges, releases,
and architectural decisions flow through `DavidHLP` (the only committer with
`CODEOWNERS` write access on `master`).

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
