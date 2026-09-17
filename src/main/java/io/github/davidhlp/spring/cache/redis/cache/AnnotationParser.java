package io.github.davidhlp.spring.cache.redis.cache;






import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheEvict;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCachePut;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCaching;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.interceptor.CacheEvictOperation;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.CachePutOperation;
import org.springframework.cache.interceptor.CacheableOperation;

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
 * <p>Method/Class 的注解读取与名称提取统一委派给
 * {@link AnnotationTargets#findMerged} 与 {@link AnnotationTargets#extractTargetName}。
 *
 * <p>3 个 parse 方法的字段填充(text + special)统一委派给
 * {@link BuilderPopulator#populate},新增 ResiCache 字段仅需追加 1 个 populate spec 行。
 */
@Slf4j
class AnnotationParser {

    private final RedisCacheAttributesProjector projector;
    private final SpringCacheableAdapter springCacheableAdapter;

    AnnotationParser() {
        this.projector = new RedisCacheAttributesProjector();
        this.springCacheableAdapter = new SpringCacheableAdapter(projector);
    }

    AnnotationParser(
            RedisCacheAttributesProjector projector,
            SpringCacheableAdapter springCacheableAdapter) {
        this.projector = projector;
        this.springCacheableAdapter = springCacheableAdapter;
    }

    /**
     * 单次解析目标元素，同时产出 Spring operation 与 annotation chain policy operation。
     */
    ParsedAnnotations parse(final Object target) {
        final List<CacheOperation> operations = new ArrayList<>();
        final List<CacheOperation> policyOperations = new ArrayList<>();
        log.trace("Parsing cache annotations for target: {}", target);

        final RedisCacheable cacheable =
                AnnotationTargets.findMerged(target, RedisCacheable.class);
        if (cacheable != null) {
            operations.add(parseRedisCacheable(cacheable, target));
            addPolicy(policyOperations, cacheable, target);
        } else {
            final Cacheable springCacheable =
                    AnnotationTargets.findMerged(target, Cacheable.class);
            if (springCacheable != null) {
                addPolicy(policyOperations, springCacheable, target);
            }
        }

        final RedisCacheEvict cacheEvict =
                AnnotationTargets.findMerged(target, RedisCacheEvict.class);
        if (cacheEvict != null) {
            operations.add(parseRedisCacheEvict(cacheEvict, target));
            addPolicy(policyOperations, cacheEvict, target);
        }

        final RedisCachePut cachePut =
                AnnotationTargets.findMerged(target, RedisCachePut.class);
        if (cachePut != null) {
            operations.add(parseRedisCachePut(cachePut, target));
            addPolicy(policyOperations, cachePut, target);
        }

        final RedisCaching caching =
                AnnotationTargets.findMerged(target, RedisCaching.class);
        if (caching != null) {
            for (final RedisCacheable annotation : caching.redisCacheable()) {
                operations.add(parseRedisCacheable(annotation, target));
                addPolicy(policyOperations, annotation, target);
            }
            for (final RedisCacheEvict annotation : caching.redisCacheEvict()) {
                operations.add(parseRedisCacheEvict(annotation, target));
                addPolicy(policyOperations, annotation, target);
            }
            for (final RedisCachePut annotation : caching.redisCachePut()) {
                operations.add(parseRedisCachePut(annotation, target));
                addPolicy(policyOperations, annotation, target);
            }
        }

        return new ParsedAnnotations(operations, policyOperations);
    }

    /**
     * 解析目标(Method 或 Class)上的所有 ResiCache 注解.
     *
     * @param target 方法或类对象
     * @return 缓存操作集合(可能为空,但不会为 null)
     */
    List<CacheOperation> parseResiCacheAnnotations(final Object target) {
        return parse(target).operations();
    }

    private void addPolicy(
            List<CacheOperation> policies, RedisCacheable annotation, Object target) {
        if (target instanceof java.lang.reflect.Method method) {
            policies.add(RedisCacheableOperation.fromAttributes(
                    method, annotation.key(), projector.from(annotation)));
        }
    }

    private void addPolicy(
            List<CacheOperation> policies, RedisCachePut annotation, Object target) {
        if (target instanceof java.lang.reflect.Method method) {
            policies.add(RedisCachePutOperation.fromAttributes(
                    method, annotation.key(), projector.from(annotation)));
        }
    }

    private void addPolicy(
            List<CacheOperation> policies, RedisCacheEvict annotation, Object target) {
        if (target instanceof java.lang.reflect.Method method) {
            policies.add(RedisCacheEvictOperation.fromAttributes(
                    method, annotation.key(), projector.from(annotation)));
        }
    }

    private void addPolicy(
            List<CacheOperation> policies, Cacheable annotation, Object target) {
        if (target instanceof java.lang.reflect.Method method) {
            policies.add(springCacheableAdapter.create(method, annotation, annotation.key()));
        }
    }

    record ParsedAnnotations(
            List<CacheOperation> operations,
            List<CacheOperation> policyOperations) {

        ParsedAnnotations {
            operations = List.copyOf(operations);
            policyOperations = List.copyOf(policyOperations);
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
        final String name = AnnotationTargets.extractTargetName(target);
        log.trace("Parsing @RedisCacheable annotation for target: {}", target);

        // 使用 Spring 标准的 CacheableOperation.Builder，确保 getClass() 返回 CacheableOperation.class
        // 这样 CacheAspectSupport 的 CacheOperationContexts 能正确按类型索引
        final CacheableOperation.Builder builder = new CacheableOperation.Builder();
        builder.setName(name);
        builder.setCacheNames(
                ann.value().length > 0 ? ann.value() : ann.cacheNames());

        // 6 文本字段 + 1 special 字段填充委派到 BuilderPopulator.populate。
        // setter 引用形态兼容 Spring 标准 Builder(setX 命名)与 Lombok Builder(x 命名)。
        BuilderPopulator.populate(builder, ann,
                List.of(
                        BuilderPopulator.TextField.textField(
                                RedisCacheable::key, CacheableOperation.Builder::setKey),
                        BuilderPopulator.TextField.textField(
                                RedisCacheable::condition, CacheableOperation.Builder::setCondition),
                        BuilderPopulator.TextField.textField(
                                RedisCacheable::unless, CacheableOperation.Builder::setUnless),
                        BuilderPopulator.TextField.textField(
                                RedisCacheable::keyGenerator, CacheableOperation.Builder::setKeyGenerator),
                        BuilderPopulator.TextField.textField(
                                RedisCacheable::cacheManager, CacheableOperation.Builder::setCacheManager),
                        BuilderPopulator.TextField.textField(
                                RedisCacheable::cacheResolver, CacheableOperation.Builder::setCacheResolver)
                ),
                List.of((b, a) -> b.setSync(a.sync())));

        final CacheableOperation operation = builder.build();
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

        // 使用 Spring 标准的 CacheEvictOperation.Builder,确保 getClass() 返回
        // CacheEvictOperation.class —— 这样 CacheAspectSupport 的 CacheOperationContexts
        // 能正确按类型索引(可缓存/可放入/可清除三桶)。ResiCache 增强字段(ttl/bloom/
        // early-expiration 等)不进 Spring operation,由本解析结果的 policy snapshot
        // 提供给 RedisCacheRegister 查询。(@RedisCacheEvict 的 sync/syncTimeout 是
        // ResiCache 扩展,Spring 原生 CacheEvictOperation 无此概念,此处不投影——
        // 与 Spring 原生 @CacheEvict 行为一致。)
        final CacheEvictOperation.Builder builder = new CacheEvictOperation.Builder();
        builder.setName(name);
        builder.setCacheNames(
                ann.value().length > 0 ? ann.value() : ann.cacheNames());

        // 5 文本字段 + 2 special 字段(cacheWide + beforeInvocation)填充委派
        BuilderPopulator.populate(builder, ann,
                List.of(
                        BuilderPopulator.TextField.textField(
                                RedisCacheEvict::key, CacheEvictOperation.Builder::setKey),
                        BuilderPopulator.TextField.textField(
                                RedisCacheEvict::cacheResolver, CacheEvictOperation.Builder::setCacheResolver),
                        BuilderPopulator.TextField.textField(
                                RedisCacheEvict::condition, CacheEvictOperation.Builder::setCondition),
                        BuilderPopulator.TextField.textField(
                                RedisCacheEvict::keyGenerator, CacheEvictOperation.Builder::setKeyGenerator),
                        BuilderPopulator.TextField.textField(
                                RedisCacheEvict::cacheManager, CacheEvictOperation.Builder::setCacheManager)
                ),
                List.of(
                        (b, a) -> b.setCacheWide(a.allEntries()),
                        (b, a) -> b.setBeforeInvocation(a.beforeInvocation())));

        final CacheEvictOperation operation = builder.build();
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

        // 使用 Spring 标准的 CachePutOperation.Builder,确保 getClass() 返回
        // CachePutOperation.class —— 这样 CacheAspectSupport 的 CacheOperationContexts
        // 能正确按类型索引(可缓存/可放入/可清除三桶)。ResiCache 增强字段(ttl/bloom/
        // nullValue/early-expiration 等)不进 Spring operation,由 policy snapshot
        // 提供给 RedisCacheRegister 查询。
        final CachePutOperation.Builder builder = new CachePutOperation.Builder();
        builder.setName(name);
        builder.setCacheNames(
                ann.value().length > 0 ? ann.value() : ann.cacheNames());

        // 6 文本字段 + 0 special 字段委派(@RedisCachePut 不携带 Spring 标准
        // CachePutOperation 没有的特殊字段,只是 key+condition+unless 等基础文本)
        BuilderPopulator.populate(builder, ann,
                List.of(
                        BuilderPopulator.TextField.textField(
                                RedisCachePut::key, CachePutOperation.Builder::setKey),
                        BuilderPopulator.TextField.textField(
                                RedisCachePut::condition, CachePutOperation.Builder::setCondition),
                        BuilderPopulator.TextField.textField(
                                RedisCachePut::unless, CachePutOperation.Builder::setUnless),
                        BuilderPopulator.TextField.textField(
                                RedisCachePut::keyGenerator, CachePutOperation.Builder::setKeyGenerator),
                        BuilderPopulator.TextField.textField(
                                RedisCachePut::cacheManager, CachePutOperation.Builder::setCacheManager),
                        BuilderPopulator.TextField.textField(
                                RedisCachePut::cacheResolver, CachePutOperation.Builder::setCacheResolver)
                ),
                List.of());

        final CachePutOperation operation = builder.build();
        log.debug("Built CachePutOperation: {}", operation);
        return operation;
    }
}
