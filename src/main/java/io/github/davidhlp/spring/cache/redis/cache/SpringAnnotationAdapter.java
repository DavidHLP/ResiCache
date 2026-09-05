package io.github.davidhlp.spring.cache.redis.cache;







import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheEvict;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCachePut;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCaching;
import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import java.lang.reflect.AnnotatedElement;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.CacheableOperation;

/**
 * Spring 原生注解兼容适配器(职责2).
 *
 * <p>承载 {@code @Cacheable}/{@code @CachePut}/{@code @CacheEvict} 的探测与转换逻辑,
 * 由 {@link RedisProCacheProperties.NativeAnnotationMode}(FULL/NONE/SELECTIVE)驱动。
 * SELECTIVE 模式下,若 ResiCache 同名注解已存在则跳过对应 Spring 注解,避免重复操作。
 * 纯函数,持有不可变的 {@code NativeAnnotationMode} 枚举(构造期注入)。
 *
 * <p>Method/Class 的注解探测与名称提取统一走 {@link AnnotationTargets} 的多态
 * {@link AnnotatedElement} 路径。
 *
 * <p>3 个 build 方法的字段填充(text + special)统一委派给
 * {@link BuilderPopulator#populate},与 {@link AnnotationParser} 共享同一填充协议。
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
     * <p>SELECTIVE / FULL 两个分支均走 {@link AnnotatedElement} 多态路径,名称提取
     * 由 {@link AnnotationTargets#extractTargetName} 完成。
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
     * <p>走 {@link AnnotatedElement} 多态路径。
     */
    private boolean hasResiCacheAnnotation(Object target) {
        if (target instanceof AnnotatedElement) {
            return hasResiCacheable(target) || hasResiCacheEvict(target)
                    || hasResiCachePut(target)
                    || AnnotationTargets.findMerged(target, RedisCaching.class) != null;
        }
        return false;
    }

    /** 走 {@link AnnotatedElement} 多态路径. */
    private boolean hasResiCacheable(Object target) {
        return AnnotationTargets.findMerged(target, RedisCacheable.class) != null;
    }

    /** 走 {@link AnnotatedElement} 多态路径. */
    private boolean hasResiCacheEvict(Object target) {
        return AnnotationTargets.findMerged(target, RedisCacheEvict.class) != null;
    }

    /** 走 {@link AnnotatedElement} 多态路径. */
    private boolean hasResiCachePut(Object target) {
        return AnnotationTargets.findMerged(target, RedisCachePut.class) != null;
    }

    /**
     * 转换 Spring 原生 {@code @Cacheable} 为 ResiCache 的
     * {@link org.springframework.cache.interceptor.CacheableOperation}.
     *
     * <p>走多态 {@code (AnnotatedElement) target} 路径;name 由调用方通过
     * {@link AnnotationTargets#extractTargetName} 预提取,本方法只负责"读注解 +
     * build operation"两步。
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
     * <p>走多态 {@link AnnotatedElement} 路径。
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
     * <p>走多态 {@link AnnotatedElement} 路径。
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

        // 6 文本字段 + 1 special 字段(sync)委派到 BuilderPopulator.populate
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

        // 6 文本字段 + 0 special 字段委派(Lombok 链式 builder 用 x 命名 setter)
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

        // 5 文本字段 + 2 special 字段(allEntries + beforeInvocation)委派。
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
