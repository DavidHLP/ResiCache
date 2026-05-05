# Contributing to ResiCache

## Development Setup

```bash
git clone https://github.com/davidhlp/ResiCache.git
cd ResiCache
mvn clean install
```

## Code Standards

- Follow Google Java Style Guide
- Run `mvn checkstyle:check` before submitting PR
- Maintain test coverage above 80%
- All public APIs must have Javadoc

## Pull Request Process

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Run tests: `mvn clean test`
5. Submit PR with description

## Testing Requirements

- Unit test coverage must be ≥80%
- All existing tests must pass
- New features require tests

## Release Process

1. Update CHANGELOG.md
2. Bump version in pom.xml
3. Create release tag