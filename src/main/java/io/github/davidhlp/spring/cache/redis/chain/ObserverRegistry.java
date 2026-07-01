package io.github.davidhlp.spring.cache.redis.chain;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Observer 列表管理单一 seam — Cross-Engine 去重 seam.
 *
 * <p>本类把"observer 注册 + 快照 + 遍历"三件关注点收口到一个泛型 utility,
 * 让 {@link ChainEngine} 与 {@link io.github.davidhlp.spring.cache.redis.handler.AnnotationChainEngine}
 * 不再各自持有 {@code CopyOnWriteArrayList<O> observers} 字段 + 重复的
 * {@code addObserver(O)} / {@code observers()} 样板.
 *
 * <p><b>使用方式</b>:
 * <pre>
 * private final ObserverRegistry<ChainObserver> observers = new ObserverRegistry<>();
 *
 * public void addObserver(ChainObserver o) { observers.add(o); }
 * public List<ChainObserver> observers() { return observers.snapshot(); }
 *
 * // In execute loop:
 * observers.forEach(o -> o.onChainStart(context));
 * </pre>
 *
 * <p><b>线程安全</b>:内部 {@link CopyOnWriteArrayList} 启动期单写、运行期多读;
 * forEach 遍历与底层 {@code CopyOnWriteArrayList.iterator()} 同语义 — 遍历期间
 * 其他线程对 list 的 add 不抛 {@code ConcurrentModificationException} (弱一致性).
 *
 * <p><b>本类的位置</b>:放在 {@code chain} 包而非独立 {@code common} 包 —
 * chain 是 observer 模式的发源域(本项目 5+ 生产 observer 都在 {@code chain.observer}),
 * 由 {@code handler} 域的 {@code AnnotationChainEngine} 反向依赖本 utility 符合
 * "domain → utility" 的依赖方向(utility 无 domain 依赖,纯泛型).
 *
 * <p><b>删除测试</b>:
 * <ul>
 *   <li>删本类 → {@code ChainEngine} 与 {@code AnnotationChainEngine} 恢复各自
 *       持有 {@code CopyOnWriteArrayList<O> observers} 字段 + 重复样板;两处
 *       状态机若漂移(eg. 一个用 {@code ArrayList} 一个用 {@code COW})回归</li>
 *   <li>替换为 {@code List<O>} 直持 — 失去 {@code add} 时的 null-check 中心化,
 *       两处各自写 IAE 守卫(易漂移)</li>
 * </ul>
 * 本 utility 挣得起存在代价(单类 60 SLOC 含 Javadoc).
 *
 * @param <O> observer 类型(由调用方语义决定:ChainObserver / AnnotationChainObserver)
 * @see ChainEngine
 * @see io.github.davidhlp.spring.cache.redis.handler.AnnotationChainEngine
 */
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
     * 遍历当前 observer — Engine 在执行链推进时调用.
     *
     * <p>遍历期间其他线程对 list 的 add 不抛
     * {@link java.util.ConcurrentModificationException} (COW 弱一致性);
     * 遍历结果可能包含 add 中的 observer (best-effort).
     *
     * @param action 对每个 observer 执行的动作
     */
    public void forEach(Consumer<? super O> action) {
        for (O o : observers) {
            action.accept(o);
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
