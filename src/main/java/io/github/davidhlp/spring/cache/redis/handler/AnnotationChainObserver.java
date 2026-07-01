package io.github.davidhlp.spring.cache.redis.handler;

import org.springframework.cache.interceptor.CacheOperation;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 注解解析责任链的观测注入点 — ADR-0013 (Annotation Chain Engine extraction).
 *
 * <p>本接口把"链入口 / 链出口"的横切关注点收口到 2 个 default no-op 钩子,
 * 让 {@link AnnotationChainEngine} 与 4 个具体 {@link AnnotationHandler} 不再
 * 各自承担观测逻辑(原 {@code AnnotationHandler.handle} 内联执行流无观测 seam)。
 *
 * <p>Engine 驱动推进时按下列顺序调用各 observer:
 *
 * <ol>
 *   <li>{@link #onChainStart(Method, Object, Object[])} — 链入口 around-hook
 *       (典型实现:MDC stamp / 计时起点 / DEBUG 日志)</li>
 *   <li>每个 handler:
 *     <ul>
 *       <li>handler.canHandle(method) 判定</li>
 *       <li>handler.doHandle(method, target, args) 收集 CacheOperation</li>
 *     </ul>
 *   </li>
 *   <li>{@link #onChainEnd(Method, Object, Object[], List)} — 链出口 around-hook
 *       (典型实现:计时结束 / DEBUG 日志汇总)</li>
 * </ol>
 *
 * <p>所有钩子默认 no-op;observability 实现(DEBUG log / 计时)各自只 override 关心
 * 的钩子,正交组合。Engine 与 handler 子类零修改即可接入新观测关注点 — 这是本 seam
 * 的核心 leverage。
 *
 * <p><b>线程模型</b>:observer 实现必须线程安全(Engine 在多线程环境共享同一
 * observer 列表),不要在 observer 内持有 per-call 状态(可放 Engine 上下文中)。
 *
 * <p><b>与 ChainObserver 的关系</b>:本接口是 cache 写入链观测 seam
 * ({@code chain.ChainObserver})的<em>平行 seam</em>,而非复用 — 两条链的
 * 决策语义(filter vs decision)不同,合并会导致 seam 抽象过载。
 */
public interface AnnotationChainObserver {

    /**
     * 链入口 hook。Engine 在遍历 handler 之前调用。
     *
     * @param method 当前解析的目标方法
     * @param target 方法所属的目标对象
     * @param args 方法参数
     */
    default void onChainStart(Method method, Object target, Object[] args) {
        // no-op
    }

    /**
     * 链出口 hook。Engine 在所有 handler.doHandle 调用完成后、返回结果前调用。
     * 即:即便中间 handler 抛异常,Engine 的 try/finally 也会保证本钩子被调用(语义
     * 详见 {@link AnnotationChainEngine#execute(Method, Object, Object[])})。
     *
     * @param method 当前解析的目标方法
     * @param target 方法所属的目标对象
     * @param args 方法参数
     * @param result 链执行最终结果(可能为空 list,异常路径下为部分结果)
     */
    default void onChainEnd(
            Method method, Object target, Object[] args, List<CacheOperation> result) {
        // no-op
    }
}
