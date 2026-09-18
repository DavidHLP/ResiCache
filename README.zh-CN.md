# ResiCache

**面向 Redis 的 Spring Cache 防护增强。** ResiCache 在保留 Spring Cache
作为应用侧模型的前提下，提供缓存穿透、缓存击穿、缓存雪崩和热点 key 提前
刷新防护。

[English](README.md) · [简体中文](README.zh-CN.md) ·
[文档地图](docs/README.md)

> [!WARNING]
> ResiCache 仍处于 1.0 之前（`v0.0.2`），不提供 SLA，目前由单人维护。当前源码
> 构建线目标为 Spring Boot 4.0 与 Java 21，但尚未发布匹配的 Maven Central
> 产物。正式采用前请先阅读[兼容性矩阵](COMPATIBILITY.md)。

## 能力概览

| 能力 | 作用 |
|---|---|
| 布隆过滤器 | 避免为已知不存在的 key 执行加载 |
| 分布式锁 | 使用 Redisson 或其他 `LockManager` 协调并发加载 |
| TTL 抖动 | 分散过期边界 |
| 空值缓存 | 显式开启后保留负查询结果 |
| 提前过期 | 在正常过期前刷新热点 key |
| 责任链 | 通过 `HandlerOrder` 统一排列防护机制 |
| 安全序列化 | 使用白名单保护的 `{version, payload}` envelope |
| Spring Cache 集成 | 复用 Spring Cache 与 `@EnableCaching` 边界 |

防护能力默认要求显式开启。默认 `native-annotation-mode` 为 `SELECTIVE`，
`@RedisCacheable` 的五类防护属性默认关闭。产品范围与非目标见
[`docs/PRODUCT.md`](docs/PRODUCT.md)。

## 兼容性

当前仓库只维护一条构建线：

- Spring Boot 4.0.0 / Spring Framework 7 / Spring Data Redis 4.0.x
- Java 21
- Redis 7.x
- 需要分布式同步时使用 Redisson 3.50.0
- Caffeine 3.1.8（内部支持）

Boot 3.x 不是当前维护的兼容线。完整版本矩阵、序列化迁移边界、失败语义和
已知限制见 [`COMPATIBILITY.md`](COMPATIBILITY.md)。

## 快速开始

当前构建线以源码为主。可以把当前检出版本构建并安装到本地 Maven 仓库，
这不会发布产物：

```bash
git clone https://github.com/davidhlp/ResiCache.git
cd ResiCache
./mvnw -Punit test -B
./mvnw install -DskipTests -B
```

消费者应用使用当前检出目录根 `pom.xml` 中的坐标和版本。不要把 Maven
Central 上历史的 `0.0.2` 产物当作当前 Boot 4 构建线。

使用同步能力时，显式配置两个 Redis 命名空间：

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
resi-cache:
  redis:
    mode: single
    host: localhost
    port: 6379
    database: 0
```

Spring Data Redis 命名空间提供缓存 I/O；`resi-cache.redis.*` 提供分布式锁
使用的 Redisson 部署，两个命名空间不会自动互相复制。是否启用 Spring Cache
仍由应用负责：

```java
import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableCaching
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    UserService userService() {
        return new UserService();
    }

    public static class UserService {
        @RedisCacheable(value = "users", key = "#id")
        public String getUserById(Long id) {
            return "user-" + id;
        }
    }
}
```

这个缓存方法是自包含的，可以直接复制到小型 Boot 应用中，不需要另造
`User` 或 `userRepository` 类型。

如果没有分布式锁，`sync=true` 默认失败关闭；只有单 JVM 场景明确接受降级时，
才设置 `resi-cache.sync-lock.local-only=true`。读取自定义缓存类型前，先为
业务包配置序列化白名单。

## 按问题阅读

| 问题 | 权威文档 |
|---|---|
| 产品范围与非目标 | [`docs/PRODUCT.md`](docs/PRODUCT.md) |
| 模块、责任链和扩展边界 | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) |
| 注解、配置和错误语义 | [`docs/REFERENCE.md`](docs/REFERENCE.md) |
| 构建、测试与调试 | [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) |
| 运行、迁移与发布 | [`docs/OPERATIONS.md`](docs/OPERATIONS.md) |
| 稳定公共面 | [`STABILITY.md`](STABILITY.md) |
| 支持版本与运行限制 | [`COMPATIBILITY.md`](COMPATIBILITY.md) |
| 变更历史 | [`CHANGELOG.md`](CHANGELOG.md) |
| 历史性能证据 | [`PERFORMANCE.md`](PERFORMANCE.md) |
| 贡献方式 | [`CONTRIBUTING.md`](CONTRIBUTING.md) |
| 漏洞报告 | [`SECURITY.md`](SECURITY.md) |

## 明确不做什么

ResiCache 不实现熔断、限流、多级本地加远端缓存、Reactive 缓存、托管缓存
服务或生产备份/部署控制器；这些职责交给专门组件或应用平台。

## 状态与支持

- **版本**：`0.0.2`；1.0 之前以已公布的稳定性契约为准。
- **维护**：单人维护、无 SLA、尽力支持。
- **历史**：[`CHANGELOG.md`](CHANGELOG.md)。
- **基准**：[`PERFORMANCE.md`](PERFORMANCE.md) 中的历史、非 SLO 性能证据。
- **许可证**：[`LICENSE`](LICENSE)。
