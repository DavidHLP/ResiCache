package io.github.davidhlp.spring.cache.redis.cache;




import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheEvict;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCachePut;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCaching;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.interceptor.CacheEvictOperation;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.CachePutOperation;
import org.springframework.cache.interceptor.CacheableOperation;
import org.springframework.lang.Nullable;

/**
 * ResiCache 注解解析器(职责1).
 *
 * <p>承载 {@code @RedisCacheable}/{@code @RedisCacheEvict}/{@code @RedisCachePut}
 * 与复合注解 {@code @RedisCaching} 的解析与展开逻辑。纯函数、无状态、无 Spring 继承负担,
 * 可被 {@link RedisCacheOperationSource} 之外的代码(测试)直接调用。
 *
 * <p>设计要点:一个注解只投影一次 —— {@link RedisCacheAttributesProjector} 产出唯一的
 * {@link RedisCacheAttributes},AOP operation 与 policy operation 都从这份投影派生
 * ({@link RedisCacheAttributes#applyTo(CacheableOperation.Builder)} /
 * {@code RedisCacheableOperation#fromAttributes})。AOP 面刻意使用 Spring 原生
 * {@link org.springframework.cache.interceptor.CacheableOperation}(而非 ResiCache 的
 * RedisCacheableOperation),确保 getClass() 返回 CacheableOperation.class —— 这样
 * CacheAspectSupport 的 CacheOperationContexts 能正确按类型索引(可缓存/可放入/可清除三桶)。
 *
 * <p>Method/Class 的注解读取与名称提取统一委派给
 * {@link AnnotationTargets#findMerged} 与 {@link AnnotationTargets#extractTargetName}。
 */
@Slf4j
class AnnotationParser {

    private final RedisCacheAttributesProjector projector;
    private final SpringCacheableAdapter springCacheableAdapter;

    AnnotationParser() {
        this.projector = new RedisCacheAttributesProjector();
        this.springCacheableAdapter = new SpringCacheableAdapter();
    }

    /**
     * 单次解析目标元素，同时产出 Spring operation 与 annotation chain policy operation。
     *
     * <p>每个注解投影一次，两副面孔共享同一份 {@link RedisCacheAttributes}。
     */
    ParsedAnnotations parse(final Object target) {
        final List<CacheOperation> operations = new ArrayList<>();
        final List<CacheOperation> policyOperations = new ArrayList<>();
        log.trace("Parsing cache annotations for target: {}", target);

        final RedisCacheable cacheable =
                AnnotationTargets.findMerged(target, RedisCacheable.class);
        if (cacheable != null) {
            addCacheable(operations, policyOperations, cacheable, target);
        } else {
            final Cacheable springCacheable =
                    AnnotationTargets.findMerged(target, Cacheable.class);
            if (springCacheable != null) {
                addSpringCacheablePolicy(policyOperations, springCacheable, target);
            }
        }

        final RedisCacheEvict cacheEvict =
                AnnotationTargets.findMerged(target, RedisCacheEvict.class);
        if (cacheEvict != null) {
            addEvict(operations, policyOperations, cacheEvict, target);
        }

        final RedisCachePut cachePut =
                AnnotationTargets.findMerged(target, RedisCachePut.class);
        if (cachePut != null) {
            addPut(operations, policyOperations, cachePut, target);
        }

        final RedisCaching caching =
                AnnotationTargets.findMerged(target, RedisCaching.class);
        if (caching != null) {
            for (final RedisCacheable annotation : caching.redisCacheable()) {
                addCacheable(operations, policyOperations, annotation, target);
            }
            for (final RedisCacheEvict annotation : caching.redisCacheEvict()) {
                addEvict(operations, policyOperations, annotation, target);
            }
            for (final RedisCachePut annotation : caching.redisCachePut()) {
                addPut(operations, policyOperations, annotation, target);
            }
        }

        return new ParsedAnnotations(operations, policyOperations);
    }

    /**
     * {@code @RedisCacheable}:一份投影 → AOP operation + policy operation。
     *
     * <p>方法级目标才有 policy operation(类级声明只参与 Spring 侧发现)。
     */
    private void addCacheable(
            final List<CacheOperation> operations,
            final List<CacheOperation> policyOperations,
            final RedisCacheable annotation,
            final Object target) {
        log.trace("Parsing @RedisCacheable annotation for target: {}", target);
        final RedisCacheAttributes attributes = projector.from(annotation);

        // AOP 面走 Spring 原生 Builder(见类注释的 getClass() 约束)
        final CacheableOperation.Builder builder = new CacheableOperation.Builder();
        builder.setName(AnnotationTargets.extractTargetName(target));
        attributes.applyTo(builder);
        final CacheableOperation operation = builder.build();
        log.debug("Built CacheableOperation: {}", operation);
        operations.add(operation);

        if (target instanceof Method method) {
            policyOperations.add(RedisCacheableOperation.fromAttributes(
                    method, annotation.key(), attributes));
        }
    }

