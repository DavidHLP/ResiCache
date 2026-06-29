package io.github.davidhlp.spring.cache.redis.chain;

import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 缓存处理器责任链管理器
 *
 * <p>支持责任链的前置处理和后置处理模式：
 * <ul>
 *   <li>前置处理：Handler 在 doHandle() 中决定是否继续链</li>
 *   <li>后置处理：Handler 通过标记请求后置处理，在链执行完成后执行</li>
 * </ul>
 */
@Slf4j
@Component
public class CacheHandlerChain {

    /**
     * MDC key 用于 stamp 每次链执行的关联 id(guide §223d:per-handler chain observability)。
     * {@link #execute(CacheContext)} 每次执行 stamp 一个新 id,使一次 GET/PUT 内所有 handler 的
     * {@code [chain]} DEBUG 行可被同一 id 关联。由 {@link AbstractCacheHandler#handle(CacheContext)} 读取。
     */
    public static final String MDC_REQUEST_ID_KEY = "requestId";

    /** 所有处理器列表（用于调试和后置处理） */
    private final List<CacheHandler> handlers = new ArrayList<>();
    /** 责任链头节点 */
    private volatile CacheHandler head;
    /** 读写锁，保证线程安全 */
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Path C 后续(WS-1.4) — 链级 Micrometer Timer.
     * <p>ObjectProvider 允许 MeterRegistry 缺失(测试用 stub / 无 actuator 环境) ——
     * 没有 registry 时 metrics 静默 no-op,行为不变。
     * <p>Tags: cacheName(链处理哪个 cache)+ operation(GET/PUT/CLEAN)
     */
    private final Timer chainExecuteTimer;

    public CacheHandlerChain(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        MeterRegistry registry =
                meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable();
        if (registry != null) {
            this.chainExecuteTimer = Timer.builder("resicache.chain.execute")
                    .description("Time spent executing the cache protection chain (full lifecycle: head + post-process)")
                    .register(registry);
        } else {
            this.chainExecuteTimer = null;
        }
    }

