package io.github.davidhlp.spring.cache.redis.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;

import java.io.File;
import java.io.FileWriter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RedissonConfiguration 单元测试
 * 测试 RedissonClient 的不同部署模式配置逻辑
 *
 * 注意：这些测试验证配置构建逻辑，不验证实际 Redis 连接
 */
@DisplayName("RedissonConfiguration Tests")
class RedissonConfigurationTest {

    private RedissonConfiguration configuration;
    private RedisProperties redisProperties;
    private RedisProCacheProperties properties;

    @BeforeEach
    void setUp() {
        configuration = new RedissonConfiguration();
        redisProperties = new RedisProperties();
        redisProperties.setHost("localhost");
        redisProperties.setPort(6379);
        redisProperties.setDatabase(0);
        properties = new RedisProCacheProperties();
    }

    @Nested
    @DisplayName("单节点模式 (single)")
    class SingleModeTests {

        @Test
        @DisplayName("使用 ResiCache 属性中的 host 和 port")
        void singleMode_usesResiCacheHostAndPort() {
            properties.getRedis().setHost("redis.example.com");
            properties.getRedis().setPort(6380);

            Config config = configuration.buildConfig(redisProperties, properties);

            assertThat(config.useSingleServer().getAddress())
                    .isEqualTo("redis://redis.example.com:6380");
        }

        @Test
        @DisplayName("当 ResiCache 属性未设置时，回退到 Spring RedisProperties")
        void singleMode_fallsBackToRedisProperties() {
            // 显式设置 ResiCache 属性为 null/0，触发回退逻辑
            properties.getRedis().setHost(null);
            properties.getRedis().setPort(0);
            redisProperties.setHost("spring-redis.example.com");
            redisProperties.setPort(7000);

            Config config = configuration.buildConfig(redisProperties, properties);

            assertThat(config.useSingleServer().getAddress())
                    .isEqualTo("redis://spring-redis.example.com:7000");
        }

        @Test
        @DisplayName("设置 database 索引")
        void singleMode_setsDatabase() {
            properties.getRedis().setDatabase(5);

            Config config = configuration.buildConfig(redisProperties, properties);

            assertThat(config.useSingleServer().getDatabase()).isEqualTo(5);
        }

        @Test
        @DisplayName("设置密码")
        void singleMode_setsPassword() {
            properties.getRedis().setPassword("secret");

            Config config = configuration.buildConfig(redisProperties, properties);

            assertThat(config.useSingleServer().getPassword()).isEqualTo("secret");
        }

        @Test
        @DisplayName("设置用户名")
        void singleMode_setsUsername() {
            properties.getRedis().setUsername("admin");

            Config config = configuration.buildConfig(redisProperties, properties);

            assertThat(config.useSingleServer().getUsername()).isEqualTo("admin");
        }
    }

    @Nested
    @DisplayName("集群模式 (cluster)")
    class ClusterModeTests {

        @Test
        @DisplayName("配置集群节点地址")
        void clusterMode_configuresNodeAddresses() {
            properties.getRedis().setMode("cluster");
            properties.getRedis().setClusterNodes(List.of(
                    "node1.example.com:6379",
                    "node2.example.com:6379",
                    "node3.example.com:6379"
            ));

            Config config = configuration.buildConfig(redisProperties, properties);

            assertThat(config.useClusterServers().getNodeAddresses()).hasSize(3);
        }

        @Test
        @DisplayName("当 cluster-nodes 为空时抛出异常")
        void clusterMode_emptyNodes_throwsException() {
            properties.getRedis().setMode("cluster");
            properties.getRedis().setClusterNodes(List.of());

            assertThatThrownBy(() -> configuration.redissonClient(redisProperties, properties))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cluster-nodes must not be empty");
        }

