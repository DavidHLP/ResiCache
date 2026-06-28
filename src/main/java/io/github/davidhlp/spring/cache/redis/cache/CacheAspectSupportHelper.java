package io.github.davidhlp.spring.cache.redis.cache;

import org.springframework.cache.interceptor.CacheAspectSupport;
import org.springframework.cache.interceptor.CacheOperationInvoker;
import org.springframework.lang.Nullable;

import java.lang.reflect.Method;

/**
 * Path C (WS-1.3) Step 4 — 包私有 helper,继承 {@link CacheAspectSupport} 暴露
 * protected {@code execute(...)} 方法.
 *
 * <p><strong>分歧推荐表(Step 4 决策)</strong>:helper 继承方式,不用反射。
 * <ul>
 *   <li><strong>优势</strong>:子类继承 + 公开方法,反射破坏(运行期
 *       {@code NoSuchMethodException})风险消除;未来 SDR/Spring 改 {@code execute()}
 *       签名时编译期立即报错,反射是运行期才发现</li>
 *   <li><strong>代价</strong>:必须 {@code extends CacheAspectSupport} → 继承面
 *       仍约 1 个类,但比 {@code extends CacheInterceptor}(~8-10 个内部类型)
 *       小一个数量级,且 {@code execute()} 是稳定 public API(已多版本未变)</li>
 *   <li><strong>未来演进</strong>:若 Spring 进一步封装 {@code execute()},可
 *       在本 helper 适配后再暴露新签名,主流程 {@code ResiCacheMethodInterceptor}
 *       无需改动</li>
 * </ul>
 *
 * <p>Step 4 范围:
 * <ul>
 *   <li>本类仅声明 + 暴露 protected {@code execute(CacheOperationInvoker, Object, Method, Object[])}</li>
 *   <li>{@code ResiCacheMethodInterceptor} 暂不调用(Step 5 接入 advisor 后才调)</li>
 *   <li>{@code CacheAspectSupport} 需要的依赖({@code cacheOperationSource} /
 *       {@code keyGenerator} / {@code cacheResolver} / {@code cacheManager})
 *       由 {@code ResiCacheMethodInterceptor} 通过 Spring DI 注入后转交给本 helper(Step 5 实现)</li>
 * </ul>
 */
class CacheAspectSupportHelper extends CacheAspectSupport {

    /**
     * 暴露 {@code CacheAspectSupport.execute(...)} — 将 protected 拓宽为 public.
     *
     * <p>{@code CacheAspectSupport} 自身的 {@code execute(...)} 签名是
     * {@code protected Object execute(CacheOperationInvoker, Object, Method, Object[])},
     * 本 override 保持签名一致,仅可见性从 protected 改为 public 以便
     * {@code ResiCacheMethodInterceptor} 调用。
     *
     * @param invoker Spring 内部的缓存操作执行器(包装 invocation.proceed())
     * @param target  目标对象
     * @param method  被拦截的方法
     * @param args    方法参数
     * @return 缓存操作的执行结果
     */
    @Nullable
    @Override
    public Object execute(
            CacheOperationInvoker invoker,
            Object target,
            Method method,
            Object[] args) {
        // Step 4 范围:仅暴露,实际行为由父类提供(spring 内部会查 cacheOperationSource
        // → 解析 @Cacheable/@RedisCacheable 注解 → 走 cache.get/put/evict)。
        // ResiCache 的链增强(Bloom/Sync/TTL/NullValue/ActualCache)由 RedisProCacheWriter
        // 在 cache.get/put/evict 路径中触发,故此 execute() 调用后行为等价于老
        // RedisCacheInterceptor + 父 CacheInterceptor 的组合(Step 5 接入时验证)。
        return super.execute(invoker, target, method, args);
    }
}
