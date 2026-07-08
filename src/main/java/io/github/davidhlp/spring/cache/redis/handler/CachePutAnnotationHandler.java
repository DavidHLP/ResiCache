package io.github.davidhlp.spring.cache.redis.handler;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCachePut;
import io.github.davidhlp.spring.cache.redis.factory.CachePutOperationFactory;
import io.github.davidhlp.spring.cache.redis.operation.OperationKind;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheRegister;
import io.github.davidhlp.spring.cache.redis.operation.RedisCachePutOperation;

import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 处理 {@link RedisCachePut @RedisCachePut} 注解：为方法上每个 @RedisCachePut
 * 构建并注册一个 {@link RedisCachePutOperation}。
 *
 * <p>注册样板（for-loop + null-check + ArrayList 装配）已收敛到
 * {@link AbstractAnnotationHandler#registerAll}。本类只负责获取注解数组 + 提供
 * key 提取器（{@code RedisCachePut::key}） + 委派：
 *
 * <pre>
 *   return registerAll(method, target, args, puts, RedisCachePut::key,
 *           cachePutOperationFactory, registerActionFor(OperationKind.CACHE_PUT), "cache put");
 * </pre>
 *
 * <p><b>ADR-0059</b>:原 {@code redisCacheRegister::registerCachePutOperation} 方法引用
 * 改为 {@link AbstractAnnotationHandler#registerActionFor(OperationKind)} 工厂 lambda。
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
        return registerAll(method, target, args, puts, RedisCachePut::key,
                cachePutOperationFactory, registerActionFor(OperationKind.CACHE_PUT), "cache put");
    }
}
