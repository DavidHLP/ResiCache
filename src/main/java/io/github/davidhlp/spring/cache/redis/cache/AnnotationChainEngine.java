package io.github.davidhlp.spring.cache.redis.cache;





import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.stereotype.Component;

/**
 * 注解解析责任链推进引擎.
 *
 * <p>把"链推进 + handler 求值 + 结果收集"三件关注点集中到单一 {@code @Component} seam。
 *
 * <p><b>推进协议</b>:Engine 持有有序的 {@link AnnotationHandler} 列表(构造期由 Spring 一次性
 * 注入),按顺序对每个 handler 做:
 * <ol>
 *   <li>{@code canHandle(method)} 判定 — 不命中则跳过,链中所有 handler 都有平等
 *       参与机会(filter 语义,无 decision 短路)</li>
 *   <li>命中则调 {@code doHandle(method, target, args)} 收集 0+ 个 {@link CacheOperation}</li>
 *   <li>结果追加到累计列表(per-handler 异常隔离 — 单 handler 失败记 ERROR 后继续遍历,
 *       不影响其他 handler,符合"单个 handler 失败不应中断整个缓存链路"的本意)</li>
 * </ol>
 *
 * <p><b>无 observer 通道</b>:{@link #execute} 是纯粹的"遍历 + 收集",不涉及
 * MDC / 计时 / observer 钩子 —— 与 cache 写入侧的 {@code chain.ChainEngine}(有
 * {@code ChainObserver} 观测通道)不同,注解解析是启动近似静态的一次性映射,无观测价值。
 *
 * <p><b>线程安全</b>:Engine 单例 Bean;handler 列表构造期一次性注入
 * ({@code List.copyOf} 不可变),运行期不变,无需读写锁守护(对比
 * {@code CacheHandlerChain} 的 addHandler 写锁场景)。
 *
 * <p><b>与 ChainEngine 的关系</b>:本 Engine 是 cache 写入链推进引擎
 * ({@code chain.ChainEngine})的<em>平行 seam</em>,非复用 — 决策语义不同
 * (filter vs decision),合并会导致抽象过载。
 */
@Slf4j
@Component
class AnnotationChainEngine {

    /** 注解元素解析后的不可变策略快照 */
    private final RedisCacheRegister redisCacheRegister;
    private final List<AnnotationHandler> handlers;

    /** 供直接单测使用的 handler-only 构造入口 */
    public AnnotationChainEngine(List<AnnotationHandler> handlers) {
        this(handlers, null);
    }

    @Autowired
    public AnnotationChainEngine(
            List<AnnotationHandler> handlers,
            RedisCacheRegister redisCacheRegister) {
        this.handlers = List.copyOf(handlers);
        this.redisCacheRegister = redisCacheRegister;
        log.debug("AnnotationChainEngine initialized with {} handlers: {}",
                this.handlers.size(),
                this.handlers.stream().map(h -> h.getClass().getSimpleName()).toList());
    }

    /**
     * 执行注解解析责任链 — 遍历所有 handler 并收集其产出的 {@link CacheOperation}。
     *
     * <p>执行流程:
     * <ol>
     *   <li>遍历 handlers,对每个 {@code canHandle} 命中的 handler 调 {@code doHandle} 收集结果
     *       (per-handler 异常隔离,不污染其他 handler)</li>
     *   <li>返回不可变结果列表</li>
     * </ol>
     *
     * <p>handler 列表从构造函数注入,运行期不变 — 无需读写锁守护(对比
     * {@code CacheHandlerChain} 的 addHandler 写锁场景)。Spring 容器保证启动
     * 期所有 handler Bean 就绪后才注入 Engine,运行期无结构变更。
     *
     * @param method 当前解析的目标方法
     * @param target 方法所属的目标对象
     * @param args 方法参数
     * @return 收集到的 CacheOperation 列表(可能为空,不会为 null)
     */
    public List<CacheOperation> execute(Method method, Object target, Object[] args) {
        if (method == null) {
            throw new IllegalArgumentException("method must not be null");
        }
        if (redisCacheRegister != null) {
            Class<?> targetClass = target != null ? target.getClass() : method.getDeclaringClass();
            AnnotationParser.ParsedAnnotations snapshot =
                    redisCacheRegister.getSnapshot(method, targetClass);
            return snapshot == null
                    ? Collections.emptyList()
                    : snapshot.policyOperations();
        }
        // handler-only fallback is retained for direct unit tests.
        Object[] safeArgs = args != null ? args : new Object[0];

        // 直接遍历 handlers — per-handler 异常隔离即可。
        List<CacheOperation> collected = new ArrayList<>();
        for (AnnotationHandler handler : handlers) {
            if (!handler.canHandle(method)) {
                continue;
            }
            try {
                List<CacheOperation> ops = handler.doHandle(method, target, safeArgs);
                if (ops != null && !ops.isEmpty()) {
                    collected.addAll(ops);
                }
            } catch (Exception handlerEx) {
                // 单 handler 异常隔离：记 ERROR 日志，继续遍历剩余 handler
                log.error("AnnotationHandler.doHandle failed: {}, method: {}",
                        handler.getClass().getSimpleName(), method.getName(), handlerEx);
            }
        }

        return Collections.unmodifiableList(collected);
    }
}
