package io.github.davidhlp.spring.cache.redis.cache;




import java.util.Collections;
import org.junit.jupiter.api.AfterAll;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

/**
 * Three-master Redis Cluster fixture for topology-sensitive integration tests.
 *
 * <p>All three Redis processes run in one {@code redis:7-alpine} container but form
 * a real Cluster with independent ports, node IDs, slot ownership, and cluster bus
 * links. Tests connect to the container bridge address, which is reachable on the
 * Linux Docker hosts used locally and by GitHub Actions.
 */
@Testcontainers(disabledWithoutDocker = false)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractRedisClusterIntegrationTest {

    private static final String REDIS_IMAGE = "redis:7-alpine";
    private static final int FIRST_REDIS_PORT = 7000;
    private static final int NODE_COUNT = 3;

    @Container
    protected static final GenericContainer<?> REDIS_CLUSTER =
            new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
                    .withCommand("sh", "-c", clusterStartupCommand())
                    .waitingFor(Wait.forLogMessage(".*CLUSTER_READY.*", 1));

    @DynamicPropertySource
    static void redisClusterProperties(DynamicPropertyRegistry registry) {
        Startables.deepStart(Collections.singletonList(REDIS_CLUSTER)).join();
        String containerIp = containerIp();
        for (int index = 0; index < NODE_COUNT; index++) {
            int port = FIRST_REDIS_PORT + index;
            int propertyIndex = index;
            registry.add("spring.data.redis.cluster.nodes[" + propertyIndex + "]",
                    () -> containerIp + ":" + port);
            registry.add("resi-cache.redis.cluster-nodes[" + propertyIndex + "]",
                    () -> containerIp + ":" + port);
        }
        registry.add("spring.data.redis.host", () -> containerIp);
        registry.add("spring.data.redis.port", () -> FIRST_REDIS_PORT);
        registry.add("resi-cache.redis.mode", () -> "cluster");
        registry.add("resi-cache.redis.database", () -> 0);
        registry.add("resi-cache.protection.bloom-filter-enabled", () -> false);
    }

    @AfterAll
    static void reportClusterFixture() throws Exception {
        if (!REDIS_CLUSTER.isRunning()) {
            throw new IllegalStateException("Redis Cluster container stopped before test completion");
        }
        String info = redisCli("cluster", "info");
        if (!info.contains("cluster_state:ok")) {
            throw new IllegalStateException("Redis Cluster did not remain healthy: " + info);
        }
        System.out.println("[IT] Redis Cluster container=" + REDIS_CLUSTER.getContainerId()
                + " nodes=" + NODE_COUNT + " state=ok");
    }

    protected static String redisCli(String... args) throws Exception {
        return redisCliAt(FIRST_REDIS_PORT, args);
    }

    protected static String redisCliAt(int port, String... args) throws Exception {
        String[] command = new String[args.length + 3];
        command[0] = "redis-cli";
        command[1] = "-p";
        command[2] = Integer.toString(port);
        System.arraycopy(args, 0, command, 3, args.length);
        var result = REDIS_CLUSTER.execInContainer(command);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("redis-cli failed: " + result.getStderr());
        }
        return result.getStdout().trim();
    }

    private static String containerIp() {
        return REDIS_CLUSTER.getContainerInfo()
                .getNetworkSettings()
                .getNetworks()
                .values()
                .iterator()
                .next()
                .getIpAddress();
    }

    private static String clusterStartupCommand() {
        return "set -eu; "
                + "IP=$(hostname -i | awk '{print $1}'); "
                + "for PORT in 7000 7001 7002; do "
                + "mkdir -p /data/$PORT; "
                + "printf 'port %s\\nbind 0.0.0.0\\nprotected-mode no\\n"
                + "cluster-enabled yes\\ncluster-config-file nodes-%s.conf\\n"
                + "cluster-node-timeout 5000\\ncluster-announce-ip %s\\n"
                + "cluster-announce-port %s\\ncluster-announce-bus-port %s\\n"
                + "appendonly no\\nsave \"\"\\nlogfile /data/%s/redis.log\\n' "
                + "$PORT $PORT $IP $PORT $((PORT+10000)) $PORT > /data/$PORT/redis.conf; "
                + "redis-server /data/$PORT/redis.conf --daemonize yes; "
                + "done; "
                + "until redis-cli -p 7000 ping >/dev/null 2>&1; do sleep 0.1; done; "
                + "redis-cli --cluster create $IP:7000 $IP:7001 $IP:7002 "
                + "--cluster-replicas 0 --cluster-yes; "
                + "until redis-cli -p 7000 cluster info | grep -q 'cluster_state:ok'; "
                + "do sleep 0.1; done; "
                + "echo CLUSTER_READY; tail -f /dev/null";
    }
}
