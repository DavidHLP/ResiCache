package io.github.davidhlp.spring.cache.redis.handler;

import io.github.davidhlp.spring.cache.redis.factory.OperationFactory;
import io.github.davidhlp.spring.cache.redis.operation.OperationKind;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheRegister;

import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.util.StringUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * 注解处理器模板基类 — ADR-0015 进一步收敛后的精简形态。
 *
 * <p>提供三种可复用样板（按"逐元素 → 逐注解"维度逐层下沉）：
 * <ol>
 *   <li>{@link #generateKey(Object, Method, Object[], String)} —— key 表达式优先，
 *       否则走 {@link KeyGenerator}（单元素版，{@link #registerOne} 内部使用）</li>
 *   <li>{@link #registerOne(Method, Object, Object[], Annotation, String, OperationFactory, RegisterAction, String)}
 *       —— 单注解注册模板：key 生成 → 工厂创建 → 注册 → 日志；异常返回 null
 *       （逐字保留原语义：单个注解解析失败不得中断整个拦截器链导致缓存全失效）</li>
 *   <li>{@link #registerAll(Method, Object, Object[], Annotation[], Function, OperationFactory, RegisterAction, String)}
 *       —— 多注解批量注册模板：迭代 {@code annotations[]} 调 {@code registerOne}，
 *       收集成功的 operation。空数组 / null 数组返回空 list；单元素异常隔离由
 *       {@code registerOne} 内部 try/catch 保证</li>
 * </ol>
 *
 * <p><b>ADR-0059 收敛</b>:原 {@link RegisterAction} 函数式接口签名
 * {@code (Method, Class<?>, O) -> void} 已扩展为 {@code (Method, Class<?>, O, OperationKind) -> void}
 * —— 新增第 4 参数传入 {@link OperationKind} 让 {@link RedisCacheRegister#register} 单一 seam
 * 区分命名空间。4 个具体 handler 调用点改为 lambda(本 seam 内嵌),丢失方法引用的
 * "语法糖",换来 register API 6 方法 → 2 方法的 seam 收敛与新增操作种类的零漂移。
 *
 * <p><b>下游契约</b>：4 个具体 handler（{@code Cacheable} / {@code CachePut} /
 * {@code Evict} / {@code Caching}）的 {@code doHandle} 方法现在只负责"获取注解
 * 数组 + 委派 registerAll"，不再重复 for-loop / null-check / ArrayList 样板。
 * 详见 ADR-0015。
 */
@Slf4j
public abstract class AbstractAnnotationHandler extends AnnotationHandler {

    protected final RedisCacheRegister redisCacheRegister;
    protected final KeyGenerator keyGenerator;

    protected AbstractAnnotationHandler(RedisCacheRegister redisCacheRegister, KeyGenerator keyGenerator) {
        this.redisCacheRegister = redisCacheRegister;
        this.keyGenerator = keyGenerator;
    }

    /** 解析 key：有表达式（SpEL/字面量）则用之，否则走 KeyGenerator */
    protected String generateKey(Object target, Method method, Object[] args, String keyExpression) {
        if (StringUtils.hasText(keyExpression)) {
            return keyExpression;
        }
        Object key = keyGenerator.generate(target, method, args);
        return String.valueOf(key);
    }

    /**
     * 注册动作的函数式接口 —— ADR-0059 扩展后增加 {@link OperationKind} 参数,
     * 对齐 {@link RedisCacheRegister#register(Method, Class, CacheOperation, OperationKind)}
     * 的 4 参 seam 签名。
     *
     * <p>调用方在 4 个具体 handler 中以 lambda 形式提供(如
     * {@code (m, c, op) -> register.register(m, c, op, OperationKind.CACHEABLE)}),
     * kind 在编译期固定,运行期无漂移风险。
     */
    @FunctionalInterface
    protected interface RegisterAction<O> {
        void register(Method method, Class<?> targetClass, O operation, OperationKind kind);
    }

    /**
     * 便捷工厂:从 {@link RedisCacheRegister} + 固定 {@link OperationKind} 生成
     * {@link RegisterAction} lambda —— 调用方无需在循环里重复传 kind。
     *
     * <p>典型用法:
     * <pre>
     * RegisterAction&lt;RedisCacheableOperation&gt; action = registerActionFor(OperationKind.CACHEABLE);
     * registerAll(method, target, args, cacheables, RedisCacheable::key, factory, action, "cacheable");
     * </pre>
     *
     * <p>消除每个具体 handler 各自的 lambda boilerplate,统一收敛到基类工厂。
     */
    protected <O extends CacheOperation> RegisterAction<O> registerActionFor(OperationKind kind) {
        return (method, targetClass, operation, k) ->
                redisCacheRegister.register(method, targetClass, operation, kind);
    }

    /**
     * 注册单个操作的标准模板：key 生成 → 工厂创建 → 注册 → 日志；异常返回 null。
     *
     * @param logTag 日志标识（如 "cacheable" / "cache put"），用于统一日志与错误信息
     * @param <A> 注解类型
     * @param <O> 操作类型
     * @return 创建并注册成功的操作，失败返回 null
     */
    protected <A extends Annotation, O extends CacheOperation> O registerOne(
            Method method, Object target, Object[] args, A annotation, String keyExpression,
            OperationFactory<A, O> factory, RegisterAction<O> registerAction, String logTag) {
        try {
            String key = generateKey(target, method, args, keyExpression);
            O operation = factory.create(method, annotation, key);
            Class<?> targetClass = target != null ? target.getClass() : null;
            registerAction.register(method, targetClass, operation, null);
            log.debug("Registered {} operation: {} with key: {} for caches: {}",
                    logTag, method.getName(), key, String.join(",", operation.getCacheNames()));
            return operation;
        } catch (Exception e) {
            log.error("Failed to register {} operation", logTag, e);
            return null;
        }
    }

    /**
     * 批量注册模板：对 {@code annotations[]} 中每个元素委派 {@link #registerOne}，
     * 收集成功的 operation 并以 {@link CacheOperation} 列表形式返回。空数组 /
     * null 数组返回空 list（不是 null）。
     *
     * <p>本方法统一了 4 个具体 handler 中重复的 for-loop + null-check + ArrayList
     * 样板（5 处 for-loop 收敛为 5 处单行委派）。每元素异常隔离由 {@code registerOne}
     * 内部 try/catch 保证——单个注解解析失败不影响其他注解的注册。
     *
     * <p><b>返回类型说明</b>：方法签名显式返回 {@code List<CacheOperation>}
     * （而非 {@code List<O>}），原因有二：
     * <ul>
     *   <li>子类 {@code doHandle(Method, Object, Object[])} 的契约是返回
     *       {@code List<CacheOperation>}，显式 return 触发 target-type 推断时不会
     *       强行把 {@code O} 拉到 {@code CacheOperation}，从而与 factory / method-ref
     *       的具体 {@code O}（如 {@code RedisCachePutOperation}）冲突。</li>
     *   <li>{@code O extends CacheOperation} 保证每个 operation 可安全上转为
     *       {@code CacheOperation}，调用方拿到的是统一的 Spring 抽象层。</li>
     * </ul>
     *
     * <p><b>典型用法</b>:
     * <pre>
     * RedisCacheEvict[] evicts = method.getAnnotationsByType(RedisCacheEvict.class);
     * return registerAll(method, target, args, evicts, RedisCacheEvict::key,
     *         evictOperationFactory, registerActionFor(OperationKind.CACHE_EVICT), "cache evict");
     * </pre>
     *
     * @param keyExtractor 从每个注解对象提取 key 表达式（SpEL / 字面量）的函数；
     *                     当前 3 个 ResiCache 注解都提供 {@code .key()} 方法，可
     *                     直接用方法引用 {@code RedisCacheable::key} 等
     * @param logTag 日志标识
     * @param <A> 注解类型
     * @param <O> 操作类型（{@code extends CacheOperation}）
     * @return 成功注册的 operation 列表（可能为空，<strong>不会</strong>为 null）
     */
    protected <A extends Annotation, O extends CacheOperation> List<CacheOperation> registerAll(
            Method method, Object target, Object[] args,
            A[] annotations,
            Function<A, String> keyExtractor,
            OperationFactory<A, O> factory,
            RegisterAction<O> registerAction,
            String logTag) {
        if (annotations == null || annotations.length == 0) {
            return Collections.emptyList();
        }
        List<CacheOperation> operations = new ArrayList<>(annotations.length);
        for (A annotation : annotations) {
            O operation = registerOne(method, target, args, annotation, keyExtractor.apply(annotation),
                    factory, registerAction, logTag);
            if (operation != null) {
                operations.add(operation);
            }
        }
        return operations;
    }
}
