---
title: 模块依赖 MOC
type: meta
tags:
  - meta
  - moc
  - 模块
  - 依赖图
  - 数据流
related: [chain-of-responsibility, auto-configuration, cache-core, mechanisms-moc, overview]
status: stable
created: 2026-06-21
updated: 2026-06-21
---

# 模块依赖 MOC(Map of Content)

> 8 个 `modules/` 页面的角色、依赖关系、数据流。
> 用法:想改某个模块 / 看某个类的位置,先来定位。

![[meta/modules-canvas.canvas]]

## 三层模型

> [!note] 按数据流分层
> - **入口层**(用户接口):`annotations` `operations`
> - **核心层**(执行):`cache-core` `holder-and-config` `configuration`
> - **支撑层**(横切):`serialization` `eviction` `observability`

## 模块速查(8 页)

### 入口层

> [!example] 用户与框架的接口
> - [[annotations]] — 4 个注解(`@RedisCacheable` 等)+ `AnnotationHandler` 解析链
> - [[operations]] — `RedisCacheableOperation` 等 4 个 Operation + `RedisCacheRegister` + `OperationFactory`

### 核心层

> [!important] 框架主干
> - [[cache-core]] — `RedisProCache` / `Manager` / `Writer` / `Interceptor` / `CachedValue`
> - [[holder-and-config]] — `CacheOperationMetadataHolder` + config 装配辅助
> - [[configuration]] — `RedisProCacheProperties` + `resi-cache.*` 全配置树

### 支撑层

> [!tip] 横切关注点
> - [[serialization]] — `SecureJackson2JsonRedisSerializer`(白名单 + NullValue 受限往返)
> - [[eviction]] — `TwoListLRU` 双链表近似 LRU + 统计
> - [[observability]] — `RedisCacheHealthIndicator` + actuator 集成

## 关键调用链

> [!question] 请求从注解到 Redis 的路径
> 1. 用户方法上的 `@RedisCacheable` → 拦截器
> 2. 拦截器通过 `AnnotationHandler` 解析 → `Operation`
> 3. `RedisCacheRegister` 把 Operation 注册到 `RedisProCacheManager`
> 4. `RedisProCache` 走责任链(5 个机制 + 1 个 ActualCache)
> 5. `RedisProCacheWriter` 调 `SecureJackson2JsonRedisSerializer` 序列化
> 6. 写入 Redis;读路径对称

详细见 [[cache-lifecycle]] 与 [[chain-of-responsibility]]。

## 依赖与边界

> [!warning] 强依赖
> - 所有机制(`mechanisms/`)→ `cache-core`:handler 必须实现 `CacheHandler` 接口
> - `cache-core` → `serialization`:writer 必传 serializer
> - `cache-core` → `eviction`:`TwoListLRU` 提供 LRU 视图
> - `auto-configuration` → 所有 8 个 module:starter 把它们装配成 bean

> [!tip] 可替换边界
> - `BloomIFilter`:三实现可替换,[[bloom-filter]] 详述
> - `LockManager`:默认 Redisson,可换其他实现
> - `RedisProCacheProperties`:配置树可裁剪

## 动态视角(Dataview)

> [!info] 模块页当前状态
> ```dataview
> TABLE status, tags, file.cday AS "创建"
> FROM "modules"
> SORT file.name ASC
> ```

## 下钻路径

- 想看入口到 Redis 的完整路径:[[cache-lifecycle]]
- 想看 Spring Boot 怎么装配:[[auto-configuration]]
- 想看配置怎么映射:[[configuration]]
- 想看机制层怎么协作:[[mechanisms-moc]]
