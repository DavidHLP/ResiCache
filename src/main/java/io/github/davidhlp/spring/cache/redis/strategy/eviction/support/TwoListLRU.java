package io.github.davidhlp.spring.cache.redis.strategy.eviction.support;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Predicate;

@Slf4j
public class TwoListLRU<K, V> {

    /** 默认Active List最大容量 */
    private static final int DEFAULT_MAX_ACTIVE_SIZE = 1024;

    /** 默认Inactive List最大容量 */
    private static final int DEFAULT_MAX_INACTIVE_SIZE = 512;

    private final int maxActiveSize;
    private final int maxInactiveSize;

    /** 元素映射表，用于快速查找节点 - ConcurrentHashMap本身就是线程安全的 */
    private final ConcurrentHashMap<K, Node<K, V>> nodeMap;

    /** Active List头哨兵节点 */
    private final Node<K, V> activeHead;

    /** Active List尾哨兵节点 */
    private final Node<K, V> activeTail;

    /** Inactive List头哨兵节点 */
    private final Node<K, V> inactiveHead;

    /** Inactive List尾哨兵节点 */
    private final Node<K, V> inactiveTail;

    /**
     * 全局写锁，保护所有链表操作。
     *
     * <p>注意：此LRU算法需要同时操作active和inactive两个链表，
     * 节点在两个链表之间移动时需要保证原子性，因此全局锁是正确性要求而非性能瓶颈。
     * 节点查找本身是线程安全的（ConcurrentHashMap），锁仅在链表结构修改时需要。
     */
    private final ReentrantReadWriteLock globalLock = new ReentrantReadWriteLock();

    /** 当前Active List大小 — 使用AtomicInteger支持无锁读取 */
    private final AtomicInteger activeSizeCounter = new AtomicInteger(0);

    /** 当前Inactive List大小 — 使用AtomicInteger支持无锁读取 */
    private final AtomicInteger inactiveSizeCounter = new AtomicInteger(0);

    /** 总淘汰次数 — 使用AtomicLong保证线程安全 */
    private final AtomicLong totalEvictions = new AtomicLong(0);

    @Setter private volatile EvictionCallback<K, V> evictionCallback;

    @Setter private volatile Predicate<V> evictionPredicate;

    public TwoListLRU() {
        this(DEFAULT_MAX_ACTIVE_SIZE, DEFAULT_MAX_INACTIVE_SIZE);
    }

    public TwoListLRU(int maxActiveSize, int maxInactiveSize) {
        this(maxActiveSize, maxInactiveSize, null);
    }

    public TwoListLRU(int maxActiveSize, int maxInactiveSize, Predicate<V> evictionPredicate) {
        if (maxActiveSize <= 0) {
            throw new IllegalArgumentException("maxActiveSize must be positive");
        }
        if (maxInactiveSize <= 0) {
            throw new IllegalArgumentException("maxInactiveSize must be positive");
        }

        this.maxActiveSize = maxActiveSize;
        this.maxInactiveSize = maxInactiveSize;
        this.evictionPredicate = evictionPredicate;

        this.nodeMap = new ConcurrentHashMap<>();

        // 初始化Active List双向链表
        this.activeHead = new Node<>(null, null);
        this.activeTail = new Node<>(null, null);
        activeHead.next = activeTail;
        activeTail.prev = activeHead;

        // 初始化Inactive List双向链表
        this.inactiveHead = new Node<>(null, null);
        this.inactiveTail = new Node<>(null, null);
        inactiveHead.next = inactiveTail;
        inactiveTail.prev = inactiveHead;
    }

    /**
     * 获取指定key对应的读锁，用于并发读取
     *
     * @return 对应的ReadLock
     */
    private ReentrantReadWriteLock.ReadLock readLockForKey() {
        return globalLock.readLock();
    }

    /**
     * 获取指定key对应的写锁，用于修改操作
     *
     * @param key 键
     * @return 对应的WriteLock
     */
    private ReentrantReadWriteLock.WriteLock writeLockForKey() {
        return globalLock.writeLock();
    }

