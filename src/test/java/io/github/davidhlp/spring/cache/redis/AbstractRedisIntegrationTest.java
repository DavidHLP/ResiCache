package io.github.davidhlp.spring.cache.redis;

import org.junit.jupiter.api.AfterEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for Redis integration tests using Testcontainers.
 *
 * <p>Each test class gets its own fresh Redis container that is automatically
 * started before tests and stopped after tests complete. This ensures complete
 * test isolation without state pollution between test runs.
 *
 * <p>Container lifecycle is managed by JUnit 5 Testcontainers extension:
 * <ul>
 *   <li>Container starts once per test class (static field)</li>
 *   <li>All tests in the same class share the same container</li>
 *   <li>Container is automatically stopped after all tests complete</li>
 *   <li>Redis connection properties are injected via @DynamicPropertySource</li>
 * </ul>
 *
 * <p>Subclasses should call {@code redisCacheTemplate.getConnectionFactory().getConnection().flushDb()}
 * in {@code @BeforeEach} if they need a clean database state between tests.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractRedisIntegrationTest {

    private static final String REDIS_IMAGE = "redis:7-alpine";
    private static final int REDIS_PORT = 6379;

    @Container
    protected static final GenericContainer<?> REDIS_CONTAINER =
            new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
                    .withExposedPorts(REDIS_PORT)
                    .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS_CONTAINER::getHost);
        registry.add("spring.data.redis.port", REDIS_CONTAINER::getFirstMappedPort);
    }

    /**
     * Get the Redis container host.
     * @return the host address of the Redis container
     */
    protected static String getRedisHost() {
        return REDIS_CONTAINER.getHost();
    }

    /**
     * Get the Redis container port.
     * @return the mapped port of the Redis container
     */
    protected static int getRedisPort() {
        return REDIS_CONTAINER.getFirstMappedPort();
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
