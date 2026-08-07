package io.github.davidhlp.spring.cache.redis.chain.metadata;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.expression.AnnotatedElementKey;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 方法元数据解析器默认实现(且 ThreadLocal 所有者).
 *
 * <p>数据所有权:ThreadLocal 存储为本 resolver 的静态字段 —— Spring Bean 单例,
 * 所有实例共享同一 ThreadLocal 存储。
 *
 * <p>设计选择:
 * <ul>
 *   <li>写入 API({@link #activateStatic} / {@link #clearStatic})是
 *       <strong>{@code private static}</strong> —— 仅本类
 *       {@link #activate} 与 {@link #restoreKey} 调用;{@link CacheInvocationContext}
 *       改走 instance {@link #restoreKey}。ThreadLocal 双写路径消除
 *       (只有 {@code activate()} 这一个公开写入入口)。</li>
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

    /**
     * 实例级 fire-and-forget 写入入口,供 {@link CacheInvocationContext#restore}
     * 在异步边界(commonPool 切线程)调用。
     *
     * <p>区别于 {@link #activate}:本方法<strong>不返回 ScopedActivation</strong>,
     * 不追踪"先前状态";调用方负责在合适的 finally 中调用本类的 {@link #runWithSnapshot}
     * 路径(其内部 finally 会清 ThreadLocal)。
     *
     * <p>可见性 {@code package-private}:仅 {@link CacheInvocationContext} 在同包内可见,
     * 杜绝外部绕开 {@link #activate} 直接写入 ThreadLocal(消除双写路径)。
     */
    void restoreKey(Method method, Class<?> targetClass) {
        CURRENT_KEY.set(new AnnotatedElementKey(method, targetClass));
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

    // ==================== 异步边界管理 ====================

    /**
     * 在异步边界(commonPool 切线程)内执行 work,snapshot/restore 自身
     * ThreadLocal + MDC,保证 work 读到的 context 与提交线程一致。
     *
     * <p>resolver 自管自身的 ThreadLocal + MDC 边界,writer 不感知 {@code clearStatic}。
     *
     * <p>MDC 一并内聚:同为「提交线程 → commonPool 线程」需透传的调用 context,
     * 集中一处优于分散。非 ThreadLocal 实现走接口默认 no-op。
     */
    @Override
    public <T> T runWithSnapshot(Supplier<T> work) {
        CacheInvocationContext snapshot = CacheInvocationContext.snapshot(this);
        boolean restored = false;
        Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();
        boolean mdcRestored = false;
        try {
            if (snapshot != null) {
                snapshot.restore(this);
                restored = true;
            }
            if (mdcSnapshot != null && !mdcSnapshot.isEmpty()) {
                MDC.setContextMap(mdcSnapshot);
                mdcRestored = true;
            }
            return work.get();
        } finally {
            // 仅在 restore 过的线程上清,避免误清其他并发调用方设置的状态
            if (restored) {
                clearStatic();
            }
            if (mdcRestored) {
                MDC.clear();
            }
        }
    }

    // ==================== 反射工具 ====================
}