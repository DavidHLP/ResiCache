package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.redisson.config.Config;
import org.redisson.misc.RedisURI;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Differential compatibility probes for operator YAML accepted by Redisson 3.50.
 * Run the same assertions with both dependency versions; failures are migration
 * blockers, not expected exceptions to suppress in the new version.
 */
class RedissonYamlCompatibilityTest {

    @TempDir
    Path directory;

    @Test
    void simpleYamlRetainsDatabaseAndLiteralValues() throws Exception {
        Config config = load("""
                singleServerConfig:
                  address: "redis://localhost:6379"
                  database: 3
                  clientName: "org.redisson.api.DefaultNameMapper"
                """);
        assertThat(config.useSingleServer().getDatabase()).isEqualTo(3);
        assertThat(config.useSingleServer().getClientName())
                .isEqualTo("org.redisson.api.DefaultNameMapper");
    }

    @Test
    void legacyRetryIntervalRetainsActualConstantDelay() throws Exception {
        Config config = load("""
                singleServerConfig:
                  address: "redis://localhost:6379"
                  retryInterval: 2468
                """);
        assertThat(config.useSingleServer().getRetryDelay().calcDelay(1))
                .isEqualTo(Duration.ofMillis(2468));
        assertThat(config.useSingleServer().getRetryDelay().calcDelay(5))
                .isEqualTo(Duration.ofMillis(2468));
    }

    @Test
    void legacyDefaultNameMapperRetainsIdentityBehavior() throws Exception {
        Config config = load("""
                singleServerConfig:
                  address: "redis://localhost:6379"
                  nameMapper: !<org.redisson.api.DefaultNameMapper> {}
                """);
        assertThat(config.useSingleServer().getNameMapper().map("tenant:orders"))
                .isEqualTo("tenant:orders");
        assertThat(config.useSingleServer().getNameMapper().unmap("tenant:orders"))
                .isEqualTo("tenant:orders");
    }

    @Test
    void legacyHostNatMapperRetainsHostMapping() throws Exception {
        Config config = load("""
                clusterServersConfig:
                  nodeAddresses: ["redis://internal.example:6379"]
                  natMapper: !<org.redisson.api.HostNatMapper>
                    hostsMap:
                      internal.example: external.example
                """);
        RedisURI mapped = config.useClusterServers().getNatMapper()
                .map(new RedisURI("redis://internal.example:6379"));
        assertThat(mapped.getHost()).isEqualTo("external.example");
        assertThat(mapped.getPort()).isEqualTo(6379);
    }

    @Test
    void legacyHostPortNatMapperRetainsHostAndPortMapping() throws Exception {
        Config config = load("""
                clusterServersConfig:
                  nodeAddresses: ["redis://internal.example:6379"]
                  natMapper: !<org.redisson.api.HostPortNatMapper>
                    hostsPortMap:
                      "internal.example:6379": "external.example:6380"
                """);
        RedisURI mapped = config.useClusterServers().getNatMapper()
                .map(new RedisURI("redis://internal.example:6379"));
        assertThat(mapped.getHost()).isEqualTo("external.example");
        assertThat(mapped.getPort()).isEqualTo(6380);
    }

    @Test
    void legacySnappyCodecLoadsWithoutSubstitution() throws Exception {
        Config config = load("""
                codec: !<org.redisson.codec.SnappyCodec> {}
                singleServerConfig:
                  address: "redis://localhost:6379"
                """);
        assertThat(config.getCodec().getClass().getName())
                .isEqualTo("org.redisson.codec.SnappyCodec");
    }

    private Config load(String yaml) throws Exception {
        Path file = directory.resolve("redisson.yml");
        Files.writeString(file, yaml);
        RedisProCacheProperties properties = new RedisProCacheProperties();
        properties.getRedis().setRedissonConfigPath(file.toString());
        return new RedissonConfiguration().buildConfig(new DataRedisProperties(), properties);
    }
}
