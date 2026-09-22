package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.chain.model.CachePolicyView;
import io.github.davidhlp.spring.cache.redis.chain.model.TtlDecision;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * TTL 优先级的唯一实现 —— 把两个真实输入解析为 {@link TtlDecision}。
 *
 * <p>输入与优先级(自上而下,第一条命中即胜出;与历史行为逐条一致):
 * <ol>
 *   <li>注解:方法级 {@link CachePolicyView#ttl()} 秒数 &gt; 0 时使用注解秒数,
 *       并按 {@code randomTtl}/{@code variance} 抖动;</li>
 *   <li>参数:{@link Duration} 非空、非零、非负时使用其秒数。写路径的这个 Duration
 *       由 Spring Data Redis 依 cache 级配置算出并传入({@code resi-cache.default-ttl},
 *       默认 30 分钟;{@code caches.*.ttl} 可覆盖),因此"配置的默认 TTL"只在方法级
 *       TTL 为 0 或不存在时才生效;</li>
 *   <li>参数为 {@code null}(无 TTL 上下文)时使用 {@link #DEFAULT_TTL_SECONDS};</li>
 *   <li>参数为零或负 → 永久缓存({@link TtlDecision#skipped()})。</li>
 * </ol>
 *
 * <p><b>已知分歧(产品决策待定,只在此处陈述,勿在第二处重复):</b>注解声明侧的 60 秒
 * ({@code @RedisCacheable}/{@code @RedisCachePut} 的 {@code ttl} 属性默认值,以及 Spring
 * {@code @CachePut} 适配路径的 {@link RedisCachePutOperation} builder 默认值)会覆盖 cache 级
 * {@code resi-cache.default-ttl}(默认 30 分钟)。评审判定"60 秒还是 30 分钟应胜出"属于产品
 * 问题且尚无裁决,故两条默认值均按现状保留。
 */
final class TtlPolicy {

    /**
     * 注解与参数都不提供 TTL 时的兜底秒数。
     *
     * <p>与 {@code @RedisCacheable}/{@code @RedisCachePut} 的 {@code ttl} 属性默认值相等 ——
     * 该相等关系使"属性未设置"与"无参数"两条路径得出同一结果,单方面改动任一侧即改变行为。
     */
    static final long DEFAULT_TTL_SECONDS = 60;

    /** TTL 来源 —— 与写链的三条 debug 日志一一对应。 */
    enum Source {
        /** 方法级注解策略({@link CachePolicyView#ttl()} &gt; 0)。 */
        ANNOTATION,
        /** {@link Duration} 参数(含参数为 {@code null} 时的兜底秒数)。 */
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
     * @param parameterTtl 调用方 TTL(Duration);可为 {@code null}(无 TTL 上下文)、零或负(永久语义)
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
        if (parameterTtl == null) {
            return new Resolution(
                    TtlDecision.applied(DEFAULT_TTL_SECONDS), Source.PARAMETER, false);
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