        @Test
        @DisplayName("设置密码")
        void clusterMode_setsPassword() {
            properties.getRedis().setMode("cluster");
            properties.getRedis().setPassword("cluster-secret");
            properties.getRedis().setClusterNodes(List.of("node1:6379"));

            Config config = configuration.buildConfig(redisProperties, properties);

            assertThat(config.useClusterServers().getPassword()).isEqualTo("cluster-secret");
        }
    }

    @Nested
    @DisplayName("哨兵模式 (sentinel)")
    class SentinelModeTests {

        @Test
        @DisplayName("配置哨兵主节点和哨兵节点")
        void sentinelMode_configuresMasterAndSentinels() {
            properties.getRedis().setMode("sentinel");
            properties.getRedis().setSentinelMaster("mymaster");
            properties.getRedis().setSentinelNodes(List.of(
                    "sentinel1.example.com:26379",
                    "sentinel2.example.com:26379"
            ));

            Config config = configuration.buildConfig(redisProperties, properties);

            assertThat(config.useSentinelServers().getMasterName()).isEqualTo("mymaster");
            assertThat(config.useSentinelServers().getSentinelAddresses()).hasSize(2);
        }

        @Test
        @DisplayName("当 sentinel-master 为空时抛出异常")
        void sentinelMode_emptyMaster_throwsException() {
            properties.getRedis().setMode("sentinel");
            properties.getRedis().setSentinelNodes(List.of("sentinel:26379"));

            assertThatThrownBy(() -> configuration.redissonClient(redisProperties, properties))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("sentinel-master must not be empty");
        }

        @Test
        @DisplayName("当 sentinel-nodes 为空时抛出异常")
        void sentinelMode_emptyNodes_throwsException() {
            properties.getRedis().setMode("sentinel");
            properties.getRedis().setSentinelMaster("mymaster");
            properties.getRedis().setSentinelNodes(List.of());

            assertThatThrownBy(() -> configuration.redissonClient(redisProperties, properties))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("sentinel-nodes must not be empty");
        }
    }

    @Nested
    @DisplayName("TLS 配置")
    class TlsTests {

        @Test
        @DisplayName("启用 TLS 时使用 rediss:// 协议")
        void tlsEnabled_usesRedissScheme() {
            properties.getRedis().setTlsEnabled(true);
            properties.getRedis().setHost("secure.redis.example.com");

            Config config = configuration.buildConfig(redisProperties, properties);

            assertThat(config.useSingleServer().getAddress())
                    .startsWith("rediss://");
        }

        @Test
        @DisplayName("集群模式启用 TLS")
        void tlsEnabled_clusterMode_usesRedissScheme() {
            properties.getRedis().setMode("cluster");
            properties.getRedis().setTlsEnabled(true);
            properties.getRedis().setClusterNodes(List.of("node1:6379"));

            Config config = configuration.buildConfig(redisProperties, properties);

            assertThat(config.useClusterServers().getNodeAddresses().get(0).toString())
                    .startsWith("rediss://");
        }
    }

    @Nested
    @DisplayName("Redisson YAML 配置文件")
    class RedissonConfigPathTests {

        @Test
        @DisplayName("验证 YAML 配置文件路径被正确解析")
        void redissonConfigPath_validYaml_loadsConfig(@TempDir File tempDir) throws Exception {
            // 创建临时 YAML 配置文件
            File yamlFile = new File(tempDir, "redisson-config.yml");
            try (FileWriter writer = new FileWriter(yamlFile)) {
                writer.write("""
                    singleServerConfig:
                      address: "redis://localhost:6379"
                      database: 3
                    """);
            }

            properties.getRedis().setRedissonConfigPath(yamlFile.getAbsolutePath());

            // 经由真实生产方法 buildConfig 读取 YAML(覆盖 redissonConfigPath 安全敏感分支)
            Config loadedConfig = configuration.buildConfig(redisProperties, properties);
            assertThat(loadedConfig.useSingleServer().getDatabase()).isEqualTo(3);
        }
    }

}