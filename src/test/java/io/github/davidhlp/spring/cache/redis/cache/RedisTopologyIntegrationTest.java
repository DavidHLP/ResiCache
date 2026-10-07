package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SslOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real Sentinel discovery and certificate verification; these are not HA/SLO claims. */
@Testcontainers(disabledWithoutDocker = false)
class RedisTopologyIntegrationTest {

    private static final Path CERTIFICATES = certificates();

    @Container
    private static final GenericContainer<?> SENTINEL = new GenericContainer<>("redis:7-alpine")
            .withCommand("sh", "-c", sentinelCommand())
            .waitingFor(Wait.forLogMessage(".*SENTINEL_READY.*", 1));

    @Container
    private static final GenericContainer<?> TLS = new GenericContainer<>("redis:7-alpine")
            .withCopyFileToContainer(MountableFile.forHostPath(CERTIFICATES.resolve("server.crt")), "/tls/server.crt")
            .withCopyFileToContainer(MountableFile.forHostPath(CERTIFICATES.resolve("server.key"), 0644), "/tls/server.key")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--port", "0", "--tls-port", "6379", "--tls-cert-file", "/tls/server.crt",
                    "--tls-key-file", "/tls/server.key", "--tls-ca-cert-file", "/tls/server.crt",
                    "--tls-auth-clients", "no", "--save", "")
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

    @Test
    void sentinelDiscoversMasterForSpringReadsAndRedissonLocks() throws Exception {
        String ip = SENTINEL.getContainerInfo().getNetworkSettings().getNetworks()
                .values().iterator().next().getIpAddress();
        var discovered = SENTINEL.execInContainer("redis-cli", "-p", "26379", "sentinel", "get-master-addr-by-name", "cache");
        assertThat(discovered.getStdout()).contains(ip, "6379");
        RedisSentinelConfiguration redis = new RedisSentinelConfiguration().master("cache")
                .sentinel(ip, 26379).sentinel(ip, 26380).sentinel(ip, 26381);
        LettuceConnectionFactory factory = new LettuceConnectionFactory(redis);
        RedisProCacheProperties properties = properties();
        properties.getRedis().setMode("sentinel");
        properties.getRedis().setSentinelMaster("cache");
        properties.getRedis().setSentinelNodes(java.util.List.of(ip + ":26379", ip + ":26380", ip + ":26381"));
        Config config = new RedissonConfiguration().buildConfig(new DataRedisProperties(), properties);
        assertReadWriteAndLock(factory, config);
    }

    @Test
    void trustedTlsCertificateAllowsSpringReadsAndRedissonLocks() throws Exception {
        LettuceConnectionFactory factory = tlsFactory(true);
        Config config = tlsConfig();
        config.useSingleServer().setSslTruststore(CERTIFICATES.resolve("trust.p12").toUri().toURL())
                .setSslTruststorePassword("test-only");
        assertReadWriteAndLock(factory, config);
    }

    @Test
    void untrustedTlsCertificateIsRejectedByBothClients() {
        LettuceConnectionFactory factory = tlsFactory(false);
        try {
            factory.afterPropertiesSet();
            factory.start();
            assertThatThrownBy(() -> {
                try (var connection = factory.getConnection()) {
                    connection.ping();
                }
            }).hasStackTraceContaining("SSLHandshakeException");
        } finally {
            factory.destroy();
        }
        assertThatThrownBy(() -> Redisson.create(tlsConfig())).hasStackTraceContaining("SSLHandshakeException");
    }

    private static void assertReadWriteAndLock(LettuceConnectionFactory factory, Config config) {
        RedissonClient redisson = null;
        try {
            factory.afterPropertiesSet();
            factory.start();
            StringRedisTemplate template = new StringRedisTemplate(factory);
            template.opsForValue().set("topology", "value", Duration.ofSeconds(30));
            assertThat(template.opsForValue().get("topology")).isEqualTo("value");
            redisson = Redisson.create(config);
            var lock = redisson.getLock("topology-lock");
            lock.lock();
            try {
                assertThat(lock.isHeldByCurrentThread()).isTrue();
            } finally {
                lock.unlock();
            }
        } finally {
            if (redisson != null) {
                redisson.shutdown();
            }
            factory.destroy();
        }
    }

    private static LettuceConnectionFactory tlsFactory(boolean trusted) {
        var ssl = SslOptions.builder();
        if (trusted) {
            ssl.trustManager(CERTIFICATES.resolve("server.crt").toFile());
        }
        var client = LettuceClientConfiguration.builder().useSsl().and()
                .commandTimeout(Duration.ofSeconds(3))
                .clientOptions(ClientOptions.builder().sslOptions(ssl.build()).build()).build();
        return new LettuceConnectionFactory(new RedisStandaloneConfiguration(TLS.getHost(), TLS.getMappedPort(6379)), client);
    }

    private static Config tlsConfig() {
        RedisProCacheProperties properties = properties();
        properties.getRedis().setHost(TLS.getHost());
        properties.getRedis().setPort(TLS.getMappedPort(6379));
        properties.getRedis().setTlsEnabled(true);
        return new RedissonConfiguration().buildConfig(new DataRedisProperties(), properties);
    }

    private static RedisProCacheProperties properties() {
        var properties = new RedisProCacheProperties();
        properties.getRedisson().setConnectionPoolSize(2);
        properties.getRedisson().setConnectionMinimumIdleSize(1);
        properties.getRedisson().setConnectTimeout(3000);
        properties.getRedisson().setTimeout(3000);
        properties.getRedisson().setRetryAttempts(0);
        return properties;
    }

    private static Path certificates() {
        try {
            Path directory = Files.createTempDirectory("resicache-tls-");
            run("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1", "-subj", "/CN=localhost",
                    "-addext", "subjectAltName=DNS:localhost,IP:127.0.0.1", "-keyout", directory.resolve("server.key").toString(),
                    "-out", directory.resolve("server.crt").toString());
            run(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(), "-importcert", "-noprompt",
                    "-alias", "redis", "-file", directory.resolve("server.crt").toString(), "-keystore",
                    directory.resolve("trust.p12").toString(), "-storepass", "test-only", "-storetype", "PKCS12");
            return directory;
        } catch (Exception exception) {
            throw new IllegalStateException("TLS smoke requires openssl and JDK keytool", exception);
        }
    }

    private static void run(String... command) throws Exception {
        int status = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor();
        if (status != 0) {
            throw new IllegalStateException("Certificate generation failed");
        }
    }

    @AfterAll
    static void removeCertificates() throws Exception {
        try (var paths = Files.walk(CERTIFICATES)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static String sentinelCommand() {
        return "set -eu; IP=$(hostname -i | awk '{print $1}'); "
                + "redis-server --port 6379 --bind 0.0.0.0 --protected-mode no --save '' --daemonize yes; "
                + "redis-server --port 6380 --bind 0.0.0.0 --protected-mode no --save '' "
                + "--replicaof $IP 6379 --daemonize yes; "
                + "for PORT in 26379 26380 26381; do "
                + "printf 'port %s\\nbind 0.0.0.0\\nprotected-mode no\\nsentinel monitor cache %s 6379 2\\n' "
                + "$PORT $IP > /tmp/sentinel-$PORT.conf; "
                + "redis-server /tmp/sentinel-$PORT.conf --sentinel --daemonize yes; done; "
                + "until redis-cli -p 26379 sentinel get-master-addr-by-name cache | grep -q 6379; do sleep 0.1; done; "
                + "echo SENTINEL_READY; tail -f /dev/null";
    }
}
