# Requirements — v1.0 缺陷修复

## Phase 1: 技术债务修复 (TECH)

- [ ] **TECH-01**: TwoListLRU getActiveSize/getInactiveSize 读锁竞争优化
  - 使用 StripedReadWriteLock 或原子计数器减少锁竞争
  - 文件: `TwoListLRU.java` (lines 225-246)

- [ ] **TECH-02**: ThreadPoolPreRefreshExecutor.cleanFinished() 清理机制修复
  - 确保已完成的任务能被及时清理，不依赖 getActiveCount() 调用
  - 文件: `ThreadPoolPreRefreshExecutor.java` (lines 262-268)

- [ ] **TECH-03**: PreRefreshHandler 预刷新异步 TTL 竞态条件修复
  - 添加 TTL-aware 预刷新，在填充缓存前检查过期时间
  - 文件: `PreRefreshHandler.java`

- [ ] **TECH-04**: RedisProCacheWriter.getChain() 双检查锁定模式简化
  - 评估并简化 DCL 模式，采用更惯用的延迟初始化
  - 文件: `RedisProCacheWriter.java` (lines 47, 303-313)

## Phase 2: 安全加固 (SEC)

- [ ] **SEC-01**: SpelConditionEvaluator 反射访问重构
  - 请求 Spring 暴露 unless 字段，或使用替代方案避免反射
  - 文件: `SpelConditionEvaluator.java` (lines 94-105)

- [ ] **SEC-02**: Bloom Filter Redis 操作添加超时配置
  - 为 add() 和 mightContain() 添加显式超时
  - 文件: `RedisBloomIFilter.java` (lines 40-48, 75-80)

- [ ] **SEC-03**: Serializer 包白名单文档完善
  - 明确文档化 `resi-cache.serializer.allowed-package-prefixes` 配置
  - 文件: `SecureJackson2JsonRedisSerializer.java`

## Phase 3: 性能优化 (PERF)

- [ ] **PERF-01**: TwoListLRU put() 写锁优化
  - 考虑使用无锁数据结构或分段锁提高并发
  - 文件: `TwoListLRU.java` (lines 102-139, 148-161)

- [ ] **PERF-02**: Bloom Filter 哈希缓存
  - 在本地 ConcurrentMap 中缓存计算出的哈希位置
  - 文件: `RedisBloomIFilter.java`

- [ ] **PERF-03**: inFlight Map 自动清理机制
  - 添加定时清理任务或使用 WeakHashMap 变体
  - 文件: `ThreadPoolPreRefreshExecutor.java` (line 45)

## Phase 4: 测试覆盖增强 (TEST)

- [ ] **TEST-01**: TwoListLRU 并发访问测试
  - 验证多线程同时 put/get 不会导致列表损坏或死锁
  - 文件: `TwoListLRUTest.java`

- [ ] **TEST-02**: PreRefreshHandler 竞态条件测试
  - 验证预刷新填充和显式 evict/put 操作之间的竞态
  - 文件: `PreRefreshHandlerTest.java`

- [ ] **TEST-03**: Handler Chain 异常处理测试
  - 验证处理器链在处理器抛出异常时的行为
  - 文件: `CacheHandlerChainTest.java`

- [ ] **TEST-04**: Bloom Filter 假阳性影响测试
  - 测量实际缓存穿透保护比率
  - 文件: `BloomFilterTest.java`

- [ ] **TEST-05**: SpEL 表达式注入测试
  - 验证 SpEL 条件表达式不会被用户输入注入恶意表达式
  - 文件: `SpelConditionEvaluatorTest.java`

## Phase 5: 文档完善 (DOC)

- [ ] **DOC-01**: 更新包白名单配置文档
- [ ] **DOC-02**: 添加 Actuator 端点使用文档
- [ ] **DOC-03**: 添加缓存事件监听器配置文档

## Out of Scope

- 分布式缓存支持 (多级缓存) — 未来版本
- Caffeine 作为 L1 缓存 — 未来版本
- Redisson 替代方案 — 当前版本稳定

## Traceability

| Requirement | Phase | Verified |
|-------------|-------|----------|
| TECH-01 | 1 | |
| TECH-02 | 1 | |
| TECH-03 | 1 | |
| TECH-04 | 1 | |
| SEC-01 | 2 | |
| SEC-02 | 2 | |
| SEC-03 | 2 | |
| PERF-01 | 3 | |
| PERF-02 | 3 | |
| PERF-03 | 3 | |
| TEST-01 | 4 | |
| TEST-02 | 4 | |
| TEST-03 | 4 | |
| TEST-04 | 4 | |
| TEST-05 | 4 | |
| DOC-01 | 5 | |
| DOC-02 | 5 | |
| DOC-03 | 5 | |