    /**
     * {@code @RedisCacheEvict}:一份投影 → AOP operation + policy operation。
     */
    private void addEvict(
            final List<CacheOperation> operations,
            final List<CacheOperation> policyOperations,
            final RedisCacheEvict annotation,
            final Object target) {
        log.trace("Parsing @RedisCacheEvict annotation for target: {}", target);
        final RedisCacheAttributes attributes = projector.from(annotation);

        // 使用 Spring 标准的 CacheEvictOperation.Builder,确保 getClass() 返回
        // CacheEvictOperation.class —— 这样 CacheAspectSupport 的 CacheOperationContexts
        // 能正确按类型索引(可缓存/可放入/可清除三桶)。ResiCache 增强字段(ttl/bloom/
        // early-expiration 等)不进 Spring operation,由同一份投影的 policy 面
        // 提供给 RedisCacheRegister 查询。(@RedisCacheEvict 的 sync/syncTimeout 是
        // ResiCache 扩展,Spring 原生 CacheEvictOperation 无此概念,此处不投影——
        // 与 Spring 原生 @CacheEvict 行为一致。)
        final CacheEvictOperation.Builder builder = new CacheEvictOperation.Builder();
        builder.setName(AnnotationTargets.extractTargetName(target));
        attributes.applyTo(builder);
        final CacheEvictOperation operation = builder.build();
        log.debug("Built CacheEvictOperation: {}", operation);
        operations.add(operation);

        if (target instanceof Method method) {
            policyOperations.add(RedisCacheEvictOperation.fromAttributes(
                    method, annotation.key(), attributes));
        }
    }

    /**
     * {@code @RedisCachePut}:一份投影 → AOP operation + policy operation。
     */
    private void addPut(
            final List<CacheOperation> operations,
            final List<CacheOperation> policyOperations,
            final RedisCachePut annotation,
            final Object target) {
        log.trace("Parsing @RedisCachePut annotation for target: {}", target);
        final RedisCacheAttributes attributes = projector.from(annotation);

        // 使用 Spring 标准的 CachePutOperation.Builder,确保 getClass() 返回
        // CachePutOperation.class —— 这样 CacheAspectSupport 的 CacheOperationContexts
        // 能正确按类型索引(可缓存/可放入/可清除三桶)。ResiCache 增强字段(ttl/bloom/
        // nullValue/early-expiration 等)不进 Spring operation,由同一份投影的 policy 面
        // 提供给 RedisCacheRegister 查询。
        final CachePutOperation.Builder builder = new CachePutOperation.Builder();
        builder.setName(AnnotationTargets.extractTargetName(target));
        attributes.applyTo(builder);
        final CachePutOperation operation = builder.build();
        log.debug("Built CachePutOperation: {}", operation);
        operations.add(operation);

        if (target instanceof Method method) {
            policyOperations.add(RedisCachePutOperation.fromAttributes(
                    method, annotation.key(), attributes));
        }
    }

    /**
     * Spring 原生 {@code @Cacheable}:只产出 policy operation —— AOP 面由
     * {@link SpringAnnotationAdapter} 负责,避免同一注解解析两次。
     */
    private void addSpringCacheablePolicy(
            final List<CacheOperation> policyOperations,
            final Cacheable annotation,
            final Object target) {
        if (target instanceof Method method) {
            policyOperations.add(springCacheableAdapter.create(method, annotation, annotation.key()));
        }
    }

    record ParsedAnnotations(
            List<CacheOperation> operations,
            List<CacheOperation> policyOperations,
            PolicyIndex policyIndex) {

        ParsedAnnotations(
                List<CacheOperation> operations,
                List<CacheOperation> policyOperations) {
            this(operations, policyOperations, PolicyIndex.of(policyOperations));
        }

        ParsedAnnotations {
            operations = List.copyOf(operations);
            policyOperations = List.copyOf(policyOperations);
        }

        /**
         * 按 kind + cacheName 取 policy operation,未命中返回 {@code null}。
         *
         * <p>查找与声明顺序无关({@link PolicyIndex} 在快照构造时一次建成);
         * 同一 kind/cacheName 被多次声明时后声明者覆盖先声明者。
         */
        @Nullable
        CacheOperation policy(OperationKind kind, String cacheName) {
            return policyIndex.find(kind, cacheName);
        }
    }

    /**
     * {@code kind + cacheName → policy operation} 的不可变索引 —— 覆盖语义
     * ("后声明者覆盖先声明者")只在本类定义一次,读取方不再向后扫描列表。
     */
    record PolicyIndex(Map<OperationKind, Map<String, CacheOperation>> byKind) {

        static PolicyIndex of(List<CacheOperation> policyOperations) {
            EnumMap<OperationKind, Map<String, CacheOperation>> byKind =
                    new EnumMap<>(OperationKind.class);
            for (final CacheOperation operation : policyOperations) {
                for (final OperationKind kind : OperationKind.values()) {
                    if (!kind.operationType().isInstance(operation)) {
                        continue;
                    }
                    final Map<String, CacheOperation> byName =
                            byKind.computeIfAbsent(kind, ignored -> new HashMap<>());
                    for (final String cacheName : operation.getCacheNames()) {
                        byName.put(cacheName, operation);
                    }
                }
            }
            return new PolicyIndex(Map.copyOf(byKind));
        }

        @Nullable
        CacheOperation find(OperationKind kind, String cacheName) {
            Map<String, CacheOperation> byName = byKind.get(kind);
            return byName == null ? null : byName.get(cacheName);
        }
    }
}