    /**
     * 添加元素
     *
     * @param key 键
     * @param value 值
     * @return true=添加成功，false=添加失败
     */
    public boolean put(K key, V value) {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }

        writeLockForKey().lock();
        try {
            Node<K, V> existingNode = nodeMap.get(key);
            if (existingNode != null) {
                // 更新值并提升优先级
                existingNode.value = value;
                promoteNodeUnsafe(existingNode);
                if (log.isDebugEnabled()) {
                    log.debug("Updated and promoted entry: key={}", key);
                }
                return true;
            }

            // 创建新节点并添加到Active List头部
            Node<K, V> newNode = new Node<>(key, value);
            boolean added = addToActiveHeadUnsafe(newNode);
            if (added) {
                nodeMap.put(key, newNode);
                if (log.isDebugEnabled()) {
                    log.debug(
                            "Added new entry: key={}, activeSize={}, inactiveSize={}",
                            key,
                            activeSizeCounter.get(),
                            inactiveSizeCounter.get());
                }
                return true;
            } else {
                log.warn("Failed to add entry: key={}", key);
                return false;
            }
        } finally {
            writeLockForKey().unlock();
        }
    }

    /**
     * 获取元素
     *
     * @param key 键
     * @return 值，不存在返回null
     */
    public V get(K key) {
        if (key == null) {
            return null;
        }

        writeLockForKey().lock();
        try {
            Node<K, V> node = nodeMap.get(key);
            if (node == null) {
                return null;
            }

            // 如果节点不需要提升（已经在Active List头部），直接返回
            if (node.isActive && activeHead.next == node) {
                return node.value;
            }

            promoteNodeSafe(node);
            return node.value;
        } finally {
            writeLockForKey().unlock();
        }
    }

    /**
     * 移除元素
     *
     * @param key 键
     * @return 被移除的值，不存在返回null
     */
    public V remove(K key) {
        if (key == null) {
            return null;
        }

        writeLockForKey().lock();
        try {
            Node<K, V> node = nodeMap.remove(key);
            if (node == null) {
                return null;
            }

            removeNodeUnsafe(node);
            if (node.isActive) {
                activeSizeCounter.decrementAndGet();
            } else {
                inactiveSizeCounter.decrementAndGet();
            }

            if (log.isDebugEnabled()) {
                log.debug(
                        "Removed entry: key={}, activeSize={}, inactiveSize={}",
                        key,
                        activeSizeCounter.get(),
                        inactiveSizeCounter.get());
            }
            return node.value;
        } finally {
            writeLockForKey().unlock();
        }
    }

    /**
     * 判断是否包含指定键
     *
     * @param key 键
     * @return true=包含，false=不包含
     */
    public boolean contains(K key) {
        return key != null && nodeMap.containsKey(key);
    }

    /**
     * 获取总元素数量
     *
     * @return 元素数量
     */
    public int size() {
        return nodeMap.size();
    }

    /**
     * 获取活跃列表大小
     *
     * @return 活跃列表大小
     */
    public int getActiveSize() {
        return activeSizeCounter.get();
    }

    /**
     * 获取不活跃列表大小
     *
     * @return 不活跃列表大小
     */
    public int getInactiveSize() {
        return inactiveSizeCounter.get();
    }

    /**
     * 获取总淘汰次数
     *
     * @return 淘汰次数
     */
    public long getTotalEvictions() {
        return totalEvictions.get();
    }

    /** 清空所有元素 */
    public void clear() {
        writeLockForKey().lock();
        try {
            nodeMap.clear();

            // 重置Active List
            activeHead.next = activeTail;
            activeTail.prev = activeHead;
            activeSizeCounter.set(0);

            // 重置Inactive List
            inactiveHead.next = inactiveTail;
            inactiveTail.prev = inactiveHead;
            inactiveSizeCounter.set(0);

            if (log.isDebugEnabled()) {
                log.debug("Cleared all entries");
            }
        } finally {
            writeLockForKey().unlock();
        }
    }

    /**
     * 提升节点优先级（调用者必须持有全局写锁）
     *
     * @param node 待提升的节点
     */
    private void promoteNodeSafe(Node<K, V> node) {
        // Caller holds global lock, no additional locking needed
        promoteNodeUnsafe(node);
    }

    /**
     * 提升节点优先级（非线程安全，需要持有写锁）
     *
     * @param node 待提升的节点
     */
    private void promoteNodeUnsafe(Node<K, V> node) {
        if (node.isActive) {
            // 已在Active List，如果已经在头部，无需操作
            if (activeHead.next == node) {
                return;
            }
            // 移到头部
            removeNodeUnsafe(node);
            insertAfterUnsafe(activeHead, node);
        } else {
            // 在Inactive List，提升到Active List
            removeNodeUnsafe(node);
            inactiveSizeCounter.decrementAndGet();

            // 尝试添加到Active List头部
            if (activeSizeCounter.get() >= maxActiveSize) {
                // Active List已满，先降级或淘汰最老的节点
                if (demoteOrEvictOldestActiveUnsafe()) {
                    // 无法腾出空间，将节点重新放回Inactive List头部
                    insertAfterUnsafe(inactiveHead, node);
                    inactiveSizeCounter.incrementAndGet();
                    node.isActive = false;
                    log.warn("Failed to promote entry from inactive to active: key={}", node.key);
                    return;
                }
            }

            insertAfterUnsafe(activeHead, node);
            node.isActive = true;
            activeSizeCounter.incrementAndGet();

            if (log.isDebugEnabled()) {
                log.debug("Promoted entry from inactive to active: key={}", node.key);
            }
        }
    }

    /**
     * 添加节点到Active List头部（非线程安全，需要持有写锁）
     *
     * @param node 待添加的节点
     * @return 是否添加成功
     */
    private boolean addToActiveHeadUnsafe(Node<K, V> node) {
        // Active List满时，先降级或淘汰最老的节点
        if (activeSizeCounter.get() >= maxActiveSize && demoteOrEvictOldestActiveUnsafe()) {
            // 无法腾出空间
            return false;
        }

        insertAfterUnsafe(activeHead, node);
        node.isActive = true;
        activeSizeCounter.incrementAndGet();
        return true;
    }

    /**
     * 查找第一个符合驱逐条件的节点（从最老的开始）
     *
     * @param head 链表头哨兵
     * @param tail 链表尾哨兵
     * @return 符合驱逐条件的节点，如果都受保护则返回null
     */
    private Node<K, V> findEvictableNode(Node<K, V> head, Node<K, V> tail) {
        final Predicate<V> pred = evictionPredicate;
        Node<K, V> candidate = tail.prev;
        while (candidate != head) {
            Node<K, V> prev = candidate.prev;
            if (pred == null || pred.test(candidate.value)) {
                return candidate;
            }
            if (log.isDebugEnabled()) {
                log.debug("Skipping protected entry: key={}", candidate.key);
            }
            candidate = prev;
        }
        return null;
    }

    /**
     * 降级或淘汰Active List中最老的节点（非线程安全，需要持有写锁）
     *
     * @return 是否成功腾出空间
     */
    private boolean demoteOrEvictOldestActiveUnsafe() {
        Node<K, V> candidate = findEvictableNode(activeHead, activeTail);
        if (candidate == null) {
            log.warn("All entries in active list are protected, cannot free space. activeSize={}, maxActiveSize={}",
                    activeSizeCounter.get(), maxActiveSize);
            return true;
        }

        removeNodeUnsafe(candidate);
        activeSizeCounter.decrementAndGet();

        if (inactiveSizeCounter.get() < maxInactiveSize) {
            demoteToInactive(candidate);
            return false;
        }

        if (evictOldestInactiveUnsafe()) {
            if (inactiveSizeCounter.get() < maxInactiveSize) {
                demoteToInactive(candidate);
                return false;
            }
            evictNode(candidate);
            if (log.isDebugEnabled()) {
                log.debug("Evicted entry from active list (inactive full): key={}", candidate.key);
            }
            return false;
        }

        insertAfterUnsafe(activeHead, candidate);
        activeSizeCounter.incrementAndGet();
        if (log.isDebugEnabled()) {
            log.debug("Failed to evict - returned to active list: key={}", candidate.key);
        }
        return false;
    }

    /** 将节点降级到Inactive List */
    private void demoteToInactive(Node<K, V> node) {
        insertAfterUnsafe(inactiveHead, node);
        node.isActive = false;
        inactiveSizeCounter.incrementAndGet();
        if (log.isDebugEnabled()) {
            log.debug("Demoted entry from active to inactive: key={}", node.key);
        }
    }

    /** 淘汰节点 */
    private void evictNode(Node<K, V> node) {
        nodeMap.remove(node.key);
        totalEvictions.incrementAndGet();

        // 触发回调
        EvictionCallback<K, V> callback = evictionCallback;
        if (callback != null) {
            callback.onEviction(node.key, node.value);
        }
    }

    /**
     * 淘汰Inactive List中最老的节点（非线程安全，需要持有写锁）
     *
     * @return 是否淘汰成功
     */
    private boolean evictOldestInactiveUnsafe() {
        Node<K, V> candidate = findEvictableNode(inactiveHead, inactiveTail);
        if (candidate == null) {
            log.warn("All entries in inactive list are protected, cannot evict. inactiveSize={}, maxInactiveSize={}",
                    inactiveSizeCounter.get(), maxInactiveSize);
            return false;
        }

        removeNodeUnsafe(candidate);
        inactiveSizeCounter.decrementAndGet();
        evictNode(candidate);

        if (log.isDebugEnabled()) {
            log.debug("Evicted entry from inactive list: key={}, totalEvictions={}",
                    candidate.key, totalEvictions);
        }
        return true;
    }

    /**
     * 在指定节点后插入新节点（非线程安全，需要持有写锁）
     *
     * @param prev 前驱节点
     * @param node 待插入的节点
     */
    private void insertAfterUnsafe(Node<K, V> prev, Node<K, V> node) {
        Node<K, V> next = prev.next;
        node.next = next;
        node.prev = prev;
        if (next != null) {
            next.prev = node;
        }
        prev.next = node;
    }

    /**
     * 修复链表链接（非线程安全，需要持有写锁）
     *
     * @param prev 前驱节点
     * @param next 后继节点
     */
    private void repairChainLinks(Node<K, V> prev, Node<K, V> next) {
        prev.next = next;
        if (next != null) {
            next.prev = prev;
        }
    }

    /**
     * 从链表中移除节点（非线程安全，需要持有写锁）
     *
     * @param node 待移除的节点
     */
    private void removeNodeUnsafe(Node<K, V> node) {
        Node<K, V> prev = node.prev;
        if (prev == null) {
            return;
        }
        Node<K, V> next = node.next;
        repairChainLinks(prev, next);
        node.prev = null;
        node.next = null;
    }

    /**
     * 淘汰回调接口
     *
     * @param <K> 键类型
     * @param <V> 值类型
     */
    @FunctionalInterface
    public interface EvictionCallback<K, V> {
        /**
         * 当元素被淘汰时调用
         *
         * @param key 被淘汰元素的键
         * @param value 被淘汰元素的值
         */
        void onEviction(K key, V value);
    }

    /**
     * 双向链表节点
     *
     * @param <K> 键类型
     * @param <V> 值类型
     */
    @Getter
    static class Node<K, V> {
        final K key;
        V value;
        Node<K, V> prev;
        Node<K, V> next;
        volatile boolean isActive; // true=Active List, false=Inactive List

        Node(K key, V value) {
            this.key = key;
            this.value = value;
            this.isActive = true;
        }
    }
}
