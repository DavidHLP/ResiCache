package io.github.davidhlp.spring.cache.redis.chain;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.expression.AnnotatedElementKey;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * Path C (WS-1.3) — 方法元数据解析器默认实现(且 ThreadLocal 所有者).
 *
 * <p>数据所有权(经 7 步迭代后):
 * <ul>
 *   <li>Step 1: ThreadLocal 在 {@code CacheOperationMetadataHolder} 静态类,本 resolver
 *       仅为调用方门面</li>
 *   <li>Step 2: 引入 {@link CacheInvocationContext} 值对象 + 在 resolver 暴露
 *       {@link #currentContext()};ScopedValue 字段声明但未激活</li>
 *   <li>Step 7 (本类):<strong>ThreadLocal 所有权从静态类迁到本 resolver 静态字段</strong>,
 *       {@code CacheOperationMetadataHolder} 静态类删除,所有 set/clear 改走
 *       {@link #activateStatic}/{@link #clearStatic};instance 方法直接读 OWN ThreadLocal
 *       (不再委托静态类)</li>
 * </ul>
 *
 * <p>设计选择 — 静态方法 vs 实例方法:
 * <ul>
 *   <li>写入 API(activateStatic/clearStatic)<strong>保持静态</strong> — 调用方
 *       (RedisProCacheWriter、ResiCacheMethodInterceptor、tests) 在
 *       拦截器作用域内不一定持有 resolver 引用,静态 API 降低耦合</li>
 *   <li>读取 API(currentKey/currentMethod/currentTargetClass/currentContext)
 *       是实例方法 — 调用方(RedisProCacheWriter.buildContext、
 *       RedisProCache.lookupOperation) 持有 resolver 引用,直接调</li>
 * </ul>
 *
 * <p>线程安全: 静态 {@code ThreadLocal<AnnotatedElementKey>} 天然线程隔离,
 * 配合 {@link #clearStatic} 在 finally 调用防 commonPool 线程复用导致
 * ThreadLocal 跨任务泄漏。
 */
@Slf4j
@Component
public class DefaultMethodMetadataResolver implements MethodMetadataResolver {

    /**
     * Step 7 落地:ThreadLocal 所有权从 {@code CacheOperationMetadataHolder}
     * 静态类迁到本 resolver(静态字段 — Spring Bean 单例,所有实例共享
     * 同一 ThreadLocal 存储)。
     */
    private static final ThreadLocal<AnnotatedElementKey> CURRENT_KEY = new ThreadLocal<>();

    // ==================== 实例方法(读取 API) ====================

    @Override
    public AnnotatedElementKey currentKey() {
        return CURRENT_KEY.get();
    }

    @Override
    public Method currentMethod() {
        AnnotatedElementKey key = currentKey();
        if (key == null) {
            return null;
        }
        Object element = reflectField(key, "element");
        return element instanceof Method ? (Method) element : null;
    }

    @Override
    public Class<?> currentTargetClass() {
        AnnotatedElementKey key = currentKey();
        if (key == null) {
            return null;
        }
        Object targetClass = reflectField(key, "targetClass");
        return targetClass instanceof Class<?> ? (Class<?>) targetClass : null;
    }

    @Override
    public CacheInvocationContext currentContext() {
        return CacheInvocationContext.of(currentKey());
    }

    @Override
    public ScopedActivation activate(Method method, Class<?> targetClass) {
        Method previousMethod = currentMethod();
        Class<?> previousTargetClass = currentTargetClass();

        activateStatic(method, targetClass);
        log.debug("Activated method metadata: method={}, targetClass={}", method.getName(), targetClass.getName());

        return new ScopedActivation(() -> {
            if (previousMethod == null) {
                clearStatic();
            } else {
                activateStatic(previousMethod, previousTargetClass);
            }
        });
    }

    // ==================== 静态方法(写入 API) ====================

    /**
     * 设置当前线程的缓存操作元数据键 — Step 7 后所有写入路径都走这里
     * (替代已删除的 {@code CacheOperationMetadataHolder.setCurrentKey})。
     *
     * @param method      被拦截的方法
     * @param targetClass 目标类(原始类,非代理类)
     */
    public static void activateStatic(Method method, Class<?> targetClass) {
        CURRENT_KEY.set(new AnnotatedElementKey(method, targetClass));
    }

    /**
     * 清除当前线程的缓存操作元数据键 — Step 7 后所有清除路径都走这里
     * (替代已删除的 {@code CacheOperationMetadataHolder.clear})。
     * 防 commonPool 线程复用导致 ThreadLocal 跨任务泄漏。
     */
    public static void clearStatic() {
        CURRENT_KEY.remove();
    }

    // ==================== 反射工具 ====================

    /**
     * 反射读取 {@link AnnotatedElementKey} 的 private 字段(Spring 6.2 之前无公开 getter)。
     */
    private static Object reflectField(AnnotatedElementKey key, String fieldName) {
        try {
            java.lang.reflect.Field f = AnnotatedElementKey.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.get(key);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            log.warn("Failed to reflect field '{}' from AnnotatedElementKey", fieldName, e);
            return null;
        }
    }
}
