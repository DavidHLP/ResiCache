package io.github.davidhlp.spring.cache.redis.annotation;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;

import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.annotation.AnnotationCacheOperationSource;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.lang.Nullable;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Redis缓存操作源.
 *
 * <p>Spring AOP 注解解析的入口 bean。继承 {@link AnnotationCacheOperationSource}
 * 以满足 {@code CacheAspectSupport} 的硬依赖(本仓不 override 父类的
 * {@code getCacheOperations}),内部以组合方式委托三个无 Spring 继承负担的纯 POJO
 * 协作器完成实际工作:
 * <ul>
 *   <li>{@link AnnotationParser} —— ResiCache 注解解析(职责1)</li>
 *   <li>{@link OperationValidator} —— 操作合法性校验(职责3)</li>
 *   <li>{@link SpringAnnotationAdapter} —— Spring 原生注解兼容(职责2)</li>
 * </ul>
 *
 * <p>编排顺序不可颠倒:AnnotationParser 先解析 ResiCache 注解 → Validator 校验每个 op
 * → SpringAnnotationAdapter 后追加 Spring 原生注解(SELECTIVE 去重依赖 ResiCache 已入 ops)。
 *
 * <p>支持 Spring 原生注解 {@code @Cacheable}, {@code @CachePut}, {@code @CacheEvict}
 * 通过 {@link RedisProCacheProperties.NativeAnnotationMode} 控制兼容模式.
 */
@Slf4j
public class RedisCacheOperationSource extends AnnotationCacheOperationSource {

    private final AnnotationParser annotationParser;
    private final OperationValidator operationValidator;
    private final SpringAnnotationAdapter springAnnotationAdapter;

    public RedisCacheOperationSource() {
        this(RedisProCacheProperties.NativeAnnotationMode.FULL);
    }

    public RedisCacheOperationSource(
            RedisProCacheProperties.NativeAnnotationMode nativeAnnotationMode) {
        super(false);
        this.annotationParser = new AnnotationParser();
        this.operationValidator = new OperationValidator();
        this.springAnnotationAdapter = new SpringAnnotationAdapter(nativeAnnotationMode);
    }

    @Override
    @Nullable
    protected Collection<CacheOperation> findCacheOperations(final Method method) {
        return parseCacheOperations(method);
    }

    @Override
    @Nullable
    protected Collection<CacheOperation> findCacheOperations(final Class<?> clazz) {
        return parseCacheOperations(clazz);
    }

    /**
     * 解析缓存注解.
     *
     * <p>编排:ResiCache 注解解析 → 逐个校验 → Spring 原生注解追加 → 返回不可变集合(空返回 null)。
     *
     * @param target 方法或类对象
     * @return 缓存操作集合
     */
    @Nullable
    private Collection<CacheOperation> parseCacheOperations(final Object target) {
        final List<CacheOperation> ops =
                annotationParser.parseResiCacheAnnotations(target);

        for (final CacheOperation op : ops) {
            operationValidator.validate(target, op);
        }

        springAnnotationAdapter.addSpringNativeOperations(target, ops);

        if (!ops.isEmpty()) {
            log.debug("Found {} cache operations for target: {}", ops.size(), target);
        } else {
            log.trace("No cache operations found for target: {}", target);
        }

        return ops.isEmpty() ? null : Collections.unmodifiableList(ops);
    }
}
