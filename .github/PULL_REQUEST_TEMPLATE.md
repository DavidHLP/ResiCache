<!--
  Keep this checklist aligned with CONTRIBUTING.md and docs/README.md.
  Current-state documentation has one canonical owner per topic.
-->

## Summary

<!-- One or two sentences: what does this PR change and why? -->

## Checklist

- [ ] `./mvnw clean verify -B` passes locally when source/build behavior changed (coverage gate).
- [ ] `./mvnw checkstyle:check -B` passes when Java source changed.
- [ ] New behavior has tests; bug fixes have a regression test.
- [ ] Redis integration tests use `AbstractRedisIntegrationTest`; Cluster tests use `AbstractRedisClusterIntegrationTest` (Testcontainers — Docker must be running).
- [ ] `bash scripts/ci/check-test-names.sh` passes; integration classes do not use `*IT.java`.
- [ ] `bash scripts/ci/check-docs-contracts.sh` passes when docs, source references, or public contracts changed.
- [ ] Documentation changes update the canonical owner in [`docs/README.md`](../docs/README.md); no task/date/session document was added as current-state policy.
- [ ] Public API/configuration/wire changes are checked against [`STABILITY.md`](../STABILITY.md) and [`COMPATIBILITY.md`](../COMPATIBILITY.md).
- [ ] No over-engineering: features that belong in [Resilience4j](https://resilience4j.readthedocs.io/) (circuit breaking / rate limiting) or [Caffeine](https://github.com/ben-manes/caffeine) (multi-level caching) are out of scope unless the product boundary changes explicitly.
- [ ] Javadoc on public API; Chinese rationale comments are welcome for design decisions matching the existing codebase style.
- [ ] Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`, `refactor:`, `docs:`, `test:`, `ci:`, `chore:`.

## Backward compatibility

<!--
  If this changes default behavior, annotation signatures, resi-cache.*
  property keys, or the {version,payload} wire format, describe the impact and
  update the canonical contract/history source rather than copying a second
  rule into this PR. Pre-1.0 changes to internals, defaults, or metric
  namespaces must be recorded in CHANGELOG.md; use a ⚠️ marker for
  user-visible default behavior changes.
-->

- [ ] Compatibility impact is described, or this change is documentation/internal-only.
- [ ] Any pre-1.0 internal/default/metric-namespace change is recorded in [`CHANGELOG.md`](../CHANGELOG.md), with a `⚠️` marker when user-visible.
