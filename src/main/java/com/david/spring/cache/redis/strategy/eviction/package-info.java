/// 缓存淘汰策略包
/// ## 概述
/// 本包提供了多种缓存淘汰策略的实现，基于策略模式设计，支持灵活扩展。
/// ## 核心组件
/// - [com.david.spring.cache.redis.strategy.eviction.EvictionStrategy] - 淘汰策略接口
/// - [com.david.spring.cache.redis.strategy.eviction.stats.EvictionStats] - 统计信息
/// - [com.david.spring.cache.redis.strategy.eviction.EvictionStrategyFactory] - 策略工厂
/// - [com.david.spring.cache.redis.strategy.eviction.LockPoolManager] - 本地锁池化管理器
/// - [com.david.spring.cache.redis.strategy.eviction.LockWrapper] - 锁包装器
/// ## 策略实现
/// - [com.david.spring.cache.redis.strategy.eviction.impl.TwoListEvictionStrategy] - 双链表策略
/// ## 使用示例
/// ### 1. 淘汰策略基本用法
/// ```java
/// // 使用工厂创建策略
/// EvictionStrategy<String, Object> strategy =
///     EvictionStrategyFactory.create(StrategyType.TWO_LIST, 1024);
/// // 添加元素
/// strategy.put("key1", "value1");
/// strategy.put("key2", "value2");
/// // 获取元素(会自动提升优先级)
/// Object value = strategy.get("key1");
/// // 查看统计信息
/// EvictionStats stats = strategy.getStats();
/// System.out.println(stats);
/// ```
///
/// ### 2. 锁池化使用示例
/// ```java
/// // 创建锁池管理器
/// LockPoolManager lockPool = new LockPoolManager();
///
/// // 获取锁
/// LockWrapper lock = lockPool.acquire("user:123");
/// lock.lock();
/// try {
///     // 临界区操作
///     // ...
/// } finally {
///     lock.unlock();
///     lockPool.release("user:123");
/// }
///
/// // 查看统计信息
/// LockPoolStats stats = lockPool.getStats();
/// System.out.println("缓存命中率: " + stats.hitRate() * 100 + "%");
/// System.out.println("内存占用: " + stats.estimatedMemoryKB() + "KB");
/// ```
///
/// ### 3. 防止缓存击穿示例
/// ```java
/// public class CacheService {
///     private final LockPoolManager lockPool = new LockPoolManager();
///     private final RedisTemplate redisTemplate;
///
///     public Object getOrLoad(String key) {
///         // 先从缓存获取
///         Object value = redisTemplate.opsForValue().get(key);
///         if (value != null) {
///             return value;
///         }
///
///         // 获取本地锁，防止击穿
///         LockWrapper lock = lockPool.acquire(key);
///         lock.lock();
///         try {
///             // 双重检查
///             value = redisTemplate.opsForValue().get(key);
///             if (value != null) {
///                 return value;
///             }
///
///             // 从数据库加载
///             value = loadFromDatabase(key);
///             redisTemplate.opsForValue().set(key, value, 5, TimeUnit.MINUTES);
///
///             return value;
///         } finally {
///             lock.unlock();
///             lockPool.release(key);
///         }
///     }
/// }
/// ```
///
/// ## 锁池化机制
/// ### 优化点：
/// - **锁对象复用**：相同 key 复用 LockWrapper 对象
/// - **自动淘汰**：使用双链表 LRU 算法淘汰冷锁
/// - **智能淘汰**：只淘汰未被持有的锁
/// - **内存可控**：限制活跃锁和不活跃锁的数量
///
/// ### 内存占用估算：
/// - 默认配置：1024(Active) + 512(Inactive) = 1536 个锁
/// - 每个 ReentrantLock 约 48 字节
/// - 总内存：1536 × 48 ≈ 74 KB
///
/// ## 设计模式
/// - **策略模式**: 定义统一接口，支持多种淘汰算法
/// - **工厂模式**: 统一创建不同策略实例
/// - **模板方法**: 双链表策略中的节点管理
/// - **对象池模式**: 锁池化管理器实现锁对象复用
///
/// ## 性能特点
/// | 策略 | 时间复杂度(get/put) | 空间复杂度 | 适用场景 |
/// |------|-------------------|----------|---------|
/// | Two-List | O(1) | O(n) | 冷热数据分离，防止缓存污染 |
/// | LockPool | O(1) | O(n) | 本地锁复用，防止缓存击穿 |
///
/// @author David
/// @version 1.0
package com.david.spring.cache.redis.strategy.eviction;
