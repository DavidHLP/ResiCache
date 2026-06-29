package io.github.davidhlp.spring.cache.redis.chain;

import io.github.davidhlp.spring.cache.redis.chain.model.*;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;



import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import org.slf4j.MDC;

/**
 * 抽象缓存处理器，提供责任链的<b>单一引擎</b>实现。
 *
 * <p>{@link #handle(CacheContext)} 是全仓唯一驱动链推进的代码：先检查 {@code skipRemaining}
 * 短路，再按 {@link HandlerResult#decision()} 单 switch 分发（CONTINUE 推进下一个 /
 * TERMINATE 直接返回 / SKIP_ALL 物化为 {@code skipRemaining}）。子类只需实现
 * {@link #shouldHandle(CacheContext)} 与 {@link #doHandle(CacheContext)} 两个钩子，
 * 无需也<strong>不应</strong>自行调用 {@code getNext().handle()} 推进链。
 *
 * <p>例外：{@code SyncLockHandler.executeChainInLock} 在分布式锁内手动推进，见其实现。
 */
@Getter
@Setter
@Slf4j
public abstract class AbstractCacheHandler implements CacheHandler {

    /** 下一个处理器 */
    private CacheHandler next;

    /**
     * guide §223b:per-handler uniform FIRED counter。由 {@link CacheHandlerChainFactory}
     * 建链后注入 MeterRegistry 时创建(handle 每次求值本 handler 时自增)。registry 缺失时为 null,no-op。
     * metric: {@code resicache.handler.fired},tag: {@code handler=<运行时子类 SimpleName>}。
     */
    private Counter firedCounter;

    /**
     * 由责任链工厂在建链阶段注入 MeterRegistry(guide §223b)。registry 非空时注册
     * {@code resicache.handler.fired} counter(handler tag = 运行时子类 SimpleName;
     * cardinality bounded = handler 数,见 guide line 261)。幂等:同名同 tag 重复 register 返回既有实例。
     */
    void attachMeterRegistry(MeterRegistry registry) {
        if (registry == null) {
            return;
        }
        this.firedCounter = Counter.builder("resicache.handler.fired")
                .description("Cache protection chain: number of times each handler was evaluated by the engine "
                        + "(guide §223b per-handler observability; tag handler = runtime subclass simple name)")
                .tag("handler", getClass().getSimpleName())
                .register(registry);
    }

    @Override
    public CacheHandler getNext() {
        return next;
    }

    @Override
    public void setNext(CacheHandler next) {
        this.next = next;
    }

    /**
     * 处理缓存操作（返回 HandlerResult，与接口定义一致）
     *
     * <p>模板方法，定义处理流程：
     * <ol>
     *   <li>如果上下文标记为跳过剩余处理器，返回 continueWith(success)</li>
     *   <li>判断是否需要处理</li>
     *   <li>如果需要处理，执行处理逻辑并根据决策继续或终止</li>
     *   <li>如果不需要处理，继续责任链</li>
     * </ol>
     *
     * @param context 缓存上下文
     * @return HandlerResult 包含决策和结果
     */
    @Override
    public HandlerResult handle(CacheContext context) {
        // 上游已标记跳过剩余处理器（SKIP_ALL 传播）：短路，等价链尾成功
        if (context.isSkipRemaining()) {
            return HandlerResult.continueWith(CacheResult.success());
        }
        // 不需要处理时等价 continueChain，统一进入 decision 分发，
        // 消除原先"shouldHandle 分支与 decision 分支"两条并行的推进路径
        HandlerResult result = shouldHandle(context) ? doHandle(context) : HandlerResult.continueChain();
        // guide §223d:单点 chain-advance 日志 —— 每个被引擎求值的 handler 记录其决策 + key +
        // 本次执行的 requestId(由 CacheHandlerChain.execute stamp 进 MDC),使一次 GET/PUT 的
        // DEBUG trace 可按 requestId 串联全部 handler 与决策。getClass() 取运行时子类名
        // (如 BloomFilterHandler);skipRemaining 短路路径未到达此 handler,故不记。
        log.debug("[chain] handler={} decision={} key={} requestId={}",
                getClass().getSimpleName(),
                result.decision(),
                context.getRedisKey(),
                MDC.get(CacheHandlerChain.MDC_REQUEST_ID_KEY));
        // guide §223b:uniform per-handler FIRED counter(registry 已由工厂注入时;否则 no-op)
        if (firedCounter != null) {
            firedCounter.increment();
        }
        return switch (result.decision()) {
            // 继续：有下一个则推进，否则链尾成功
            case CONTINUE -> getNext() != null ? getNext().handle(context) : result;
            // 跳过剩余：单点物化 skipRemaining，供下游 handler 短路与 BloomFilterHandler 后置门控读取
            case SKIP_ALL -> {
                context.markSkipRemaining();
                yield result;
            }
            // 终止：直接返回
            case TERMINATE -> result;
        };
    }

    /**
     * 判断当前处理器是否应该处理此操作
     *
     * @param context 缓存上下文
     * @return true 表示应该处理
     */
    protected abstract boolean shouldHandle(CacheContext context);

    /**
     * 执行实际的处理逻辑
     *
     * <p>返回 HandlerResult 包含：
     * <ul>
     *   <li>decision: 控制责任链后续执行（CONTINUE/TERMINATE/SKIP_ALL）</li>
     *   <li>result: 处理结果（可选，为 null 时继续链）</li>
     * </ul>
     *
     * @param context 缓存上下文
     * @return HandlerResult 包含决策和结果
     */
    protected abstract HandlerResult doHandle(CacheContext context);
}
