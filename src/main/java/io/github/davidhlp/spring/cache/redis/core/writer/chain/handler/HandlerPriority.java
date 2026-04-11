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
public @interface HandlerPriority {
    /**
     * 执行顺序枚举
     * @return 执行顺序枚举值，数值越小越先执行
     */
    HandlerOrder value();

    /**
     * 兼容旧版本的 int 类型顺序值
     * @return 顺序值，数值越小越先执行
     * @deprecated 使用 {@link #value()} 代替
     */
    @Deprecated
    int order() default -1;
}
