package io.github.davidhlp.spring.cache.redis.annotation;

import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.util.StringUtils;

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
 *
 * <p><b>Round 10 / ADR-0020 seam 收敛</b>:本类原先 9 处
 * {@code if (target instanceof Method) ... else if (target instanceof Class) ...}
 * 重复分派(Method/Class 各自强转后调 {@code AnnotatedElementUtils.findMergedAnnotation}
 * 或取 {@code getName()})已统一委派给 {@link AnnotationTargets#findMerged} 与
 * {@link AnnotationTargets#extractTargetName}。本类签名零变化,行为零回归
 * (现有 {@code SpringAnnotationAdapterTest} + {@code RedisCacheOperationSourceSelectiveTest}
 * + 本类新增 {@code AnnotatedElementPolymorphicSeamTest} 联合钉住)。
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
        final RedisCacheable cacheable =
                AnnotationTargets.findMerged(target, RedisCacheable.class);
        if (cacheable != null) {
            log.debug("Found @RedisCacheable annotation on target: {}", target);
            ops.add(parseRedisCacheable(cacheable, target));
        }

        // 处理单个 @RedisCacheEvict 注解
        final RedisCacheEvict cacheEvict =
                AnnotationTargets.findMerged(target, RedisCacheEvict.class);
        if (cacheEvict != null) {
            log.debug("Found @RedisCacheEvict annotation on target: {}", target);
            ops.add(parseRedisCacheEvict(cacheEvict, target));
        }

        // 处理单个 @RedisCachePut 注解 (ADR-0027: 修补历史遗漏 —— 此前仅 @RedisCaching
        // 复合形式下的 @RedisCachePut 被展开,单注解形式被静默忽略,导致 @RedisCachePut 方法
        // 不触发 Spring AOP 的 cache.put 调用)
        final RedisCachePut cachePut =
                AnnotationTargets.findMerged(target, RedisCachePut.class);
        if (cachePut != null) {
            log.debug("Found @RedisCachePut annotation on target: {}", target);
            ops.add(parseRedisCachePut(cachePut, target));
        }

        // 处理 @RedisCaching 复合注解
        final RedisCaching caching =
                AnnotationTargets.findMerged(target, RedisCaching.class);
        if (caching != null) {
            log.debug("Found @RedisCaching annotation on target: {}", target);
            for (final RedisCacheable c : caching.redisCacheable()) {
                ops.add(parseRedisCacheable(c, target));
            }
            for (final RedisCacheEvict e : caching.redisCacheEvict()) {
                ops.add(parseRedisCacheEvict(e, target));
            }
            for (final RedisCachePut p : caching.redisCachePut()) {
                ops.add(parseRedisCachePut(p, target));
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
        final String name = AnnotationTargets.extractTargetName(target);
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
    private CacheOperation parseRedisCacheEvict(
            final RedisCacheEvict ann, final Object target) {
        final String name = AnnotationTargets.extractTargetName(target);
        log.trace("Parsing @RedisCacheEvict annotation for target: {}", target);

        // ADR-0027: 使用 Spring 标准的 CacheEvictOperation.Builder,确保 getClass() 返回
        // CacheEvictOperation.class —— 这样 CacheAspectSupport 的 CacheOperationContexts
        // 能正确按类型索引(可缓存/可放入/可清除三桶)。此前产出 ResiCache 子类
        // RedisCacheEvictOperation,其 getClass() ≠ CacheEvictOperation.class,Spring
        // 无法将其分入 evict 桶 → @RedisCacheEvict 方法执行但不触发 cache.evict。
        // ResiCache 增强字段(ttl/bloom/early-expiration 等)不进 Spring operation:
        // 由 AnnotationChainEngine 的 handler 注册到 RedisCacheRegister,链路
        // buildContext 按需查询。(@RedisCacheEvict 的 sync/syncTimeout 是 ResiCache
        // 扩展,Spring 原生 CacheEvictOperation 无此概念,此处不投影——与 Spring
        // 原生 @CacheEvict 行为一致。)
        final org.springframework.cache.interceptor.CacheEvictOperation.Builder builder =
                new org.springframework.cache.interceptor.CacheEvictOperation.Builder();
        builder.setName(name);
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

        if (StringUtils.hasText(ann.keyGenerator())) {
            builder.setKeyGenerator(ann.keyGenerator());
        }

        if (StringUtils.hasText(ann.cacheManager())) {
            builder.setCacheManager(ann.cacheManager());
        }

        final org.springframework.cache.interceptor.CacheEvictOperation operation = builder.build();
        log.debug("Built CacheEvictOperation: {}", operation);
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
        final String name = AnnotationTargets.extractTargetName(target);
        log.trace("Parsing @RedisCachePut annotation for target: {}", target);

        // ADR-0027: 使用 Spring 标准的 CachePutOperation.Builder,确保 getClass() 返回
        // CachePutOperation.class —— 这样 CacheAspectSupport 的 CacheOperationContexts
        // 能正确按类型索引(可缓存/可放入/可清除三桶)。与 parseRedisCacheable 对齐
        //(后者自 ADR-0020 起已改用 Spring 标准类);parseRedisCachePut/Evict 此前漏改,
        //产出 ResiCache 子类导致 Spring 分桶失败。ResiCache 增强字段(ttl/bloom/
        // nullValue/early-expiration 等)不进 Spring operation:由 AnnotationChainEngine
        // 的 handler 注册到 RedisCacheRegister,链路 buildContext 按需查询。
        final org.springframework.cache.interceptor.CachePutOperation.Builder builder =
                new org.springframework.cache.interceptor.CachePutOperation.Builder();
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

        if (StringUtils.hasText(ann.keyGenerator())) {
            builder.setKeyGenerator(ann.keyGenerator());
        }

        if (StringUtils.hasText(ann.cacheManager())) {
            builder.setCacheManager(ann.cacheManager());
        }

        if (StringUtils.hasText(ann.cacheResolver())) {
            builder.setCacheResolver(ann.cacheResolver());
        }

        final org.springframework.cache.interceptor.CachePutOperation operation = builder.build();
        log.debug("Built CachePutOperation: {}", operation);
        return operation;
    }
}
