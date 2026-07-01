package io.github.davidhlp.spring.cache.redis.protection.refresh;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * 默认提前过期判定策略,利用可注入的时钟以提高可测试性。
 *
 * <p>实现 {@link EarlyExpirationPolicy} seam;自定义实现声明 {@code @Bean} 可顶替(对齐
 * {@code DefaultNullValuePolicy} / {@code DefaultTtlPolicy} 纪律,落实 ADR-0005)。
 *
 * <p>ADR-0025:逻辑自 {@code DefaultTtlPolicy.shouldEarlyExpiration} 原样迁入(byte-for-byte 等价),
 * 仅改名 {@code shouldRefresh} 以对齐全域 ubiquitous language({@code needsRefresh} /
 * {@code scheduleAsyncRefresh} / {@code RefreshRetryPolicy})。{@code Clock} 字段随之回归 refresh 域。
 */
@Component
@RequiredArgsConstructor
public class DefaultEarlyExpirationPolicy implements EarlyExpirationPolicy {

    private final Clock clock;

    /**
     * 判断是否应该提前刷新缓存项
     *
     * @param createdTime 创建时间戳(毫秒)
     * @param ttlSeconds TTL 时间(秒)
     * @param threshold 提前过期阈值
     * @return 如果应该提前刷新返回 true,否则返回 false
     */
    @Override
    public boolean shouldRefresh(long createdTime, long ttlSeconds, double threshold) {
        if (ttlSeconds <= 0 || threshold <= 0 || threshold >= 1) {
            return false;
        }

        long elapsedTime = clock.millis() - createdTime;
        long totalTime = ttlSeconds * 1000;
        double usedRatio = (double) elapsedTime / totalTime;
        return usedRatio >= (1 - threshold);
    }
}
