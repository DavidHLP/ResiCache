package io.github.davidhlp.spring.cache.redis.handler;

import io.github.davidhlp.spring.cache.redis.factory.OperationFactory;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheRegister;

import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.util.StringUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

/**
 * 注解处理器模板基类，收敛四个 handler 的注册样板：
 * <ul>
 *   <li>{@link #generateKey(Object, Method, Object[], String)} —— key 表达式优先，否则 KeyGenerator
 *       （原散落 3 份逐字重复）</li>
 *   <li>{@link #registerOne(Method, Object, Object[], Annotation, String, OperationFactory, RegisterAction, String)}
 *       —— key 生成 → 工厂创建 → 注册 → 日志 的 try/catch 模板，异常返回 null
 *       （逐字保留原语义：单个注解解析失败不得中断整个拦截器链导致缓存全失效）</li>
 * </ul>
 *
 * <p>子类只需在 {@code doHandle} 中调用 {@code registerOne(...)} 并传入自己的工厂与
 * {@code redisCacheRegister::registerXxxOperation} 方法引用。
 */
@Slf4j
public abstract class AbstractAnnotationHandler extends AnnotationHandler {

    protected final RedisCacheRegister redisCacheRegister;
    protected final KeyGenerator keyGenerator;

    protected AbstractAnnotationHandler(RedisCacheRegister redisCacheRegister, KeyGenerator keyGenerator) {
        this.redisCacheRegister = redisCacheRegister;
        this.keyGenerator = keyGenerator;
    }

    /** 解析 key：有表达式（SpEL/字面量）则用之，否则走 KeyGenerator */
    protected String generateKey(Object target, Method method, Object[] args, String keyExpression) {
        if (StringUtils.hasText(keyExpression)) {
            return keyExpression;
        }
        Object key = keyGenerator.generate(target, method, args);
        return String.valueOf(key);
    }

    /** 注册动作的函数式接口，对齐 {@code RedisCacheRegister::registerXxxOperation(Method, Class, O)} 签名 */
    @FunctionalInterface
    protected interface RegisterAction<O> {
        void register(Method method, Class<?> targetClass, O operation);
    }

    /**
     * 注册单个操作的标准模板：key 生成 → 工厂创建 → 注册 → 日志；异常返回 null。
     *
     * @param logTag 日志标识（如 "cacheable" / "cache put"），用于统一日志与错误信息
     * @param <A> 注解类型
     * @param <O> 操作类型
     * @return 创建并注册成功的操作，失败返回 null
     */
    protected <A extends Annotation, O extends CacheOperation> O registerOne(
            Method method, Object target, Object[] args, A annotation, String keyExpression,
            OperationFactory<A, O> factory, RegisterAction<O> registerAction, String logTag) {
        try {
            String key = generateKey(target, method, args, keyExpression);
            O operation = factory.create(method, annotation, target, args, key);
            Class<?> targetClass = target != null ? target.getClass() : null;
            registerAction.register(method, targetClass, operation);
            log.debug("Registered {} operation: {} with key: {} for caches: {}",
                    logTag, method.getName(), key, String.join(",", operation.getCacheNames()));
            return operation;
        } catch (Exception e) {
            log.error("Failed to register {} operation", logTag, e);
            return null;
        }
    }
}
