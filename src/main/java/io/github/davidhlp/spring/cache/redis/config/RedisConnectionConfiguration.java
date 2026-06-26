package io.github.davidhlp.spring.cache.redis.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.BaseMasterSlaveServersConfig;
import org.redisson.config.ClusterServersConfig;
import org.redisson.config.Config;
import org.redisson.config.SentinelServersConfig;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.github.davidhlp.spring.cache.redis.serialization.SecureJacksonRedisSerializer;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.io.File;
import java.io.IOException;

/** Redis连接和模板配置 负责： 1. RedisTemplate配置和序列化策略 2. RedissonClient连接配置 3. 连接池参数优化 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class RedisConnectionConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "redisCacheTemplate")
    public RedisTemplate<String, Object> redisCacheTemplate(
            RedisConnectionFactory redisConnectionFactory, ObjectMapper objectMapper) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);

        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        SecureJacksonRedisSerializer jsonSerializer =
                new SecureJacksonRedisSerializer(objectMapper);

        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.setDefaultSerializer(jsonSerializer);
        template.setEnableDefaultSerializer(true);
        // Timeout is configured via spring.data.redis.timeout in application.yml
        template.afterPropertiesSet();

        log.debug(
                "Created RedisCacheTemplate with StringRedisSerializer for keys and SecureJacksonRedisSerializer for values");
        return template;
    }

    @Bean
    @ConditionalOnMissingBean
    public HashOperations<String, String, String> hashOperations(
            RedisTemplate<String, Object> redisTemplate) {
        return redisTemplate.opsForHash();
    }

    @Bean
    @ConditionalOnMissingBean
    public ValueOperations<String, Object> valueOperations(
            RedisTemplate<String, Object> redisTemplate) {
        return redisTemplate.opsForValue();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(RedissonClient.class)
    public RedissonClient redissonClient(RedisProperties redisProperties, RedisProCacheProperties properties)
            throws IOException {

        RedisProCacheProperties.RedisDeploymentProperties redis = properties.getRedis();

        // Advanced override: user-provided Redisson YAML config.
        // 安全须知：该文件路径必须仅由可信的运维/部署配置源提供（如 application.yml、环境变量、
        // 配置中心），严禁接受终端用户输入。Config.fromYAML 会读取并解析任意路径下的 YAML 文件，
        // 若路径可被外部控制，将导致任意本地文件读取风险。
        if (redis.getRedissonConfigPath() != null && !redis.getRedissonConfigPath().isBlank()) {
            log.info("Loading Redisson configuration from: {}", redis.getRedissonConfigPath());
            return Redisson.create(Config.fromYAML(new File(redis.getRedissonConfigPath())));
        }

        Config config = new Config();
        String scheme = redis.isTlsEnabled() ? "rediss://" : "redis://";

        switch (redis.getMode()) {
            case "cluster" -> configureCluster(config, redis, properties, scheme);
            case "sentinel" -> configureSentinel(config, redis, properties, scheme);
            default -> configureSingle(config, redis, redisProperties, properties, scheme);
        }

        log.debug("Created RedissonClient with mode: {}", redis.getMode());
        return Redisson.create(config);
    }

    private void configureCluster(
            Config config,
            RedisProCacheProperties.RedisDeploymentProperties redis,
            RedisProCacheProperties properties,
            String scheme) {

        if (redis.getClusterNodes().isEmpty()) {
            throw new IllegalStateException("cluster-nodes must not be empty when mode=cluster");
        }

        String[] nodes = redis.getClusterNodes().stream()
                .map(n -> scheme + n)
                .toArray(String[]::new);

        ClusterServersConfig clusterConfig = config.useClusterServers().addNodeAddress(nodes);
        applyCommonSettings(clusterConfig, redis, properties);
        log.debug("Configured Redisson for cluster mode with {} nodes", nodes.length);
    }

    private void configureSentinel(
            Config config,
            RedisProCacheProperties.RedisDeploymentProperties redis,
            RedisProCacheProperties properties,
            String scheme) {

        if (redis.getSentinelMaster() == null || redis.getSentinelMaster().isBlank()) {
            throw new IllegalStateException("sentinel-master must not be empty when mode=sentinel");
        }
        if (redis.getSentinelNodes().isEmpty()) {
            throw new IllegalStateException("sentinel-nodes must not be empty when mode=sentinel");
        }

        String[] sentinels = redis.getSentinelNodes().stream()
                .map(n -> scheme + n)
                .toArray(String[]::new);

        SentinelServersConfig sentinelConfig = config.useSentinelServers()
                .setMasterName(redis.getSentinelMaster())
                .addSentinelAddress(sentinels);

        applyCommonSettings(sentinelConfig, redis, properties);
        log.debug("Configured Redisson for sentinel mode with master: {}", redis.getSentinelMaster());
    }

    private void configureSingle(
            Config config,
            RedisProCacheProperties.RedisDeploymentProperties redis,
            RedisProperties redisProperties,
            RedisProCacheProperties properties,
            String scheme) {

        // Fallback to Spring's RedisProperties if not set in ResiCache properties
        String host = redis.getHost() != null && !redis.getHost().isBlank()
                ? redis.getHost()
                : redisProperties.getHost();
        int port = redis.getPort() != 0 ? redis.getPort() : redisProperties.getPort();
        int database = redis.getDatabase() != 0 ? redis.getDatabase() : redisProperties.getDatabase();

        String address = scheme + host + ":" + port;

        RedisProCacheProperties.RedissonProperties pool = properties.getRedisson();
        SingleServerConfig singleConfig = config.useSingleServer()
                .setAddress(address)
                .setDatabase(database)
                .setConnectionPoolSize(pool.getConnectionPoolSize())
                .setConnectionMinimumIdleSize(pool.getConnectionMinimumIdleSize())
                .setIdleConnectionTimeout(pool.getIdleConnectionTimeout())
                .setConnectTimeout(pool.getConnectTimeout())
                .setTimeout(pool.getTimeout())
                .setRetryAttempts(pool.getRetryAttempts())
                .setRetryInterval(pool.getRetryInterval());

        // Apply password from ResiCache properties, fallback to Spring's RedisProperties
        String password = redis.getPassword() != null && !redis.getPassword().isEmpty()
                ? redis.getPassword()
                : redisProperties.getPassword();
        if (password != null && !password.isEmpty()) {
            singleConfig.setPassword(password);
        }
        if (redis.getUsername() != null && !redis.getUsername().isEmpty()) {
            singleConfig.setUsername(redis.getUsername());
        }

        log.debug("Configured Redisson for single server mode: {}", address);
    }

    private void applyCommonSettings(
            BaseMasterSlaveServersConfig<?> serverConfig,
            RedisProCacheProperties.RedisDeploymentProperties redis,
            RedisProCacheProperties properties) {

        RedisProCacheProperties.RedissonProperties pool = properties.getRedisson();
        // For cluster/sentinel, use master/slave connection pool settings
        serverConfig
                .setMasterConnectionPoolSize(pool.getConnectionPoolSize())
                .setSlaveConnectionPoolSize(pool.getConnectionPoolSize())
                .setMasterConnectionMinimumIdleSize(pool.getConnectionMinimumIdleSize())
                .setSlaveConnectionMinimumIdleSize(pool.getConnectionMinimumIdleSize())
                .setIdleConnectionTimeout(pool.getIdleConnectionTimeout())
                .setConnectTimeout(pool.getConnectTimeout())
                .setTimeout(pool.getTimeout())
                .setRetryAttempts(pool.getRetryAttempts())
                .setRetryInterval(pool.getRetryInterval());

        // Username/password for ACL (Redis 6+)
        if (redis.getPassword() != null && !redis.getPassword().isEmpty()) {
            serverConfig.setPassword(redis.getPassword());
        }
        if (redis.getUsername() != null && !redis.getUsername().isEmpty()) {
            serverConfig.setUsername(redis.getUsername());
        }
    }
}
