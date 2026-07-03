package io.github.davidhlp.spring.cache.redis.chain;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.expression.AnnotatedElementKey;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Path C (WS-1.3) — 方法元数据解析器默认实现(且 ThreadLocal 所有者).
 *
 * <p>数据所有权(经 7 步迭代后):
 * <ul>
 *   <li>Step 1: ThreadLocal 在 {@code CacheOperationMetadataHolder} 静态类,本 resolver
 *       仅为调用方门面</li>
 *   <li>Step 2: 引入 {@link CacheInvocationContext} 值对象 + 在 resolver 暴露
 *       {@link #currentContext()};ScopedValue 字段声明但未激活</li>
 *   <li>Step 7 (本类):<strong>ThreadLocal 所有权从静态类迁到本 resolver 静态字段</strong>,
 *       {@code CacheOperationMetadataHolder} 静态类删除,所有 set/clear 改走
 *       {@link #activateStatic}/{@link #clearStatic};instance 方法直接读 OWN ThreadLocal
 *       (不再委托静态类)</li>
 * </ul>
 *
 * <p>设计选择 — 静态方法 vs 实例方法(ADR-0036 后):
 * <ul>
 *   <li>写入 API(activateStatic/clearStatic)<strong>保持 public 静态</strong> —
 *       ADR-0036 起 RedisCacheInterceptor 改走 {@link #activate}(ScopedActivation)消除跨包寄生;
 *       剩余静态调用者(CacheInvocationContext.restore + RedisProCacheWriterTest)因重构成本
 *       暂保留 public 访问,可见性收紧留作 follow-up</li>
 *   <li>读取 API(currentKey/currentMethod/currentTargetClass/currentContext)
 *       是实例方法 — 调用方(RedisProCacheWriter.buildContext、
 *       RedisProCache.lookupOperation) 持有 resolver 引用,直接调</li>
 * </ul>
 *
 * <p>线程安全: 静态 {@code ThreadLocal<AnnotatedElementKey>} 天然线程隔离,
 * 配合 {@link #clearStatic} 在 finally 调用防 commonPool 线程复用导致
 * ThreadLocal 跨任务泄漏。
 */
@Slf4j
@Component
public class DefaultMethodMetadataResolver implements MethodMetadataResolver {

    /**
     * Step 7 落地:ThreadLocal 所有权从 {@code CacheOperationMetadataHolder}
     * 静态类迁到本 resolver(静态字段 — Spring Bean 单例,所有实例共享
     * 同一 ThreadLocal 存储)。
     */
    private static final ThreadLocal<AnnotatedElementKey> CURRENT_KEY = new ThreadLocal<>();

    // ==================== 实例方法(读取 API) ====================

    @Override
    public AnnotatedElementKey currentKey() {
        return CURRENT_KEY.get();
    }

    @Override
    public Method currentMethod() {
        return MetadataKeys.extractMethod(currentKey());
    }

    @Override
    public Class<?> currentTargetClass() {
        return MetadataKeys.extractTargetClass(currentKey());
    }

    @Override
    public CacheInvocationContext currentContext() {
        return CacheInvocationContext.of(currentKey());
    }

    @Override
    public ScopedActivation activate(Method method, Class<?> targetClass) {
        Method previousMethod = currentMethod();
        Class<?> previousTargetClass = currentTargetClass();

        activateStatic(method, targetClass);
        log.debug("Activated method metadata: method={}, targetClass={}", method.getName(), targetClass.getName());

        return new ScopedActivation(() -> {
            if (previousMethod == null) {
                clearStatic();
            } else {
                activateStatic(previousMethod, previousTargetClass);
            }
        });
    }

    // ==================== 静态方法(写入 API) ====================

    /**
     * 设置当前线程的缓存操作元数据键 — Step 7 后所有写入路径都走这里
     * (替代已删除的 {@code CacheOperationMetadataHolder.setCurrentKey})。
     *
     * <p><b>ADR-0036 / Round 26 (C2)</b>:可见性保持 {@code public} —— RedisCacheInterceptor
     * 已迁移至 {@link #activate}(ScopedActivation)消除跨包寄生,但 {@code RedisProCacheWriterTest}
     * 与 {@code CacheInvocationContext.restore} 仍直接调用本静态 API,收紧至 package-private
     * 需同步重构 test,故本轮以 interceptor 迁移为 C2 核心交付,可见性收紧留作 follow-up。
     *
     * @param method      被拦截的方法
     * @param targetClass 目标类(原始类,非代理类)
     */
    public static void activateStatic(Method method, Class<?> targetClass) {
        CURRENT_KEY.set(new AnnotatedElementKey(method, targetClass));
    }

    /**
     * 清除当前线程的缓存操作元数据键 — Step 7 后所有清除路径都走这里
     * (替代已删除的 {@code CacheOperationMetadataHolder.clear})。
     * 防 commonPool 线程复用导致 ThreadLocal 跨任务泄漏。
     */
    public static void clearStatic() {
        CURRENT_KEY.remove();
    }

    // ==================== 异步边界管理(ADR-0035) ====================

    /**
     * ADR-0035 — 在异步边界(commonPool 切线程)内执行 work,snapshot/restore 自身
     * ThreadLocal + MDC,保证 work 读到的 context 与提交线程一致。
     *
     * <p>归位:原 {@code RedisProCacheWriter.withMethodMetadataSnapshot} 持有本类
     * snapshot/restore + {@link #clearStatic} 的跨域寄生逻辑(30 行)收敛到本方法 ——
     * resolver 自管自身的 ThreadLocal 边界,writer 不再知道 {@code clearStatic} 的存在。
     *
     * <p>MDC 一并内聚:同为「提交线程 → commonPool 线程」需透传的调用 context,
     * 集中一处优于分散(writer 各自 snapshot/restore)。非 ThreadLocal 实现走接口默认 no-op。
     */
    @Override
    public <T> T runWithSnapshot(Supplier<T> work) {
        CacheInvocationContext snapshot = CacheInvocationContext.snapshot(this);
        boolean restored = false;
        Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();
        boolean mdcRestored = false;
        try {
            if (snapshot != null) {
                snapshot.restore(this);
                restored = true;
            }
            if (mdcSnapshot != null && !mdcSnapshot.isEmpty()) {
                MDC.setContextMap(mdcSnapshot);
                mdcRestored = true;
            }
            return work.get();
        } finally {
            // 仅在 restore 过的线程上清,避免误清其他并发调用方设置的状态
            if (restored) {
                clearStatic();
            }
            if (mdcRestored) {
                MDC.clear();
            }
        }
    }

    // ==================== 反射工具 ====================
    // Round 23 / ADR-0032:`reflectField` 私有 helper 已迁到 package-private
    // {@link MetadataKeys} 工具 seam;本类不再持有反射样板。
}