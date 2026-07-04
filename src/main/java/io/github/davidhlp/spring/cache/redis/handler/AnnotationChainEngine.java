package io.github.davidhlp.spring.cache.redis.handler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 注解解析责任链推进引擎 — ADR-0013 (Annotation Chain Engine extraction).
 *
 * <p>把"链推进 + handler 求值 + 结果收集 + 观测编排"四件关注点集中到单一
 * {@code @Component} seam,替换原先散落在 {@link AnnotationHandler#handle(Method, Object, Object[])}
 * 的并行实现(约 25 SLOC 中 ~15 SLOC 是递归样板)。
 *
 * <p><b>推进协议</b>:Engine 持有有序的 {@link AnnotationHandler} 列表(snapshot
 * 形式,对应原 {@code AnnotationHandler.next} 链表),按顺序对每个 handler 做:
 * <ol>
 *   <li>{@code canHandle(method)} 判定 — 不命中则跳过,链中所有 handler 都有平等
 *       参与机会(filter 语义,无 decision 短路)</li>
 *   <li>命中则调 {@code doHandle(method, target, args)} 收集 0+ 个 {@link CacheOperation}</li>
 *   <li>结果追加到累计列表(并发异常隔离 — 单 handler 失败不影响其他 handler,
 *       与原 {@code AnnotationHandler.handle} 行为一致:整个链解析失败不阻塞拦截器)</li>
 * </ol>
 *
 * <p><b>观测编排</b>:Engine 在链入口调用所有 observer 的
 * {@link AnnotationChainObserver#onChainStart(Method, Object, Object[])},
 * 链出口调用 {@link AnnotationChainObserver#onChainEnd(Method, Object, Object[], List)}
 * (try/finally 守护,异常路径也保证 onChainEnd 触发).
 * Observer 实现以 default no-op 形式提供(见 {@link AnnotationChainObserver}),
 * Engine 自身不感知 MDC / 计时 / DEBUG log 等具体关注点.
 *
 * <p><b>失败隔离</b>:Engine 捕获每个 handler 抛出的异常,记 ERROR 日志后继续遍历
 * 剩余 handler. 这与原 {@code AnnotationHandler.handle} 的"全链失败"语义<em>不同</em>
 * — 原实现是"任一 handler 抛异常 → 整个链求值中断 → 拦截器失败 → 缓存全失效".
 * 新实现的 per-handler 隔离<strong>是严格更宽松</strong>的行为:
 * <ul>
 *   <li>原本能容忍的链路(无 handler 抛异常)行为零变化</li>
 *   <li>原本会"全链失败"的场景现在降级为"部分 handler 失败 + 剩余 handler 正常
 *       贡献",与 {@link AbstractAnnotationHandler#registerOne} 内部已有的
 *       try/catch 模式一致</li>
 * </ul>
 * 行为收窄方向:更宽松,符合"单个 handler 失败不应中断整个缓存链路"的本意
 * (Spring 注解处理也有同源约定).
 *
 * <p><b>线程安全</b>:Engine 单例 Bean,observers 字段委派到 {@link ObserverRegistry}
 * (内部 {@code CopyOnWriteArrayList}, 启动期单写、运行期多读), observer 自身必须
 * 线程安全. Handler 列表由 Spring 启动时一次性注入,运行期不变(无 addHandler
 * 暴露,本场景下链是静态的).
 *
 * <p><b>Observer 列表管理委派</b>(ADR-0016 / ADR-0026):{@code addObserver} /
 * {@code observers} / 遍历逻辑委派到 {@link ObserverRegistry} 单一 seam,与
 * {@code chain.ChainEngine} 共用 — 消除两 engine 间 ~30 SLOC 的 observer 列表样板重复.
 * Observer 遍历期间抛出的异常由 {@link ObserverRegistry#forEachSafe(Consumer)} 统一隔离
 * (ADR-0026),不阻塞主链;两 engine 语义对齐.
 *
 * <p><b>与 ChainEngine 的关系</b>:本 Engine 是 cache 写入链推进引擎
 * ({@code chain.ChainEngine})的<em>平行 seam</em>,非复用 — 决策语义不同
 * (filter vs decision),合并会导致抽象过载.
 */
@Slf4j
@Component
public class AnnotationChainEngine {

    /** 注入的所有 AnnotationHandler 实现(Spring 自动按 List 注入 4 个具体 handler) */
    private final List<AnnotationHandler> handlers;

    public AnnotationChainEngine(List<AnnotationHandler> handlers) {
        this.handlers = List.copyOf(handlers);
        log.debug("AnnotationChainEngine initialized with {} handlers: {}",
                this.handlers.size(),
                this.handlers.stream().map(h -> h.getClass().getSimpleName()).toList());
    }

    /**
     * 执行注解解析责任链 — 整条 chain 全生命周期(aroundChain 观测 + 所有 handler 求值)。
     *
     * <p>执行流程:
     * <ol>
     *   <li>onChainStart(observer 钩子)</li>
     *   <li>遍历 handlers,对每个 canHandle 命中的 handler 调 doHandle 收集结果
     *       (per-handler 异常隔离,不污染其他 handler)</li>
     *   <li>onChainEnd(observer 钩子,finally 守护)</li>
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
        // 防御性:args 允许 null(无参方法),target 允许 null(静态方法工具调用)
        // 但本 seam 期望 target 非 null(对应拦截器契约),只做 args null 兜底
        Object[] safeArgs = args != null ? args : new Object[0];

        // ADR-0044：observer 通道已删除（AnnotationChainObserver 0 生产实现），
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
