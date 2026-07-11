package io.github.davidhlp.spring.cache.redis.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
 *   <li>Redis connection properties are injected via @DynamicPropertySource in
 *       combination with a WSL2-local socat forward</li>
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
    // socat 在 WSL 的 127.0.0.1 上监听,转发到 docker 容器 bridge IP:6379。
    // 解决 WSL2 native docker:docker0 网桥 state DOWN,WSL 内直接连容器 bridge IP 不通
    // (已用 python socket 验证通过 socat 中转可通 +PONG)。
    private static final int FORWARD_PORT;
    private static final List<Process> SOCAT_PROCESSES = new ArrayList<>();

    static {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            FORWARD_PORT = s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot allocate free local port for socat", e);
        }
        Runtime.getRuntime().addShutdownHook(
                new Thread(AbstractRedisIntegrationTest::stopSocatForwards));
    }

    @Container
    protected static final GenericContainer<?> REDIS_CONTAINER =
            new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
                    .withExposedPorts(REDIS_PORT)
                    .withCommand("redis-server", "--save", "")
                    // 默认 wait(基于 isRunning)在 bridge network 下工作正常;
                    // host network 才有 isRunning() 谓词的 false 误判 bug,故不调
                    // withNetworkMode("host"),改走 socat 中转方案。
                    .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        // Spring 连 WSL 内 socat 转发的 127.0.0.1:FORWARD_PORT。
        // socat 进程在容器 ready 后由 startContainerAndForward() 静态方法启
        // (JUnit 5 Testcontainers extension 在 @BeforeAll 之前会启动 @Container,
        // 但为保险起见我们用 JUnit 5 @BeforeAll 钩子显式确保 socat 启动)。
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> FORWARD_PORT);
    }

    /**
     * JUnit 5 钩子:testcontainers 启动容器后,启动 socat 转发到 FORWARD_PORT。
     * 用 @BeforeAll 让 Spring context 启动前 socat 已就绪。
     */
    @org.junit.jupiter.api.BeforeAll
    static void startContainerAndForward() {
        // deepStart 幂等:已启动的容器直接返回,未启动的启动它
        Startables.deepStart(Collections.singletonList(REDIS_CONTAINER)).join();
        startSocatForward();
    }

    /**
     * Stop the class-scoped forward before Testcontainers reuses the inherited
     * static container for another integration-test class. Without this hook,
     * the next class sees a non-empty process list and keeps forwarding to the
     * previous container IP.
     */
    @AfterAll
    static void stopClassSocatForward() {
        stopSocatForwards();
    }

    private static synchronized void startSocatForward() {
        if (!SOCAT_PROCESSES.isEmpty()) {
            return;
        }
        // 拿容器在 docker bridge 上的 IP
        String containerIp;
        try {
            containerIp = REDIS_CONTAINER.getContainerInfo()
                    .getNetworkSettings()
                    .getNetworks()
                    .values().iterator().next()
                    .getIpAddress();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot resolve container bridge IP", e);
        }
        if (containerIp == null || containerIp.isEmpty()) {
            throw new IllegalStateException("Container bridge IP is empty");
        }
        Process p;
        try {
            p = new ProcessBuilder("socat",
                    "TCP-LISTEN:" + FORWARD_PORT + ",fork,reuseaddr",
                    "TCP:" + containerIp + ":" + REDIS_PORT)
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot start socat for container " + containerIp, e);
        }
        // 等 socat 端口起来
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress("127.0.0.1", FORWARD_PORT), 200);
                break;
            } catch (IOException e) {
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        SOCAT_PROCESSES.add(p);
        System.out.println("[IT] socat forwarding 127.0.0.1:" + FORWARD_PORT
                + " -> container " + containerIp + ":" + REDIS_PORT
                + " (pid=" + p.pid() + ")");
    }

    private static synchronized void stopSocatForwards() {
        for (Process p : SOCAT_PROCESSES) {
            if (p.isAlive()) {
                p.destroy();
                try {
                    p.waitFor(2, TimeUnit.SECONDS);
                } catch (InterruptedException ignore) {
                    Thread.currentThread().interrupt();
                }
                if (p.isAlive()) {
                    p.destroyForcibly();
                }
            }
        }
        SOCAT_PROCESSES.clear();
    }

    /**
     * Get the Redis container host. Returns the socat forward endpoint.
     * @return the host address that Spring/Redisson should connect to
     */
    protected static String getRedisHost() {
        return "127.0.0.1";
    }

    /**
     * Get the Redis container port. Returns the socat forward port.
     * @return the port that Spring/Redisson should connect to
     */
    protected static int getRedisPort() {
        return FORWARD_PORT;
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
