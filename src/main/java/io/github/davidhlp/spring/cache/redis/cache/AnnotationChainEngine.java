package io.github.davidhlp.spring.cache.redis.cache;





import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.stereotype.Component;

/**
 * 注解解析结果消费引擎.
 *
 * <p>Spring operation source 在元素解析阶段把一个不可变 {@link AnnotationParser.ParsedAnnotations}
 * 写入 {@link RedisCacheRegister}。生产构造入口只从该快照读取 chain policy operations，
 * 因而调用方法时不再解析注解或写入 register。
 *
 * <p>单参数构造入口仅供现有 handler 单元测试使用，生产 Spring 装配使用带 register 的构造入口。
 *
 * <p>线程安全:Engine 持有不可变的 handler 列表与 register 引用；快照本身不可变。
 */
@Slf4j
@Component
class AnnotationChainEngine {

    /** 注解元素解析后的不可变策略快照 */
    private final RedisCacheRegister redisCacheRegister;
    private final List<AnnotationHandler> handlers;

    /** 供直接单测使用的 handler-only 构造入口 */
    public AnnotationChainEngine(List<AnnotationHandler> handlers) {
        this(handlers, null);
    }

    @Autowired
    public AnnotationChainEngine(
            List<AnnotationHandler> handlers,
            RedisCacheRegister redisCacheRegister) {
        this.handlers = List.copyOf(handlers);
        this.redisCacheRegister = redisCacheRegister;
        log.debug("AnnotationChainEngine initialized with {} handlers: {}",
                this.handlers.size(),
                this.handlers.stream().map(h -> h.getClass().getSimpleName()).toList());
    }

    /**
     * Reads the policy operations for the current annotated element.
     *
     * <p>The production path returns the immutable snapshot populated by
     * {@link RedisCacheOperationSource}; the handler-only fallback exists for direct unit tests.
     *
     * @param method current method
     * @param target target object
     * @param args invocation arguments (used only by the handler-only test path)
     * @return policy operations, never {@code null}
     */
    public List<CacheOperation> execute(Method method, Object target, Object[] args) {
        if (method == null) {
            throw new IllegalArgumentException("method must not be null");
        }
        if (redisCacheRegister != null) {
            Class<?> targetClass = target != null ? target.getClass() : method.getDeclaringClass();
            AnnotationParser.ParsedAnnotations snapshot =
                    redisCacheRegister.getSnapshot(method, targetClass);
            return snapshot == null
                    ? Collections.emptyList()
                    : snapshot.policyOperations();
        }
        // handler-only fallback is retained for direct unit tests.
        Object[] safeArgs = args != null ? args : new Object[0];

        // 直接遍历 handlers — per-handler 异常隔离即可。
        List<CacheOperation> collected = new ArrayList<>();
        for (AnnotationHandler handler : handlers) {
            if (!handler.canHandle(method)) {
                continue;
            }
            try {
                List<CacheOperation> ops = handler.doHandle(method, target, safeArgs);
                if (ops != null && !ops.isEmpty()) {
                    collected.addAll(ops);
                }
            } catch (Exception handlerEx) {
                // 单 handler 异常隔离：记 ERROR 日志，继续遍历剩余 handler
                log.error("AnnotationHandler.doHandle failed: {}, method: {}",
                        handler.getClass().getSimpleName(), method.getName(), handlerEx);
            }
        }

        return Collections.unmodifiableList(collected);
    }
}
