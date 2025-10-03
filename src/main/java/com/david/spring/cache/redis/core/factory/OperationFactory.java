package com.david.spring.cache.redis.core.factory;

import org.springframework.cache.interceptor.CacheOperation;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

/**
 * 缓存操作工厂接口
 * 用于创建不同类型的缓存操作对象
 *
 * @param <A> 注解类型
 * @param <O> 操作类型
 */
public interface OperationFactory<A extends Annotation, O extends CacheOperation> {

    /**
     * 创建缓存操作对象
     *
     * @param method 方法
     * @param annotation 注解
     * @param target 目标对象
     * @param args 方法参数
     * @param key 生成的缓存key
     * @return 缓存操作对象
     */
    O create(Method method, A annotation, Object target, Object[] args, String key);

    /**
     * 判断是否支持该注解类型
     *
     * @param annotation 注解
     * @return 是否支持
     */
    boolean supports(Annotation annotation);
}
