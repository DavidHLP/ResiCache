package io.github.davidhlp.spring.cache.redis.annotation;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheEvictOperation;
import io.github.davidhlp.spring.cache.redis.operation.RedisCachePutOperation;

import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Spring 原生注解兼容适配器(职责2).
 *
 * <p>承载 {@code @Cacheable}/{@code @CachePut}/{@code @CacheEvict} 的探测与转换逻辑,
 * 由 {@link RedisProCacheProperties.NativeAnnotationMode}(FULL/NONE/SELECTIVE)驱动。
 * SELECTIVE 模式下,若 ResiCache 同名注解已存在则跳过对应 Spring 注解,避免重复操作。
 * 纯函数,持有不可变的 {@code NativeAnnotationMode} 枚举(构造期注入)。
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
     * @param target 方法或类对象
     * @param ops 操作集合
     */
    void addSpringNativeOperations(
            final Object target, final List<CacheOperation> ops) {
        if (nativeAnnotationMode == RedisProCacheProperties.NativeAnnotationMode.NONE) {
            return;
        }

        if (nativeAnnotationMode == RedisProCacheProperties.NativeAnnotationMode.SELECTIVE) {
            // SELECTIVE:无任何 ResiCache 注解则跳过;否则按需转换 Spring 注解(已有 ResiCache 对应项则跳过,避免重复)
            if (!hasResiCacheAnnotation(target)) {
                return;
            }
            if (target instanceof Method method) {
                if (!hasResiCacheable(method)) { convertSpringCacheable(method, ops); }
                if (!hasResiCacheEvict(method)) { convertSpringCacheEvict(method, ops); }
                if (!hasResiCachePut(method)) { convertSpringCachePut(method, ops); }
            } else if (target instanceof Class<?> clazz) {
                if (!hasResiCacheable(clazz)) { convertSpringCacheable(clazz, ops); }
                if (!hasResiCacheEvict(clazz)) { convertSpringCacheEvict(clazz, ops); }
                if (!hasResiCachePut(clazz)) { convertSpringCachePut(clazz, ops); }
            }
            return;
        }

        // FULL mode: convert all Spring native annotations
        if (target instanceof Method method) {
            convertSpringCacheable(method, ops);
            convertSpringCachePut(method, ops);
            convertSpringCacheEvict(method, ops);
        } else if (target instanceof Class<?> clazz) {
            convertSpringCacheable(clazz, ops);
            convertSpringCachePut(clazz, ops);
            convertSpringCacheEvict(clazz, ops);
        }
    }

    private boolean hasResiCacheAnnotation(Object target) {
        if (target instanceof Method method) {
            return hasResiCacheable(method) || hasResiCacheEvict(method)
                    || hasResiCachePut(method)
                    || AnnotatedElementUtils.findMergedAnnotation(method, RedisCaching.class) != null;
        } else if (target instanceof Class<?> clazz) {
            return hasResiCacheable(clazz) || hasResiCacheEvict(clazz)
                    || hasResiCachePut(clazz)
                    || AnnotatedElementUtils.findMergedAnnotation(clazz, RedisCaching.class) != null;
        }
        return false;
    }

    private boolean hasResiCacheable(Method method) {
        return AnnotatedElementUtils.findMergedAnnotation(method, RedisCacheable.class) != null;
    }

    private boolean hasResiCacheable(Class<?> clazz) {
        return AnnotatedElementUtils.findMergedAnnotation(clazz, RedisCacheable.class) != null;
    }

    private boolean hasResiCacheEvict(Method method) {
        return AnnotatedElementUtils.findMergedAnnotation(method, RedisCacheEvict.class) != null;
    }

    private boolean hasResiCacheEvict(Class<?> clazz) {
        return AnnotatedElementUtils.findMergedAnnotation(clazz, RedisCacheEvict.class) != null;
    }

    private boolean hasResiCachePut(Method method) {
        return AnnotatedElementUtils.findMergedAnnotation(method, RedisCachePut.class) != null;
    }

    private boolean hasResiCachePut(Class<?> clazz) {
        return AnnotatedElementUtils.findMergedAnnotation(clazz, RedisCachePut.class) != null;
    }

    private void convertSpringCacheable(Method method, List<CacheOperation> ops) {
        Cacheable ann = AnnotatedElementUtils.findMergedAnnotation(method, Cacheable.class);
        if (ann != null) {
            ops.add(buildRedisCacheableOperation(ann, method.getName()));
            log.debug("Converted Spring @Cacheable on method: {}", method.getName());
        }
    }

    private void convertSpringCacheable(Class<?> clazz, List<CacheOperation> ops) {
        Cacheable ann = AnnotatedElementUtils.findMergedAnnotation(clazz, Cacheable.class);
        if (ann != null) {
            ops.add(buildRedisCacheableOperation(ann, clazz.getName()));
            log.debug("Converted Spring @Cacheable on class: {}", clazz.getName());
        }
    }

    private void convertSpringCachePut(Method method, List<CacheOperation> ops) {
        CachePut ann = AnnotatedElementUtils.findMergedAnnotation(method, CachePut.class);
        if (ann != null) {
            ops.add(buildRedisCachePutOperation(ann, method.getName()));
            log.debug("Converted Spring @CachePut on method: {}", method.getName());
        }
    }

    private void convertSpringCachePut(Class<?> clazz, List<CacheOperation> ops) {
        CachePut ann = AnnotatedElementUtils.findMergedAnnotation(clazz, CachePut.class);
        if (ann != null) {
            ops.add(buildRedisCachePutOperation(ann, clazz.getName()));
            log.debug("Converted Spring @CachePut on class: {}", clazz.getName());
        }
    }

    private void convertSpringCacheEvict(Method method, List<CacheOperation> ops) {
        CacheEvict ann = AnnotatedElementUtils.findMergedAnnotation(method, CacheEvict.class);
        if (ann != null) {
            ops.add(buildRedisCacheEvictOperation(ann, method.getName()));
            log.debug("Converted Spring @CacheEvict on method: {}", method.getName());
        }
    }

    private void convertSpringCacheEvict(Class<?> clazz, List<CacheOperation> ops) {
        CacheEvict ann = AnnotatedElementUtils.findMergedAnnotation(clazz, CacheEvict.class);
        if (ann != null) {
            ops.add(buildRedisCacheEvictOperation(ann, clazz.getName()));
            log.debug("Converted Spring @CacheEvict on class: {}", clazz.getName());
        }
    }

    private org.springframework.cache.interceptor.CacheableOperation buildRedisCacheableOperation(
            Cacheable ann, String name) {
        org.springframework.cache.interceptor.CacheableOperation.Builder builder =
                new org.springframework.cache.interceptor.CacheableOperation.Builder();
        builder.setName(name);
        builder.setCacheNames(ann.value().length > 0 ? ann.value() : ann.cacheNames());
        if (StringUtils.hasText(ann.key())) {
            builder.setKey(ann.key());
        }
        if (StringUtils.hasText(ann.condition())) {
            builder.setCondition(ann.condition());
        }
        if (StringUtils.hasText(ann.unless())) {
            builder.setUnless(ann.unless());
        }
        if (StringUtils.hasText(ann.keyGenerator())) {
            builder.setKeyGenerator(ann.keyGenerator());
        }
        if (StringUtils.hasText(ann.cacheManager())) {
            builder.setCacheManager(ann.cacheManager());
        }
        if (StringUtils.hasText(ann.cacheResolver())) {
            builder.setCacheResolver(ann.cacheResolver());
        }
        builder.setSync(ann.sync());
        return builder.build();
    }

    private RedisCachePutOperation buildRedisCachePutOperation(
            CachePut ann, String name) {
        RedisCachePutOperation.Builder builder = RedisCachePutOperation.builder();
        builder.name(name);
        builder.cacheNames(ann.value().length > 0 ? ann.value() : ann.cacheNames());
        if (StringUtils.hasText(ann.key())) {
            builder.key(ann.key());
        }
        if (StringUtils.hasText(ann.condition())) {
            builder.condition(ann.condition());
        }
        if (StringUtils.hasText(ann.unless())) {
            builder.unless(ann.unless());
        }
        if (StringUtils.hasText(ann.keyGenerator())) {
            builder.keyGenerator(ann.keyGenerator());
        }
        if (StringUtils.hasText(ann.cacheManager())) {
            builder.cacheManager(ann.cacheManager());
        }
        if (StringUtils.hasText(ann.cacheResolver())) {
            builder.cacheResolver(ann.cacheResolver());
        }
        return builder.build();
    }

    private RedisCacheEvictOperation buildRedisCacheEvictOperation(
            CacheEvict ann, String name) {
        RedisCacheEvictOperation.Builder builder = RedisCacheEvictOperation.builder();
        builder.name(name);
        builder.cacheNames(ann.value().length > 0 ? ann.value() : ann.cacheNames());
        if (StringUtils.hasText(ann.key())) {
            builder.key(ann.key());
        }
        if (StringUtils.hasText(ann.condition())) {
            builder.condition(ann.condition());
        }
        if (StringUtils.hasText(ann.keyGenerator())) {
            builder.setKeyGenerator(ann.keyGenerator());
        }
        if (StringUtils.hasText(ann.cacheManager())) {
            builder.setCacheManager(ann.cacheManager());
        }
        if (StringUtils.hasText(ann.cacheResolver())) {
            builder.setCacheResolver(ann.cacheResolver());
        }
        builder.allEntries(ann.allEntries());
        builder.beforeInvocation(ann.beforeInvocation());
        return builder.build();
    }
}
