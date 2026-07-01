package io.github.davidhlp.spring.cache.redis.handler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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
 * (try/finally 守护,异常路径也保证 onChainEnd 触发)。
 * Observer 实现以 default no-op 形式提供(见 {@link AnnotationChainObserver}),
 * Engine 自身不感知 MDC / 计时 / DEBUG log 等具体关注点。
 *
 * <p><b>失败隔离</b>:Engine 捕获每个 handler 抛出的异常,记 ERROR 日志后继续遍历
 * 剩余 handler。这与原 {@code AnnotationHandler.handle} 的"全链失败"语义<em>不同</em>
 * — 原实现是"任一 handler 抛异常 → 整个链求值中断 → 拦截器失败 → 缓存全失效"。
 * 新实现的 per-handler 隔离<strong>是严格更宽松</strong>的行为:
 * <ul>
 *   <li>原本能容忍的链路(无 handler 抛异常)行为零变化</li>
 *   <li>原本会"全链失败"的场景现在降级为"部分 handler 失败 + 剩余 handler 正常
 *       贡献",与 {@link AbstractAnnotationHandler#registerOne} 内部已有的
 *       try/catch 模式一致</li>
 * </ul>
 * 行为收窄方向:更宽松,符合"单个 handler 失败不应中断整个缓存链路"的本意
 * (Spring 注解处理也有同源约定)。
 *
 * <p><b>线程安全</b>:Engine 单例 Bean,observers 字段为 CopyOnWriteArrayList
 * (启动期单写、运行期多读),observer 自身必须线程安全。Handler 列表由 Spring
 * 启动时一次性注入,运行期不变(无 addHandler 暴露,本场景下链是静态的)。
 *
 * <p><b>与 ChainEngine 的关系</b>:本 Engine 是 cache 写入链推进引擎
 * ({@code chain.ChainEngine})的<em>平行 seam</em>,非复用 — 决策语义不同
 * (filter vs decision),合并会导致抽象过载。
 */
@Slf4j
@Component
public class AnnotationChainEngine {

    /** 注入的所有 AnnotationHandler 实现(Spring 自动按 List 注入 4 个具体 handler) */
    private final List<AnnotationHandler> handlers;

    /** observer 列表 — 启动期单写、运行期多读(CopyOnWrite 适配) */
    private final List<AnnotationChainObserver> observers = new CopyOnWriteArrayList<>();

    public AnnotationChainEngine(List<AnnotationHandler> handlers) {
        this.handlers = List.copyOf(handlers);
        log.debug("AnnotationChainEngine initialized with {} handlers: {}",
                this.handlers.size(),
                this.handlers.stream().map(h -> h.getClass().getSimpleName()).toList());
    }

    /**
     * 注册一个 observer。重复注册同名 observer 由调用方负责去重(Engine 不强制
     * 唯一性,避免反射 / class 名比较的开销)。注册时机:Engine 创建后、首次
     * execute 前。
     *
     * @param observer 待注册的 observer(不为 null)
     */
    public void addObserver(AnnotationChainObserver observer) {
        if (observer == null) {
            throw new IllegalArgumentException("observer must not be null");
        }
        observers.add(observer);
    }

    /**
     * 暴露当前已注册的 observer 列表(只读快照)。测试与诊断用;运行期勿修改。
     *
     * @return 不可变 observer 列表快照
     */
    public List<AnnotationChainObserver> observers() {
        return List.copyOf(observers);
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

        // 1. aroundChain:onChainStart
        for (AnnotationChainObserver o : observers) {
            try {
                o.onChainStart(method, target, safeArgs);
            } catch (Exception observerEx) {
                // observer 异常不阻塞主链(与 ChainEngine.execute 行为一致)
                log.error("AnnotationChainObserver.onChainStart failed: {}",
                        o.getClass().getSimpleName(), observerEx);
            }
        }

        // 2. 遍历 handler 求值
        List<CacheOperation> collected = new ArrayList<>();
        try {
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
                    // 单 handler 异常隔离:记 ERROR 日志,继续遍历剩余 handler
                    log.error("AnnotationHandler.doHandle failed: {}, method: {}",
                            handler.getClass().getSimpleName(), method.getName(), handlerEx);
                }
            }
        } finally {
            // 3. aroundChain:onChainEnd(try/finally 守护,异常路径也保证触发)
            List<CacheOperation> snapshot = Collections.unmodifiableList(collected);
            for (AnnotationChainObserver o : observers) {
                try {
                    o.onChainEnd(method, target, safeArgs, snapshot);
                } catch (Exception observerEx) {
                    log.error("AnnotationChainObserver.onChainEnd failed: {}",
                            o.getClass().getSimpleName(), observerEx);
                }
            }
        }

        return Collections.unmodifiableList(collected);
    }
}
