package io.github.davidhlp.spring.cache.redis.annotation;

import io.github.davidhlp.spring.cache.redis.register.operation.RedisCacheEvictOperation;

import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.annotation.AnnotationCacheOperationSource;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.CacheableOperation;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/** Redis缓存操作源 负责解析Redis缓存注解并转换为缓存操作 */
@Slf4j
public class RedisCacheOperationSource extends AnnotationCacheOperationSource {

    public RedisCacheOperationSource() {
        super(false);
    }

    @Override
    @Nullable
    protected Collection<CacheOperation> findCacheOperations(Method method) {
        return parseCacheAnnotations(method);
    }

    @Override
    @Nullable
    protected Collection<CacheOperation> findCacheOperations(Class<?> clazz) {
        return parseCacheAnnotations(clazz);
    }

    @Nullable
    private Collection<CacheOperation> parseCacheAnnotations(Object target) {
        List<CacheOperation> ops = new ArrayList<>();
        log.trace("Parsing cache annotations for target: {}", target);

        // 处理单个 @RedisCacheable 注解
        RedisCacheable cacheable = null;
        if (target instanceof Method) {
            cacheable =
                    AnnotatedElementUtils.findMergedAnnotation(
                            (Method) target, RedisCacheable.class);
        } else if (target instanceof Class) {
            cacheable =
                    AnnotatedElementUtils.findMergedAnnotation(
                            (Class<?>) target, RedisCacheable.class);
        }

        if (cacheable != null) {
            log.debug("Found @RedisCacheable annotation on target: {}", target);
            CacheOperation operation = parseRedisCacheable(cacheable, target);
            validateCacheOperation(target, operation);
            ops.add(operation);
        }

        // 处理单个 @RedisCacheEvict 注解
        RedisCacheEvict cacheEvict = null;
        if (target instanceof Method) {
            cacheEvict =
                    AnnotatedElementUtils.findMergedAnnotation(
                            (Method) target, RedisCacheEvict.class);
        } else if (target instanceof Class) {
            cacheEvict =
                    AnnotatedElementUtils.findMergedAnnotation(
                            (Class<?>) target, RedisCacheEvict.class);
        }

        if (cacheEvict != null) {
            log.debug("Found @RedisCacheEvict annotation on target: {}", target);
            RedisCacheEvictOperation operation = parseRedisCacheEvict(cacheEvict, target);
            validateCacheOperation(target, operation);
            ops.add(operation);
        }

        // 处理 @RedisCaching 复合注解
        RedisCaching caching = null;
        if (target instanceof Method) {
            caching =
                    AnnotatedElementUtils.findMergedAnnotation((Method) target, RedisCaching.class);
        } else if (target instanceof Class) {
            caching =
                    AnnotatedElementUtils.findMergedAnnotation(
                            (Class<?>) target, RedisCaching.class);
        }

        if (caching != null) {
            log.debug("Found @RedisCaching annotation on target: {}", target);
            for (RedisCacheable c : caching.redisCacheable()) {
                CacheOperation operation = parseRedisCacheable(c, target);
                validateCacheOperation(target, operation);
                ops.add(operation);
            }
            for (RedisCacheEvict e : caching.redisCacheEvict()) {
                RedisCacheEvictOperation operation = parseRedisCacheEvict(e, target);
                validateCacheOperation(target, operation);
                ops.add(operation);
            }
        }

        if (!ops.isEmpty()) {
            log.debug("Found {} cache operations for target: {}", ops.size(), target);
        } else {
            log.trace("No cache operations found for target: {}", target);
        }

        return ops.isEmpty() ? null : Collections.unmodifiableList(ops);
    }

