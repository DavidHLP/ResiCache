package io.github.davidhlp.spring.cache.redis.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties.SerializerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Component;

/**
 * 启动期序列化 pre-flight 探测(guide §115:"v0.0.3 对 v0.2.0 migration tool 的 down-payment")。
 *
 * <p>ApplicationReady 时(opt-in via {@code resi-cache.serializer.probe-enabled})采样 N 个 Redis key,
 * 检测其值是否为 ResiCache 的 {@code VersionEnvelope {version,payload}} 格式。若发现非 envelope 值
 * (如 Spring 原生 {@code GenericJackson2JsonRedisSerializer} / {@code JdkSerializer} 的遗留数据),发
 * prominent WARN 提示存量缓存将在接入后全量 miss,链 ADR-0003 + v0.2.0 迁移工具。
 *
 * <p>设计:诊断工具,非迁移工具 —— 不松 envelope(ADR-0003),尊重减法纪律。仅 WARN,不阻塞启动。
 * 默认关闭(扫描 Redis 是启动副作用);{@link ObjectProvider} 允许无 Redis 环境静默跳过。
 */
@Slf4j
@Component
public class SerializationPreFlightProbe {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ObjectProvider<RedisConnectionFactory> connectionFactoryProvider;
    private final RedisProCacheProperties properties;

    public SerializationPreFlightProbe(
            ObjectProvider<RedisConnectionFactory> connectionFactoryProvider,
            RedisProCacheProperties properties) {
        this.connectionFactoryProvider = connectionFactoryProvider;
        this.properties = properties;
    }

    /**
     * ApplicationReady 时若 probe-enabled 则采样扫描。@EventListener 由 Spring 生命周期触发;
     * 集成测试可直接调 {@link #scanAndReport()} 避开事件 firing 复杂度(镜像 R15 shouldWarn() 范式)。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!properties.getSerializer().isProbeEnabled()) {
            return;
        }
        scanAndReport();
    }

    /**
     * 采样 Redis keys,检测非 envelope 值,若发现则 WARN。package-private 便于测试直接调用。
     */
    void scanAndReport() {
        RedisConnectionFactory factory = connectionFactoryProvider.getIfAvailable();
        if (factory == null) {
            return;
        }
        SerializerProperties ser = properties.getSerializer();
        int sampleSize = Math.max(1, ser.getProbeSampleSize());
        int scanned = 0;
        int nonEnvelope = 0;
        ScanOptions options = ScanOptions.scanOptions().count(sampleSize).build();
        try (RedisConnection connection = factory.getConnection()) {
            try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
                while (cursor.hasNext() && scanned < sampleSize) {
                    byte[] key = cursor.next();
                    scanned++;
                    byte[] value = connection.stringCommands().get(key);
                    if (value == null || value.length == 0) {
                        continue;
                    }
                    if (!isEnvelope(value)) {
                        nonEnvelope++;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[ResiCache] Serialization pre-flight probe failed to scan Redis (non-fatal): {}",
                    e.getMessage());
            return;
        }
        if (nonEnvelope > 0) {
            log.warn(
                "[ResiCache] Serialization pre-flight probe found {}/{} sampled Redis keys whose values are NOT "
                    + "in ResiCache's {{version,payload}} envelope format (ADR-0003). These will fail to deserialize "
                    + "and miss on cutover. Migrate legacy caches before relying on them; a shadow→dual-write→cutover "
                    + "tool is planned for v0.2.0. (sample-size={}, set resi-cache.serializer.probe-enabled=false "
                    + "to silence)",
                    nonEnvelope, scanned, sampleSize);
        }
    }

    /**
     * 检测 bytes 是否为 ResiCache envelope(JSON object 含 {@code version} + {@code payload} 字段)。
     * 非 JSON(如 JDK 序列化)或缺少这两个字段的 JSON 视为非 envelope(遗留/外来格式)。
     */
    static boolean isEnvelope(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return false;
        }
        try {
            JsonNode node = JSON.readTree(bytes);
            return node != null && node.isObject() && node.has("version") && node.has("payload");
        } catch (Exception e) {
            return false;
        }
    }
}
