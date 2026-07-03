package io.github.davidhlp.spring.cache.redis.annotation;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheEvictOperation;
import io.github.davidhlp.spring.cache.redis.operation.RedisCachePutOperation;

import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.util.StringUtils;

import java.lang.reflect.AnnotatedElement;
import java.util.List;

/**
 * Spring 原生注解兼容适配器(职责2).
 *
 * <p>承载 {@code @Cacheable}/{@code @CachePut}/{@code @CacheEvict} 的探测与转换逻辑,
 * 由 {@link RedisProCacheProperties.NativeAnnotationMode}(FULL/NONE/SELECTIVE)驱动。
 * SELECTIVE 模式下,若 ResiCache 同名注解已存在则跳过对应 Spring 注解,避免重复操作。
 * 纯函数,持有不可变的 {@code NativeAnnotationMode} 枚举(构造期注入)。
 *
 * <p><b>Round 10 / ADR-0020 seam 收敛</b>:本类原先 14 处
 * {@code instanceof Method} / {@code instanceof Class} 分派样板已统一委派给
 * {@link AnnotationTargets#findMerged} 与 {@link AnnotationTargets#extractTargetName}。
 * 原 6 对 {@code hasResiCacheXxx(Method)} + {@code hasResiCacheXxx(Class)} 重载 +
 * 6 对 {@code convertSpringXxx(Method, List)} + {@code convertSpringXxx(Class, List)}
 * 重载 + {@code addSpringNativeOperations} 内 SELECTIVE / FULL 双分支的 Method/Class
 * 二分法,共 14 个分派点全部塌缩为多态 {@link AnnotatedElement} 路径。
 */
@Slf4j
final class SpringAnnotationAdapter {

    private final RedisProCacheProperties.NativeAnnotationMode nativeAnnotationMode;

    SpringAnnotationAdapter(
            final RedisProCacheProperties.NativeAnnotationMode nativeAnnotationMode) {
        this.nativeAnnotationMode = nativeAnnotationMode;
    }

    /**
     * 添加 Spring 原生注解的支持.
     *
     * <p>使 @Cacheable, @CachePut, @CacheEvict 也能被 ResiCache 处理.
     *
     * <p>Round 10 / ADR-0020:SELECTIVE / FULL 两个分支的 Method/Class 二分法
     * 已统一为 {@link AnnotatedElement} 多态路径(target 走
     * {@code instanceof Method} / {@code instanceof Class} 判断已下沉至
     * {@link AnnotationTargets#extractTargetName})。
     *
     * @param target 方法或类对象
     * @param ops 操作集合
     */
    void addSpringNativeOperations(
            final Object target, final List<CacheOperation> ops) {
        if (nativeAnnotationMode == RedisProCacheProperties.NativeAnnotationMode.NONE) {
            return;
        }

        final String name = AnnotationTargets.extractTargetName(target);

        if (nativeAnnotationMode == RedisProCacheProperties.NativeAnnotationMode.SELECTIVE) {
            // SELECTIVE:无任何 ResiCache 注解则跳过;否则按需转换 Spring 注解(已有 ResiCache 对应项则跳过,避免重复)
            if (!hasResiCacheAnnotation(target)) {
                return;
            }
            if (!hasResiCacheable(target)) { convertSpringCacheable(target, name, ops); }
            if (!hasResiCacheEvict(target)) { convertSpringCacheEvict(target, name, ops); }
            if (!hasResiCachePut(target)) { convertSpringCachePut(target, name, ops); }
            return;
        }

        // FULL mode: convert all Spring native annotations
        convertSpringCacheable(target, name, ops);
        convertSpringCachePut(target, name, ops);
        convertSpringCacheEvict(target, name, ops);
    }

    /**
     * 目标上是否含任意 ResiCache 注解(3 个原生 + @RedisCaching 复合).
     *
     * <p>Round 10 / ADR-0020:多态 {@link AnnotatedElement} 路径,
     * 原 Method/Class 二分法已消失。
     */
    private boolean hasResiCacheAnnotation(Object target) {
        if (target instanceof AnnotatedElement) {
            return hasResiCacheable(target) || hasResiCacheEvict(target)
                    || hasResiCachePut(target)
                    || AnnotationTargets.findMerged(target, RedisCaching.class) != null;
        }
        return false;
    }

    /** 多态 {@link AnnotatedElement} 路径,原 Method/Class 重载对已合并. */
    private boolean hasResiCacheable(Object target) {
        return AnnotationTargets.findMerged(target, RedisCacheable.class) != null;
    }

    /** 多态 {@link AnnotatedElement} 路径,原 Method/Class 重载对已合并. */
    private boolean hasResiCacheEvict(Object target) {
        return AnnotationTargets.findMerged(target, RedisCacheEvict.class) != null;
    }

    /** 多态 {@link AnnotatedElement} 路径,原 Method/Class 重载对已合并. */
    private boolean hasResiCachePut(Object target) {
        return AnnotationTargets.findMerged(target, RedisCachePut.class) != null;
    }

