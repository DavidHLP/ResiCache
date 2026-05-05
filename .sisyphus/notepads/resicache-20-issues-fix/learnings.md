# ResiCache 20 Issues Fix - Learnings

## 项目结构
- Spring Boot 3.2.4 + Java 17
- Maven 项目，parent pom 继承 Spring Boot
- 主要源码: src/main/java/io/github/davidhlp/spring/cache/redis/
- 测试: src/test/java/

## 关键文件位置
- CI/CD: `.github/workflows/qodana_code_quality.yml`, `deploy.yml`
- 构建配置: `pom.xml` (JaCoCo 在 192-230 行)
- Checkstyle: `src/main/resources/checkstyle-custom.xml`
- Gitignore: `.gitignore`

## Wave 1 上下文
- T1: qodana_code_quality.yml 当前只有 Qodana Scan，没有 mvn test
- T2: JaCoCo 阈值在 pom.xml:222 当前为 0.00
- T3: checkstyle-custom.xml 所有规则 severity="ignore"
- T4: 需要创建 .editorconfig
- T5: 需要生成 Maven Wrapper

## 并发修复上下文 (Wave 2)
- TwoListLRU: 严重并发缺陷，775/800 测试失败
- DistributedLockManager: 中断处理有问题
- SyncSupport: InterruptedException 被吞掉
- PreRefreshHandler: 竞态条件

## JaCoCo Threshold Update (Task 2, Wave 1)
- Changed `<minimum>0.00</minimum>` to `<minimum>0.60</minimum>` in pom.xml line 222
- JaCoCo check goal preserved, only threshold modified
- Verification: grep confirms 0.60 in place and 0.00 no longer exists

## Qodana Workflow Update (Task 1, Wave 1)
- Added JDK 17 setup before Qodana Scan: `actions/setup-java@v4` with `cache: 'maven'`
- Added `mvn test` step before Qodana Scan
- Used deploy.yml as reference for Java 17 setup pattern
- YAML validated successfully

## EditorConfig Creation (Task 4, Wave 1)
- Created `.editorconfig` in project root
- Java: UTF-8, LF, 4-space indent, max_line_length=200 (matches Checkstyle)
- XML/YAML: UTF-8, LF, 2-space indent
- Properties, Markdown, Shell scripts also configured
- Verification: `test -f .editorconfig && grep -q 'indent_size = 4' .editorconfig` passes

## Maven Wrapper (Task 5, Wave 1)
- `mvn wrapper:wrapper` generates Maven wrapper files
- Modern Maven wrapper plugin (3.3.4+) only-script mode: downloads wrapper.jar on first run
- `.mvn/wrapper/maven-wrapper.jar` in .gitignore line 3 handles the JAR when downloaded
- Files generated: mvnw (755), mvnw.cmd, .mvn/wrapper/maven-wrapper.properties
- Verification: `./mvnw -version` returns "Apache Maven 4.0.0-rc-5"
- Commit: c0d599a "build: add Maven Wrapper for consistent build environment"

## RateLimiterCacheWrapper Token Race Fix (Task 6)
- Anti-pattern: Using CAS (compareAndSet) inside a ReentrantLock critical section is redundant and potentially risky
- Under lock protection, simple get/set on AtomicLong is sufficient and cleaner
- The original CAS loop could theoretically fail if interrupted at the JVM level, leaving lastUpdate unset
- Concurrent QPS testing requires controlled request spacing to avoid lock contention skewing results
- Token bucket QPS accuracy is best tested with request generation rate matching target QPS (not exceeding it)
- Full test suite: 648 tests pass after fix

## Unused Parameter Check (RedisProCacheProperties)
- `unit` field in `SyncLockProperties` (line 103) IS used
- Usage: `SyncLockHandler.java:145-146` → `unit.toSeconds(timeout)` converts timeout to seconds
- `mvn compile -q` passes with exit code 0, no unused warnings

## @Deprecated Annotation (Task N)
- Javadoc @deprecated and @Deprecated annotation are independent - both should be present
- Adding @Deprecated annotation before class declaration keeps existing Javadoc intact
- Simple edit: add `@Deprecated` line before `public class EvictionStrategyFactory`

## Testcontainers Redis Integration Tests
- Added Testcontainers dependencies (1.19.7) to pom.xml: org.testcontainers:testcontainers and org.testcontainers:junit-jupiter
- Created AbstractRedisIntegrationTest base class with @Testcontainers(disabledWithoutDocker = true) for automatic skip when Docker unavailable
- Used @DynamicPropertySource to inject Redis container host/port into Spring environment
- Redis container: redis:7-alpine with .withReuse(true) for efficiency
- Created 3 integration test classes:
  - CacheOperationsIntegrationTest: tests basic RedisTemplate write/read, TTL, delete operations
  - DistributedLockIntegrationTest: tests Redisson-based distributed lock acquisition, exclusivity, timeout
  - BloomFilterIntegrationTest: tests RedisBloomIFilter add/check/clear and false positive rate
- Test configuration: TestRedisConfiguration provides @Primary beans for RedisConnectionFactory, RedisTemplate, RedissonClient
- Integration tests use @ActiveProfiles("integration-test") with application-integration-test.yml
- application-integration-test.yml enables bloom-filter and sync-lock (unlike application-test.yml which disables them)
- All 10 integration tests skip gracefully when Docker unavailable; 659 existing unit tests continue to pass
- BUILD SUCCESS confirmed with mvn test
