# ResiCache

ResiCache 是一个 Spring Boot 3 的缓存扩展，用于增强基于 Redis 的缓存在穿透、击穿和雪崩场景下的防护能力。它用一个基于拦截器的处理管道替换了原生的 `RedisCacheManager`，并支持自定义的 [`@RedisCacheable`](src/main/java/io/github/davidhlp/spring/cache/redis/annotation/RedisCacheable.java)、[`@RedisCacheEvict`](src/main/java/io/github/davidhlp/spring/cache/redis/annotation/RedisCacheEvict.java) 和 [`@RedisCaching`](src/main/java/io/github/davidhlp/spring/cache/redis/annotation/RedisCaching.java) 注解。

## 核心特性
- 使用布隆过滤器和空值控制防止缓存穿透。
- 通过带超时的同步锁、二级缓存钩子和可选的空值缓存机制缓解缓存击穿。
- 利用 TTL 随机化、差异控制以及异步/同步预刷新机制缓解缓存雪崩。
- 双列表驱逐注册表，用于跟踪缓存操作，实现快速查找和热加载。
- 开箱即用的自动配置 (`RedisCacheAutoConfiguration`)，自动装配增强的写入器/管理器 Bean、统计信息和键生成策略。

## 安装
```xml
<!-- pom.xml -->
<dependency>
  <groupId>io.github.davidhlp</groupId>
  <artifactId>ResiCache</artifactId>
  <version>3.2.4</version>
</dependency>
```
使用 Gradle (Kotlin DSL):
```kotlin
dependencies {
    implementation("io.github.davidhlp:ResiCache:3.2.4")
}
```
需要 Java 17+、Spring Boot 3.2.x 和一个可访问的 Redis 实例。

## 快速开始
```java
@SpringBootApplication
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
```
添加最小配置:
```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
```
自动配置会自动导入增强的写入器/管理器 Bean，并注册支持 Redis 的缓存拦截器。

## 使用 @RedisCacheable
```java
@Service
public class ProductService {

    @RedisCacheable(
        cacheNames = "product",
        key = "#id",
        ttl = 300,
        sync = true,
        syncTimeout = 5,
        useBloomFilter = true,
        randomTtl = true,
        variance = 0.25F,
        enablePreRefresh = true,
        preRefreshThreshold = 0.2,
        preRefreshMode = PreRefreshMode.ASYNC)
    public ProductDto findById(Long id) {
        return repository.fetch(id);
    }
}
```
行为亮点:
- `sync`/`syncTimeout` 通过细粒度锁 (`SyncLockHandler`) 保护重新生成过程。
- `useBloomFilter` 在写入时将键添加到布隆过滤器处理器，在读取时检查是否命中。
- `randomTtl`+`variance` 通过 `TtlPolicy` 路由 TTL 决策，使过期时间错开。
- `enablePreRefresh` 触发 `PreRefreshSupport` 在热键过期前刷新它们。
- `cacheNullValues` 和 `useSecondLevelCache` 在需要时切换空值缓存和 L2 集成。

## 缓存驱逐与复合操作
使用 `@RedisCacheEvict` 进行目标驱逐:
```java
@RedisCacheEvict(cacheNames = "product", key = "#id", beforeInvocation = true)
public void delete(Long id) {
    repository.remove(id);
}
```
使用 `@RedisCaching` 组合多个缓存操作:
```java
@RedisCaching(
    redisCacheable = {
        @RedisCacheable(cacheNames = "product", key = "#request.id")
    },
    redisCacheEvict = {
        @RedisCacheEvict(cacheNames = "product:list", allEntries = true)
    }
)
public ProductDto refresh(ProductRequest request) {
    return repository.reload(request.id());
}
```
拦截器链在 `RedisCacheRegister` 中注册操作，允许写入器按缓存/键对解析 TTL、锁和布隆参数。

## 开发与测试
- 构建 + 测试: `mvn clean verify`
- 快速测试循环: `mvn test`
- 运行示例应用: `mvn spring-boot:run`
- 检查依赖图: `mvn dependency:tree`
  静态分析通过 `qodana.yaml` 配置；在本地运行 `jetbrains/qodana-jvm-community` Docker 镜像以模拟 CI 环境。

## 故障排除
- 在启用布隆过滤器或预刷新之前，请确保 Redis 凭据与 `spring.data.redis` 设置匹配。
- 如果同步锁出现卡顿，请验证配置的锁键模式并增加 `syncTimeout`。
- 布隆过滤器出现误报是预期行为；在扩展模块时，请在 `BloomSupport` 设置中调整容量/错误率。