    /**
     * 转换 Spring 原生 {@code @Cacheable} 为 ResiCache 的
     * {@link org.springframework.cache.interceptor.CacheableOperation}.
     *
     * <p>Round 10 / ADR-0020:多态 {@code (AnnotatedElement) target} 路径,原
     * Method/Class 重载对已合并;name 由调用方通过 {@link AnnotationTargets#extractTargetName}
     * 预提取,本方法只负责"读注解 + build operation"两步。
     */
    private void convertSpringCacheable(Object target, String name, List<CacheOperation> ops) {
        Cacheable ann = AnnotationTargets.findMerged(target, Cacheable.class);
        if (ann != null) {
            ops.add(buildRedisCacheableOperation(ann, name));
            log.debug("Converted Spring @Cacheable on target: {}", name);
        }
    }

    /**
     * 转换 Spring 原生 {@code @CachePut} 为 ResiCache 的
     * {@link RedisCachePutOperation}.
     *
     * <p>Round 10 / ADR-0020:多态路径,原 Method/Class 重载对已合并。
     */
    private void convertSpringCachePut(Object target, String name, List<CacheOperation> ops) {
        CachePut ann = AnnotationTargets.findMerged(target, CachePut.class);
        if (ann != null) {
            ops.add(buildRedisCachePutOperation(ann, name));
            log.debug("Converted Spring @CachePut on target: {}", name);
        }
    }

    /**
     * 转换 Spring 原生 {@code @CacheEvict} 为 ResiCache 的
     * {@link RedisCacheEvictOperation}.
     *
     * <p>Round 10 / ADR-0020:多态路径,原 Method/Class 重载对已合并。
     */
    private void convertSpringCacheEvict(Object target, String name, List<CacheOperation> ops) {
        CacheEvict ann = AnnotationTargets.findMerged(target, CacheEvict.class);
        if (ann != null) {
            ops.add(buildRedisCacheEvictOperation(ann, name));
            log.debug("Converted Spring @CacheEvict on target: {}", name);
        }
    }

    private org.springframework.cache.interceptor.CacheableOperation buildRedisCacheableOperation(
            Cacheable ann, String name) {
        org.springframework.cache.interceptor.CacheableOperation.Builder builder =
                new org.springframework.cache.interceptor.CacheableOperation.Builder();
        builder.setName(name);
        builder.setCacheNames(ann.value().length > 0 ? ann.value() : ann.cacheNames());
        applyText(ann.key(), builder::setKey);
        applyText(ann.condition(), builder::setCondition);
        applyText(ann.unless(), builder::setUnless);
        applyText(ann.keyGenerator(), builder::setKeyGenerator);
        applyText(ann.cacheManager(), builder::setCacheManager);
        applyText(ann.cacheResolver(), builder::setCacheResolver);
        builder.setSync(ann.sync());
        return builder.build();
    }

    private RedisCachePutOperation buildRedisCachePutOperation(
            CachePut ann, String name) {
        RedisCachePutOperation.Builder builder = RedisCachePutOperation.builder();
        builder.name(name);
        builder.cacheNames(ann.value().length > 0 ? ann.value() : ann.cacheNames());
        applyText(ann.key(), builder::key);
        applyText(ann.condition(), builder::condition);
        applyText(ann.unless(), builder::unless);
        applyText(ann.keyGenerator(), builder::keyGenerator);
        applyText(ann.cacheManager(), builder::cacheManager);
        applyText(ann.cacheResolver(), builder::cacheResolver);
        return builder.build();
    }

    private RedisCacheEvictOperation buildRedisCacheEvictOperation(
            CacheEvict ann, String name) {
        RedisCacheEvictOperation.Builder builder = RedisCacheEvictOperation.builder();
        builder.name(name);
        builder.cacheNames(ann.value().length > 0 ? ann.value() : ann.cacheNames());
        applyText(ann.key(), builder::key);
        applyText(ann.condition(), builder::condition);
        applyText(ann.keyGenerator(), builder::setKeyGenerator);
        applyText(ann.cacheManager(), builder::setCacheManager);
        applyText(ann.cacheResolver(), builder::setCacheResolver);
        builder.allEntries(ann.allEntries());
        builder.beforeInvocation(ann.beforeInvocation());
        return builder.build();
    }

    /**
     * 仅当 value 非空时执行 setter(ADR-0029 applyText seam)。
     *
     * <p>抹平两类 Builder setter 风格差异:Spring 标准 Builder 用 {@code setX(String)}
     * (如 {@code CacheableOperation.Builder.setKey}),ResiCache Lombok Builder 用 {@code x(String)}
     * (如 {@code RedisCachePutOperation.Builder.key})。两者皆兼容 {@code Consumer<String>}
     * (Lombok 版返回 Builder 被丢弃),收敛 3 处 build 方法共 17 个 {@code if (hasText) set} 样板。
     */
    private static void applyText(String value, java.util.function.Consumer<String> setter) {
        if (StringUtils.hasText(value)) {
            setter.accept(value);
        }
    }
}
