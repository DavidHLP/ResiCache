package io.github.davidhlp.spring.cache.redis.annotation;

import io.github.davidhlp.spring.cache.redis.register.operation.RedisCacheEvictOperation;
import io.github.davidhlp.spring.cache.redis.register.operation.RedisCacheableOperation;

import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.annotation.AnnotationCacheOperationSource;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Redis缓存操作源.
 *
 * <p>负责解析Redis缓存注解并转换为缓存操作.
 */
@Slf4j
public class RedisCacheOperationSource extends AnnotationCacheOperationSource {

    public RedisCacheOperationSource() {
        super(false);
    }

    @Override
    @Nullable
    protected Collection<CacheOperation> findCacheOperations(final Method method) {
        return parseCacheAnnotations(method);
    }

    @Override
    @Nullable
    protected Collection<CacheOperation> findCacheOperations(final Class<?> clazz) {
        return parseCacheAnnotations(clazz);
    }

    /**
     * 解析缓存注解.
     *
     * @param target 方法或类对象
     * @return 缓存操作集合
     */
    @Nullable
    private Collection<CacheOperation> parseCacheAnnotations(final Object target) {
        final List<CacheOperation> ops = new ArrayList<>();
        log.trace("Parsing cache annotations for target: {}", target);

        // 处理单个 @RedisCacheable 注解
        RedisCacheable cacheable = null;
        if (target instanceof Method) {
            cacheable = AnnotatedElementUtils.findMergedAnnotation(
                    (Method) target, RedisCacheable.class);
        } else if (target instanceof Class) {
            cacheable = AnnotatedElementUtils.findMergedAnnotation(
                    (Class<?>) target, RedisCacheable.class);
        }

        if (cacheable != null) {
            log.debug("Found @RedisCacheable annotation on target: {}", target);
            final CacheOperation operation = parseRedisCacheable(cacheable, target);
            validateCacheOperation(target, operation);
            ops.add(operation);
        }

        // 处理单个 @RedisCacheEvict 注解
        RedisCacheEvict cacheEvict = null;
        if (target instanceof Method) {
            cacheEvict = AnnotatedElementUtils.findMergedAnnotation(
                    (Method) target, RedisCacheEvict.class);
        } else if (target instanceof Class) {
            cacheEvict = AnnotatedElementUtils.findMergedAnnotation(
                    (Class<?>) target, RedisCacheEvict.class);
        }

        if (cacheEvict != null) {
            log.debug("Found @RedisCacheEvict annotation on target: {}", target);
            final RedisCacheEvictOperation operation =
                    parseRedisCacheEvict(cacheEvict, target);
            validateCacheOperation(target, operation);
            ops.add(operation);
        }

        // 处理 @RedisCaching 复合注解
        RedisCaching caching = null;
        if (target instanceof Method) {
            caching = AnnotatedElementUtils.findMergedAnnotation(
                    (Method) target, RedisCaching.class);
        } else if (target instanceof Class) {
            caching = AnnotatedElementUtils.findMergedAnnotation(
                    (Class<?>) target, RedisCaching.class);
        }

        if (caching != null) {
            log.debug("Found @RedisCaching annotation on target: {}", target);
            for (final RedisCacheable c : caching.redisCacheable()) {
                final CacheOperation operation = parseRedisCacheable(c, target);
                validateCacheOperation(target, operation);
                ops.add(operation);
            }
            for (final RedisCacheEvict e : caching.redisCacheEvict()) {
                final RedisCacheEvictOperation operation =
                        parseRedisCacheEvict(e, target);
                validateCacheOperation(target, operation);
                ops.add(operation);
            }
            for (final RedisCachePut p : caching.redisCachePut()) {
                final CacheOperation operation = parseRedisCachePut(p, target);
                validateCacheOperation(target, operation);
                ops.add(operation);
            }
        }

        // 处理 Spring 原生注解兼容性
        addSpringNativeCacheOperations(target, ops);

        if (!ops.isEmpty()) {
            log.debug("Found {} cache operations for target: {}", ops.size(), target);
        } else {
            log.trace("No cache operations found for target: {}", target);
        }

        return ops.isEmpty() ? null : Collections.unmodifiableList(ops);
    }

    /**
     * 添加 Spring 原生注解的支持.
     *
     * <p>使 @Cacheable, @CachePut, @CacheEvict 也能被 ResiCache 处理.
     *
     * @param target 方法或类对象
     * @param ops 操作集合
     */
    private void addSpringNativeCacheOperations(
            final Object target, final List<CacheOperation> ops) {
        // 处理 Spring @Cacheable
        final Cacheable springCacheable;
        if (target instanceof Method) {
            springCacheable = AnnotatedElementUtils.findMergedAnnotation(
                    (Method) target, Cacheable.class);
        } else if (target instanceof Class) {
            springCacheable = AnnotatedElementUtils.findMergedAnnotation(
                    (Class<?>) target, Cacheable.class);
        } else {
            return;
        }

        if (springCacheable != null) {
            log.debug("Found Spring @Cacheable annotation on target: {}, "
                    + "forwarding to native handler", target);
        }
    }

