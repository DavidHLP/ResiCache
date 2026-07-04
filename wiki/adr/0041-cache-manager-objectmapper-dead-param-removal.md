---
title: ADR-0041 cacheManager + buildInitialCacheConfigurations 的 ObjectMapper 死参数删除
type: adr
status: accepted
created: 2026-07-04
related: [0030, 0040, 0017]
---

# ADR-0041:cacheManager + buildInitialCacheConfigurations 的 ObjectMapper 死参数删除

## Context

Round 31 跨域结构扫描,定位 `config/RedisProCacheConfiguration` 内一处 ObjectMapper 接线死循环:

- **`RedisProCacheConfiguration#cacheManager(...)` @Bean** 注入 `com.fasterxml.jackson.databind.ObjectMapper objectMapper`,方法体内**仅**用于第 99 行转发实参:
  ```java
  Map<String, RedisCacheConfiguration> initialCacheConfigurations =
      buildInitialCacheConfigurations(properties, defaultRedisCacheConfiguration, objectMapper);
  ```
- **`RedisProCacheConfiguration#buildInitialCacheConfigurations(properties, defaultConfig, objectMapper)`**(private)接收第 3 参 `objectMapper`,但方法体(遍历 `properties.getCaches()`,对每个 cache 在 `defaultConfig` 基础上应用 TTL / keyPrefix / cacheNullValues)**从未引用** `objectMapper`。

即 `cacheManager` 是 ObjectMapper 的**死消费者**——注入只为转发给一个不读它的私有方法。对比同文件 / 同包的**真消费者**:

| 消费者 | 用法 | 真实性 |
|---|---|---|
| `RedisConnectionConfiguration#redisCacheTemplate` | `serializerFactory.create(objectMapper, properties.getSerializer())` | 真 |
| `RedisProCacheConfiguration#defaultRedisCacheConfiguration` | `serializerFactory.create(objectMapper, properties.getSerializer())` | 真 |
| `TypeSupport`(构造器) | 复用为类型转换 / 序列化委派底座 | 真 |
| `RedisProCacheConfiguration#cacheManager` | **仅转发**给 `buildInitialCacheConfigurations`(后者不读) | **死** |

`SecureJacksonRedisSerializer` 不直接注入 @Bean,而是经 `SecureJacksonSerializerFactory.create(objectMapper, …)` 接收(`.copy()` 后再加固),不属 bean 消费者。

deletion test:**集中**(净化接线,byte-equivalent)而非**移动**(无逻辑迁移)。

## Decision

**byte-equivalent 死参数删除**:

1. 删 `buildInitialCacheConfigurations` 第 3 参 `com.fasterxml.jackson.databind.ObjectMapper objectMapper`(签名 3 参 → 2 参);
2. 删 `cacheManager` @Bean 的 `com.fasterxml.jackson.databind.ObjectMapper objectMapper` 形参 + 行 99 转发实参(调用 `buildInitialCacheConfigurations(properties, defaultRedisCacheConfiguration)`);
3. 不动 `defaultRedisCacheConfiguration`(真消费者,经 `serializerFactory.create` 消费 ObjectMapper)。

## 路径裁决(The Only Path)

存在 X(顺手给 `JacksonConfig#objectMapper()` @Bean 补 `@ConditionalOnMissingBean`,与同文件其余 7 个 @Bean 模式对齐)与 Y(只删 byte-equivalent 死参数,行为零变化)歧路。**彻底扼杀 X**:`@ConditionalOnMissingBean` 是**行为改变**(starter 语义从"强加全局 ObjectMapper"变为"退让宿主优先"),会改变现有用户的 bean 覆盖预期,需独立 ADR 评估其兼容性影响,不可与 byte-equivalent cleanup 混入同一 commit。**直接采用 Y**:死参数方法体零引用,byte-equivalent 零风险,是 ADR-0030 / ADR-0040 死代码扫尾系列的同构续篇。X 作为后续观察留待独立行为改变 ADR。

## 内部红蓝博弈(CR & Fix)

Plan 阶段通读 `RedisProCacheConfiguration` 全文 + 两个独立 Explore agent 跨域扫描后,CR 自审四轮防御:

1. **方法体引用核实**——`grep -n 'objectMapper' RedisProCacheConfiguration.java`,删后仅剩 `defaultRedisCacheConfiguration` 形参 + `serializerFactory.create` 真消费,无残留;
2. **调用点核实**——`buildInitialCacheConfigurations` 全仓 grep 仅 2 处(调用点 + private 签名),无外部调用;
3. **测试断言核实**——该方法 `private`,无直接单测;`RedisProCacheManagerTest` 间接经 `cacheManager` bean 行为测试,无私有方法反射断言;
4. **环境韧性**——本地 vfox JDK 21 解压损坏(`libjava.so` 缺失)且 cache tar.gz EOF 损坏,系统仅 JDK 25(Lombok 内部 API 不兼容)。Fix:下载 Adoptium Temurin 21.0.5+11 到 `$HOME`(持久可执行,绕过 `/tmp` noexec 与 sandbox FS 不持久),在 JDK 21 下 `mvn clean test` 一次性 BUILD SUCCESS。

## Consequences

- **正面 一致性**:ObjectMapper 接线纪律统一——只剩 3 个真消费者(`redisCacheTemplate` / `defaultRedisCacheConfiguration` / `TypeSupport`),`cacheManager` 不再背负无意义形参;
- **正面 模块清晰**:`buildInitialCacheConfigurations` 签名缩为 `(properties, defaultConfig)`,签名即文档(投影逻辑只依赖 properties + 默认配置,与 ObjectMapper 无关);
- **正面 收敛**:延续 ADR-0030(死 protected 方法)/ ADR-0040(死工厂)死代码扫尾脉络;
- **规模**:净 −2 SLOC(2 处形参 / 实参删除,无新代码);
- **负面**:无(byte-equivalent,745 测试绿)。

## 后续观察(Future Work,非本 ADR scope)

`JacksonConfig#objectMapper()` @Bean **缺 `@ConditionalOnMissingBean`** —— 与 `RedisProCacheConfiguration` 其余 7 个 @Bean(全有 `@ConditionalOnMissingBean`)模式不一致。starter 注册全局 ObjectMapper 会覆盖/冲突宿主应用自身的 Jackson 配置,是 Spring Boot starter 反模式。**留作独立行为改变 ADR**(需评估现有用户 bean 覆盖兼容性 + 加 `@ConditionalOnMissingBean(ObjectMapper.class)` 的退化语义),不混入本 byte-equivalent cleanup。

## deletion test

| 对象 | 删除效果 | 裁决 |
|---|---|---|
| `buildInitialCacheConfigurations` 第 3 参 `objectMapper` | 浓缩(方法体零引用,签名净化) | 删 |
| `cacheManager` 形参 `objectMapper` | 浓缩(死消费者,仅转发给不读它的方法) | 删 |

## 测试影响

零行为变化。`mvn clean test`(JDK 21 Temurin 21.0.5+11):**Tests run: 745, Failures: 0, Errors: 0, Skipped: 17**(17 skipped 为需 docker 的集成测试,与本改动无关)。checkstyle 绿。

## 参考

- ADR-0030:`RedisProCacheWriter` 死 protected 方法删除(同款 byte-equivalent 死代码扫尾)
- ADR-0040:`LockContext.noLock` + `NullDecision.passthrough` 死工厂删除(同款扫尾,本 ADR 同构续篇)
- ADR-0017:`Operation.fromAttributes` 静态 seam(Operation 域前序,与本 ADR 同属"接线收敛"主题)
