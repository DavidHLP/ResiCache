package io.github.davidhlp.spring.cache.redis.chain.observer;

import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.chain.ChainObserver;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

/**
 * ChainObserver 的 aroundChain 实现 — 链级 Micrometer Timer，记录
 * 整条 chain.execute 的全生命周期（head handle + post-process + 所有节点）。
 *
 * <p>替换原 {@code CacheHandlerChain.execute} 的内联 Timer 装配 / 记录逻辑
 * （{@code chainExecuteTimer} 字段 + {@code Timer.record(...)} 调用）。Engine
 * 不再持有 Timer 状态。
 *
 * <p>当前无 per-cacheName / per-operation tags（与原实现一致 — 留 WS-1.4 测试套件
 * 扩展时按 cardinality 评估再加）。{@code registry} 缺失时全链 no-op 计时，
 * 行为与原 {@code CacheHandlerChain} 一致。
 *
 * <p>延迟注册策略：{@code onChainStart} 第一次被调用时检查并创建 Timer，
 * 后续复用（同名 register 幂等返回同一实例）。
 *
 * <p><b>ADR-0061 scope token 收尾</b>(Round 46):onChainStart 把"start nanos"
 * 装入 {@link TimerScope} record 返回,onChainEnd 接收该 token 计算 elapsed
 * 后 record 到 Timer。完全摆脱原 {@code CacheContext.attributes} 字符串键 map
 * (原 {@code __chainTimer.startNanos} 常量 + setAttribute/getAttribute/removeAttribute
 * 调用链全部删除),observer 状态机完全自承。Engine 不感知 token 内部协议。
 *
 * <p>线程安全：{@code timer} 单写多读，double-checked locking 守护;scope token
 * 是 per-call 不可变 record,无共享状态。
 */
@Slf4j
public final class ChainTimerChainObserver implements ChainObserver {

    private final MeterRegistry registry;
    /** 首次 onChainStart 时按需创建；registry 缺失时为 null。 */
    private volatile Timer timer;

    public ChainTimerChainObserver(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Object onChainStart(CacheContext context) {
        if (registry == null) {
            return null;  // no-op 路径,token = null → onChainEnd 也走 no-op 分支
        }
        Timer t = ensureTimer();
        if (t == null) {
            return null;
        }
        // ADR-0061:把"start nanos"装入 TimerScope record 返回,Engine 在 onChainEnd 配对回传
        return new TimerScope(System.nanoTime());
    }

    @Override
    public void onChainEnd(CacheContext context, Object scopeToken, CacheResult result) {
        Timer t = this.timer;
        // ADR-0061:token 即 onChainStart 返回的 TimerScope 实例,instanceof 模式匹配恢复 startNanos
        if (t == null || !(scopeToken instanceof TimerScope scope)) {
            return;
        }
        long elapsed = System.nanoTime() - scope.startNanos();
        t.record(elapsed, TimeUnit.NANOSECONDS);
    }

    /**
     * 双重检查锁定初始化 Timer。Micrometer {@code Timer.builder(name).register(registry)}
     * 同名同 tag 重复 register 幂等返回同一实例，无需 DCL 也安全 —— DCL 是为了避免重复
     * builder 调用的微量开销。
     */
    private Timer ensureTimer() {
        Timer t = this.timer;
        if (t == null) {
            synchronized (this) {
                t = this.timer;
                if (t == null) {
                    t = Timer.builder("resicache.chain.execute")
                            .description("Time spent executing the cache protection chain "
                                    + "(full lifecycle: head + post-process + all nodes)")
                            .register(registry);
                    this.timer = t;
                }
            }
        }
        return t;
    }

    /**
     * Timer scope token — ADR-0061 引入的 per-call 状态值对象.
     *
     * <p>本 record 由 {@link #onChainStart} 构造,持有本次链执行的 start nanos,
     * 由 {@link #onChainEnd} 读取计算 elapsed。Engine 不感知 record 内容。
     *
     * <p>本 record 是 observer 私有(本类嵌套):当前仅 ChainTimerChainObserver 一个
     * 消费者,未达提升为顶层类型的必要性(YAGNI)。
     *
     * @param startNanos {@code System.nanoTime()} 在 onChainStart 时记录的起始点
     */
    private record TimerScope(long startNanos) {
    }
}
