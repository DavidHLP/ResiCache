package io.github.davidhlp.spring.cache.redis.annotation;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheEvictOperation;
import io.github.davidhlp.spring.cache.redis.operation.RedisCachePutOperation;

import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.CacheableOperation;

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
 *
 * <p><b>Round 50 / 架构评审候选 A 收敛</b>:3 个 build 方法原持有 17 处私有
 * {@code applyText(ann.x(), builder::setX)} 调用 + 私有 {@code applyText} helper
 * 已统一委派到 {@link BuilderPopulator#populate} —— 字段填充形状(text + special)
 * 收口到 deep seam,与 {@link AnnotationParser} 共享同一填充协议,新增字段触点
 * = 1 个 populate spec 行而非两份独立 applyText 实现。
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

    private CacheableOperation buildRedisCacheableOperation(
            Cacheable ann, String name) {
        CacheableOperation.Builder builder = new CacheableOperation.Builder();
        builder.setName(name);
        builder.setCacheNames(ann.value().length > 0 ? ann.value() : ann.cacheNames());

        // Round 50:6 文本字段 + 1 special 字段(sync)委派到 BuilderPopulator.populate
        BuilderPopulator.populate(builder, ann,
                List.of(
                        BuilderPopulator.TextField.textField(
                                Cacheable::key, CacheableOperation.Builder::setKey),
                        BuilderPopulator.TextField.textField(
                                Cacheable::condition, CacheableOperation.Builder::setCondition),
                        BuilderPopulator.TextField.textField(
                                Cacheable::unless, CacheableOperation.Builder::setUnless),
                        BuilderPopulator.TextField.textField(
                                Cacheable::keyGenerator, CacheableOperation.Builder::setKeyGenerator),
                        BuilderPopulator.TextField.textField(
                                Cacheable::cacheManager, CacheableOperation.Builder::setCacheManager),
                        BuilderPopulator.TextField.textField(
                                Cacheable::cacheResolver, CacheableOperation.Builder::setCacheResolver)
                ),
                List.of((b, a) -> b.setSync(a.sync())));
        return builder.build();
    }

    private RedisCachePutOperation buildRedisCachePutOperation(
            CachePut ann, String name) {
        RedisCachePutOperation.Builder builder = RedisCachePutOperation.builder();
        builder.name(name);
        builder.cacheNames(ann.value().length > 0 ? ann.value() : ann.cacheNames());

        // Round 50:6 文本字段 + 0 special 字段委派(Lombok 链式 builder 用 x 命名 setter)
        BuilderPopulator.populate(builder, ann,
                List.of(
                        BuilderPopulator.TextField.textField(
                                CachePut::key, RedisCachePutOperation.Builder::key),
                        BuilderPopulator.TextField.textField(
                                CachePut::condition, RedisCachePutOperation.Builder::condition),
                        BuilderPopulator.TextField.textField(
                                CachePut::unless, RedisCachePutOperation.Builder::unless),
                        BuilderPopulator.TextField.textField(
                                CachePut::keyGenerator, RedisCachePutOperation.Builder::keyGenerator),
                        BuilderPopulator.TextField.textField(
                                CachePut::cacheManager, RedisCachePutOperation.Builder::cacheManager),
                        BuilderPopulator.TextField.textField(
                                CachePut::cacheResolver, RedisCachePutOperation.Builder::cacheResolver)
                ),
                List.of());
        return builder.build();
    }

    private RedisCacheEvictOperation buildRedisCacheEvictOperation(
            CacheEvict ann, String name) {
        RedisCacheEvictOperation.Builder builder = RedisCacheEvictOperation.builder();
        builder.name(name);
        builder.cacheNames(ann.value().length > 0 ? ann.value() : ann.cacheNames());

        // Round 50:5 文本字段 + 2 special 字段(allEntries + beforeInvocation)委派。
        // 注:本方法返回 RedisCacheEvictOperation(ResiCache 子类)而非 Spring 标准
        // CacheEvictOperation,与 AnnotationParser 产出 Spring 标准类的语义不同——
        // ResiCache 自家 build 走 ResiCache 子类(持有 ResiCache 增强字段)。
        // Evict 的 5 文本字段覆盖 key + condition + cacheResolver + keyGenerator +
        // cacheManager(与 parseRedisCacheEvict 同结构,语义对齐)。
        BuilderPopulator.populate(builder, ann,
                List.of(
                        BuilderPopulator.TextField.textField(
                                CacheEvict::key, RedisCacheEvictOperation.Builder::key),
                        BuilderPopulator.TextField.textField(
                                CacheEvict::condition, RedisCacheEvictOperation.Builder::condition),
                        BuilderPopulator.TextField.textField(
                                CacheEvict::keyGenerator, RedisCacheEvictOperation.Builder::keyGenerator),
                        BuilderPopulator.TextField.textField(
                                CacheEvict::cacheManager, RedisCacheEvictOperation.Builder::cacheManager),
                        BuilderPopulator.TextField.textField(
                                CacheEvict::cacheResolver, RedisCacheEvictOperation.Builder::cacheResolver)
                ),
                List.of(
                        (b, a) -> b.allEntries(a.allEntries()),
                        (b, a) -> b.beforeInvocation(a.beforeInvocation())));
        return builder.build();
    }
}
