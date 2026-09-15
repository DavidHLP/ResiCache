package io.github.davidhlp.spring.cache.redis.cache;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.context.expression.AnnotatedElementKey;

/**
 * Redis 缓存注册器。
 *
 * <p>Spring operation source 在元素解析阶段写入一个不可变
 * {@link AnnotationParser.ParsedAnnotations} 快照。annotation chain 与策略查询都从
 * 这份快照读取，避免 chain 与 resolver 各自维护索引导致两侧不一致。
 *
 * <p><b>查找键</b> = {@link AnnotatedElementKey} method/target pair. operation 自身的
 * {@code key} 字段(SpEL/字面量)是运行时缓存键的来源,与这里的注册查找键无关。
 */
@Slf4j
class RedisCacheRegister {

    private final Map<AnnotatedElementKey, AnnotationParser.ParsedAnnotations> snapshotsByElement =
            new ConcurrentHashMap<>();

    public RedisCacheRegister() {
    }

    /**
     * Registers the immutable parse result for an annotated element.
     */
    public void registerSnapshot(
            Method method,
            Class<?> targetClass,
            AnnotationParser.ParsedAnnotations snapshot) {
        AnnotatedElementKey elementKey = new AnnotatedElementKey(method, targetClass);
        snapshotsByElement.put(elementKey, snapshot);
    }

    /**
     * Returns the parse result shared by the Spring source and annotation chain.
     */
    public AnnotationParser.ParsedAnnotations getSnapshot(Method method, Class<?> targetClass) {
        AnnotationParser.ParsedAnnotations snapshot = findSnapshot(method, targetClass);
        Method specificMethod = AopUtils.getMostSpecificMethod(method, targetClass);
        if (snapshot == null && !specificMethod.equals(method)) {
            snapshot = findSnapshot(specificMethod, targetClass);
        }
        if (snapshot == null) {
            snapshot = findInterfaceSnapshot(specificMethod, targetClass, new HashSet<>());
        }
        return snapshot;
    }

    private AnnotationParser.ParsedAnnotations findSnapshot(Method method, Class<?> targetClass) {
        AnnotationParser.ParsedAnnotations snapshot = snapshotsByElement.get(
                new AnnotatedElementKey(method, targetClass));
        if (snapshot == null) {
            snapshot = snapshotsByElement.get(
                    new AnnotatedElementKey(method, method.getDeclaringClass()));
        }
        return snapshot;
    }

    private AnnotationParser.ParsedAnnotations findInterfaceSnapshot(
            Method method, Class<?> targetClass, Set<Class<?>> visited) {
        Class<?> type = targetClass;
        while (type != null) {
            for (Class<?> interfaceType : type.getInterfaces()) {
                if (!visited.add(interfaceType)) {
                    continue;
                }
                try {
                    Method interfaceMethod = interfaceType.getMethod(
                            method.getName(), method.getParameterTypes());
                    AnnotationParser.ParsedAnnotations snapshot =
                            findSnapshot(interfaceMethod, targetClass);
                    if (snapshot != null) {
                        return snapshot;
                    }
                    snapshot = findInterfaceSnapshot(interfaceMethod, interfaceType, visited);
                    if (snapshot != null) {
                        return snapshot;
                    }
                } catch (NoSuchMethodException ignored) {
                    // Continue searching inherited interfaces.
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    // ============================ 注册（单一 seam）============================

    /**
     * 注册一个缓存操作 —— 仅供 handler-only 测试路径构建快照。
     *
     * <p>生产解析路径使用 {@link #registerSnapshot(Method, Class, AnnotationParser.ParsedAnnotations)}。
     * 此兼容入口不建立独立 operation 索引，而是把 operation 合并进同一份快照，保证
     * fallback chain 与 resolver 仍读取相同的数据源。
     */
    public void register(Method method, Class<?> targetClass,
                         CacheOperation operation, OperationKind kind) {
        if (!kind.operationType().isInstance(operation)) {
            log.error("Operation kind mismatch: kind={} expects {} but got {}",
                    kind, kind.operationType().getSimpleName(),
                    operation.getClass().getSimpleName());
            return;
        }
        AnnotatedElementKey elementKey = new AnnotatedElementKey(method, targetClass);
        AnnotationParser.ParsedAnnotations existing = snapshotsByElement.get(elementKey);
        List<CacheOperation> operations = new ArrayList<>();
        List<CacheOperation> policies = new ArrayList<>();
        if (existing != null) {
            operations.addAll(existing.operations());
            policies.addAll(existing.policyOperations());
        }
        policies.removeIf(existingOperation ->
                kind.operationType().isInstance(existingOperation)
                        && existingOperation.getCacheNames().stream()
                        .anyMatch(operation.getCacheNames()::contains));
        policies.add(operation);
        operations.removeIf(existingOperation ->
                kind.operationType().isInstance(existingOperation)
                        && existingOperation.getCacheNames().stream()
                        .anyMatch(operation.getCacheNames()::contains));
        operations.add(operation);
        snapshotsByElement.put(elementKey,
                new AnnotationParser.ParsedAnnotations(operations, policies));
    }

    // ============================ 查询（单一 seam）============================

    /**
     * 查询一个缓存操作 —— 从元素快照按 kind + cacheName 过滤。
     *
     * <p>类型不匹配或未命中视为未命中;同一 kind/cacheName 的多次注册
     * 保持覆盖语义,返回最新 operation。
     */
    @SuppressWarnings("unchecked")
    public <O extends CacheOperation> O get(String name, AnnotatedElementKey elementKey, OperationKind kind) {
        Method method = MetadataKeys.extractMethod(elementKey);
        Class<?> targetClass = MetadataKeys.extractTargetClass(elementKey);
        AnnotationParser.ParsedAnnotations snapshot =
                method == null || targetClass == null ? null : getSnapshot(method, targetClass);
        if (snapshot != null) {
            List<CacheOperation> policies = snapshot.policyOperations();
            for (int i = policies.size() - 1; i >= 0; i--) {
                CacheOperation operation = policies.get(i);
                if (kind.operationType().isInstance(operation)
                        && operation.getCacheNames().contains(name)) {
                    return (O) operation;
                }
            }
        }
        log.debug("{} operation not found: name={}, elementKey={}", kind.tag(), name, elementKey);
        return null;
    }

}
