<!--
  Thanks for the PR! This checklist mirrors CONTRIBUTING.md "Pull request
  checklist" so it surfaces at PR-creation time. See CONTRIBUTING.md for the
  full contributor guide and the "Architecture pointers" table.
-->

## Summary

<!-- One or two sentences: what does this PR change and why? -->

## Checklist

- [ ] `./mvnw clean verify -B` passes locally (including the coverage gate and Checkstyle).
- [ ] New behavior has tests; bug fixes have a regression test.
- [ ] Integration tests touching Redis extend `AbstractRedisIntegrationTest` (Testcontainers — Docker must be running).
- [ ] No over-engineering: features that belong in [Resilience4j](https://resilience4j.readthedocs.io/) (circuit breaking / rate limiting) or [Caffeine](https://github.com/ben-manes/caffeine) (multi-level caching) are **out of scope** — see README "Not in Scope".
- [ ] Javadoc on public API; Chinese rationale comments are welcome for design decisions (matching the existing codebase style).
- [ ] Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`, `refactor:`, `docs:`, `test:`, `ci:`, `chore:`.

## Backward compatibility

<!--
  If this changes default behavior, annotation signatures, resi-cache.*
  property keys, or the {version,payload} wire format, check STABILITY.md and
  describe the impact here. Pre-1.0 changes to internals/defaults/metric
  namespace are allowed but must be noted in CHANGELOG.md (⚠️ BREAKING if
  user-visible default behavior changes).
-->
