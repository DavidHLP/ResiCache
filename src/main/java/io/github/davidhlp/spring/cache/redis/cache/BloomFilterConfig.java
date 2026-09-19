package io.github.davidhlp.spring.cache.redis.cache;




import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * 布隆过滤器行为的配置持有者。
 */
@Getter
@Slf4j
class BloomFilterConfig {

    private final String keyPrefix;
    private final int bitSize;
    private final int hashFunctions;
    private final int hashCacheSize;

    public BloomFilterConfig(
            String keyPrefix, int bitSize, int hashFunctions, int hashCacheSize) {
        this.keyPrefix = keyPrefix;
        this.bitSize = Math.max(1, bitSize);
        this.hashFunctions = Math.max(1, hashFunctions);
        this.hashCacheSize = Math.max(1, hashCacheSize);
    }

    int[] positionsFor(String key) {
        if (key == null) {
            return new int[0];
        }

        int[] positions = new int[hashFunctions];
        long hash1 = digest(key, "MD5");
        long hash2 = digest(key, "SHA-256");
        for (int i = 0; i < hashFunctions; i++) {
            long combinedHash = hash1 + (i * hash2);
            positions[i] = positionFor(combinedHash, bitSize);
        }
        return positions;
    }

    static int positionFor(long hash, int bitSize) {
        return (int) Math.floorMod(hash, bitSize);
    }

    private long digest(String key, String algorithm) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] hash = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            long result = 0;
            for (int i = 0; i < Math.min(8, hash.length); i++) {
                result = (result << 8) | (hash[i] & 0xFF);
            }
            return result;
        } catch (NoSuchAlgorithmException e) {
            log.error("Hash algorithm not supported: {}", algorithm, e);
            return key.hashCode();
        }
    }
}
