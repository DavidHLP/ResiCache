package io.github.davidhlp.spring.cache.redis.core.writer.chain;

import io.github.davidhlp.spring.cache.redis.core.writer.chain.handler.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 缓存处理器责任链工厂
 * 
 * 职责：
 * 1. 自动发现所有 CacheHandler 实现
 * 2. 按 @HandlerOrder 注解排序
 * 3. 构建责任链
 * 
 * 设计改进：
 * - 原设计：手动添加 Handler，顺序硬编码
 * - 新设计：自动注入 + 注解排序，便于扩展
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheHandlerChainFactory {

    /** 自动注入所有 CacheHandler 实现 */
    private final List<CacheHandler> handlers;

    /**
     * 创建责任链（自动排序）
     * 
     * @return 配置好的责任链
     */
    public CacheHandlerChain createChain() {
        CacheHandlerChain chain = new CacheHandlerChain();

        // 按 @HandlerOrder 注解排序
        List<CacheHandler> sortedHandlers = handlers.stream()
            .sorted(Comparator.comparingInt(this::getOrder))
            .toList();

        // 添加到链
        for (CacheHandler handler : sortedHandlers) {
            chain.addHandler(handler);
            log.debug("Added handler to chain: {} (order={})", 
                      handler.getClass().getSimpleName(), 
                      getOrder(handler));
        }

        log.info("Handler chain created with {} handlers: {}", 
                 chain.size(), chain.getHandlerNames());

        return chain;
    }

    /**
     * 获取 Handler 的执行顺序
     * 
     * @param handler Handler 实例
     * @return 顺序值，未标注则返回 Integer.MAX_VALUE
     */
    private int getOrder(CacheHandler handler) {
        HandlerOrder annotation = handler.getClass().getAnnotation(HandlerOrder.class);
        return annotation != null ? annotation.value() : Integer.MAX_VALUE;
    }
}
