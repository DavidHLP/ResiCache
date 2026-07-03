package io.github.davidhlp.spring.cache.redis.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 缓存层 timing & metric 注册与调用的单一 seam —— Round 22 / ADR-0031 收敛.
 *
 * <p>本类吸收 {@link RedisProCache} 中重复的 6 处 {@code try-finally + System.nanoTime() + safeRecord}
 * 模板,把 null-safe timer/counter 行为封装在四个静态入口:
 * <ul>
 *   <li>{@link #registerTimer} / {@link #registerCounter} —— Timer & Counter 创建,
 *       {@code registry == null} 时返回 {@code null}</li>
 *   <li>{@link #safeIncrement} —— Counter null-safe increment</li>
 *   <li>{@link #timed} —— void body timing wrapper(put / evict / clear 路径)</li>
 *   <li>{@link #timedGet} —— 返回值 body timing wrapper(get 的 3 个重载)</li>
 * </ul>
 *
 * <p><b>行为保真</b>:与 Round 21 之前散在 {@link RedisProCache} 的字节级逻辑等价。
 * <ul>
 *   <li>{@code timer == null}({@code meterRegistry} 未启用)时静默 no-op:
 *       {@code timed/timedGet} 直接执行 body,不计算 nanoTime;
 *       等价于原{@code try-finally + safeRecord(null, ..., NANOSECONDS)}(后者对 null timer 是 no-op)</li>
 *   <li>{@code timer != null} 时按 {@code start → body → finally record duration} 推进,
 *       异常不被吞 —— 仍沿 finally 释放,与原 try-finally 字节级等价</li>
 * </ul>
 *
 * <p><b>接口是测试面</b>:RedisProCache 的 6 处私有样板收敛后,本类四个方法成为单一测试目标。
 * 未来若新增 metric(hit-ratio / 复合 timer 等)只在 seam 内扩展,不污染 6 个调用点。
 *
 * <p><b>deletion test 通过</b>(per Round 22 复审):删 {@code RedisProCache} 散落的
 * {@code safeRecord} + 6 处样板后,复杂度从 6 处集中消失,不在调用点重现 —— 真实归并,
 * 不是搬家。
 *
 * @see RedisProCache
 * @see <a href="../../../../../../../../../wiki/adr/0031-redisprocache-timing-helper-seam.md">ADR-0031</a>
 */
final class RedisProCacheTimers {

    private RedisProCacheTimers() {
        // 工具类,不可实例化
    }

    /**
     * 注册 Timer.{@code registry == null} 时返回 {@code null}(由 {@link #timed} /
     * {@link #timedGet} 静默 no-op 吸收)。
     *
     * @param registry     Micrometer 注册中心,生产可为 null
     * @param name         Timer 名,如 {@code "resicache.cache.get"}
     * @param description  Timer 描述
     * @param cacheName    cache tag 值,用于 {@code tags("cache", cacheName)}
     * @return 注册成功的 Timer;{@code registry == null} 时返回 null
     */
    static Timer registerTimer(MeterRegistry registry, String name,
                               String description, String cacheName) {
        if (registry == null) {
            return null;
        }
        return Timer.builder(name)
                .tag("cache", cacheName)
                .description(description)
                .register(registry);
    }

    /**
     * 注册 Counter.{@code registry == null} 时返回 {@code null}(由
     * {@link #safeIncrement} 静默 no-op 吸收)。
     *
     * @param registry     Micrometer 注册中心,生产可为 null
     * @param name         Counter 名,如 {@code "resicache.cache.hit"}
     * @param description  Counter 描述
     * @param cacheName    cache tag 值,用于 {@code tags("cache", cacheName)}
     * @return 注册成功的 Counter;{@code registry == null} 时返回 null
     */
    static Counter registerCounter(MeterRegistry registry, String name,
                                   String description, String cacheName) {
        if (registry == null) {
            return null;
        }
        return Counter.builder(name)
                .tag("cache", cacheName)
                .description(description)
                .register(registry);
    }

    /**
     * Counter null-safe increment.{@code counter == null} 时静默 no-op(等价于
     * {@link RedisProCache#getHitCount()} 等 getter 在无 meterRegistry 时的零返回值路径)。
     *
     * @param counter 待自增 Counter,生产可为 null
     */
    static void safeIncrement(Counter counter) {
        if (counter != null) {
            counter.increment();
        }
    }

    /**
     * void body 用 timing wrapper —— 语义与 try-finally 等价:
     * <ul>
     *   <li>{@code timer == null}:直接执行 body,不计算 nanoTime(语义等价于原
     *       {@code try { body.run(); } finally { safeRecord(null, ..., NANOSECONDS); }},
     *       后者对 null timer 是 no-op)</li>
     *   <li>{@code timer != null}:{@code start → body → finally record duration};异常不被吞,
     *       仍沿 finally 释放</li>
     * </ul>
     *
     * @param timer 待记录 Timer,生产可为 null
     * @param body  待执行的操作,可抛出 RuntimeException
     */
    static void timed(Timer timer, Runnable body) {
        if (timer == null) {
            body.run();
            return;
        }
        long start = System.nanoTime();
        try {
            body.run();
        } finally {
            timer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * 返回值 body 用 timing wrapper —— 语义与 try-finally 等价:
     * <ul>
     *   <li>{@code timer == null}:直接执行 body 并返回,不计算 nanoTime</li>
     *   <li>{@code timer != null}:{@code start → body → finally record duration};异常不被吞,
     *       仍沿 finally 释放</li>
     * </ul>
     *
     * <p>调用方如需把 body 异常翻译为 {@code Cache.ValueRetrievalException} 或自增 miss 计数,
     * 应当在本 {@code timedGet} 之外再套一层 try-catch —— 调用点的 catch 与本类的
     * {@code finally} 互不干扰,与原 RedisProCache.get(key, loader) 结构语义等价。
     *
     * @param <T>  返回值类型
     * @param timer 待记录 Timer,生产可为 null
     * @param body  待执行的操作,可抛出任意 Exception
     * @return body.get() 的结果
     */
    static <T> T timedGet(Timer timer, Supplier<T> body) {
        if (timer == null) {
            return body.get();
        }
        long start = System.nanoTime();
        try {
            return body.get();
        } finally {
            timer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }
}
