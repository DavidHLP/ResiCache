package io.github.davidhlp.spring.cache.redis.cache;







import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheEvict;
import java.lang.reflect.Method;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.stereotype.Component;

/**
 * 处理 {@link RedisCacheEvict @RedisCacheEvict} 注解：为方法上每个 @RedisCacheEvict
 * 构建并注册一个 {@code RedisCacheEvictOperation}。
 *
 * <p>注册样板（for-loop + null-check + ArrayList 装配）由
 * {@link AbstractAnnotationHandler#registerAll} 承担。本类只负责获取注解数组 + 提供
 * key 提取器（{@code RedisCacheEvict::key}） + 委派。
 *
 * <p>register 调用使用 {@link AbstractAnnotationHandler#registerActionFor(OperationKind)}
 * 工厂 lambda,kind = {@link OperationKind#CACHE_EVICT};factory 以 lambda 直传
 * {@link RedisCacheAttributesProjector#from} +
 * {@link RedisCacheEvictOperation#fromAttributes}。
 */
@Slf4j
@Component
class EvictAnnotationHandler extends AbstractAnnotationHandler {

    private final RedisCacheAttributesProjector projector;

    public EvictAnnotationHandler(
            RedisCacheRegister redisCacheRegister,
            KeyGenerator keyGenerator,
            RedisCacheAttributesProjector projector) {
        super(redisCacheRegister, keyGenerator);
        this.projector = projector;
    }

    @Override
    protected boolean canHandle(Method method) {
        return method.isAnnotationPresent(RedisCacheEvict.class);
    }

    @Override
    protected List<CacheOperation> doHandle(Method method, Object target, Object[] args) {
        RedisCacheEvict[] evicts = method.getAnnotationsByType(RedisCacheEvict.class);
        return registerAll(method, target, args, evicts, RedisCacheEvict::key,
                (m, a, k) -> RedisCacheEvictOperation.fromAttributes(m, k, projector.from(a)),
                registerActionFor(OperationKind.CACHE_EVICT), "cache evict");
    }
}
