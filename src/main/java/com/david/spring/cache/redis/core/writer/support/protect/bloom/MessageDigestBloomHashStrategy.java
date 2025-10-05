package com.david.spring.cache.redis.core.writer.support.protect.bloom;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/** Message-digest based double hashing strategy. */
@Slf4j
@Component
public class MessageDigestBloomHashStrategy implements BloomHashStrategy {

    private static final List<String> BASE_ALGORITHMS = List.of("MD5", "SHA-256");

    @Override
    public int[] positionsFor(String key, BloomFilterConfig config) {
        if (key == null) {
            return new int[0];
        }

        int hashFunctions = config.getHashFunctions();
        int[] positions = new int[hashFunctions];

        long hash1 = digest(key, BASE_ALGORITHMS.get(0));
        long hash2 = digest(key, BASE_ALGORITHMS.get(1));

        for (int i = 0; i < hashFunctions; i++) {
            long combinedHash = hash1 + (i * hash2);
            positions[i] = (int) (Math.abs(combinedHash) % config.getBitSize());
        }

        return positions;
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
