package io.github.davidhlp.spring.cache.redis.annotation;

import io.github.davidhlp.spring.cache.redis.operation.RedisCacheEvictOperation;
import io.github.davidhlp.spring.cache.redis.operation.RedisCachePutOperation;

import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * ResiCache 注解解析器(职责1).
 *
 * <p>承载 {@code @RedisCacheable}/{@code @RedisCacheEvict}/{@code @RedisCachePut}
 * 与复合注解 {@code @RedisCaching} 的解析与展开逻辑。纯函数、无状态、无 Spring 继承负担,
 * 可被 {@link RedisCacheOperationSource} 之外的代码(测试)直接调用。
 *
 * <p>设计要点:{@code parseRedisCacheable} 刻意构建 Spring 标准
 * {@link org.springframework.cache.interceptor.CacheableOperation}(而非 ResiCache 的
 * RedisCacheableOperation),确保 getClass() 返回 CacheableOperation.class —— 这样
 * CacheAspectSupport 的 CacheOperationContexts 能正确按类型索引(可缓存/可放入/可清除三桶)。
 */
@Slf4j
final class AnnotationParser {

    /**
     * 解析目标(Method 或 Class)上的所有 ResiCache 注解.
     *
     * @param target 方法或类对象
     * @return 缓存操作集合(可能为空,但不会为 null)
     */
    List<CacheOperation> parseResiCacheAnnotations(final Object target) {
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
                ops.add(operation);
            }
            for (final RedisCacheEvict e : caching.redisCacheEvict()) {
                final RedisCacheEvictOperation operation =
                        parseRedisCacheEvict(e, target);
                ops.add(operation);
            }
            for (final RedisCachePut p : caching.redisCachePut()) {
                final CacheOperation operation = parseRedisCachePut(p, target);
                ops.add(operation);
            }
        }

        return ops;
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