    /**
     * 解析 @RedisCacheable 注解.
     *
     * @param ann 注解实例
     * @param target 方法或类对象
     * @return 缓存操作
     */
    private CacheOperation parseRedisCacheable(
            final RedisCacheable ann, final Object target) {
        final String name = (target instanceof Method)
                ? ((Method) target).getName() : target.toString();
        log.trace("Parsing @RedisCacheable annotation for target: {}", target);

        final RedisCacheableOperation.Builder builder =
                RedisCacheableOperation.builder();
        builder.setName(name);
        builder.setCacheNames(
                ann.value().length > 0 ? ann.value() : ann.cacheNames());

        if (StringUtils.hasText(ann.key())) {
            builder.setKey(ann.key());
        }

        if (StringUtils.hasText(ann.condition())) {
            builder.setCondition(ann.condition());
        }

        if (StringUtils.hasText(ann.unless())) {
            builder.unless(ann.unless());
        }

        builder.sync(ann.sync());

        if (StringUtils.hasText(ann.keyGenerator())) {
            builder.setKeyGenerator(ann.keyGenerator());
        }

        if (StringUtils.hasText(ann.cacheManager())) {
            builder.setCacheManager(ann.cacheManager());
        }

        final RedisCacheableOperation operation = builder.build();
        log.debug("Built CacheableOperation: {}", operation);
        return operation;
    }

    /**
     * 解析 @RedisCacheEvict 注解.
     *
     * @param ann 注解实例
     * @param target 方法或类对象
     * @return 缓存操作
     */
    private RedisCacheEvictOperation parseRedisCacheEvict(
            final RedisCacheEvict ann, final Object target) {
        final String name = (target instanceof Method)
                ? ((Method) target).getName() : target.toString();
        log.trace("Parsing @RedisCacheEvict annotation for target: {}", target);

        final RedisCacheEvictOperation.Builder builder =
                new RedisCacheEvictOperation.Builder()
                        .name(name)
                        .cacheNames(ann.value().length > 0
                                ? ann.value() : ann.cacheNames());

        if (StringUtils.hasText(ann.key())) {
            builder.key(ann.key());
        }

        if (StringUtils.hasText(ann.cacheResolver())) {
            builder.cacheResolver(ann.cacheResolver());
        }

        if (StringUtils.hasText(ann.condition())) {
            builder.condition(ann.condition());
        }

        builder.sync(ann.sync())
                .allEntries(ann.allEntries())
                .beforeInvocation(ann.beforeInvocation());

        if (StringUtils.hasText(ann.keyGenerator())) {
            builder.keyGenerator(ann.keyGenerator());
        }

        if (StringUtils.hasText(ann.cacheManager())) {
            builder.cacheManager(ann.cacheManager());
        }

        final RedisCacheEvictOperation operation = builder.build();
        log.debug("Built RedisCacheEvictOperation: {}", operation);
        return operation;
    }

    /**
     * 验证缓存操作的合法性.
     *
     * @param target 方法或类对象
     * @param operation 缓存操作
     * @throws IllegalStateException 如果配置无效
     */
    private void validateCacheOperation(
            final Object target, final CacheOperation operation) {
        log.trace("Validating cache operation for target: {}", target);

        if (StringUtils.hasText(operation.getKey())
                && StringUtils.hasText(operation.getKeyGenerator())) {
            final String errorMsg = "Invalid cache annotation configuration on '"
                    + target + "'. Both 'key' and 'keyGenerator' attributes "
                    + "have been set. These attributes are mutually exclusive.";
            log.error(errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        if (StringUtils.hasText(operation.getCacheManager())
                && StringUtils.hasText(operation.getCacheResolver())) {
            final String errorMsg = "Invalid cache annotation configuration on '"
                    + target + "'. Both 'cacheManager' and 'cacheResolver' "
                    + "attributes have been set.";
            log.error(errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        if (operation.getCacheNames().isEmpty()) {
            final String errorMsg = "Invalid cache annotation configuration on '"
                    + target + "'. At least one cache name must be specified.";
            log.error(errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        log.debug("Cache operation validation passed for target: {}", target);
    }

    /**
     * 解析 @RedisCachePut 注解.
     *
     * @param ann 注解实例
     * @param target 方法或类对象
     * @return 缓存操作
     */
    private CacheOperation parseRedisCachePut(
            final RedisCachePut ann, final Object target) {
        final String name = (target instanceof Method)
                ? ((Method) target).getName() : target.toString();
        log.trace("Parsing @RedisCachePut annotation for target: {}", target);

        final RedisCacheableOperation.Builder builder =
                RedisCacheableOperation.builder();
        builder.setName(name);
        builder.setCacheNames(
                ann.value().length > 0 ? ann.value() : ann.cacheNames());

        if (StringUtils.hasText(ann.key())) {
            builder.setKey(ann.key());
        }

        if (StringUtils.hasText(ann.condition())) {
            builder.setCondition(ann.condition());
        }

        if (StringUtils.hasText(ann.unless())) {
            builder.unless(ann.unless());
        }

        if (StringUtils.hasText(ann.keyGenerator())) {
            builder.setKeyGenerator(ann.keyGenerator());
        }

        if (StringUtils.hasText(ann.cacheManager())) {
            builder.setCacheManager(ann.cacheManager());
        }

        final RedisCacheableOperation operation = builder.build();
        log.debug("Built RedisCachePut operation: {}", operation);
        return operation;
    }
}
