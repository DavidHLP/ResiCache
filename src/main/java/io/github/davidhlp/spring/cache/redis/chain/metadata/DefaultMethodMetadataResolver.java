package io.github.davidhlp.spring.cache.redis.chain.metadata;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.expression.AnnotatedElementKey;

import java.lang.reflect.Method;

/**
 * 方法元数据解析器默认实现(且 ThreadLocal 所有者).
 *
 * <p>数据所有权:ThreadLocal 存储为本 resolver 的静态字段 —— Spring Bean 单例,
 * 所有实例共享同一 ThreadLocal 存储。
 *
 * <p>写入 API 仅通过 {@link #activate(Method, Class)} 暴露;默认实现以
 * ThreadLocal 隔离线程状态。异步生命周期由接口默认的 capture/restore/clear
 * 负责,并在 worker finally 恢复原状态。
 *
 * <ul>
 *   <li>读取 API(currentKey/currentMethod/currentTargetClass/currentContext)
 *       是实例方法 — 调用方持有 resolver 引用直接调用</li>
 * </ul>
 *
 * <p>线程安全: 静态 {@code ThreadLocal<AnnotatedElementKey>} 天然线程隔离,
 * 配合 {@link #clearStatic} 在 finally 调用防 commonPool 线程复用导致
 * ThreadLocal 跨任务泄漏。
 */
@Slf4j
public class DefaultMethodMetadataResolver implements MethodMetadataResolver {

    /**
     * ThreadLocal 存储 — Spring Bean 单例,所有实例共享同一 ThreadLocal 存储。
     */
    private static final ThreadLocal<AnnotatedElementKey> CURRENT_KEY = new ThreadLocal<>();

    // ==================== 实例方法(读取 API) ====================

    @Override
    public AnnotatedElementKey currentKey() {
        return CURRENT_KEY.get();
    }

    @Override
    public Method currentMethod() {
        return MetadataKeys.extractMethod(currentKey());
    }

    @Override
    public Class<?> currentTargetClass() {
        return MetadataKeys.extractTargetClass(currentKey());
    }

    @Override
    public MethodSnapshot currentContext() {
        return MethodSnapshot.of(currentKey());
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
     * 设置当前线程的缓存操作元数据键 — {@code private static},仅供本类 {@link #activate} 内部调用。
     *
     * @param method      被拦截的方法
     * @param targetClass 目标类(原始类,非代理类)
     */
    private static void activateStatic(Method method, Class<?> targetClass) {
        CURRENT_KEY.set(new AnnotatedElementKey(method, targetClass));
    }

    /**
     * 清除当前线程的缓存操作元数据键 — {@code private static},
     * 仅供本类 {@link #activate}(回滚分支)与 {@link #runWithSnapshot}(finally)调用。
     * 防 commonPool 线程复用导致 ThreadLocal 跨任务泄漏。
     */
    private static void clearStatic() {
        CURRENT_KEY.remove();
    }


    // ==================== 反射工具 ====================
}