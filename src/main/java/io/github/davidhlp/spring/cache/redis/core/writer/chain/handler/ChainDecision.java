package io.github.davidhlp.spring.cache.redis.core.writer.chain.handler;

/**
 * 责任链控制决策
 * 
 * 明确控制责任链的执行流程，避免隐式终止条件。
 */
public enum ChainDecision {
    /** 继续执行下一个 Handler */
    CONTINUE,
    
    /** 终止责任链，返回当前结果 */
    TERMINATE,
    
    /** 跳过剩余处理器，返回成功 */
    SKIP_ALL
}
