package com.david.spring.cache.redis.strategy.eviction;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Predicate;

/// 双链表(Two-List)淘汰策略 基于Linux内核的Two-List策略实现
///
/// 策略说明：
///
/// - Active List：活跃使用的元素，容量为maxActiveSize
/// - Inactive List：不活跃的元素，容量为maxInactiveSize
/// - 新元素加入Active List头部
/// - Active List满时，将最老的元素降级到Inactive List
/// - Inactive List满时，淘汰最老的元素
/// - 访问Inactive List中的元素时，提升到Active List
///
///
/// 线程安全：使用ReentrantReadWriteLock保证并发访问的安全性
///
/// @param <K> 键类型
/// @param <V> 值类型
@Slf4j
public class TwoListEvictionStrategy<K, V> implements EvictionStrategy<K, V> {

    /** 默认Active List最大容量 */
    private static final int DEFAULT_MAX_ACTIVE_SIZE = 1024;

    /** 默认Inactive List最大容量 */
    private static final int DEFAULT_MAX_INACTIVE_SIZE = 512;

    private final int maxActiveSize;
    private final int maxInactiveSize;

    /** 元素映射表，用于快速查找节点 */
    private final ConcurrentHashMap<K, Node<K, V>> nodeMap;

    /** Active List头哨兵节点 */
    private final Node<K, V> activeHead;

    /** Active List尾哨兵节点 */
    private final Node<K, V> activeTail;

    /** Inactive List头哨兵节点 */
    private final Node<K, V> inactiveHead;

    /** Inactive List尾哨兵节点 */
    private final Node<K, V> inactiveTail;

    /** 读写锁，保证链表操作的线程安全 */
    private final ReadWriteLock listLock;

    /** 当前Active List大小 */
    private int activeSize;

    /** 当前Inactive List大小 */
    private int inactiveSize;

    /** 总淘汰次数 */
    private long totalEvictions;

    /** 元素淘汰判断器(可选) -- SETTER -- 设置淘汰判断器 */
    @Setter private volatile Predicate<V> evictionPredicate;

    public TwoListEvictionStrategy() {
        this(DEFAULT_MAX_ACTIVE_SIZE, DEFAULT_MAX_INACTIVE_SIZE);
    }

    public TwoListEvictionStrategy(int maxActiveSize, int maxInactiveSize) {
        this(maxActiveSize, maxInactiveSize, null);
    }

    public TwoListEvictionStrategy(
            int maxActiveSize, int maxInactiveSize, Predicate<V> evictionPredicate) {
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
        this.listLock = new ReentrantReadWriteLock();

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

        this.activeSize = 0;
        this.inactiveSize = 0;
        this.totalEvictions = 0;
    }

    @Override
    public void put(K key, V value) {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }

        listLock.writeLock().lock();
        try {
            Node<K, V> existingNode = nodeMap.get(key);
            if (existingNode != null) {
                // 更新值并提升优先级
                existingNode.value = value;
                promoteNodeUnsafe(existingNode);
                if (log.isDebugEnabled()) {
                    log.debug("Updated and promoted entry: key={}", key);
                }
                return;
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
                            activeSize,
                            inactiveSize);
                }
            } else {
                log.warn("Failed to add entry to cache: key={}", key);
            }
        } finally {
            listLock.writeLock().unlock();
        }
    }

    @Override
    public V get(K key) {
        if (key == null) {
            return null;
        }

        Node<K, V> node = nodeMap.get(key);
        if (node == null) {
            return null;
        }

        // 提升访问优先级
        promoteNodeSafe(node);
        return node.value;
    }

    @Override
    public V remove(K key) {
        if (key == null) {
            return null;
        }

        listLock.writeLock().lock();
        try {
            Node<K, V> node = nodeMap.remove(key);
            if (node == null) {
                return null;
            }

            removeNodeUnsafe(node);
            if (node.isActive) {
                activeSize--;
            } else {
                inactiveSize--;
            }

            if (log.isDebugEnabled()) {
                log.debug(
                        "Removed entry: key={}, activeSize={}, inactiveSize={}",
                        key,
                        activeSize,
                        inactiveSize);
            }
            return node.value;
        } finally {
            listLock.writeLock().unlock();
        }
    }

    @Override
    public boolean contains(K key) {
        return key != null && nodeMap.containsKey(key);
    }

    @Override
    public int size() {
        return nodeMap.size();
    }

    @Override
    public void clear() {
        listLock.writeLock().lock();
        try {
            nodeMap.clear();

            // 重置Active List
            activeHead.next = activeTail;
            activeTail.prev = activeHead;
            activeSize = 0;

            // 重置Inactive List
            inactiveHead.next = inactiveTail;
            inactiveTail.prev = inactiveHead;
            inactiveSize = 0;

            if (log.isDebugEnabled()) {
                log.debug("Cleared all entries");
            }
        } finally {
            listLock.writeLock().unlock();
        }
    }

    @Override
    public EvictionStats getStats() {
        listLock.readLock().lock();
        try {
            return new EvictionStats(
                    nodeMap.size(),
                    activeSize,
                    inactiveSize,
                    maxActiveSize,
                    maxInactiveSize,
                    totalEvictions);
        } finally {
            listLock.readLock().unlock();
        }
    }

    /**
     * 提升节点优先级（线程安全版本）
     *
     * @param node 待提升的节点
     */
    private void promoteNodeSafe(Node<K, V> node) {
        listLock.writeLock().lock();
        try {
            promoteNodeUnsafe(node);
        } finally {
            listLock.writeLock().unlock();
        }
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
            inactiveSize--;

            // 尝试添加到Active List头部
            if (activeSize >= maxActiveSize) {
                // Active List已满，先降级或淘汰最老的节点
                if (demoteOrEvictOldestActiveUnsafe()) {
                    // 无法腾出空间，将节点重新放回Inactive List头部
                    insertAfterUnsafe(inactiveHead, node);
                    inactiveSize++;
                    node.isActive = false;
                    log.warn("Failed to promote entry from inactive to active: key={}", node.key);
                    return;
                }
            }

            insertAfterUnsafe(activeHead, node);
            node.isActive = true;
            activeSize++;

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
        if (activeSize >= maxActiveSize && demoteOrEvictOldestActiveUnsafe()) {
            // 无法腾出空间
            return false;
        }

        insertAfterUnsafe(activeHead, node);
        node.isActive = true;
        activeSize++;
        return true;
    }

    /**
     * 降级或淘汰Active List中最老的节点（非线程安全，需要持有写锁）
     *
     * @return 是否成功腾出空间
     */
    private boolean demoteOrEvictOldestActiveUnsafe() {
        // 查找可以降级的节点（从最老的开始）
        Node<K, V> candidate = activeTail.prev;
        while (candidate != activeHead) {
            // 如果没有淘汰判断器，或者判断器允许操作
            if (evictionPredicate == null || evictionPredicate.test(candidate.value)) {
                removeNodeUnsafe(candidate);
                activeSize--;

                // 尝试降级到Inactive List或淘汰
                if (inactiveSize < maxInactiveSize) {
                    // Inactive List有空间，直接降级
                    demoteToInactive(candidate);
                } else if (evictOldestInactiveUnsafe()) {
                    // Inactive List已满，尝试淘汰后降级
                    demoteToInactive(candidate);
                } else {
                    // 无法淘汰Inactive节点，直接淘汰当前节点
                    evictNode(candidate);
                    if (log.isDebugEnabled()) {
                        log.debug(
                                "Evicted entry from active list (inactive full): key={}",
                                candidate.key);
                    }
                }
                return false;
            }

            // 受保护的节点，尝试下一个
            if (log.isDebugEnabled()) {
                log.debug("Skipping protected entry in active list: key={}", candidate.key);
            }
            candidate = candidate.prev;
        }

        // 所有节点都受保护
        log.warn(
                "All entries in active list are protected, cannot free space. activeSize={}, maxActiveSize={}",
                activeSize,
                maxActiveSize);
        return true;
    }

    /** 将节点降级到Inactive List */
    private void demoteToInactive(Node<K, V> node) {
        insertAfterUnsafe(inactiveHead, node);
        node.isActive = false;
        inactiveSize++;
        if (log.isDebugEnabled()) {
            log.debug("Demoted entry from active to inactive: key={}", node.key);
        }
    }

    /** 淘汰节点 */
    private void evictNode(Node<K, V> node) {
        nodeMap.remove(node.key);
        totalEvictions++;
    }

    /**
     * 淘汰Inactive List中最老的节点（非线程安全，需要持有写锁）
     *
     * @return 是否淘汰成功
     */
    private boolean evictOldestInactiveUnsafe() {
        // 查找可以淘汰的节点（从最老的开始）
        Node<K, V> candidate = inactiveTail.prev;
        while (candidate != inactiveHead) {
            // 如果没有淘汰判断器，或者判断器允许淘汰
            if (evictionPredicate == null || evictionPredicate.test(candidate.value)) {
                removeNodeUnsafe(candidate);
                inactiveSize--;
                nodeMap.remove(candidate.key);
                totalEvictions++;

                if (log.isDebugEnabled()) {
                    log.debug(
                            "Evicted entry from inactive list: key={}, totalEvictions={}",
                            candidate.key,
                            totalEvictions);
                }
                return true;
            }

            // 受保护的节点，尝试下一个
            if (log.isDebugEnabled()) {
                log.debug("Skipping protected entry in inactive list: key={}", candidate.key);
            }
            candidate = candidate.prev;
        }

        // 所有节点都受保护
        log.warn(
                "All entries in inactive list are protected, cannot evict. inactiveSize={}, maxInactiveSize={}",
                inactiveSize,
                maxInactiveSize);
        return false;
    }

    /**
     * 在指定节点后插入新节点（非线程安全，需要持有写锁）
     *
     * @param prev 前驱节点
     * @param node 待插入的节点
     */
    private void insertAfterUnsafe(Node<K, V> prev, Node<K, V> node) {
        node.next = prev.next;
        node.prev = prev;
        prev.next.prev = node;
        prev.next = node;
    }

    /**
     * 从链表中移除节点（非线程安全，需要持有写锁）
     *
     * @param node 待移除的节点
     */
    private void removeNodeUnsafe(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
        // 清空节点的链表引用，帮助GC
        node.prev = null;
        node.next = null;
    }

    /** 诊断：验证数据一致性 */
    public void validateConsistency() {
        listLock.readLock().lock();
        try {
            int actualActiveCount = 0;
            Node<K, V> node = activeHead.next;
            while (node != activeTail) {
                actualActiveCount++;
                if (!node.isActive) {
                    log.error("发现Active链表中的非Active节点: {}", node.key);
                }
                if (!nodeMap.containsKey(node.key)) {
                    log.error("发现Active链表中但不在nodeMap的节点: {}", node.key);
                }
                node = node.next;
            }

            int actualInactiveCount = 0;
            node = inactiveHead.next;
            while (node != inactiveTail) {
                actualInactiveCount++;
                if (node.isActive) {
                    log.error("发现Inactive链表中的Active节点: {}", node.key);
                }
                if (!nodeMap.containsKey(node.key)) {
                    log.error("发现Inactive链表中但不在nodeMap的节点: {}", node.key);
                }
                node = node.next;
            }

            if (log.isInfoEnabled()) {
                log.info(
                        "链表验证: activeSize={}/{}, inactiveSize={}/{}, nodeMap.size={}",
                        actualActiveCount,
                        activeSize,
                        actualInactiveCount,
                        inactiveSize,
                        nodeMap.size());
            }

            // 检查nodeMap中的孤儿节点
            int orphans = 0;
            for (Node<K, V> n : nodeMap.values()) {
                if (n.prev == null && n.next == null) {
                    orphans++;
                    log.error("发现孤儿节点(prev/next都为null): key={}, isActive={}", n.key, n.isActive);
                }
            }
            if (log.isInfoEnabled()) {
                log.info("孤儿节点数: {}", orphans);
            }

        } finally {
            listLock.readLock().unlock();
        }
    }

    /**
     * 双向链表节点
     *
     * @param <K> 键类型
     * @param <V> 值类型
     */
    private static class Node<K, V> {
        final K key;
        V value;
        Node<K, V> prev;
        Node<K, V> next;
        boolean isActive; // true=Active List, false=Inactive List

        Node(K key, V value) {
            this.key = key;
            this.value = value;
            this.isActive = true;
        }
    }
}