    private CacheOperation parseRedisCacheable(RedisCacheable ann, Object target) {
        String name = (target instanceof Method) ? ((Method) target).getName() : target.toString();
        log.trace("Parsing @RedisCacheable annotation for target: {}", target);

        // 使用标准的 Spring CacheableOperation. Builder
        CacheableOperation.Builder builder = new CacheableOperation.Builder();
        builder.setName(name);
        builder.setCacheNames(ann.value().length > 0 ? ann.value() : ann.cacheNames());

        // 只在有值时设置key
        if (StringUtils.hasText(ann.key())) {
            builder.setKey(ann.key());
        }

        // 只在有值时设置condition
        if (StringUtils.hasText(ann.condition())) {
            builder.setCondition(ann.condition());
        }

        // 只在有值时设置unless
        if (StringUtils.hasText(ann.unless())) {
            builder.setUnless(ann.unless());
        }

        builder.setSync(ann.sync());

        // 只有当keyGenerator有值时才设置
        if (StringUtils.hasText(ann.keyGenerator())) {
            builder.setKeyGenerator(ann.keyGenerator());
        }

        // 只有当cacheManager有值时才设置
        if (StringUtils.hasText(ann.cacheManager())) {
            builder.setCacheManager(ann.cacheManager());
        }

        CacheableOperation operation = builder.build();
        log.debug("Built CacheableOperation: {}", operation);
        return operation;
    }

    private RedisCacheEvictOperation parseRedisCacheEvict(RedisCacheEvict ann, Object target) {
        String name = (target instanceof Method) ? ((Method) target).getName() : target.toString();
        log.trace("Parsing @RedisCacheEvict annotation for target: {}", target);

        RedisCacheEvictOperation.Builder builder =
                new RedisCacheEvictOperation.Builder()
                        .name(name)
                        .cacheNames(ann.value().length > 0 ? ann.value() : ann.cacheNames());

        // 只在有值时设置key
        if (StringUtils.hasText(ann.key())) {
            builder.key(ann.key());
        }

        // 只在有值时设置cacheResolver
        if (StringUtils.hasText(ann.cacheResolver())) {
            builder.cacheResolver(ann.cacheResolver());
        }

        // 只在有值时设置condition
        if (StringUtils.hasText(ann.condition())) {
            builder.condition(ann.condition());
        }

        builder.sync(ann.sync())
                .allEntries(ann.allEntries())
                .beforeInvocation(ann.beforeInvocation());

        // 只有当keyGenerator有值时才设置
        if (StringUtils.hasText(ann.keyGenerator())) {
            builder.keyGenerator(ann.keyGenerator());
        }

        // 只有当cacheManager有值时才设置
        if (StringUtils.hasText(ann.cacheManager())) {
            builder.cacheManager(ann.cacheManager());
        }

        RedisCacheEvictOperation operation = builder.build();
        log.debug("Built RedisCacheEvictOperation: {}", operation);
        return operation;
    }

    private void validateCacheOperation(Object target, CacheOperation operation) {
        log.trace("Validating cache operation for target: {}", target);

        if (StringUtils.hasText(operation.getKey())
                && StringUtils.hasText(operation.getKeyGenerator())) {
            String errorMsg =
                    "Invalid cache annotation configuration on '"
                            + target
                            + "'. Both 'key' and 'keyGenerator' attributes have been set. "
                            + "These attributes are mutually exclusive: either set the SpEL expression used to "
                            + "compute the key at runtime or set the name of the KeyGenerator bean to use.";
            log.error(errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        if (StringUtils.hasText(operation.getCacheManager())
                && StringUtils.hasText(operation.getCacheResolver())) {
            String errorMsg =
                    "Invalid cache annotation configuration on '"
                            + target
                            + "'. Both 'cacheManager' and 'cacheResolver' attributes have been set. "
                            + "These attributes are mutually exclusive: the cache manager is used to configure a "
                            + "default cache resolver if none is set. If a cache resolver is set, the cache manager "
                            + "won't be used.";
            log.error(errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        if (operation.getCacheNames().isEmpty()) {
            String errorMsg =
                    "Invalid cache annotation configuration on '"
                            + target
                            + "'. At least one cache name must be specified.";
            log.error(errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        log.debug("Cache operation validation passed for target: {}", target);
    }
}
