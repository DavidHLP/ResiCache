package com.david.spring.cache.redis.protection;

import com.david.spring.cache.redis.operations.RedisHashOperations;
import com.david.spring.cache.redis.operations.RedisStringOperations;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.Cache;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 缓存穿透防护服务
 * 
 * <p>
 * 提供独立的缓存穿透防护功能，不依赖于特定的上下文，可被各种组件调用：
 * 1. 参数校验：检查缓存键的合理性
 * 2. 攻击检测：检测和防御缓存穿透攻击
 * 3. 空值缓存：缓存查询结果为空的数据，防止重复穿透
 * 4. 布隆过滤器：基于Redis位图的简单布隆过滤器实现
 * 5. 统计监控：记录和统计穿透尝试次数
 *
 * @author david
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CachePenetrationProtection {

    // 空值缓存配置
    public static final String NULL_VALUE_PLACEHOLDER = "CACHE_NULL_VALUE";
    public static final Duration NULL_VALUE_TTL = Duration.ofMinutes(2); // 空值缓存2分钟
    public static final Duration NULL_VALUE_TTL_RANDOM_RANGE = Duration.ofSeconds(30); // 随机范围±30秒
    // 布隆过滤器相关配置
    public static final String BLOOM_FILTER_PREFIX = "cache:bloom:";
    public static final String PENETRATION_COUNT_PREFIX = "cache:penetration:count:";
    public static final int MAX_PENETRATION_ATTEMPTS = 10; // 最大穿透尝试次数
    public static final Duration PENETRATION_WINDOW = Duration.ofMinutes(5); // 统计窗口5分钟
    
    // 布隆过滤器性能配置
    public static final int DEFAULT_EXPECTED_INSERTIONS = 100000; // 预期插入元素数量
    public static final double DEFAULT_FALSE_POSITIVE_PROBABILITY = 0.01; // 默认误判率1%
    public static final int HASH_FUNCTIONS_COUNT = 7; // 哈希函数数量（基于误判率计算）
    public static final int BIT_ARRAY_SIZE = 958506; // 位数组大小（基于预期元素和误判率计算）
    public static final Duration BLOOM_FILTER_DEFAULT_TTL = Duration.ofDays(7); // 布隆过滤器默认过期时间
    // 缓存键校验配置
    public static final int MAX_CACHE_KEY_LENGTH = 250; // Redis键最大长度限制
    public static final long MAX_NUMERIC_KEY_VALUE = Long.MAX_VALUE - 1000; // 数值型键最大值
    private final RedisStringOperations redisStringOperations;
    private final RedisHashOperations redisHashOperations;

    /**
     * 检查缓存键的合理性
     *
     * @param cacheKey 缓存键
     * @return 键值合理返回true
     */
    public boolean isValidCacheKey(String cacheKey) {
        if (cacheKey == null || cacheKey.trim().isEmpty()) {
            log.warn("缓存键为空");
            return false;
        }

        // 检查键长度
        if (cacheKey.length() > MAX_CACHE_KEY_LENGTH) {
            log.warn("缓存键过长: key={}, length={}", cacheKey, cacheKey.length());
            return false;
        }

        // 检查恶意模式
        String lowerKey = cacheKey.toLowerCase();
        if (lowerKey.contains("null") || lowerKey.contains("undefined") ||
                lowerKey.contains("..") || lowerKey.contains("//")) {
            log.warn("缓存键包含恶意模式: key={}", cacheKey);
            return false;
        }

        // 检查数值型键的范围
        if (cacheKey.matches("^-?\\d+$")) {
            try {
                long numKey = Long.parseLong(cacheKey);
                if (numKey < 0 || numKey > MAX_NUMERIC_KEY_VALUE) {
                    log.warn("数值型缓存键超出合理范围: key={}", cacheKey);
                    return false;
                }
            } catch (NumberFormatException e) {
                // 忽略数值解析异常，按字符串处理
                log.debug("数值解析异常，按字符串处理: key={}", cacheKey);
            }
        }

        return true;
    }

    /**
     * 检查是否为穿透攻击
     *
     * @param cacheKey 缓存键
     * @return 是攻击返回true
     */
    public boolean isPenetrationAttack(String cacheKey) {
        String countKey = PENETRATION_COUNT_PREFIX + cacheKey;

        try {
            // 获取当前穿透计数
            Integer currentCount = redisStringOperations.get(countKey, Integer.class);
            if (currentCount == null) {
                return false;
            }

            // 检查是否超过阈值
            if (currentCount >= MAX_PENETRATION_ATTEMPTS) {
                log.warn("缓存键穿透次数超过阈值: key={}, count={}, threshold={}",
                        cacheKey, currentCount, MAX_PENETRATION_ATTEMPTS);
                return true;
            }

            return false;
        } catch (Exception e) {
            log.warn("检查穿透攻击状态异常: key={}, error={}", cacheKey, e.getMessage());
            return false;
        }
    }

    /**
     * 记录穿透尝试
     *
     * @param cacheKey 缓存键
     * @return 当前穿透次数，失败时返回null
     */
    public Long recordPenetrationAttempt(String cacheKey) {
        String countKey = PENETRATION_COUNT_PREFIX + cacheKey;

        try {
            // 递增计数器
            Long newCount = redisStringOperations.increment(countKey, 1);

            // 首次记录时设置过期时间
            if (newCount != null && newCount == 1) {
                redisStringOperations.expire(countKey, PENETRATION_WINDOW);
                log.debug("开始统计穿透尝试: key={}, window={}min",
                        cacheKey, PENETRATION_WINDOW.toMinutes());
            }

            // 记录日志
            if (newCount != null && newCount % 5 == 0) { // 每5次记录一次日志
                log.warn("检测到重复穿透尝试: key={}, count={}, window={}min",
                        cacheKey, newCount, PENETRATION_WINDOW.toMinutes());
            }

            return newCount;
        } catch (Exception e) {
            log.warn("记录穿透尝试异常: key={}, error={}", cacheKey, e.getMessage());
            return null;
        }
    }

    /**
     * 缓存空值防止穿透
     *
     * @param cache    缓存对象
     * @param cacheKey 缓存键
     * @return 缓存成功返回true
     */
    public boolean cacheNullValue(Cache cache, String cacheKey) {
        try {
            // 添加随机时间避免缓存雪崩
            Duration ttl = addRandomOffset(NULL_VALUE_TTL);

            // 使用Redis直接设置TTL（Spring Cache可能不支持单独设置TTL）
            String redisKey = cache.getName() + "::" + cacheKey;
            redisStringOperations.set(redisKey, NULL_VALUE_PLACEHOLDER, String.class, ttl);

            log.debug("空值已缓存: key={}, ttl={}s", redisKey, ttl.getSeconds());
            return true;

        } catch (Exception e) {
            log.warn("缓存空值失败: key={}, error={}", cacheKey, e.getMessage());

            // 降级：使用Spring Cache的默认方式
            try {
                cache.put(cacheKey, NULL_VALUE_PLACEHOLDER);
                log.debug("降级缓存空值成功: key={}", cacheKey);
                return true;
            } catch (Exception fallbackException) {
                log.error("降级缓存空值也失败: key={}, error={}", cacheKey, fallbackException.getMessage());
                return false;
            }
        }
    }

    /**
     * 缓存空值防止穿透（使用缓存名称）
     *
     * @param cacheName 缓存名称
     * @param cacheKey  缓存键
     * @param ttl       过期时间（可选，为null时使用默认值）
     * @return 缓存成功返回true
     */
    public boolean cacheNullValue(String cacheName, String cacheKey, Duration ttl) {
        try {
            Duration actualTtl = ttl != null ? addRandomOffset(ttl) : addRandomOffset(NULL_VALUE_TTL);
            String redisKey = cacheName + "::" + cacheKey;

            redisStringOperations.set(redisKey, NULL_VALUE_PLACEHOLDER, String.class, actualTtl);
            log.debug("空值已缓存: cacheName={}, key={}, ttl={}s", cacheName, cacheKey, actualTtl.getSeconds());
            return true;

        } catch (Exception e) {
            log.warn("缓存空值失败: cacheName={}, key={}, error={}", cacheName, cacheKey, e.getMessage());
            return false;
        }
    }

    /**
     * 检查是否为空值占位符
     *
     * @param cachedValue 缓存值
     * @return 是空值占位符返回true
     */
    public boolean isNullValuePlaceholder(Object cachedValue) {
        return NULL_VALUE_PLACEHOLDER.equals(cachedValue);
    }

    /**
     * 为TTL添加随机偏移，避免缓存雪崩
     *
     * @param baseTtl 基础TTL
     * @return 带随机偏移的TTL
     */
    public Duration addRandomOffset(Duration baseTtl) {
        long baseSeconds = baseTtl.getSeconds();
        long randomSeconds = ThreadLocalRandom.current().nextLong(
                -NULL_VALUE_TTL_RANDOM_RANGE.getSeconds(),
                NULL_VALUE_TTL_RANDOM_RANGE.getSeconds());

        long finalSeconds = Math.max(10, baseSeconds + randomSeconds); // 最少10秒
        return Duration.ofSeconds(finalSeconds);
    }

    /**
     * 布隆过滤器检查（基于Redis Hash实现）
     *
     * @param key        要检查的键
     * @param filterName 过滤器名称，为null时使用默认过滤器
     * @return 可能存在返回true，肯定不存在返回false
     */
    public boolean bloomFilterMightContain(String key, String filterName) {
        try {
            String bloomKey = BLOOM_FILTER_PREFIX + (filterName != null ? filterName : "default");
            int[] hashes = calculateHashes(key);

            // 检查所有哈希位是否都为1
            for (int hash : hashes) {
                String hashField = String.valueOf(hash);
                Integer bitValue = redisHashOperations.get(bloomKey, hashField, Integer.class);
                if (bitValue == null || bitValue == 0) {
                    return false; // 肯定不存在
                }
            }

            return true; // 可能存在

        } catch (Exception e) {
            log.warn("布隆过滤器检查异常: key={}, filter={}, error={}", key, filterName, e.getMessage());
            return true; // 异常时假设存在，避免误拦截
        }
    }

    /**
     * 布隆过滤器检查（使用默认过滤器）
     *
     * @param key 要检查的键
     * @return 可能存在返回true，肯定不存在返回false
     */
    public boolean bloomFilterMightContain(String key) {
        return bloomFilterMightContain(key, null);
    }

    /**
     * 向布隆过滤器添加键
     *
     * @param key        要添加的键
     * @param filterName 过滤器名称，为null时使用默认过滤器
     * @param ttl        过期时间，为null时使用默认30天
     * @return 添加成功返回true
     */
    public boolean addToBloomFilter(String key, String filterName, Duration ttl) {
        try {
            String bloomKey = BLOOM_FILTER_PREFIX + (filterName != null ? filterName : "default");
            Duration actualTtl = ttl != null ? ttl : Duration.ofDays(30);
            int[] hashes = calculateHashes(key);

            // 使用Hash结构存储位图，每个哈希值作为field，值为1
            for (int hash : hashes) {
                String hashField = String.valueOf(hash);
                redisHashOperations.put(bloomKey, hashField, 1, Integer.class);
            }

            // 设置整个Hash的过期时间
            redisHashOperations.expire(bloomKey, actualTtl);

            log.debug("键已添加到布隆过滤器: key={}, filter={}, hashes={}", key, filterName, hashes.length);
            return true;

        } catch (Exception e) {
            log.warn("添加到布隆过滤器异常: key={}, filter={}, error={}", key, filterName, e.getMessage());
            return false;
        }
    }

    /**
     * 向布隆过滤器添加键（使用默认过滤器和TTL）
     *
     * @param key 要添加的键
     * @return 添加成功返回true
     */
    public boolean addToBloomFilter(String key) {
        return addToBloomFilter(key, null, null);
    }

    /**
     * 获取穿透统计信息
     *
     * @param cacheKey 缓存键
     * @return 当前穿透次数，不存在时返回0
     */
    public int getPenetrationCount(String cacheKey) {
        String countKey = PENETRATION_COUNT_PREFIX + cacheKey;
        try {
            Integer count = redisStringOperations.get(countKey, Integer.class);
            return count != null ? count : 0;
        } catch (Exception e) {
            log.warn("获取穿透统计异常: key={}, error={}", cacheKey, e.getMessage());
            return 0;
        }
    }

    /**
     * 重置穿透统计
     *
     * @param cacheKey 缓存键
     * @return 重置成功返回true
     */
    public boolean resetPenetrationCount(String cacheKey) {
        String countKey = PENETRATION_COUNT_PREFIX + cacheKey;
        try {
            redisStringOperations.delete(countKey);
            log.debug("穿透统计已重置: key={}", cacheKey);
            return true;
        } catch (Exception e) {
            log.warn("重置穿透统计异常: key={}, error={}", cacheKey, e.getMessage());
            return false;
        }
    }

    /**
     * 清理过期的穿透统计数据
     * 注意：此方法只是记录日志，实际清理由Redis的TTL机制自动完成
     */
    public void cleanupExpiredPenetrationStats() {
        log.debug("穿透统计数据清理（由Redis TTL自动处理）");
    }

    /**
     * 使用双重哈希计算多个高质量哈希值
     * 基于MurmurHash和FNV算法的组合，减少哈希冲突
     *
     * @param key 输入字符串
     * @return 哈希值数组
     */
    private int[] calculateHashes(String key) {
        byte[] keyBytes = key.getBytes();
        int hash1 = murmurHash3(keyBytes);
        int hash2 = fnvHash(keyBytes);
        
        int[] hashes = new int[HASH_FUNCTIONS_COUNT];
        for (int i = 0; i < HASH_FUNCTIONS_COUNT; i++) {
            // 双重哈希公式：hash(i) = (hash1 + i * hash2) % m
            int combinedHash = Math.abs((hash1 + i * hash2) % BIT_ARRAY_SIZE);
            hashes[i] = combinedHash;
        }
        
        return hashes;
    }
    
    /**
     * MurmurHash3算法实现（32位版本）
     */
    private int murmurHash3(byte[] data) {
        int seed = 0x9747b28c;
        int h1 = seed;
        int c1 = 0xcc9e2d51;
        int c2 = 0x1b873593;
        int r1 = 15;
        int r2 = 13;
        int m = 5;
        int n = 0xe6546b64;
        
        int len = data.length;
        int roundedEnd = (len & 0xfffffffc); // round down to 4 byte block
        
        for (int i = 0; i < roundedEnd; i += 4) {
            int k1 = (data[i] & 0xff) | ((data[i + 1] & 0xff) << 8) |
                    ((data[i + 2] & 0xff) << 16) | (data[i + 3] << 24);
            k1 *= c1;
            k1 = (k1 << r1) | (k1 >>> (32 - r1));
            k1 *= c2;
            
            h1 ^= k1;
            h1 = (h1 << r2) | (h1 >>> (32 - r2));
            h1 = h1 * m + n;
        }
        
        // tail
        int k1 = 0;
        switch (len & 0x03) {
            case 3:
                k1 = (data[roundedEnd + 2] & 0xff) << 16;
                // fallthrough
            case 2:
                k1 |= (data[roundedEnd + 1] & 0xff) << 8;
                // fallthrough
            case 1:
                k1 |= (data[roundedEnd] & 0xff);
                k1 *= c1;
                k1 = (k1 << r1) | (k1 >>> (32 - r1));
                k1 *= c2;
                h1 ^= k1;
        }
        
        // finalization
        h1 ^= len;
        h1 ^= (h1 >>> 16);
        h1 *= 0x85ebca6b;
        h1 ^= (h1 >>> 13);
        h1 *= 0xc2b2ae35;
        h1 ^= (h1 >>> 16);
        
        return h1;
    }
    
    /**
     * FNV-1a哈希算法实现
     */
    private int fnvHash(byte[] data) {
        int hash = 0x811c9dc5; // FNV offset basis
        int prime = 0x01000193; // FNV prime
        
        for (byte b : data) {
            hash ^= (b & 0xff);
            hash *= prime;
        }
        
        return hash;
    }
    
    /**
     * 初始化布隆过滤器
     * 可以预加载已知存在的数据，提高过滤器的准确性
     *
     * @param filterName 过滤器名称
     * @param existingKeys 已知存在的键列表（可选）
     * @param ttl 过期时间
     * @return 初始化成功返回true
     */
    public boolean initializeBloomFilter(String filterName, java.util.List<String> existingKeys, Duration ttl) {
        try {
            String bloomKey = BLOOM_FILTER_PREFIX + (filterName != null ? filterName : "default");
            Duration actualTtl = ttl != null ? ttl : BLOOM_FILTER_DEFAULT_TTL;
            
            // 检查是否已经初始化
            if (redisHashOperations.hasKey(bloomKey)) {
                log.debug("布隆过滤器已存在，跳过初始化: filter={}", filterName);
                return true;
            }
            
            // 预加载已知存在的键如果有的话
            if (existingKeys != null && !existingKeys.isEmpty()) {
                log.info("开始初始化布隆过滤器，预加载{}个已知键: filter={}", existingKeys.size(), filterName);
                
                for (String key : existingKeys) {
                    addToBloomFilter(key, filterName, actualTtl);
                }
                
                log.info("布隆过滤器初始化完成: filter={}, keys={}, ttl={}h", 
                        filterName, existingKeys.size(), actualTtl.toHours());
            } else {
                // 创建空的布隆过滤器
                redisHashOperations.put(bloomKey, "_initialized", 1, Integer.class);
                redisHashOperations.expire(bloomKey, actualTtl);
                log.info("空布隆过滤器初始化完成: filter={}, ttl={}h", filterName, actualTtl.toHours());
            }
            
            return true;
            
        } catch (Exception e) {
            log.error("初始化布隆过滤器失败: filter={}, error={}", filterName, e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 获取布隆过滤器统计信息
     *
     * @param filterName 过滤器名称
     * @return 统计信息映射
     */
    public java.util.Map<String, Object> getBloomFilterStats(String filterName) {
        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        
        try {
            String bloomKey = BLOOM_FILTER_PREFIX + (filterName != null ? filterName : "default");
            
            // 检查过滤器是否存在
            boolean exists = redisHashOperations.hasKey(bloomKey);
            stats.put("exists", exists);
            
            if (!exists) {
                return stats;
            }
            
            // 获取哈希表大小（近似位图使用情况）
            Long size = redisHashOperations.size(bloomKey);
            stats.put("hashFieldCount", size != null ? size : 0);
            
            // 获取过期时间
            Duration ttl = redisHashOperations.getExpire(bloomKey);
            stats.put("ttlSeconds", ttl != null ? ttl.getSeconds() : -1L);
            
            // 计算理论参数
            stats.put("expectedInsertions", DEFAULT_EXPECTED_INSERTIONS);
            stats.put("falsePositiveProbability", DEFAULT_FALSE_POSITIVE_PROBABILITY);
            stats.put("hashFunctions", HASH_FUNCTIONS_COUNT);
            stats.put("bitArraySize", BIT_ARRAY_SIZE);
            
            log.debug("布隆过滤器统计: filter={}, stats={}", filterName, stats);
            
        } catch (Exception e) {
            log.warn("获取布隆过滤器统计失败: filter={}, error={}", filterName, e.getMessage());
            stats.put("error", e.getMessage());
        }
        
        return stats;
    }
    
    /**
     * 清空布隆过滤器
     *
     * @param filterName 过滤器名称
     * @return 清空成功返回true
     */
    public boolean clearBloomFilter(String filterName) {
        try {
            String bloomKey = BLOOM_FILTER_PREFIX + (filterName != null ? filterName : "default");
            redisHashOperations.delete(bloomKey);
            log.info("布隆过滤器已清空: filter={}", filterName);
            return true;
        } catch (Exception e) {
            log.error("清空布隆过滤器失败: filter={}, error={}", filterName, e.getMessage());
            return false;
        }
    }
    
    /**
     * 检查布隆过滤器是否存在
     *
     * @param filterName 过滤器名称
     * @return 存在返回true
     */
    public boolean bloomFilterExists(String filterName) {
        try {
            String bloomKey = BLOOM_FILTER_PREFIX + (filterName != null ? filterName : "default");
            return redisHashOperations.hasKey(bloomKey);
        } catch (Exception e) {
            log.warn("检查布隆过滤器存在性失败: filter={}, error={}", filterName, e.getMessage());
            return false;
        }
    }
}
