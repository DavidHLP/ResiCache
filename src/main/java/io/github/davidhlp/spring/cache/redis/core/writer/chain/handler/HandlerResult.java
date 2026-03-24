package io.github.davidhlp.spring.cache.redis.core.writer.chain.handler;

import io.github.davidhlp.spring.cache.redis.core.writer.chain.CacheResult;

/**
 * Handler 处理结果，包含链控制决策
 * 
 * 设计原则：
 * 1. 每个 Handler 返回明确的决策，控制链的执行
 * 2. 决策与结果分离，便于理解和维护
 * 3. 避免通过 resultBytes != null 等隐式条件判断终止
 */
public record HandlerResult(ChainDecision decision, CacheResult result) {
    
    /** 继续执行下一个 Handler（无中间结果） */
    public static HandlerResult continueChain() {
        return new HandlerResult(ChainDecision.CONTINUE, null);
    }
    
    /** 继续执行，携带中间结果 */
    public static HandlerResult continueWith(CacheResult result) {
        return new HandlerResult(ChainDecision.CONTINUE, result);
    }
    
    /** 终止责任链，返回结果 */
    public static HandlerResult terminate(CacheResult result) {
        return new HandlerResult(ChainDecision.TERMINATE, result);
    }
    
    /** 终止责任链，无结果 */
    public static HandlerResult terminate() {
        return new HandlerResult(ChainDecision.TERMINATE, null);
    }
    
    /** 跳过所有剩余处理器 */
    public static HandlerResult skipAll() {
        return new HandlerResult(ChainDecision.SKIP_ALL, CacheResult.success());
    }
    
    /** 跳过所有剩余处理器，携带结果 */
    public static HandlerResult skipAll(CacheResult result) {
        return new HandlerResult(ChainDecision.SKIP_ALL, result);
    }
    
    /** 是否应该终止链 */
    public boolean shouldTerminate() {
        return decision == ChainDecision.TERMINATE || decision == ChainDecision.SKIP_ALL;
    }
    
    /** 是否应该跳过剩余处理器 */
    public boolean shouldSkipAll() {
        return decision == ChainDecision.SKIP_ALL;
    }
}
