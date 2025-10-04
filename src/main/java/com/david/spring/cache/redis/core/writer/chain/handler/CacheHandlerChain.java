package com.david.spring.cache.redis.core.writer.chain.handler;

import com.david.spring.cache.redis.core.writer.chain.CacheHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 缓存处理器责任链管理器 */
@Slf4j
@Component
public class CacheHandlerChain {
    /** 责任链头节点 */
    private CacheHandler head;

    /** 所有处理器列表（用于调试） */
    private final List<CacheHandler> handlers = new ArrayList<>();

    /**
     * 添加处理器到责任链末尾
     *
     * @param handler 处理器
     * @return 当前链（支持链式调用）
     */
    public CacheHandlerChain addHandler(CacheHandler handler) {
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
    }

    /**
     * 执行责任链
     *
     * @param context 缓存上下文
     * @return 处理结果
     */
    public CacheResult execute(CacheContext context) {
        if (head == null) {
            log.warn("Handler chain is empty!");
            return CacheResult.success();
        }

        log.debug(
                "Executing handler chain for operation: {}, cacheName: {}, key: {}",
                context.getOperation(),
                context.getCacheName(),
                context.getRedisKey());

        return head.handle(context);
    }

    /**
     * 获取处理器数量
     *
     * @return 处理器数量
     */
    public int size() {
        return handlers.size();
    }

    /**
     * 清空责任链
     */
    public void clear() {
        head = null;
        handlers.clear();
        log.debug("Handler chain cleared");
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
