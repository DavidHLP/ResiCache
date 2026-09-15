package io.github.davidhlp.spring.cache.redis.cache;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.context.expression.AnnotatedElementKey;

/**
 * Redis 缓存注册器。
 *
 * <p>Spring operation source 在元素解析阶段写入一个不可变
 * {@link AnnotationParser.ParsedAnnotations} 快照。annotation chain 与策略查询都从
 * 这份快照读取，避免独立索引在淘汰后出现两侧不一致。
 *
 * <p><b>查找键</b> = {@code SNAPSHOT:<elementKey>}，由
 * {@link #buildSnapshotKey(AnnotatedElementKey)} 统一构造。operation 自身的
 * {@code key} 字段(SpEL/字面量)是运行时缓存键的来源,与这里的注册查找键无关。
 *
 * <p>本类<em>直接</em>绑定 {@link TwoListLRU},无中间策略包装。
 */
@Slf4j
class RedisCacheRegister {

    private final TwoListLRU<String, AnnotationParser.ParsedAnnotations> snapshotLru;

    public RedisCacheRegister() {
        this(2048, 1024);
    }

    public RedisCacheRegister(int maxActiveSize, int maxInactiveSize) {
        this.snapshotLru = new TwoListLRU<>(maxActiveSize, maxInactiveSize);
    }

    /**
     * Registers the immutable parse result for an annotated element.
     */
    public void registerSnapshot(
            Method method,
            Class<?> targetClass,
            AnnotationParser.ParsedAnnotations snapshot) {
        AnnotatedElementKey elementKey = new AnnotatedElementKey(method, targetClass);
        snapshotLru.put(buildSnapshotKey(elementKey), snapshot);
    }

    /**
     * Returns the parse result shared by the Spring source and annotation chain.
     */
    public AnnotationParser.ParsedAnnotations getSnapshot(Method method, Class<?> targetClass) {
        AnnotationParser.ParsedAnnotations snapshot = snapshotLru.get(
                buildSnapshotKey(new AnnotatedElementKey(method, targetClass)));
        if (snapshot == null && targetClass != method.getDeclaringClass()) {
            snapshot = snapshotLru.get(buildSnapshotKey(
                    new AnnotatedElementKey(method, method.getDeclaringClass())));
        }
        return snapshot;
    }

    private String buildSnapshotKey(AnnotatedElementKey elementKey) {
        return "SNAPSHOT:" + elementKey;
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
        String key = buildSnapshotKey(elementKey);
        AnnotationParser.ParsedAnnotations existing = snapshotLru.get(key);
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
        snapshotLru.put(key,
                new AnnotationParser.ParsedAnnotations(operations, policies));
    }

    // ============================ 查询（单一 seam）============================

    /**
     * 查询一个缓存操作 —— 从元素快照按 kind + cacheName 过滤。
     *
     * <p>类型不匹配或未命中视为未命中,返回 {@code null};同一 kind/cacheName 的多次注册
     * 保持旧 LRU 的覆盖语义,返回最新 operation。
     */
    @SuppressWarnings("unchecked")
    public <O extends CacheOperation> O get(String name, AnnotatedElementKey elementKey, OperationKind kind) {
        AnnotationParser.ParsedAnnotations snapshot = snapshotLru.get(buildSnapshotKey(elementKey));
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
