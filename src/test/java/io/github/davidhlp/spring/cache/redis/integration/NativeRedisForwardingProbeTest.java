package io.github.davidhlp.spring.cache.redis.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Foundation probe (TASK-007): proves Testcontainers NATIVE port forwarding
 * reaches real Redis on the current host WITHOUT the socat WSL2 shim and
 * WITHOUT a manually-pinned Docker API version (the
 * {@link DockerApiVersionLauncherSessionListener} handles negotiation at session
 * start via the registered META-INF/services entry).
 *
 * <p>If this passes with {@code -Dtestcontainers.ryuk.disabled=false}, the entire
 * socat + Ryuk-disable apparatus is unnecessary and the unified cross-platform
 * base can drop it. Cross-platform rationale: {@code getHost()} /
 * {@code getFirstMappedPort()} resolve identically on Windows / Linux / macOS
 * wherever Docker runs — they target the Docker-managed localhost port forward,
 * <em>not</em> the container bridge IP, so a DOWN docker0 bridge is irrelevant.
 *
 * <p>Pure-socket (no Spring context) on purpose: isolates the infrastructure
 * hypothesis from any bean-wiring concern.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NativeRedisForwardingProbeTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--save", "")
                    .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

    @Test
    void nativePortForwarding_reachesRealRedis_pingPong() throws Exception {
        String host = REDIS.getHost();
        int port = REDIS.getFirstMappedPort();
        System.out.println("[PROBE] getHost()=" + host + " getFirstMappedPort()=" + port);

        try (Socket sock = new Socket()) {
            sock.setSoTimeout(2000);
            sock.connect(new InetSocketAddress(host, port), 2000);
            sock.getOutputStream().write("*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.US_ASCII));
            sock.getOutputStream().flush();
            byte[] buf = new byte[64];
            int n = sock.getInputStream().read(buf);
            String resp = new String(buf, 0, n, StandardCharsets.US_ASCII);
            System.out.println("[PROBE] PING -> " + resp.trim());
            assertThat(resp).contains("PONG");
        }
    }

    @Test
    void nativePortForwarding_realSetGetRoundTrip() throws Exception {
        String host = REDIS.getHost();
        int port = REDIS.getFirstMappedPort();
        try (Socket sock = new Socket()) {
            sock.setSoTimeout(2000);
            sock.connect(new InetSocketAddress(host, port), 2000);
            var out = sock.getOutputStream();
            var in = sock.getInputStream();
            // SET resicache:probe hello
            out.write("*3\r\n$3\r\nSET\r\n$15\r\nresicache:probe\r\n$5\r\nhello\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            byte[] buf = new byte[128];
            int n = in.read(buf);
            System.out.println("[PROBE] SET -> "
                    + new String(buf, 0, n, StandardCharsets.US_ASCII).trim());
            // GET resicache:probe
            out.write("*2\r\n$3\r\nGET\r\n$15\r\nresicache:probe\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            n = in.read(buf);
            String getResp = new String(buf, 0, n, StandardCharsets.US_ASCII);
            System.out.println("[PROBE] GET -> " + getResp.trim());
            assertThat(getResp).contains("hello");
        }
    }
}
