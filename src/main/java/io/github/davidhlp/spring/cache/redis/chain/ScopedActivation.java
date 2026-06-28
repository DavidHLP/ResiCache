package io.github.davidhlp.spring.cache.redis.chain;

/**
 * Path C (WS-1.3) — try-with-resources 句柄,封装
 * {@link MethodMetadataResolver#activate} 的作用域.
 *
 * <p>嵌套激活时 {@link #close()} 恢复到 activate() 调用前的状态(而非粗暴清空),
 * 是 Step 2+ ScopedValue 迁移的基础。
 */
public final class ScopedActivation implements AutoCloseable {

    private final Runnable restore;
    private boolean closed = false;

    /**
     * @param restore close() 时执行的恢复动作(通常由 resolver.activate() 内部构造)
     */
    ScopedActivation(Runnable restore) {
        this.restore = restore;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        restore.run();
    }
}
