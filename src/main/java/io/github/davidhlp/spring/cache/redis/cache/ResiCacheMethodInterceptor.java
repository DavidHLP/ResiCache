package io.github.davidhlp.spring.cache.redis.cache;

import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.lang.Nullable;

/**
 * Path C (WS-1.3) — ResiCache 缓存方法拦截器.
 *
 * <p>独立实现 Spring AOP {@link MethodInterceptor} 接口,**<strong>不</strong>
 * 继承 Spring 的 {@code CacheInterceptor}。这是 Path C 的核心设计决策:
 * <ul>
 *   <li>避免继承 Spring 缓存抽象的 {@code ~8-10} 个内部类型,降低与
 *       Spring Data Redis / Spring Framework 主版本变更的耦合面</li>
 *   <li>为 Step 4 (组合 {@code CacheAspectSupport}) + Step 5 (advisor advice 切换)
 *       准备独立骨架</li>
 *   <li>Step 7 删除 {@code RedisCacheInterceptor} 时,本类作为唯一缓存入口</li>
 * </ul>
 *
 * <p>Path C 序列:
 * <ul>
 *   <li>Step 3 (本类): 新建骨架 + 纯 delegation Step 5 前 no-op</li>
 *   <li>Step 4: 组合 {@code CacheAspectSupport}(execute() protected,需 helper 继承
 *       或反射,分歧推荐表选 helper 继承)</li>
 *   <li>Step 5: {@code RedisProxyCachingConfiguration} 把 advisor advice 从
 *       {@code RedisCacheInterceptor} 换成 {@code ResiCacheMethodInterceptor}</li>
 *   <li>Step 6: {@code RedisProCacheWriter.retrieve()/store()} 加
 *       {@link io.github.davidhlp.spring.cache.redis.chain.CacheInvocationContext}
 *       snapshot/restore + {@code supportsAsyncRetrieve()=true} 恢复</li>
 *   <li>Step 7: 删除 {@code holder/CacheOperationMetadataHolder} +
 *       {@code cache/RedisCacheInterceptor}</li>
 * </ul>
 *
 * <p>Step 3 当前实现 = 纯 delegation(no-op):<strong>不读注解、不调缓存</strong>,
 * 仅透传到原方法。等 Step 4 加 {@code CacheAspectSupport} 组合后才真正处理缓存。
 * 这保证了"Step 3 切换 advisor 不会破坏现有行为"的安全演进(老
 * {@code RedisCacheInterceptor} 仍在线,本类不参与运行时)。
 */
@Slf4j
public class ResiCacheMethodInterceptor implements MethodInterceptor {

    /**
     * Step 3 骨架 — 纯 delegation,无任何缓存逻辑。
     *
     * <p>Step 4 将在此方法内组合 {@code CacheAspectSupport.execute()}(helper
     * 继承方式访问 protected 方法),Step 5 接入 advisor 后本方法开始承担
     * 全部缓存入口职责。
     *
     * @param invocation Spring AOP 方法调用上下文
     * @return 原方法的返回值(不读缓存、不写缓存)
     * @throws Throwable 原方法抛出的异常
     */
    @Override
    @Nullable
    public Object invoke(@Nullable MethodInvocation invocation) throws Throwable {
        // Step 3 骨架: 透传。Step 4 替换为 CacheAspectSupport.execute() 组合调用。
        log.debug("ResiCacheMethodInterceptor.invoke — Step 3 skeleton (no-op, delegation only)");
        if (invocation == null) {
            return null;
        }
        return invocation.proceed();
    }
}
