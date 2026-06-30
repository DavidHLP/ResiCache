package io.github.davidhlp.spring.cache.redis.chain.observer;

import io.github.davidhlp.spring.cache.redis.chain.ChainObserver;

/**
 * ChainObserver 的 no-op 默认实现 — 用于测试与未配置观测能力的生产环境。
 *
 * <p>所有钩子继承 {@link ChainObserver} 的 default no-op 实现，自身不持有
 * 任何状态也不注册任何外部依赖。Engine 的 observer 列表可安全地包含本实例
 * 而不产生任何副作用。
 *
 * <p>线程安全：完全无状态，可作为 Spring singleton bean 或静态常量共享。
 */
public final class NoOpChainObserver implements ChainObserver {

    /** 共享单例（Engine 装配时优先选用，避免每实例一对象）。 */
    public static final NoOpChainObserver INSTANCE = new NoOpChainObserver();

    public NoOpChainObserver() {
        // singleton / DI 皆可
    }
}
