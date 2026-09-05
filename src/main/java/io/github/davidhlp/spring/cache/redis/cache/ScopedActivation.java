package io.github.davidhlp.spring.cache.redis.cache;


/**
 * try-with-resources 句柄,封装
 * {@link MethodMetadataResolver#activate} 的作用域.
 *
 * <p>嵌套激活时 {@link #close()} 恢复到 activate() 调用前的状态(而非粗暴清空).
 */
final class ScopedActivation implements AutoCloseable {

    private final Runnable restore;
    private boolean closed = false;

    /**
     * @param restore close() 时执行的恢复动作(通常由 resolver.activate() 内部构造)
     */
    public ScopedActivation(Runnable restore) {
        this.restore = java.util.Objects.requireNonNull(restore, "restore must not be null");
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
