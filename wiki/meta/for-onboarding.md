---
title: 角色 · 新人入门
type: meta
tags:
  - meta
  - moc
  - onboarding
  - 角色
related: [overview, chain-of-responsibility, cache-lifecycle, configure-behavior, bloom-filter]
status: stable
created: 2026-07-05
updated: 2026-07-05
---

# 角色:新人入门

> 目标:用最短路径理解 ResiCache 是什么、怎么用。读完能在自己的 Spring Boot 项目加一个防护注解。

## 5 分钟读懂

1. **它是什么** → [[overview]] 一句话:Spring Cache 的防护增强;`@Cacheable` 兼容回退但不获得防护,`@RedisCacheable` 即防护入口。
2. **核心是责任链** → [[chain-of-responsibility]]:5 个防护 handler 按固定档位(100/200/250/300/400)依次处理每个 key,顺序由 `HandlerOrder` 枚举单一真理源定义。
3. **数据怎么流** → [[cache-lifecycle]]:GET / PUT 端到端读写路径。

## 第一个能跑的注解

```java
@RedisCacheable(cacheName = "users", key = "#id", bloomFilter = true, sync = true)
public User getUser(Long id) {
    return userRepository.findById(id).orElse(null);
}
```

- `bloomFilter = true` → 防穿透([[bloom-filter]])
- `sync = true` → 防击穿([[breakdown-lock]])
- TTL 抖动、空值缓存、热 key 刷新默认生效(见 [[configuration]])

## 配置起步

→ [[configure-behavior]]:三层配置(注解属性 → `resi-cache.*` → 默认)与典型套餐。

## 下钻

- 想懂某个机制 → [[mechanisms-moc]](5 机制拓扑)
- 想懂某个模块 → [[modules-moc]](8 模块依赖)
- 卡在环境问题 → [[env-notes/fix-docker-pull-ipv6-timeout]] · [[env-notes/fix-wsl2-testcontainers-socat-forward]]
