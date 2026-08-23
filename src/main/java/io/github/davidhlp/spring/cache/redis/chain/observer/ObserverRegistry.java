package io.github.davidhlp.spring.cache.redis.chain.observer;

import io.github.davidhlp.spring.cache.redis.chain.ChainEngine;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Observer 列表管理单一 seam — observer 注册 / 不可变快照收口.
 *
 * <p>本类把"observer 注册 + 不可变快照"两件关注点收口到一个 utility,
 * {@link ChainEngine} 借此无需直接持有 {@code CopyOnWriteArrayList<ChainObserver>} 字段 +
 * 重复的 {@code addObserver(ChainObserver)} / {@code observers()} 样板。
 *
 * <p><b>使用方式</b>:
 * <pre>
 * private final ObserverRegistry observers = new ObserverRegistry();
 *
 * public void addObserver(ChainObserver o) { observers.add(o); }
 * public List&lt;ChainObserver&gt; observers() { return observers.snapshot(); }
 *
 * // 消费方按需遍历快照(Engine 内联,index 对齐 scope-token 数组):
 * List&lt;ChainObserver&gt; snapshot = observers.snapshot();
 * Object[] tokens = new Object[snapshot.size()];
 * for (int i = 0; i &lt; snapshot.size(); i++) {
 *     tokens[i] = snapshot.get(i).onChainStart(ctx);
 * }
 * </pre>
 *
 * <p><b>为什么没有 forEach</b>:唯一消费者 {@link ChainEngine} 需要 index 对齐的
 * {@code Object[]} scope-token 数组,只能走 {@code snapshot().get(i)} 索引遍历;
 * {@code Consumer} 式遍历拿不到下标,无法承载 token 配对。Observer 异常隔离
 * 由 {@link ChainEngine} 在调用点内联 try/catch 收口 —— 本 registry
 * 不承担"异常隔离遍历"职责。
 *
 * <p><b>线程安全</b>:内部 {@link CopyOnWriteArrayList} 启动期单写、运行期多读;
 * {@link #snapshot()} 返回 {@code List.copyOf} 不可变快照,遍历快照期间其他线程对
 * registry 的 add 不抛 {@code ConcurrentModificationException}(弱一致性)。
 *
 * <p><b>本类的位置</b>:放在 {@code chain} 包 — chain 是 observer 模式的发源域
 * (本项目 5+ 生产 observer 都在 {@code chain.observer})。当前唯一消费者是
 * {@code chain.ChainEngine};原本保留的泛型 {@code <O>} 假设"未来其他 observer-bearing
 * engine 复用"从未实现(YAGNI),Wave 3 TASK-018 特化为 {@link ChainObserver}。
 *
 * <p><b>删除测试</b>:删本类 → {@link ChainEngine} 恢复持有
 * {@code CopyOnWriteArrayList<ChainObserver>} 字段 + add/snapshot 样板(约 30 SLOC 重复)。
 * 本 utility 挣得起存在代价(单类 ~60 SLOC 含 Javadoc)。
 *
 * @see ChainEngine
 */
public final class ObserverRegistry {

    /** 内部 list — 启动期单写、运行期多读(COW 弱一致性迭代). */
    private final List<ChainObserver> observers = new CopyOnWriteArrayList<>();

    /**
     * 注册一个 observer.
     *
     * <p>重复注册同名 observer 由调用方负责去重 — registry 不强制唯一性,
     * 避免反射 / class 名比较的开销.
     *
     * @param observer 待注册的 observer (不为 null)
     * @throws IllegalArgumentException 若 observer 为 null
     */
    public void add(ChainObserver observer) {
        if (observer == null) {
            throw new IllegalArgumentException("observer must not be null");
        }
        observers.add(observer);
    }

    /**
     * 暴露当前已注册的 observer 列表(只读快照).
     *
     * <p>测试与诊断用;运行期勿修改. 返回的 list 是当前状态的不可变快照 —
     * 后续的 {@link #add(ChainObserver)} 不影响已返回的快照.
     *
     * @return 不可变 observer 列表快照
     */
    public List<ChainObserver> snapshot() {
        return List.copyOf(observers);
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
