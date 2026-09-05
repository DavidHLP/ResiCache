package io.github.davidhlp.spring.cache.redis.cache;





import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import org.springframework.cache.interceptor.CacheOperation;

/**
 * 缓存操作工厂接口:把注解 + method + 预生成 key 投影为 {@link CacheOperation}。
 *
 * @param <A> 注解类型
 * @param <O> 操作类型
 */
interface OperationFactory<A extends Annotation, O extends CacheOperation> {

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
