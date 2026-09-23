package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.chain.model.CachePolicyView;
import io.github.davidhlp.spring.cache.redis.chain.model.TtlDecision;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * TTL 优先级的唯一实现 —— 把两个真实输入解析为 {@link TtlDecision}。
 *
 * <p>输入与优先级(自上而下,第一条命中即胜出):
 * <ol>
 *   <li>注解:方法级 {@link CachePolicyView#ttl()} 秒数 &gt; 0 时使用注解秒数,
 *       并按 {@code randomTtl}/{@code variance} 抖动。注解属性未设置时其值为 {@code 0},
 *       不构成声明 —— 注解是唯一能压过配置默认值的声明面;</li>
 *   <li>参数:{@link Duration} 非空、非零、非负时使用其秒数。写路径的这个 Duration
 *       由 Spring Data Redis 依 cache 级配置算出并传入({@code resi-cache.default-ttl},
 *       默认 30 分钟;{@code caches.*.ttl} 可覆盖),因此"配置的默认 TTL"是唯一的
 *       隐式默认值,只在方法级 TTL 未声明时才生效;</li>
 *   <li>其余情况(参数为零、为负、或为 {@code null})→ 永久缓存
 *       ({@link TtlDecision#skipped()})。与 Spring Data Redis 同义:
 *       {@code DefaultRedisCacheWriter.shouldExpireWithin} 把 null、零、负一样视为
 *       "无过期",故三者不再各自表述。</li>
 * </ol>
 *
 * <p><b>已裁决的规则(此处为唯一陈述处):</b>注解 {@code ttl} 属性未设置时不再有
 * 隐式 60 秒默认值,该方法的条目落回 cache 级 {@code resi-cache.default-ttl}
 * (默认 30 分钟)。配置默认值是唯一的隐式默认值;注解是唯一能覆盖它的声明。
 */
final class TtlPolicy {

    /** TTL 来源 —— 与写链的三条 debug 日志一一对应。 */
    enum Source {
        /** 方法级注解策略({@link CachePolicyView#ttl()} &gt; 0)。 */
        ANNOTATION,
        /** {@link Duration} 参数(cache 级 {@code resi-cache.default-ttl} 的 Spring 计算值)。 */
        PARAMETER,
        /** 不应用 TTL(永久缓存)。 */
        NONE
    }

    /**
     * 解析结果。
     *
     * @param decision        写入 {@code CacheContext} 的 TTL 决策
     * @param source          胜出的来源
     * @param jitterRequested 来源为注解且 {@code randomTtl=true};保持既有
     *                        {@code resicache.handler.ttl.jittered} 计数语义(与 variance
     *                        是否真的展开抖动无关)
     */
    record Resolution(TtlDecision decision, Source source, boolean jitterRequested) {
    }

    private TtlPolicy() {
    }

    /**
     * 解析 TTL。
     *
     * @param parameterTtl 调用方 TTL(Duration);{@code null} 与零、负同为"无过期"语义
     * @param policy       方法级注解策略视图;{@link CachePolicyView#NONE} 表示无方法级声明
     * @return 决策与来源
     */
    static Resolution resolve(Duration parameterTtl, CachePolicyView policy) {
        long annotationTtlSeconds = policy.ttl();
        if (annotationTtlSeconds > 0) {
            long finalTtl =
                    calculateFinalTtl(annotationTtlSeconds, policy.randomTtl(), policy.variance());
            return new Resolution(
                    TtlDecision.applied(finalTtl), Source.ANNOTATION, policy.randomTtl());
        }
        if (applicable(parameterTtl)) {
            return new Resolution(
                    TtlDecision.applied(parameterTtl.getSeconds()), Source.PARAMETER, false);
        }
        return new Resolution(TtlDecision.skipped(), Source.NONE, false);
    }

    /** {@link Duration} 非空、非零、非负则可作为参数 TTL 应用。 */
    private static boolean applicable(Duration parameterTtl) {
        return parameterTtl != null && !parameterTtl.isZero() && !parameterTtl.isNegative();
    }

    /** 计算最终 TTL;randomTtl=true 时按 variance 抖动以防雪崩(仅注解路径调用)。 */
    static long calculateFinalTtl(Long baseTtl, boolean randomTtl, float variance) {
        if (baseTtl == null || baseTtl <= 0) {
            return -1;
        }
        if (!randomTtl || variance <= 0) {
            return baseTtl;
        }

        float boundedVariance = Math.min(1.0f, Math.max(0.0f, variance));
        double randomFactor = ThreadLocalRandom.current().nextGaussian();
        randomFactor = Math.max(-3.0, Math.min(3.0, randomFactor));

        long offset = (long) (baseTtl * boundedVariance * randomFactor / 3.0);
        long result = baseTtl + offset;
        return Math.max(1, Math.min(result, baseTtl * 2));
    }
}