    /**
     * 添加处理器到责任链末尾
     *
     * @param handler 处理器
     * @return 当前链（支持链式调用）
     */
    public CacheHandlerChain addHandler(CacheHandler handler) {
        lock.writeLock().lock();
        try {
            if (head == null) {
                head = handler;
            } else {
                // 找到链尾
                CacheHandler current = head;
                while (current.getNext() != null) {
                    current = current.getNext();
                }
                current.setNext(handler);
            }
            handlers.add(handler);
            log.debug("Added handler to chain: {}", handler.getClass().getSimpleName());
            return this;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 执行责任链
     *
     * <p>执行流程：
     * <ol>
     *   <li>从 head 开始执行责任链</li>
     *   <li>提取最终结果</li>
     *   <li>执行后置处理（如 BloomFilterHandler 需要在 PUT 成功后添加 key）</li>
     * </ol>
     *
     * @param context 缓存上下文
     * @return 处理结果
     */
    public CacheResult execute(CacheContext context) {
        // 获取 head 的快照并在执行期间持有读锁，防止 clear() 修改链
        lock.readLock().lock();
        CacheHandler currentHead;
        List<CacheHandler> handlersSnapshot;
        try {
            currentHead = head;
            if (currentHead == null) {
                log.warn("Handler chain is empty!");
                return CacheResult.success();
            }
            handlersSnapshot = List.copyOf(handlers);
        } finally {
            lock.readLock().unlock();
        }

        log.debug(
                "Executing handler chain for operation: {}, cacheName: {}, key: {}",
                context.getOperation(),
                context.getCacheName(),
                context.getRedisKey());

        // guide §223d:为本次链执行 stamp 一个 requestId 进 MDC,使本次 GET/PUT 内所有 handler 的
        // [chain] DEBUG 行可被同一 id 关联(falsifiable observability)。
        // snapshot/restore:只动自己的 key,finally 恢复调用方原值,**不**用 MDC.clear() 误清宿主
        // 线程其它 MDC(如 traceId);与 RedisProCacheWriter 的防御式 MDC 风格一致。
        String previousRequestId = MDC.get(MDC_REQUEST_ID_KEY);
        MDC.put(MDC_REQUEST_ID_KEY, generateRequestId());
        try {
            // 执行责任链（注意：此时仍持有 head 的引用快照，clear() 无法修改链）
            // WS-1.4:链级 Micrometer Timer 记录 full lifecycle(head handle + post-process)
            // 本 tick 单 Timer(无 tags,简洁)— per-cacheName/per-operation tags
            // 留后续 tick(WS-1.4 测试套件扩展)添加
            if (chainExecuteTimer != null) {
                return chainExecuteTimer.record(
                        () -> executeChainInternal(currentHead, handlersSnapshot, context));
            }
            // 无 MeterRegistry 时 no-op 计时(测试 stub / 无 actuator 环境)
            return executeChainInternal(currentHead, handlersSnapshot, context);
        } finally {
            if (previousRequestId == null) {
                MDC.remove(MDC_REQUEST_ID_KEY);
            } else {
                MDC.put(MDC_REQUEST_ID_KEY, previousRequestId);
            }
        }
    }

    /**
     * 生成本次链执行的关联 id。
     *
     * <p>用 {@link ThreadLocalRandom} 而非 {@code UUID.randomUUID()}:{@code execute} 是缓存热路径
     * (每次 GET/PUT 必经),需规避 {@code SecureRandom} 的熵竞争/潜在阻塞;64-bit 随机数对 DEBUG
     * 日志关联已足够(碰撞概率可忽略)。无符号十六进制输出,避免负值的符号扩展噪音。
     */
    private static String generateRequestId() {
        return Long.toUnsignedString(ThreadLocalRandom.current().nextLong(), 16);
    }

    /**
     * 链执行内部实现(head handle + post-process) — 由 Timer.record 包装。
     */
    private CacheResult executeChainInternal(CacheHandler currentHead, List<CacheHandler> handlersSnapshot, CacheContext context) {
        HandlerResult handlerResult = currentHead.handle(context);
        CacheResult result = handlerResult.result();
        CacheResult finalResult = result != null ? result : CacheResult.success();
        executePostProcess(handlersSnapshot, context, finalResult);
        return finalResult;
    }

    /**
     * 执行后置处理
     *
     * <p>遍历所有 Handler，如果实现了 PostProcessHandler 接口且满足条件，
     * 则调用其后置处理方法。
     *
     * <p>执行顺序与责任链顺序一致，确保后置处理按照预期顺序执行。
     *
     * @param handlers 后置处理器列表快照
     * @param context 缓存上下文
     * @param result 责任链执行结果
     */
    private void executePostProcess(List<CacheHandler> handlers, CacheContext context, CacheResult result) {
        for (CacheHandler handler : handlers) {
            if (handler instanceof PostProcessHandler postHandler) {
                if (postHandler.requiresPostProcess(context)) {
                    try {
                        postHandler.afterChainExecution(context, result);
                        log.debug("Post-processing executed for: {}",
                                  handler.getClass().getSimpleName());
                    } catch (Exception e) {
                        log.error("Post-processing failed for: {}, operation: {}, key: {}",
                                  handler.getClass().getSimpleName(),
                                  context.getOperation(),
                                  context.getRedisKey(), e);
                        // 后置处理失败不影响主链结果
                    }
                }
            }
        }
    }

    /**
     * 获取处理器数量
     *
     * @return 处理器数量
     */
    public int size() {
        lock.readLock().lock();
        try {
            return handlers.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 清空责任链
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            head = null;
            handlers.clear();
            log.debug("Handler chain cleared");
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 获取所有处理器名称
     *
     * @return 处理器名称列表
     */
    public List<String> getHandlerNames() {
        lock.readLock().lock();
        try {
            return handlers.stream().map(h -> h.getClass().getSimpleName()).toList();
        } finally {
            lock.readLock().unlock();
        }
    }
}
