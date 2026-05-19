package io.github.davidhlp.spring.cache.redis;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Singleton Redis test container shared across all integration test classes.
 * Ensures the container stays running for the entire test suite lifetime.
 */
public final class RedisTestContainer {

    private static final String REDIS_IMAGE = "redis:7-alpine";
    private static final int REDIS_PORT = 6379;

    private static final GenericContainer<?> INSTANCE = new GenericContainer<>(
            DockerImageName.parse(REDIS_IMAGE))
            .withExposedPorts(REDIS_PORT)
            .withReuse(true)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

    private RedisTestContainer() {}

    public static GenericContainer<?> getInstance() {
        if (!INSTANCE.isRunning()) {
            INSTANCE.start();
        }
        return INSTANCE;
    }

    public static String getHost() {
        return getInstance().getHost();
    }

    public static int getPort() {
        return getInstance().getFirstMappedPort();
    }
}
