package io.github.davidhlp.spring.cache.redis.cache;







import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheEvict;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCachePut;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCaching;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

/**
 * 处理 {@link RedisCaching @RedisCaching} 组合注解：将其内嵌的 @RedisCacheable /
 * @RedisCacheEvict / @RedisCachePut 各自展开并注册。
 *
 * <p>三种子注解的注册均复用 {@link AbstractAnnotationHandler#registerAll} 模板:
 * <pre>
 *   operations.addAll(registerAll(method, target, args, caching.redisCacheable(),
 *           RedisCacheable::key,
 *           (m, a, k) -> RedisCacheableOperation.fromAttributes(m, k, projector.from(a)),
 *           registerActionFor(OperationKind.CACHEABLE), "cacheable from @RedisCaching"));
 * </pre>
 * 三个 for-loop 全部下沉到基类,本类只剩 3 行 {@code addAll} 委派。
 *
 * <p>三处 register 调用使用 {@link AbstractAnnotationHandler#registerActionFor(OperationKind)}
 * 工厂 lambda(kind 在编译期固定),register API 为 2 方法,新增操作类型仅需追加
 * enum 行 + register/get 内部 switch —— 调用方零改动。
 *
 * <p>factory 以 lambda 直传 {@link RedisCacheAttributesProjector#from} +
 * {@code XxxOperation.fromAttributes},本类只持有 1 个 projector 字段。
 */
@Slf4j
@Component
class CachingAnnotationHandler extends AbstractAnnotationHandler {

    private final RedisCacheAttributesProjector projector;

    public CachingAnnotationHandler(
            RedisCacheRegister redisCacheRegister,
            KeyGenerator keyGenerator,
            RedisCacheAttributesProjector projector) {
        super(redisCacheRegister, keyGenerator);
        this.projector = projector;
    }

    @Override
    protected boolean canHandle(Method method) {
        return AnnotatedElementUtils.findMergedAnnotation(method, RedisCaching.class) != null;
    }

    @Override
    protected List<CacheOperation> doHandle(Method method, Object target, Object[] args) {
        RedisCaching caching = AnnotatedElementUtils.findMergedAnnotation(method, RedisCaching.class);
        List<CacheOperation> operations = new ArrayList<>();

        // 处理组合注解中的 @RedisCacheable
        operations.addAll(registerAll(method, target, args, caching.redisCacheable(),
                RedisCacheable::key,
                (m, a, k) -> RedisCacheableOperation.fromAttributes(m, k, projector.from(a)),
                registerActionFor(OperationKind.CACHEABLE), "cacheable from @RedisCaching"));

        // 处理组合注解中的 @RedisCacheEvict
        operations.addAll(registerAll(method, target, args, caching.redisCacheEvict(),
                RedisCacheEvict::key,
                (m, a, k) -> RedisCacheEvictOperation.fromAttributes(m, k, projector.from(a)),
                registerActionFor(OperationKind.CACHE_EVICT), "cache evict from @RedisCaching"));

        // 处理组合注解中的 @RedisCachePut
        operations.addAll(registerAll(method, target, args, caching.redisCachePut(),
                RedisCachePut::key,
                (m, a, k) -> RedisCachePutOperation.fromAttributes(m, k, projector.from(a)),
                registerActionFor(OperationKind.CACHE_PUT), "cache put from @RedisCaching"));

        return operations;
    }
}
