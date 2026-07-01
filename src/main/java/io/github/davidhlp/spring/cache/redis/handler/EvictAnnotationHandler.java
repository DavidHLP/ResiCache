package io.github.davidhlp.spring.cache.redis.handler;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheEvict;
import io.github.davidhlp.spring.cache.redis.factory.EvictOperationFactory;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheRegister;

import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 处理 {@link RedisCacheEvict @RedisCacheEvict} 注解：为方法上每个 @RedisCacheEvict
 * 构建并注册一个 {@code RedisCacheEvictOperation}。
 *
 * <p>注册样板（for-loop + null-check + ArrayList 装配）已收敛到
 * {@link AbstractAnnotationHandler#registerAll}。本类只负责获取注解数组 + 提供
 * key 提取器（{@code RedisCacheEvict::key}） + 委派。
 */
@Slf4j
@Component
public class EvictAnnotationHandler extends AbstractAnnotationHandler {

    private final EvictOperationFactory evictOperationFactory;

    public EvictAnnotationHandler(
            RedisCacheRegister redisCacheRegister,
            KeyGenerator keyGenerator,
            EvictOperationFactory evictOperationFactory) {
        super(redisCacheRegister, keyGenerator);
        this.evictOperationFactory = evictOperationFactory;
    }

    @Override
    protected boolean canHandle(Method method) {
        return method.isAnnotationPresent(RedisCacheEvict.class);
    }

    @Override
    protected List<CacheOperation> doHandle(Method method, Object target, Object[] args) {
        RedisCacheEvict[] evicts = method.getAnnotationsByType(RedisCacheEvict.class);
        return registerAll(method, target, args, evicts, RedisCacheEvict::key,
                evictOperationFactory, redisCacheRegister::registerCacheEvictOperation, "cache evict");
    }
}
