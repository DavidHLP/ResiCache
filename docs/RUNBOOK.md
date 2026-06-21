# ResiCache Runbook

<!-- AUTO-GENERATED: Deployment -->
## Publishing to Maven Central

ResiCache uses Sonatype's central-publishing-maven-plugin for Maven Central release.

### Prerequisites

1. **GPG Key**: Generate and publish your GPG key
   ```bash
   gpg --gen-key
   gpg --keyring gpg_pubring.kbx --export > public.gpg
   gpg --keyring gpg_pubring.kbx --export-secret-keys > private.gpg
   ```

2. **Sonatype Central Portal Account**: Sign in at https://central.sonatype.com
   - Verify namespace `io.github.davidhlp` is associated with your account

3. **Configure Maven settings.xml**:
   ```xml
   <settings>
     <servers>
       <server>
         <id>central</id>
         <username>your-sonatype-username</username>
         <password>your-sonatype-password</password>
       </server>
     </servers>
     <profiles>
       <profile>
         <id>release</id>
         <properties>
           <gpg.passphrase>your-gpg-passphrase</gpg.passphrase>
         </properties>
       </profile>
     </profiles>
   </settings>
   ```

### Release Process

```bash
# 1. Set version (e.g., 0.0.3)
./mvnw versions:set -DnewVersion=0.0.3

# 2. Run full CI build
./mvnw clean verify -B

# 3. Deploy to Maven Central (GPG sign auto-bound to deploy phase)
./mvnw deploy -B

# 4. Publish via Sonatype Central Portal
# Visit: https://central.sonatype.com/
# Login → Publishing → Find deployment → Publish (auto-publish can be enabled)
```

### Version Management

| Version Type | Command | Example |
|-------------|---------|---------|
| Set release | `./mvnw versions:set` | `0.0.3` |
| Rollback | `./mvnw versions:rollback` | - |
| Commit | `./mvnw versions:commit` | - |

<!-- AUTO-GENERATED: CI Pipeline -->
## CI Pipeline

`.github/workflows/ci.yml` defines the CI process:

```
┌─────────────────────────────────────────────────────────┐
│                    CI Pipeline                          │
├─────────────────────────────────────────────────────────┤
│  build (40s)     → Maven verify + JaCoCo              │
│  checkstyle (20s) → Checkstyle check                  │
│  qodana (60s)     → Qodana static analysis            │
│  dependency-check → dependency:analyze + versions      │
│  build-package    → mvn package (needs all above)      │
└─────────────────────────────────────────────────────────┘
```

### Local CI Simulation

```bash
# Build + test + checkstyle
./mvnw clean verify -B

# Checkstyle only
./mvnw checkstyle:check

# Qodana (requires JetBrains token)
./mvnw qodana:check
```

<!-- AUTO-GENERATED: Health Checks -->
## Health Check

```bash
# Check JaCoCo coverage
ls target/site/jacoco/index.html

# Check test results
ls target/surefire-reports/*.txt

# Verify JAR contents
jar tf target/resicache-*.jar | head -20
```

<!-- AUTO-GENERATED: Rollback -->
## Rollback Procedures

### Version Rollback
```bash
# If versions:set caused issues
./mvnw versions:rollback

# Then set correct version
./mvnw versions:set -DnewVersion=X.X.X
```

### Failed Deploy Rollback
1. Login to https://central.sonatype.com/
2. Navigate to Publishing → Deployments
3. Drop the failed deployment
4. Fix issues and re-deploy

### Git Rollback
```bash
# Revert last commit
git revert HEAD

# Or reset to previous version
git reset --hard HEAD~1
git push --force
```

<!-- AUTO-GENERATED: Common Issues -->
## Common Issues

| Issue | Solution |
|-------|----------|
| `GPG signing failed` | Ensure gpg-agent running, passphrase in settings.xml |
| `Checkstyle violations` | Run `./mvnw checkstyle:check` locally first |
| `JaCoCo < 70% line / 40% branch` | Add tests for uncovered code paths |
| `Testcontainers failed` | Ensure Docker is running (`docker ps`) |
| `Redisson connection timeout` | Check Redis is running on localhost:6379 |
