package io.github.davidhlp.spring.cache.redis.annotation;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheEvictOperation;
import io.github.davidhlp.spring.cache.redis.operation.RedisCachePutOperation;

import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.annotation.AnnotationCacheOperationSource;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
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
 * <p>支持 Spring 原生注解 {@code @Cacheable}, {@code @CachePut}, {@code @CacheEvict}
 * 通过 {@link RedisProCacheProperties.NativeAnnotationMode} 控制兼容模式.
 */
@Slf4j
public class RedisCacheOperationSource extends AnnotationCacheOperationSource {

    private final RedisProCacheProperties.NativeAnnotationMode nativeAnnotationMode;

    public RedisCacheOperationSource() {
        this(RedisProCacheProperties.NativeAnnotationMode.FULL);
    }

    public RedisCacheOperationSource(
            RedisProCacheProperties.NativeAnnotationMode nativeAnnotationMode) {
        super(false);
        this.nativeAnnotationMode = nativeAnnotationMode;
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
        if (nativeAnnotationMode == RedisProCacheProperties.NativeAnnotationMode.NONE) {
            return;
        }

        if (nativeAnnotationMode == RedisProCacheProperties.NativeAnnotationMode.SELECTIVE
                && !hasResiCacheAnnotation(target)) {
            return;
        }

        // In SELECTIVE mode, skip Spring annotations whose ResiCache counterpart
        // is already present to avoid duplicate cache operations
        if (nativeAnnotationMode == RedisProCacheProperties.NativeAnnotationMode.SELECTIVE) {
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
            builder.keyGenerator(ann.keyGenerator());
        }
        if (StringUtils.hasText(ann.cacheManager())) {
            builder.cacheManager(ann.cacheManager());
        }
        if (StringUtils.hasText(ann.cacheResolver())) {
            builder.cacheResolver(ann.cacheResolver());
        }
        builder.allEntries(ann.allEntries());
        builder.beforeInvocation(ann.beforeInvocation());
        return builder.build();
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

        // 使用 Spring 标准的 CacheableOperation.Builder，确保 getClass() 返回 CacheableOperation.class
        // 这样 CacheAspectSupport 的 CacheOperationContexts 能正确按类型索引
        final org.springframework.cache.interceptor.CacheableOperation.Builder builder =
                new org.springframework.cache.interceptor.CacheableOperation.Builder();
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
            builder.setUnless(ann.unless());
        }

        builder.setSync(ann.sync());

        if (StringUtils.hasText(ann.keyGenerator())) {
            builder.setKeyGenerator(ann.keyGenerator());
        }

        if (StringUtils.hasText(ann.cacheManager())) {
            builder.setCacheManager(ann.cacheManager());
        }

        if (StringUtils.hasText(ann.cacheResolver())) {
            builder.setCacheResolver(ann.cacheResolver());
        }

        final org.springframework.cache.interceptor.CacheableOperation operation = builder.build();
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
                RedisCacheEvictOperation.builder();
        builder.name(name);
        builder.setCacheNames(
                ann.value().length > 0 ? ann.value() : ann.cacheNames());

        if (StringUtils.hasText(ann.key())) {
            builder.setKey(ann.key());
        }

        if (StringUtils.hasText(ann.cacheResolver())) {
            builder.setCacheResolver(ann.cacheResolver());
        }

        if (StringUtils.hasText(ann.condition())) {
            builder.setCondition(ann.condition());
        }

        builder.setCacheWide(ann.allEntries());
        builder.setBeforeInvocation(ann.beforeInvocation());
        builder.sync(ann.sync());
        builder.syncTimeout(ann.syncTimeout());

        if (StringUtils.hasText(ann.keyGenerator())) {
            builder.setKeyGenerator(ann.keyGenerator());
        }

        if (StringUtils.hasText(ann.cacheManager())) {
            builder.setCacheManager(ann.cacheManager());
        }

        builder.ttl(ann.ttl());
        builder.useBloomFilter(ann.useBloomFilter());
        builder.expectedInsertions(ann.expectedInsertions());
        builder.falseProbability(ann.falseProbability());
        builder.enableEarlyExpiration(ann.enableEarlyExpiration());
        builder.earlyExpirationThreshold(ann.earlyExpirationThreshold());
        builder.earlyExpirationMode(ann.earlyExpirationMode());

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

        final RedisCachePutOperation.Builder builder =
                RedisCachePutOperation.builder();
        builder.name(name);
        builder.setCacheNames(
                ann.value().length > 0 ? ann.value() : ann.cacheNames());

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

        builder.ttl(ann.ttl());
        builder.type(ann.type());
        builder.cacheNullValues(ann.cacheNullValues());
        builder.useBloomFilter(ann.useBloomFilter());
        builder.expectedInsertions(ann.expectedInsertions());
        builder.falseProbability(ann.falseProbability());
        builder.sync(ann.sync());
        builder.syncTimeout(ann.syncTimeout());
        builder.randomTtl(ann.randomTtl());
        builder.variance(ann.variance());
        builder.enableEarlyExpiration(ann.enableEarlyExpiration());
        builder.earlyExpirationThreshold(ann.earlyExpirationThreshold());
        builder.earlyExpirationMode(ann.earlyExpirationMode());

        final RedisCachePutOperation operation = builder.build();
        log.debug("Built RedisCachePutOperation: {}", operation);
        return operation;
    }
}
