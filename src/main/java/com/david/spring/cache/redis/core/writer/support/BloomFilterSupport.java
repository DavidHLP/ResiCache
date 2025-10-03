package com.david.spring.cache.redis.core.writer.support;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.redis.core.HashOperations;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 布隆过滤器支持类 使用Redis Hash结构实现布隆过滤器，用于防止缓存穿透 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BloomFilterSupport {

    /** 布隆过滤器在Redis中的key前缀 */
    private static final String BLOOM_FILTER_PREFIX = "bf:";

    /** 默认位数组大小 (1MB = 8,388,608 bits) */
    private static final int DEFAULT_BIT_SIZE = 8_388_608;

    /** 默认哈希函数个数 */
    private static final int DEFAULT_HASH_FUNCTIONS = 3;

    private final HashOperations<String, String, String> hashOperations;

    /**
     * 根据预期元素数量和期望误判率计算最优位数组大小
     *
     * @param expectedElements 预期元素数量
     * @param falsePositiveRate 期望误判率（例如：0.01 表示 1%）
     * @return 最优位数组大小
     */
    public static int calculateOptimalBitSize(long expectedElements, double falsePositiveRate) {
        if (expectedElements <= 0 || falsePositiveRate <= 0 || falsePositiveRate >= 1) {
            return DEFAULT_BIT_SIZE;
        }
        // m = -(n * ln(p)) / (ln(2)^2)
        return (int)
                Math.ceil(
                        -expectedElements
                                * Math.log(falsePositiveRate)
                                / (Math.log(2) * Math.log(2)));
    }

    /**
     * 根据位数组大小和预期元素数量计算最优哈希函数个数
     *
     * @param bitSize 位数组大小
     * @param expectedElements 预期元素数量
     * @return 最优哈希函数个数
     */
    public static int calculateOptimalHashFunctions(int bitSize, long expectedElements) {
        if (bitSize <= 0 || expectedElements <= 0) {
            return DEFAULT_HASH_FUNCTIONS;
        }
        // k = (m/n) * ln(2)
        return Math.max(1, (int) Math.round((bitSize / (double) expectedElements) * Math.log(2)));
    }

    /**
     * 添加key到布隆过滤器
     *
     * @param cacheName 缓存名称
     * @param key 缓存key
     */
    public void add(String cacheName, String key) {
        if (cacheName == null || key == null) {
            return;
        }

        try {
            String bloomKey = getBloomFilterKey(cacheName);
            int[] positions = getHashPositions(key);

            for (int position : positions) {
                hashOperations.put(bloomKey, String.valueOf(position), "1");
            }

            logBloomFilterAdd(cacheName, key, positions);
        } catch (Exception e) {
            logBloomFilterAddFailed(cacheName, key, e);
        }
    }

    /**
     * 检查key是否可能存在于布隆过滤器中
     *
     * @param cacheName 缓存名称
     * @param key 缓存key
     * @return true表示可能存在，false表示一定不存在
     */
    public boolean mightContain(String cacheName, String key) {
        if (cacheName == null || key == null) {
            return false;
        }

        try {
            String bloomKey = getBloomFilterKey(cacheName);
            int[] positions = getHashPositions(key);

            for (int position : positions) {
                Object value = hashOperations.get(bloomKey, String.valueOf(position));
                if (value == null) {
                    logBloomFilterMiss(cacheName, key);
                    return false;
                }
            }

            logBloomFilterHit(cacheName, key);
            return true;
        } catch (Exception e) {
            logBloomFilterCheckFailed(cacheName, key, e);
            // 发生异常时，返回true，允许查询继续进行
            return true;
        }
    }

    /**
     * 删除指定缓存的布隆过滤器
     *
     * @param cacheName 缓存名称
     */
    public void delete(String cacheName) {
        if (cacheName == null) {
            return;
        }

        try {
            String bloomKey = getBloomFilterKey(cacheName);
            hashOperations.getOperations().delete(bloomKey);
            logBloomFilterDeleted(cacheName);
        } catch (Exception e) {
            logBloomFilterDeleteFailed(cacheName, e);
        }
    }

    /**
     * 获取布隆过滤器在Redis中的key
     *
     * @param cacheName 缓存名称
     * @return Redis key
     */
    private String getBloomFilterKey(String cacheName) {
        return BLOOM_FILTER_PREFIX + cacheName;
    }

    /**
     * 计算多个哈希函数的位置 使用双重哈希技术：h(k,i) = (h1(k) + i * h2(k)) mod m
     *
     * @param key 要哈希的key
     * @return 位置数组
     */
    private int[] getHashPositions(String key) {
        int[] positions = new int[BloomFilterSupport.DEFAULT_HASH_FUNCTIONS];

        // 使用MD5和SHA-256作为两个独立的哈希函数
        long hash1 = getHash(key, "MD5");
        long hash2 = getHash(key, "SHA-256");

        for (int i = 0; i < BloomFilterSupport.DEFAULT_HASH_FUNCTIONS; i++) {
            // 双重哈希技术
            long combinedHash = hash1 + (i * hash2);
            // 取绝对值并对位数组大小取模
            positions[i] = (int) (Math.abs(combinedHash) % BloomFilterSupport.DEFAULT_BIT_SIZE);
        }

        return positions;
    }

    /**
     * 使用指定算法计算哈希值
     *
     * @param key 要哈希的key
     * @param algorithm 哈希算法（MD5或SHA-256）
     * @return 哈希值
     */
    private long getHash(String key, String algorithm) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] hash = digest.digest(key.getBytes(StandardCharsets.UTF_8));

            // 将前8个字节转换为long
            long result = 0;
            for (int i = 0; i < Math.min(8, hash.length); i++) {
                result <<= 8;
                result |= (hash[i] & 0xFF);
            }
            return result;
        } catch (NoSuchAlgorithmException e) {
            log.error("哈希算法不支持: {}", algorithm, e);
            // 降级使用Java的hashCode
            return key.hashCode();
        }
    }

    // 日志方法
    private void logBloomFilterAdd(String cacheName, String key, int[] positions) {
        if (log.isDebugEnabled()) {
            log.debug(
                    "布隆过滤器添加: cacheName={}, key={}, positions={}",
                    cacheName,
                    key,
                    formatPositions(positions));
        }
    }

    private void logBloomFilterAddFailed(String cacheName, String key, Exception e) {
        log.error("布隆过滤器添加失败: cacheName={}, key={}", cacheName, key, e);
    }

    private void logBloomFilterHit(String cacheName, String key) {
        log.debug("布隆过滤器命中（可能存在）: cacheName={}, key={}", cacheName, key);
    }

    private void logBloomFilterMiss(String cacheName, String key) {
        log.debug("布隆过滤器未命中（一定不存在）: cacheName={}, key={}", cacheName, key);
    }

    private void logBloomFilterCheckFailed(String cacheName, String key, Exception e) {
        log.error("布隆过滤器检查失败: cacheName={}, key={}", cacheName, key, e);
    }

    private void logBloomFilterDeleted(String cacheName) {
        log.debug("布隆过滤器已删除: cacheName={}", cacheName);
    }

    private void logBloomFilterDeleteFailed(String cacheName, Exception e) {
        log.error("布隆过滤器删除失败: cacheName={}", cacheName, e);
    }

    private String formatPositions(int[] positions) {
        if (positions == null || positions.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < positions.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(positions[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
