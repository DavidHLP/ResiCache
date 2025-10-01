/// 缓存淘汰策略包
/// ## 概述
/// 本包提供了多种缓存淘汰策略的实现，基于策略模式设计，支持灵活扩展。
/// ## 核心组件
/// - [com.david.spring.cache.redis.strategy.eviction.EvictionStrategy] - 淘汰策略接口
/// - [com.david.spring.cache.redis.strategy.eviction.EvictionStats] - 统计信息
/// - [com.david.spring.cache.redis.strategy.eviction.EvictionStrategyFactory] - 策略工厂
/// ## 策略实现
/// - [com.david.spring.cache.redis.strategy.eviction.impl.TwoListEvictionStrategy] - 双链表策略
/// ## 使用示例
/// ```java
/// // 1. 使用工厂创建策略
/// EvictionStrategy<String, Object> strategy =
///     EvictionStrategyFactory.create(StrategyType.TWO_LIST, 1024);
/// // 2. 添加元素
/// strategy.put("key1", "value1");
/// strategy.put("key2", "value2");
/// // 3. 获取元素(会自动提升优先级)
/// Object value = strategy.get("key1");
/// // 4. 查看统计信息
/// EvictionStats stats = strategy.getStats();
/// System.out.println(stats);
/// // 5. 创建带淘汰判断器的策略
/// TwoListEvictionStrategy<String, Lock> lockStrategy =
///     EvictionStrategyFactory.createTwoListWithPredicate(
///         1024, 512,
///         lock -> !lock.isLocked() // 只淘汰未被持有的锁
///     );
/// ```
/// ## 设计模式
/// - **策略模式**: 定义统一接口，支持多种淘汰算法
/// - **工厂模式**: 统一创建不同策略实例
/// - **模板方法**: 双链表策略中的节点管理
/// ## 性能特点
/// | 策略 | 时间复杂度(get/put) | 空间复杂度 | 适用场景 |
/// |------|-------------------|----------|---------|
/// | Two-List | O(1) | O(n) | 冷热数据分离，防止缓存污染 |
///
/// @author David
/// @version 1.0
package com.david.spring.cache.redis.strategy.eviction;
