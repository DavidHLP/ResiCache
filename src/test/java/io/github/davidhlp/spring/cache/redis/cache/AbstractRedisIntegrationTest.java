package io.github.davidhlp.spring.cache.redis.cache;



import org.junit.jupiter.api.AfterEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
 * Unified, cross-platform base class for real-Redis integration tests.
 *
 * <p>Uses Testcontainers' NATIVE port forwarding ({@code getHost()} +
 * {@code getFirstMappedPort()}) to reach the containerised Redis. This targets
 * the Docker-managed localhost port forward — <em>not</em> the container bridge
 * IP — so it is unaffected by a DOWN {@code docker0} bridge and resolves
 * identically on Windows / Linux / macOS wherever Docker runs. No host-side
 * {@code socat} binary or WSL2-specific shim is required.
 *
 * <p><b>Why no socat:</b> an earlier revision forwarded via a host {@code socat}
 * process on the grounds that "WSL2 docker0 bridge is DOWN so the JVM cannot
 * reach the container bridge IP". That reasoning conflated two things: connecting
 * to the bridge IP directly (which indeed can fail) vs. Testcontainers' native
 * port mapping (a Docker-managed forward on {@code localhost}, which does not).
 * A foundation probe ({@link NativeRedisForwardingProbeIntegrationTest}) proved native
 * {@code PING->PONG} + real {@code SET/GET} work with no socat and no manually
 * pinned API version. The socat apparatus has therefore been removed.
 *
 * <p>Container lifecycle is managed by the JUnit 5 Testcontainers extension:
 * <ul>
 *   <li>Container starts once per test class (static {@code @Container} field)</li>
 *   <li>All tests in the same class share the same container</li>
 *   <li>Container is automatically stopped after all tests complete (Testcontainers
 *       shutdown hooks; Ryuk is disabled, see {@code pom.xml})</li>
 *   <li>Redis connection properties are injected via {@code @DynamicPropertySource}</li>
 * </ul>
 *
 * <p>Subclasses should call
 * {@code redisCacheTemplate.getConnectionFactory().getConnection().flushDb()}
 * in {@code @BeforeEach} for a clean database state between tests.
 *
 * <p>Docker API version negotiation is handled once at session start by
 * {@link DockerApiVersionLauncherSessionListener} (registered via
 * {@code META-INF/services}) — no manual {@code -Dapi.version} flag is needed.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestRedisConfiguration.class)
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
                    .withCommand("redis-server", "--save", "")
                    .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        // Native Testcontainers port forwarding: Docker maps a random host port
        // (on the daemon host, reachable as localhost) to container port 6379.
        // Works on every platform with Docker — no socat / bridge-IP hack.
        registry.add("spring.data.redis.host", REDIS_CONTAINER::getHost);
        registry.add("spring.data.redis.port",
                () -> REDIS_CONTAINER.getMappedPort(REDIS_PORT));
    }

    /**
     * Get the Redis container host (Testcontainers-managed; resolves to localhost
     * on Windows / Linux / macOS).
     * @return the host address that Spring/Redisson connect to
     */
    protected static String getRedisHost() {
        return REDIS_CONTAINER.getHost();
    }

    /**
     * Get the Redis container port (Testcontainers-mapped random host port).
     * @return the port that Spring/Redisson connect to
     */
    protected static int getRedisPort() {
        return REDIS_CONTAINER.getMappedPort(REDIS_PORT);
    }

    /**
     * Default cleanup hook. Subclasses can override for additional cleanup.
     * Subclasses should flush the database in {@code @BeforeEach} for isolation.
     */
    @AfterEach
    void cleanUpRedis() {
        // Subclasses can override for additional cleanup
    }
}
