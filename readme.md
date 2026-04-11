# ResiCache

基于 Spring Cache 和 Redis 的高性能缓存防护工具包。

## 📋 功能特性

| 特性 | 说明 |
|------|------|
| **布隆过滤器** | 防止缓存穿透，自动将不存在的数据拦截在缓存层 |
| **分布式锁** | 基于 Redisson 的分布式锁，防止缓存击穿 |
| **TTL 变异 | 随机化 TTL，避免缓存雪崩 |
| **预刷新 | 异步预刷新热 key，保证缓存命中率 |
| **空值缓存 | 防止缓存击穿的同时避免缓存穿透 |

## 🏗️ 架构设计

ResiCache 采用 **责任链模式** 实现缓存写入防护：

```
┌─────────────────────────────────────────────────────────────┐
│                    CacheHandlerChain                       │
├─────────────────────────────────────────────────────────────┤
│  ① BloomFilter (100)  ── 布隆过滤器，防缓存穿透            │
│  ② SyncLock    (200)  ── 分布式锁，防缓存击穿              │
│  ③ PreRefresh  (250)  ── 预刷新，热 key 保护               │
│  ④ TTL         (300)  ── TTL 计算，缓存雪崩防护            │
│  ⑤ NullValue   (400)  ── 空值处理                         │
│  ⑥ ActualCache (500)  ── 实际缓存操作                      │
└─────────────────────────────────────────────────────────────┘
```

## 🚀 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>io.github.davidhlp</groupId>
    <artifactId>ResiCache</artifactId>
    <version>0.0.2</version>
</dependency>
```

### 2. 配置 Redis

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
```

### 3. 启用缓存

```java
@SpringBootApplication
@EnableCaching
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### 4. 使用 @Cacheable

```java
@Service
public class UserService {

    @Cacheable(value = "users", key = "#id")
    public User getUserById(Long id) {
        // 首次查询会写入缓存
        return userRepository.findById(id);
    }
}
```

## ⚙️ 配置选项

### 布隆过滤器配置

```yaml
resicache:
  bloom-filter:
    enabled: true
    key-prefix: "bf:"
    expected-insertions: 100000
    false-probability: 0.01
```

### 分布式锁配置

```yaml
resicache:
  lock:
    enabled: true
    wait-time: 5
    lease-time: 30
```

### TTL 变异配置

```yaml
resicache:
  ttl:
    variance: 0.2
```

### 预刷新配置

```yaml
resicache:
  pre-refresh:
    enabled: true
    threshold: 0.8
    core-pool-size: 4
```

## 📖 工作原理

### 缓存穿透防护

布隆过滤器在缓存层之前拦截不存在的数据请求：

```
请求 ──→ BloomFilter ──→ 存在？──→ 是 ──→ 继续执行
                     │
                     └──→ 否 ──→ 直接返回 null（不查缓存）
```

### 缓存击穿防护

分布式锁确保同一时刻只有一个请求去加载数据：

```
请求A ──→ 获取锁 ──→ 查 DB ──→ 写入缓存 ──→ 释放锁
请求B ──→ 获取锁 ──→ 已存在，直接从缓存获取
```

### 缓存雪崩防护

TTL 随机化避免大量缓存同时过期：

```
设置 TTL = baseTtl + random(-variance, +variance)
```

## 📦 项目结构

```
src/main/java/io/github/davidhlp/spring/cache/redis/
├── core/
│   ├── handler/              # 注解处理器
│   │   ├── AnnotationHandler.java
│   │   ├── CacheableAnnotationHandler.java
│   │   └── ...
│   └── writer/
│       └── chain/
│           └── handler/       # 责任链处理器
│               ├── BloomFilterHandler.java
│               ├── SyncLockHandler.java
│               ├── TtlHandler.java
│               └── ...
├── register/                 # 缓存注册
│   └── RedisCacheRegister.java
└── config/                   # 配置类
    ├── ResiCacheProperties.java
    └── ...
```

## 🔧 依赖版本

| 依赖 | 版本 |
|------|------|
| Spring Boot | 3.2.4 |
| Java | 17+ |
| Redisson | 3.27.0 |
| Hutool | 5.8.25 |

## 📄 License

MIT License
