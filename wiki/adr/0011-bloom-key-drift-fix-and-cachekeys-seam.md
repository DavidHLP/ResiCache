# ADR-0011: Bloom 键漂移修复 + CacheKeys 键派生 seam

- **Status**: Accepted
- **Date**: 2026-06-30
- **Deciders**: DavidHLP
- **Related**: `/tmp/architecture-review-1782832306.html`（候选 A/B） /
  `wiki/mechanisms/bloom-filter.md` /
  `wiki/log.md` C4（双路径 bloom 为有意双层防御）
- **Supersedes**: (局部) `RedisProCache.get(key, loader)` 的 loader 前置 bloom 短路所用键

---

## 背景

`/tmp/architecture-review-1782832306.html` 把"loader 路径 / chain 路径防护分裂"列为 Strong 候选 A,
"键派生无 seam"列为 Worth exploring 候选 B。全文精读核实后,校准如下:

### 核实结论

1. **bloom 键漂移(已核实 bug)** —— 唯一一个 bloom 消费者用错了键形态:
   - 链层写入:`BloomFilterHandler.add(cacheName, actualKey)`(:176)—— 剥前缀。
   - 链层查询:`BloomFilterHandler.handleGet` 用 `context.getActualKey()`(:86)—— 剥前缀。
   - **loader 路径查询**:`RedisProCache.get(key, loader):170` 用 `createCacheKey(key)`—— **带前缀**。
   - 后果:PUT 以 `actualKey`(如 `user:1`)写入过滤器,loader 路径却查 `cacheName::user:1`
     → 查的 key 永不在过滤器里 → sync + bloom 组合静默返回 null。
   - 测试树此前**无** sync+bloom+已填充+loader 交集用例,漂移未被捕获。

2. **报告过度判断的修正** —— 报告称 loader 路径"完全缺失 TTL/Null/Early"。
   精读 `executeSyncLoad` 证伪:其内 `super.get`/`super.put` 经 `RedisProCacheWriter` 走责任链,
   TTL/NullValue/EarlyExpiration **本就生效**。报告此条不准确,本 ADR 不据此动作。

3. **C4 已裁定双路径为有意双层防御**(`wiki/log.md` C4 ⏭️ 跳过)——
   `RedisProCache.get(key, loader)` 防 loader/数据源,`BloomFilterHandler` 防 Redis GET。
   本 ADR **不推翻**该设计,仅修其键一致性。

---

## 决策

### D1 — 引入 `CacheKeys` 键派生 seam(候选 B)

新增 `cache/CacheKeys.java`(不可变 record,单一权威):

- `CacheKeys.fromRedisKey(cacheName, redisKey)` —— 从带前缀 redis key 反推键形态。
- `actualKey()` / `redisKey()` / `cacheName()` —— 各形态访问器。
- `bloomKey()` —— ≡ `actualKey()`,**单一真理源**。

**删除测试**:删掉本类 → actualKey/bloomKey 必须在 `RedisProCacheWriter` +
`RedisProCache` 两处重新各自推导,复杂度重现且漂移风险回归 → seam 挣得起存在代价。

### D2 — 修复 bloom 键漂移(保留 C4 双路径)

- `RedisProCache.get(key, loader)` loader 前置 bloom 检查改用
  `CacheKeys.fromRedisKey(getName(), createCacheKey(key)).bloomKey()`(= actualKey),
  与链层 `add` 同源。
- `RedisProCacheWriter.extractActualKey` 委派 `CacheKeys.fromRedisKey(...).actualKey()`。

两个 bloom 消费者(链层 + loader 路径)同源派生 → 漂移**结构性**杜绝。双路径本身(C4)保留。

### D3 — 冷启动 sync+bloom 局限:**文档化,不在本 ADR 实现**

`useBloomFilter=true` + `sync=true` 在 bloom **冷(空)**时,loader 前置 bloom 短路仍会
`mightContain=false → return null` 不调 loader,违反 `@Cacheable`(`BloomSupport` javadoc
自承此缺陷形状;CLEAN 场景已有 rebuilding 窗口 fail-open 补丁,**冷启动未覆盖**)。

本 ADR **不**实现冷启动 fail-open,理由:
- 属 C4 双路径设计的已知局限,修法(populated-flag / lazy-init rebuilding 窗口)是
  **行为变更**,涉及多实例语义与 `BloomSupportTest` 既有断言调整,应单独评估。
- 推荐:在 `BloomSupport` 维护 per-cacheName "已预热" 标志(首次 `add` 置位),
  `mightContain` 在未预热时 fail-open —— 与现有 rebuilding 窗口同哲学。留作后续 ADR。

---

## 后果

**增益**:

- sync + bloom 组合键一致:预热后查询命中 actualKey,不再静默 null。
- 键派生单一权威(`CacheKeys`):未来键形态变更(如自定义 keyPrefix 真正支持)只动一处。
- 新增 sync+bloom 回归测试 + CacheKeys 单元测试,固化不变量。

**代价**:

- 新增 1 个 record(13 SLOC)+ 2 处调用点改造;`extractActualKey` 行为不变(委派)。
- 冷启动 sync+bloom 缺陷仍存(见 D3),需用户预暖 bloom 或等后续 ADR。

**不变**:

- C4 双路径 bloom 双层防御保留。
- TTL/Null/Early/Sync 机制经责任链的提供方式不变。
- bloom 写入语义(actualKey)、rebuilding 窗口、`@RedisCacheable` 契约均不变。

---

## 实施

### 新建

- `cache/CacheKeys.java`(record + `fromRedisKey` + `bloomKey`)
- `test/.../cache/CacheKeysTest.java`(3 单元测试:剥前缀 / bloomKey≡actualKey / 无前缀透传)

### 修改

- `cache/RedisProCache.java` —— loader 前置 bloom 检查改用 `CacheKeys.bloomKey()`
- `cache/RedisProCacheWriter.java` —— `extractActualKey` 委派 `CacheKeys`
- `test/.../integration/TestCacheService.java` —— 新增 `getByIdWithSyncAndBloom`(`sync=true, useBloomFilter=true`)
- `test/.../integration/PathCAopContractIT.java` —— 新增"预热 bloom 后 sync+bloom 返回真实值非 null"回归

### 验证

- `mvnw checkstyle:check` —— PASS
- `mvnw verify` —— **692 tests, 0 failures, 0 errors**;覆盖率门通过
  (新增 sync+bloom 回归 + CacheKeysTest 3 项均绿;Path C 全部契约零回归)
