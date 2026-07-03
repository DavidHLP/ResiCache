package io.github.davidhlp.spring.cache.redis.chain;

import org.springframework.context.expression.AnnotatedElementKey;

import java.lang.reflect.Method;

/**
 * Path C (WS-1.3) — 方法元数据解析器.
 *
 * <p>封装"当前 AOP 拦截的缓存方法元数据"的访问,默认实现基于 private ThreadLocal。
 * 引入本接口是为了把 ThreadLocal 依赖从静态工具类
 * {@code CacheOperationMetadataHolder} 抽到 Spring 托管的 Bean,以便后续
 * Step 2+ 替换为 {@code ScopedValue} / TTL-aware snapshot 等非 ThreadLocal
 * 实现,而不污染调用点(Step 7 删除静态 holder 时,本接口成为唯一访问面)。
 *
 * <p>Step 1 行为完全不变: 默认实现仍基于 ThreadLocal,只是把所有权从静态类
 * 换到 Bean,调用点从静态方法换到注入的 resolver(无操作重构)。
 */
public interface MethodMetadataResolver {

    /**
     * 当前缓存方法的 {@link AnnotatedElementKey}.
     *
     * @return 当前方法+目标类的组合键;若不在 AOP 拦截作用域内,返回 {@code null}
     */
    AnnotatedElementKey currentKey();

    /**
     * 当前被拦截的方法.
     *
     * @return 当前方法;若不在作用域内,返回 {@code null}
     */
    Method currentMethod();

    /**
     * 当前目标类(原始类,非代理类).
     *
     * @return 当前目标类;若不在作用域内,返回 {@code null}
     */
    Class<?> currentTargetClass();

    /**
     * Path C Step 2+ — 当前调用上下文的不可变快照(完整 method/targetClass/key 包装).
     *
     * <p>Step 2 引入的便捷方法,供 {@code RedisProCacheWriter.retrieve()/store()}
     * 异步透传场景使用(Step 6 接入)。当前实现从 {@link #currentKey()} 反射构造;
     * Step 6+ 改用 {@code ScopedValue<CacheInvocationContext>} 时,可直接读
     * ScopedValue 跳过反射开销。
     *
     * @return 当前上下文;若不在作用域内,返回 {@code null}
     */
    CacheInvocationContext currentContext();

    /**
     * 激活:临时把当前方法的元数据置入本解析器的作用域。
     * 返回的 {@link ScopedActivation} 用于 try-with-resources,保证
     * {@link ScopedActivation#close()} 时恢复到调用前状态(嵌套调用安全)。
     *
     * <p>典型用法:
     * <pre>{@code
     * try (ScopedActivation ignored = resolver.activate(method, targetClass)) {
     *     // 链 / writer 在此作用域内可读 currentKey/currentMethod/currentTargetClass
     * }
     * }</pre>
     *
     * @param method      被拦截的方法
     * @param targetClass 目标类
     * @return AutoCloseable 句柄
     */
    ScopedActivation activate(Method method, Class<?> targetClass);

    /**
     * Path C Step 6 / ADR-0035 — 在异步边界(commonPool 切线程)内执行 work,
     * 保证 work 读到的方法元数据 + MDC 与提交线程一致。
     *
     * <p>默认 no-op(直接执行 work)—— 适用于非 ThreadLocal 实现(如未来
     * {@code ScopedValue}-based resolver)。{@link DefaultMethodMetadataResolver}
     * 覆盖本方法,snapshot/restore 自身 ThreadLocal + MDC,防 commonPool 线程复用
     * 导致 context 跨任务泄漏。
     *
     * <p>边界管理归位本 resolver(owner),消除原 {@code RedisProCacheWriter}
     * 持有 snapshot/restore + {@code DefaultMethodMetadataResolver.clearStatic}
     * 的跨域寄生(ADR-0035)。
     *
     * @param work 异步工作
     * @param <T>  返回类型
     * @return work 结果
     */
    default <T> T runWithSnapshot(java.util.function.Supplier<T> work) {
        return work.get();
    }
}
