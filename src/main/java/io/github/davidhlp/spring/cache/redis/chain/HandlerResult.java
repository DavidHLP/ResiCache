package io.github.davidhlp.spring.cache.redis.chain;




/**
 * Handler 处理结果，包含链控制决策
 * 
 * 设计原则：
 * 1. 每个 Handler 返回明确的决策，控制链的执行
 * 2. 决策与结果分离，便于理解和维护
 * 3. 避免通过 resultBytes != null 等隐式条件判断终止
 */
public record HandlerResult(FlowControl decision, CacheResult result) {
    
    /** 继续执行下一个 Handler（无中间结果） */
    public static HandlerResult continueChain() {
        return new HandlerResult(FlowControl.CONTINUE, null);
    }
    
    /** 继续执行，携带中间结果 */
    public static HandlerResult continueWith(CacheResult result) {
        return new HandlerResult(FlowControl.CONTINUE, result);
    }
    
    /** 终止责任链，返回结果 */
    public static HandlerResult terminate(CacheResult result) {
        return new HandlerResult(FlowControl.TERMINATE, result);
    }
    
    /** 终止责任链，无结果 */
    public static HandlerResult terminate() {
        return new HandlerResult(FlowControl.TERMINATE, null);
    }
    
    /** 跳过所有剩余处理器 */
    public static HandlerResult skipAll() {
        return new HandlerResult(FlowControl.SKIP_ALL, CacheResult.success());
    }
    
    /** 跳过所有剩余处理器，携带结果 */
    public static HandlerResult skipAll(CacheResult result) {
        return new HandlerResult(FlowControl.SKIP_ALL, result);
    }

    /** 是否应该终止链 — 测试断言便捷入口(21 处 test 引用,保留)。 */
    public boolean shouldTerminate() {
        return decision == FlowControl.TERMINATE || decision == FlowControl.SKIP_ALL;
    }
}
