package io.github.davidhlp.spring.cache.redis.cache;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.context.expression.AnnotatedElementKey;

/**
 * Redis 缓存注册器。
 *
 * <p>Spring operation source 在元素解析阶段写入一个不可变
 * {@link AnnotationParser.ParsedAnnotations} 快照。operation source 与策略查询都从
 * 这份快照读取,避免各自维护索引导致两侧不一致。
 *
 * <p><b>查找键</b> = {@link AnnotatedElementKey} method/target pair. operation 自身的
 * {@code key} 字段(SpEL/字面量)是运行时缓存键的来源,与这里的注册查找键无关。
 */
@Slf4j
class RedisCacheRegister {

    private record SnapshotAlias(
            long generation, AnnotationParser.ParsedAnnotations snapshot) {
    }

    private final Map<AnnotatedElementKey, AnnotationParser.ParsedAnnotations> snapshotsByElement =
            new ConcurrentHashMap<>();

    private final Map<AnnotatedElementKey, SnapshotAlias> snapshotsByRequestedElement =
            new ConcurrentHashMap<>();
    private final AtomicLong registrationGeneration = new AtomicLong();
    private final Object snapshotAliasLock = new Object();

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
        synchronized (snapshotAliasLock) {
            registrationGeneration.incrementAndGet();
            snapshotsByElement.put(elementKey, snapshot);
            snapshotsByRequestedElement.clear();
        }
    }

    /**
     * Returns the parse result shared by the Spring source and policy resolver.
     */
    public AnnotationParser.ParsedAnnotations getSnapshot(Method method, Class<?> targetClass) {
        AnnotatedElementKey requestedKey = new AnnotatedElementKey(method, targetClass);
        long lookupGeneration;
        synchronized (snapshotAliasLock) {
            lookupGeneration = registrationGeneration.get();
            SnapshotAlias alias = snapshotsByRequestedElement.get(requestedKey);
            if (alias != null) {
                if (alias.generation() == lookupGeneration) {
                    return alias.snapshot();
                }
                snapshotsByRequestedElement.remove(requestedKey, alias);
            }
        }

        AnnotationParser.ParsedAnnotations snapshot = findSnapshot(method, targetClass);
        Method specificMethod = AopUtils.getMostSpecificMethod(method, targetClass);
        if (snapshot == null && !specificMethod.equals(method)) {
            snapshot = findSnapshot(specificMethod, targetClass);
        }
        if (snapshot == null) {
            snapshot = findInterfaceSnapshot(specificMethod, targetClass, new HashSet<>());
        }
        if (snapshot != null) {
            cacheAliasIfCurrent(requestedKey, snapshot, lookupGeneration);
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

    AnnotationParser.ParsedAnnotations findInterfaceSnapshot(
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

    long currentRegistrationGeneration() {
        return registrationGeneration.get();
    }

    void cacheAliasIfCurrent(
            AnnotatedElementKey requestedKey,
            AnnotationParser.ParsedAnnotations snapshot,
            long generation) {
        synchronized (snapshotAliasLock) {
            if (registrationGeneration.get() == generation) {
                snapshotsByRequestedElement.put(requestedKey, new SnapshotAlias(generation, snapshot));
            }
        }
    }


    // ============================ 查询（单一 seam）============================

    /**
     * 查询一个缓存操作 —— 从元素快照按 kind + cacheName 查索引。
     *
     * <p>类型不匹配或未命中视为未命中;同一 kind/cacheName 的多次声明保持覆盖语义
     * (后声明者胜),该语义由 {@link AnnotationParser.PolicyIndex} 在快照构造时一次建定,
     * 读取侧不再依赖列表扫描顺序。
     */
    @SuppressWarnings("unchecked")
    public <O extends CacheOperation> O get(String name, AnnotatedElementKey elementKey, OperationKind kind) {
        Method method = MetadataKeys.extractMethod(elementKey);
        Class<?> targetClass = MetadataKeys.extractTargetClass(elementKey);
        AnnotationParser.ParsedAnnotations snapshot =
                method == null || targetClass == null ? null : getSnapshot(method, targetClass);
        final CacheOperation operation = snapshot == null ? null : snapshot.policy(kind, name);
        if (operation != null) {
            return (O) operation;
        }
        log.debug("{} operation not found: name={}, elementKey={}", kind.tag(), name, elementKey);
        return null;
    }

}
