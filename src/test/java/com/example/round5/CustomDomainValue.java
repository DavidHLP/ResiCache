package com.example.round5;

/**
 * 集成测试专用 POJO:包路径故意不在默认白名单
 * (默认 {@code io.github.davidhlp})内,用于验证
 * {@code resi-cache.serializer.allowed-package-prefixes} 在
 * {@code RedisConnectionConfiguration#redisCacheTemplate} 实例化的
 * {@link io.github.davidhlp.spring.cache.redis.serialization.SecureJacksonRedisSerializer}
 * 上是否被真正尊重。
 *
 * <p>字段保持简单(Jackson 无参构造 + 基本 getter/setter)以保证 roundtrip 行为
 * 与 whitelist 机制正交,不受 Lombok / builder / 复杂反序列化路径干扰。
 */
public class CustomDomainValue {

    private Long id;
    private String label;

    public CustomDomainValue() {}

    public CustomDomainValue(Long id, String label) {
        this.id = id;
        this.label = label;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }
}
