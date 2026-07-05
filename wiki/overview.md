---
title: 项目概览
type: meta
tags:
  - meta
  - 概览
  - 入口
related: [index, chain-of-responsibility, auto-configuration, configuration, cache-lifecycle, mechanisms-moc, modules-moc]
status: stable
created: 2026-06-21
updated: 2026-07-05
---

# ResiCache 概览

> wiki 阅读入口。从这里开始,顺着链接下钻。

## 一句话

ResiCache 是 **Spring Cache 的防护增强注解生态**——在 `@Cacheable` 之外,用 `@RedisCacheable` 一行注解 + 可编排的责任链,为 Redis 缓存补齐防穿透 / 防击穿 / 防雪崩 / 热 key 刷新能力。Spring Cache 只解决"缓存",ResiCache 解决"防护";`@Cacheable` 兼容回退但不获得防护,`@RedisCacheable` 即防护入口。

## 核心架构

所有缓存操作(GET / PUT / PUT_IF_ABSENT / REMOVE / CLEAN)流经一条责任链,顺序由 `HandlerOrder` 枚举单一真理源定义(间隔 100):

![[meta/overview.canvas]]

```
请求 → BloomFilter(100) → SyncLock(200) → EarlyExpiration(250) → TTL(300) → NullValue(400) → ActualCache(500)
```

→ 详见 [[chain-of-responsibility]];完整读写路径见 [[cache-lifecycle]]。

## 阅读路线(按角色)

- **新人入门** → [[for-onboarding]]:从一句话到第一个能跑的防护注解
- **扩展开发** → [[for-implementer]]:加新 handler / 理解控制流与数据传递
- **想配置** → [[configuration]] → [[configure-behavior]](三层配置实操)
- **想理解装配** → [[auto-configuration]](Spring Boot starter 零配置生效)

机制拓扑见 [[mechanisms-moc]],模块依赖见 [[modules-moc]]。

## 技术栈

→ 见 `CLAUDE.md` 顶部「Tech Stack」表(单一真理源)。wiki 不重复版本号,避免易变数据漂移。

## 相关

[[index]] · [[chain-of-responsibility]] · [[auto-configuration]] · [[configuration]] · [[mechanisms-moc]] · [[modules-moc]] · [[log]]
