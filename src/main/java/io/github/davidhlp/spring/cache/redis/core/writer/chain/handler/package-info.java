/**
 * 缓存处理器责任链包（Chain of Responsibility Pattern）
 *
 * <h2>设计模式</h2>
 * <ul>
 *   <li><b>责任链模式</b>：将缓存操作拆分为多个独立的处理器，每个处理器负责特定功能</li>
 *   <li><b>策略模式</b>：每个处理器内部使用策略模式处理不同的操作类型</li>
 * </ul>
 *
 * <h2>责任链顺序</h2>
 * <pre>
 * CacheOperation Request
 *     ↓
 * 1. BloomFilterHandler    - 布隆过滤器检查（防止缓存穿透）
 *     ↓
 * 2. SyncLockHandler       - 同步锁处理（防止缓存击穿）
 *     ↓
 * 3. TtlHandler            - TTL 计算和配置
 *     ↓
 * 4. NullValueHandler      - 空值处理
 *     ↓
 * 5. ActualCacheHandler    - 实际缓存操作
 *     ↓
 * CacheResult
 * </pre>
 *
 * <h2>核心类说明</h2>
 * <ul>
 *   <li><b>CacheHandler</b>：处理器接口</li>
 *   <li><b>AbstractCacheHandler</b>：抽象处理器，提供模板方法</li>
 *   <li><b>CacheHandlerChain</b>：责任链管理器</li>
 *   <li><b>CacheHandlerChainFactory</b>：责任链工厂</li>
 *   <li><b>CacheContext</b>：上下文对象，贯穿整个责任链</li>
 *   <li><b>CacheResult</b>：结果对象</li>
 *   <li><b>CacheOperation</b>：操作类型枚举</li>
 * </ul>
 *
 * <h2>设计优势</h2>
 * <ul>
 *   <li>单一职责：每个处理器只负责一个功能</li>
 *   <li>开闭原则：可以轻松添加新的处理器而不修改现有代码</li>
 *   <li>可测试性：每个处理器可以独立测试</li>
 *   <li>灵活性：可以动态组合不同的处理器</li>
 *   <li>可维护性：代码结构清晰，易于理解和维护</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 创建责任链
 * CacheHandlerChain chain = chainFactory.createChain();
 *
 * // 构建上下文
 * CacheContext context = CacheContext.builder()
 *     .operation(CacheOperation.GET)
 *     .cacheName("user")
 *     .redisKey("user::123")
 *     .build();
 *
 * // 执行责任链
 * CacheResult result = chain.execute(context);
 * }</pre>
 *
 * @author David
 * @version 2.0
 * @since 2.0
 */
package io.github.davidhlp.spring.cache.redis.core.writer.chain.handler;
