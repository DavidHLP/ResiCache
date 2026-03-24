package io.github.davidhlp.spring.cache.redis.core.writer.chain.handler;

import java.lang.annotation.*;

/**
 * 标注 Handler 的执行顺序
 * 
 * 数值越小，越先执行。
 * 允许运行时通过配置调整顺序，也便于新增 Handler。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface HandlerOrder {
    /**
     * 执行顺序值
     * @return 顺序值，数值越小越先执行
     */
    int value();
}
