package com.david.spring.cache.redis.locks.enums;

import lombok.Getter;

/**
 * 分布式锁类型枚举
 *
 * @author David
 */
@Getter
public enum LockType {

    /** 可重入锁 - 同一线程可以多次获取同一把锁 */
    REENTRANT("reentrant"),

    /** 公平锁 - 按照请求顺序获取锁 */
    FAIR("fair"),

    /** 读锁 - 读写锁的读锁部分 */
    READ("read"),

    /** 写锁 - 读写锁的写锁部分 */
    WRITE("write"),

    /** 多重锁 - 同时锁定多个资源 */
    MULTI("multi"),

    /** 红锁 - 基于多个 Redis 实例的分布式锁 */
    RED_LOCK("redlock");

    private final String code;

    LockType(String code) {
        this.code = code;
    }

    /** 根据代码获取锁类型 */
    public static LockType fromCode(String code) {
        for (LockType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown lock type: " + code);
    }
}
