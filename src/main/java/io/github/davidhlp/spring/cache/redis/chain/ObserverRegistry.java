package io.github.davidhlp.spring.cache.redis.chain;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Observer 列表管理单一 seam — Cross-Engine 去重 seam.
 *
 * <p>本类把"observer 注册 + 快照 + 遍历 + 异常隔离"四件关注点收口到一个泛型 utility,
 * 让 {@link ChainEngine} 与 {@link io.github.davidhlp.spring.cache.redis.handler.AnnotationChainEngine}
 * 不再各自持有 {@code CopyOnWriteArrayList<O> observers} 字段 + 重复的
 * {@code addObserver(O)} / {@code observers()} 样板,也不再各自实现 observer 异常 try-catch。
 *
 * <p><b>使用方式</b>:
 * <pre>
 * private final ObserverRegistry&lt;ChainObserver&gt; observers = new ObserverRegistry&lt;&gt;();
 *
 * public void addObserver(ChainObserver o) { observers.add(o); }
 * public List&lt;ChainObserver&gt; observers() { return observers.snapshot(); }
 *
 * // In execute loop — 异常隔离遍历(observer 抛异常不阻断主链):
 * observers.forEachSafe(o -> o.onChainStart(context));
 * </pre>
 *
 * <p><b>异常隔离(ADR-0026)</b>:{@link #forEachSafe(Consumer)} 把"observer 抛异常 → 吞 +
 * 记 ERROR 日志、主链继续"的语义收口到本 seam。此前 {@code ChainEngine} 裸调 forEach
 * (异常冒泡,仅 try/finally 保证 onChainEnd 配对)与 {@code AnnotationChainEngine}
 * 自写 try-catch(吞)语义不一致 —— {@code AnnotationChainEngineTest} 注释声称"与
 * ChainEngine.execute 行为一致"实际为假。两 engine 统一改用 forEachSafe 后,契约对齐,
 * 新增第 3 个 observer-bearing engine 零重复。
 *
 * <p><b>线程安全</b>:内部 {@link CopyOnWriteArrayList} 启动期单写、运行期多读;
 * forEach / forEachSafe 遍历与底层 {@code CopyOnWriteArrayList.iterator()} 同语义 —
 * 遍历期间其他线程对 list 的 add 不抛 {@code ConcurrentModificationException} (弱一致性).
 *
 * <p><b>本类的位置</b>:放在 {@code chain} 包而非独立 {@code common} 包 —
 * chain 是 observer 模式的发源域(本项目 5+ 生产 observer 都在 {@code chain.observer}),
 * 由 {@code handler} 域的 {@code AnnotationChainEngine} 反向依赖本 utility 符合
 * "domain → utility" 的依赖方向(utility 无 domain 依赖,纯泛型)。
 *
 * <p><b>删除测试</b>:
 * <ul>
 *   <li>删本类 → {@code ChainEngine} 与 {@code AnnotationChainEngine} 恢复各自
 *       持有 {@code CopyOnWriteArrayList<O> observers} 字段 + 重复样板;两处
 *       状态机若漂移(eg. 一个用 {@code ArrayList} 一个用 {@code COW})回归</li>
 *   <li>删 {@link #forEachSafe} → 两 engine 各自重写 observer try-catch,异常隔离
 *       语义再次分裂(ADR-0026 修复的 friction 回归)</li>
 * </ul>
 * 本 utility 挣得起存在代价(单类 ~80 SLOC 含 Javadoc).
 *
 * @param <O> observer 类型(由调用方语义决定:ChainObserver / AnnotationChainObserver)
 * @see ChainEngine
 * @see io.github.davidhlp.spring.cache.redis.handler.AnnotationChainEngine
 */
@Slf4j
public final class ObserverRegistry<O> {

    /** 内部 list — 启动期单写、运行期多读(COW 弱一致性迭代). */
    private final List<O> observers = new CopyOnWriteArrayList<>();

    /**
     * 注册一个 observer.
     *
     * <p>重复注册同名 observer 由调用方负责去重 — registry 不强制唯一性,
     * 避免反射 / class 名比较的开销.
     *
     * @param observer 待注册的 observer (不为 null)
     * @throws IllegalArgumentException 若 observer 为 null
     */
    public void add(O observer) {
        if (observer == null) {
            throw new IllegalArgumentException("observer must not be null");
        }
        observers.add(observer);
    }

    /**
     * 暴露当前已注册的 observer 列表(只读快照).
     *
     * <p>测试与诊断用;运行期勿修改. 返回的 list 是当前状态的不可变快照 —
     * 后续的 {@link #add(Object)} 不影响已返回的快照.
     *
     * @return 不可变 observer 列表快照
     */
    public List<O> snapshot() {
        return List.copyOf(observers);
    }

    /**
     * 遍历当前 observer — 纯遍历,不做异常隔离。
     *
     * <p>遍历期间其他线程对 list 的 add 不抛
     * {@link java.util.ConcurrentModificationException} (COW 弱一致性);
     * 遍历结果可能包含 add 中的 observer (best-effort).
     *
     * <p><b>异常语义</b>:action 抛异常会冒泡到调用方。Engine 驱动 observer 钩子
     * 应改用 {@link #forEachSafe(Consumer)}(异常隔离);本方法保留给不需要隔离的
     * 纯遍历场景与 registry 自身契约测试。
     *
     * @param action 对每个 observer 执行的动作
     */
    public void forEach(Consumer<? super O> action) {
        for (O o : observers) {
            action.accept(o);
        }
    }

    /**
     * 异常隔离遍历 — Engine 在执行 observer 钩子时调用(ADR-0026)。
     *
     * <p>对每个 observer 执行 action;单个 observer 抛异常时记 ERROR 日志后
     * <strong>继续遍历剩余 observer</strong>,异常不冒泡到调用方。语义:observer 是
     * 观测旁路,其失败不阻断主链(与原 {@code AnnotationChainEngine} 行为一致;
     * {@code ChainEngine} 自 ADR-0026 起从"裸调冒泡"对齐到本语义)。
     *
     * <p>日志格式:{@code "Observer {className} action failed: {ex}"}(含异常栈),
     * 足以定位失败的 observer 实现类。
     *
     * <p>线程安全:与 {@link #forEach} 同(COW 弱一致性迭代)。
     *
     * @param action 对每个 observer 执行的动作(不为 null)
     */
    public void forEachSafe(Consumer<? super O> action) {
        for (O o : observers) {
            try {
                action.accept(o);
            } catch (Exception ex) {
                log.error("Observer {} action failed: {}",
                        o.getClass().getSimpleName(), ex.toString(), ex);
            }
        }
    }

    /**
     * 当前已注册 observer 数量 — 测试用.
     *
     * @return observer 数量
     */
    public int size() {
        return observers.size();
    }
}
