package io.github.davidhlp.spring.cache.redis.handler;

/**
 * 默认 no-op observer 单例 — 镜像 {@code chain.observer.NoOpChainObserver}。
 *
 * <p>典型用法:Engine 启动后,未注册任何业务 observer 时的占位实现。
 * 当前版本不强制注入此 default(no-op 钩子已由 {@link AnnotationChainObserver}
 * default method 提供),保留以备未来 Engine 内部需要"始终存在一个 observer"
 * 的不变量时使用。
 *
 * <p>枚举单例优于 {@code public static final INSTANCE = new ...} 的原因是:
 * 序列化安全 / 反射攻击免疫 / 简洁。
 */
public enum NoOpAnnotationChainObserver implements AnnotationChainObserver {
    /** 全局唯一 no-op observer 实例 */
    INSTANCE
}
