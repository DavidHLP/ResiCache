package io.github.davidhlp.spring.cache.redis;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for Redis integration tests using Testcontainers.
 *
 * <p>Test isolation is ensured through:
 * <ul>
 *   <li>Container reuse via {@code withReuse(true)} - single container for all tests</li>
 *   <li>Database flush in {@code @BeforeEach} - clean state between tests</li>
 *   <li>Manual connection via {@link TestRedisConfiguration} - explicit Redis setup</li>
 * </ul>
 *
 * <p>Subclasses should call {@code redisCacheTemplate.getConnectionFactory().getConnection().flushDb()}
 * in {@code @BeforeEach} if they need a clean database state.
 *
 * @see RedisTestContainer
 * @see TestRedisConfiguration
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
public abstract class AbstractRedisIntegrationTest {

    @BeforeAll
    static void ensureContainerRunning() {
        RedisTestContainer.getInstance();
    }

    /**
     * Default cleanup method. Subclasses can override for additional cleanup.
     * Note: Subclasses should flush the database in @BeforeEach for test isolation.
     */
    @AfterEach
    void cleanUpRedis() {
        // Subclasses can override for additional cleanup
    }
}