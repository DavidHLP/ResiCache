package io.github.davidhlp.spring.cache.redis.handler;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCachePut;
import io.github.davidhlp.spring.cache.redis.factory.CachePutOperationFactory;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheRegister;
import io.github.davidhlp.spring.cache.redis.operation.RedisCachePutOperation;

import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 处理 {@link RedisCachePut @RedisCachePut} 注解：为方法上每个 @RedisCachePut
 * 构建并注册一个 {@link RedisCachePutOperation}。
 *
 * <p>注册样板（key 生成 → 工厂创建 → 注册 → 日志，异常返回 null）收敛到
 * {@link AbstractAnnotationHandler#registerOne}，本类只提供工厂与
 * {@code redisCacheRegister::registerCachePutOperation} 方法引用。
 */
@Slf4j
@Component
public class CachePutAnnotationHandler extends AbstractAnnotationHandler {

    private final CachePutOperationFactory cachePutOperationFactory;

    public CachePutAnnotationHandler(
            RedisCacheRegister redisCacheRegister,
            KeyGenerator keyGenerator,
            CachePutOperationFactory cachePutOperationFactory) {
        super(redisCacheRegister, keyGenerator);
        this.cachePutOperationFactory = cachePutOperationFactory;
    }

    @Override
    protected boolean canHandle(Method method) {
        return method.isAnnotationPresent(RedisCachePut.class);
    }

    @Override
    protected List<CacheOperation> doHandle(Method method, Object target, Object[] args) {
        RedisCachePut[] puts = method.getAnnotationsByType(RedisCachePut.class);
        List<CacheOperation> operations = new ArrayList<>();

        for (RedisCachePut put : puts) {
            RedisCachePutOperation operation = registerOne(
                    method, target, args, put, put.key(),
                    cachePutOperationFactory, redisCacheRegister::registerCachePutOperation, "cache put");
            if (operation != null) {
                operations.add(operation);
            }
        }

        return operations;
    }
}
