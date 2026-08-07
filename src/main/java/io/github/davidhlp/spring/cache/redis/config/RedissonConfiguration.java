package io.github.davidhlp.spring.cache.redis.config;

import lombok.extern.slf4j.Slf4j;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.BaseConfig;
import org.redisson.config.BaseMasterSlaveServersConfig;
import org.redisson.config.ClusterServersConfig;
import org.redisson.config.Config;
import org.redisson.config.SentinelServersConfig;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.io.IOException;

/**
 * Redisson 自动配置,独立于 {@link RedisConnectionConfiguration}.
 *
 * <p><b>为什么独立成类:</b> Spring 的 {@code @ConditionalOnClass} 只有在标注于
 * 独立的 {@code @Configuration} 类(类级别)时,才会在类加载/解析阶段可靠生效。
 * {@link RedisConnectionConfiguration} 的 import 与方法签名若直接引用 Redisson
 * 强类型,在 Redisson 不在 classpath 时会触发 {@code NoClassDefFoundError}。
 *
 * <p>将 Redisson 相关代码隔离到本类、并在<b>类级别</b>加
 * {@code @ConditionalOnClass(RedissonClient.class)},可确保 Redisson 缺失时
 * Spring 用 ASM 读取注解后直接跳过本类——整个类不被加载,从而消除强引用风险。
 * 这使 Redisson 成为真正的可选依赖(仅 {@code sync=true} 防击穿需要它;
 * 缺失时 {@code SyncSupport} 优雅降级为 JVM 内锁)。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(RedissonClient.class)
public class RedissonConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RedissonClient redissonClient(
            DataRedisProperties redisProperties, RedisProCacheProperties properties) {
        return Redisson.create(buildConfig(redisProperties, properties));
    }

    /**
     * 构建 Redisson Config(包级可见,便于单测覆盖真实配置逻辑)。
     *
     * <p>从 {@link #redissonClient(DataRedisProperties, RedisProCacheProperties)} 抽取,使单测
     * 可直接断言 Config 字段(address/password/database/pool),覆盖 configureSingle 的
     * pool 调优与 password/database fallback,而非手写复制品。
     */
    Config buildConfig(DataRedisProperties redisProperties, RedisProCacheProperties properties) {

        RedisProCacheProperties.RedisDeploymentProperties redis = properties.getRedis();

        // Advanced override: user-provided Redisson YAML config.
        // 安全须知:该文件路径必须仅由可信的运维/部署配置源提供(如 application.yml、环境变量、
        // 配置中心),严禁接受终端用户输入。Config.fromYAML 会读取并解析任意路径下的 YAML 文件,
        // 若路径可被外部控制,将导致任意本地文件读取风险。
        if (redis.getRedissonConfigPath() != null && !redis.getRedissonConfigPath().isBlank()) {
            // 安全:异常 message 不含完整绝对路径(可能含用户名/敏感目录),避免向上传播到
            // 用户可见的堆栈/响应时泄漏。完整路径仅在下方 log.info 输出(运维预期日志,
            // 且依 SECURITY.md 该路径必须来自可信配置源)。
            log.info("Loading Redisson configuration from: {}", redis.getRedissonConfigPath());
            try {
                return Config.fromYAML(new File(redis.getRedissonConfigPath()));
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Failed to load Redisson config (see startup log for the configured path)", e);
            }
        }

        Config config = new Config();
        String scheme = redis.isTlsEnabled() ? "rediss://" : "redis://";

        switch (redis.getMode()) {
            case "cluster" -> configureCluster(config, redis, properties, scheme);
            case "sentinel" -> configureSentinel(config, redis, properties, scheme);
            default -> configureSingle(config, redis, redisProperties, properties, scheme);
        }

        log.debug("Created RedissonClient with mode: {}", redis.getMode());
        return config;
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
            DataRedisProperties redisProperties,
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
                .setConnectionMinimumIdleSize(pool.getConnectionMinimumIdleSize());
        // timeout/retry 5 setter 与 cluster/sentinel 共享 BaseConfig 基类,抽离收敛 2-site 样板
        applyTimeoutAndRetrySettings(singleConfig, pool);

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
                .setSlaveConnectionMinimumIdleSize(pool.getConnectionMinimumIdleSize());
        // timeout/retry 5 setter 与 single 模式共享 BaseConfig 基类,抽离收敛 2-site 样板
        applyTimeoutAndRetrySettings(serverConfig, pool);

        // Username/password for ACL (Redis 6+)
        if (redis.getPassword() != null && !redis.getPassword().isEmpty()) {
            serverConfig.setPassword(redis.getPassword());
        }
        if (redis.getUsername() != null && !redis.getUsername().isEmpty()) {
            serverConfig.setUsername(redis.getUsername());
        }
    }

    /**
     * 应用 5 个跨部署模式共享的 timeout/retry 设置到任意 Redisson 服务器配置。
     *
     * <p>这 5 个 setter —— {@code setIdleConnectionTimeout} / {@code setConnectTimeout} /
     * {@code setTimeout} / {@code setRetryAttempts} / {@code setRetryInterval} —— 定义在
     * {@link BaseConfig} 上,被 {@link SingleServerConfig} 与
     * {@link BaseMasterSlaveServersConfig} 共同继承,因此可跨 single 与 cluster/sentinel
     * 模式复用,避免 {@code configureSingle} 与 {@code applyCommonSettings} 各写一遍的
     * 2-site 样板。
     *
     * <p><b>不在本 helper 内</b>:pool size / database / address —— 它们的 setter 名在
     * SingleServer({@code setConnectionPoolSize})与 MasterSlave
     * ({@code setMasterConnectionPoolSize}/{@code setSlaveConnectionPoolSize})上不同,
     * 是各自子类 API 独有,无法跨 {@link BaseConfig} 基类收敛;password / username 在
     * {@code configureSingle} 内有 ResiCache → Spring {@code RedisProperties} fallback 链,
     * 与 {@code applyCommonSettings} 的直取语义不同,亦不合并。
     *
     * <p><b>顺序无关</b>:5 个 setter 之间无顺序依赖。
     *
     * @param config 任意 Redisson {@link BaseConfig} 子类(Single / Cluster / Sentinel)
     * @param pool   ResiCache Redisson 连接池/超时属性
     */
    private static void applyTimeoutAndRetrySettings(
            BaseConfig<?> config, RedisProCacheProperties.RedissonProperties pool) {
        config.setIdleConnectionTimeout(pool.getIdleConnectionTimeout());
        config.setConnectTimeout(pool.getConnectTimeout());
        config.setTimeout(pool.getTimeout());
        config.setRetryAttempts(pool.getRetryAttempts());
        config.setRetryInterval(pool.getRetryInterval());
    }
}
