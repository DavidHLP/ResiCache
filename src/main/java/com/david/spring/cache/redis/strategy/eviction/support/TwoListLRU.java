package com.david.spring.cache.redis.strategy.eviction.support;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
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

    @Setter private EvictionCallback<K, V> evictionCallback;

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
                            activeSize,
                            inactiveSize);
                }
                return true;
            } else {
                log.warn("Failed to add entry: key={}", key);
                return false;
            }
        } finally {
            listLock.writeLock().unlock();
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

        Node<K, V> node = nodeMap.get(key);
        if (node == null) {
            return null;
        }

        // 提升访问优先级
        promoteNodeSafe(node);
        return node.value;
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
        listLock.readLock().lock();
        try {
            return activeSize;
        } finally {
            listLock.readLock().unlock();
        }
    }

    /**
     * 获取不活跃列表大小
     *
     * @return 不活跃列表大小
     */
    public int getInactiveSize() {
        listLock.readLock().lock();
        try {
            return inactiveSize;
        } finally {
            listLock.readLock().unlock();
        }
    }

    /**
     * 获取总淘汰次数
     *
     * @return 淘汰次数
     */
    public long getTotalEvictions() {
        listLock.readLock().lock();
        try {
            return totalEvictions;
        } finally {
            listLock.readLock().unlock();
        }
    }

    /** 清空所有元素 */
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

        // 触发回调
        if (evictionCallback != null) {
            evictionCallback.onEviction(node.key, node.value);
        }
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
                evictNode(candidate);

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
        boolean isActive; // true=Active List, false=Inactive List

        Node(K key, V value) {
            this.key = key;
            this.value = value;
            this.isActive = true;
        }
    }
}
