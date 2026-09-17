package io.github.davidhlp.spring.cache.redis.cache;





import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.AnnotationCacheOperationSource;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

/**
 * Redis缓存操作源.
 *
 * <p>Spring AOP 注解解析的入口 bean。继承 {@link AnnotationCacheOperationSource}
 * 以满足 {@code CacheAspectSupport} 的硬依赖(本仓不 override 父类的
 * {@code getCacheOperations}),内部以组合方式委托三个无 Spring 继承负担的纯 POJO
 * 协作器完成实际工作:
 * <ul>
 *   <li>{@link AnnotationParser} —— ResiCache 注解解析与策略快照生成(职责1)</li>
 *   <li>本类私有校验 seam —— 操作合法性校验(职责3)</li>
 *   <li>{@link SpringAnnotationAdapter} —— Spring 原生注解兼容(职责2)</li>
 * </ul>
 *
 * <p>编排顺序不可颠倒:AnnotationParser 先解析 ResiCache 注解 → 本类校验每个 op
 * → SpringAnnotationAdapter 后追加 Spring 原生注解(SELECTIVE 去重依赖 ResiCache 已入 ops)。
 *
 * <p>支持 Spring 原生注解 {@code @Cacheable}, {@code @CachePut}, {@code @CacheEvict}
 * 通过 {@link RedisProCacheProperties.NativeAnnotationMode} 控制兼容模式.
 */
@Slf4j
class RedisCacheOperationSource extends AnnotationCacheOperationSource {

    private final AnnotationParser annotationParser;
    private final SpringAnnotationAdapter springAnnotationAdapter;
    private RedisCacheRegister redisCacheRegister;

    public RedisCacheOperationSource() {
        this(RedisProCacheProperties.NativeAnnotationMode.SELECTIVE);
    }

    public RedisCacheOperationSource(
            RedisProCacheProperties.NativeAnnotationMode nativeAnnotationMode) {
        this(nativeAnnotationMode, new AnnotationParser(), null);
    }

    RedisCacheOperationSource(
            RedisProCacheProperties.NativeAnnotationMode nativeAnnotationMode,
            RedisCacheRegister redisCacheRegister) {
        this(nativeAnnotationMode, new AnnotationParser(), redisCacheRegister);
    }

    RedisCacheOperationSource(
            RedisProCacheProperties.NativeAnnotationMode nativeAnnotationMode,
            AnnotationParser annotationParser,
            RedisCacheRegister redisCacheRegister) {
        super(false);
        this.annotationParser = annotationParser;
        this.springAnnotationAdapter = new SpringAnnotationAdapter(nativeAnnotationMode);
        this.redisCacheRegister = redisCacheRegister;
    }

    @Autowired
    void setRedisCacheRegister(RedisCacheRegister redisCacheRegister) {
        this.redisCacheRegister = redisCacheRegister;
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
        final AnnotationParser.ParsedAnnotations parsed = annotationParser.parse(target);
        final List<CacheOperation> ops = new ArrayList<>(parsed.operations());

        for (final CacheOperation op : ops) {
            validate(target, op);
        }

        springAnnotationAdapter.addSpringNativeOperations(target, ops);
        final AnnotationParser.ParsedAnnotations snapshot =
                new AnnotationParser.ParsedAnnotations(ops, parsed.policyOperations());

        if (redisCacheRegister != null && !ops.isEmpty() && target instanceof Method method) {
            redisCacheRegister.registerSnapshot(method, method.getDeclaringClass(), snapshot);
        }

        if (!snapshot.operations().isEmpty()) {
            log.debug("Found {} cache operations for target: {}", snapshot.operations().size(), target);
        } else {
            log.trace("No cache operations found for target: {}", target);
        }

        return snapshot.operations().isEmpty() ? null : snapshot.operations();
    }

    /**
     * 校验单个缓存操作的合法性,保留注解入口原有失败消息与异常语义。
     *
     * @param target 方法或类对象
     * @param operation 缓存操作
     * @throws IllegalStateException 如果配置无效
     */
    private void validate(final Object target, final CacheOperation operation) {
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
}
