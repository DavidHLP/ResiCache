package io.github.davidhlp.spring.cache.redis.factory;

import org.springframework.cache.interceptor.CacheOperation;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

/**
 * 缓存操作工厂接口:把注解 + method + 预生成 key 投影为 {@link CacheOperation}。
 *
 * <p><b>ADR-0028 seam 收窄</b>:删除 {@code supports(Annotation)} 方法 —— 它是
 * ADR-0010 删除 strategy dispatch 后的死残骸(main 零调用,仅测试断言一个从未被
 * 调用的方法)。同时 {@code create} 签名移除 implementation 从未使用的
 * {@code target}/{@code args}(原 5 参 → 3 参),interface 对齐 implementation 真实面。
 *
 * @param <A> 注解类型
 * @param <O> 操作类型
 */
public interface OperationFactory<A extends Annotation, O extends CacheOperation> {

    /**
     * 创建缓存操作对象。
     *
     * @param method     被拦截的方法(用于 operation 的 name 与 fromAttributes 上下文)
     * @param annotation 注解
     * @param key        调用方预生成的缓存 key(已走完 SpEL/KeyGenerator 解析)
     * @return 缓存操作对象
     */
    O create(Method method, A annotation, String key);
}
