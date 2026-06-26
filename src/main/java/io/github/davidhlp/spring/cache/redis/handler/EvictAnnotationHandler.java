package io.github.davidhlp.spring.cache.redis.handler;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheEvict;
import io.github.davidhlp.spring.cache.redis.factory.EvictOperationFactory;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheEvictOperation;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheRegister;

import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 处理 {@link RedisCacheEvict @RedisCacheEvict} 注解：为方法上每个 @RedisCacheEvict
 * 构建并注册一个 {@link RedisCacheEvictOperation}。
 *
 * <p>注册样板（key 生成 → 工厂创建 → 注册 → 日志，异常返回 null）收敛到
 * {@link AbstractAnnotationHandler#registerOne}，本类只提供工厂与
 * {@code redisCacheRegister::registerCacheEvictOperation} 方法引用。
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
        List<CacheOperation> operations = new ArrayList<>();

        for (RedisCacheEvict evict : evicts) {
            RedisCacheEvictOperation operation = registerOne(
                    method, target, args, evict, evict.key(),
                    evictOperationFactory, redisCacheRegister::registerCacheEvictOperation, "cache evict");
            if (operation != null) {
                operations.add(operation);
            }
        }

        return operations;
    }
}
