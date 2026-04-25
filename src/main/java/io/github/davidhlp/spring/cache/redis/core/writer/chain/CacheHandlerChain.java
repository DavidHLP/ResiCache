package io.github.davidhlp.spring.cache.redis.core.writer.chain;

import io.github.davidhlp.spring.cache.redis.core.writer.chain.handler.CacheContext;
import io.github.davidhlp.spring.cache.redis.core.writer.chain.handler.CacheHandler;
import io.github.davidhlp.spring.cache.redis.core.writer.chain.handler.HandlerResult;
import io.github.davidhlp.spring.cache.redis.core.writer.chain.handler.PostProcessHandler;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
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
    /** 所有处理器列表（用于调试和后置处理） */
    private final List<CacheHandler> handlers = new ArrayList<>();
    /** 责任链头节点 */
    private volatile CacheHandler head;
    /** 读写锁，保证线程安全 */
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

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

        // 执行责任链（注意：此时仍持有 head 的引用快照，clear() 无法修改链）
        HandlerResult handlerResult = currentHead.handle(context);
        CacheResult result = handlerResult.result();
        CacheResult finalResult = result != null ? result : CacheResult.success();

        // 执行后置处理
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
        return handlers.stream().map(h -> h.getClass().getSimpleName()).toList();
    }
}
