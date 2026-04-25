package io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom.filter;

import io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom.BloomFilterConfig;
import io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom.strategy.BloomHashStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.BitSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * JVM 内存级别的布隆过滤器，用于降低 Redis 查询的频率。
 *
 * <p>线程安全说明：
 * <ul>
 *   <li>每个 cacheName 对应一个独立的 BitSet 和 ReadWriteLock</li>
 *   <li>读操作获取读锁，支持并发读</li>
 *   <li>写操作获取写锁，独占访问</li>
 *   <li>clear() 操作清空 BitSet 而不是移除，避免竞态条件</li>
 * </ul>
 */
@Slf4j
@Component("localBloomFilter")
@RequiredArgsConstructor
public class LocalBloomIFilter implements BloomIFilter {

    private final BloomFilterConfig config;
    private final BloomHashStrategy hashStrategy;
    private final ConcurrentMap<String, BitSet> localFilters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ReadWriteLock> locks = new ConcurrentHashMap<>();

    private ReadWriteLock lockFor(String cacheName) {
        return locks.computeIfAbsent(cacheName, name -> new ReentrantReadWriteLock());
    }

    @Override
    public void add(String cacheName, String key) {
        if (cacheName == null || key == null) {
            return;
        }
        BitSet bitSet = bitSetFor(cacheName);
        int[] positions = hashStrategy.positionsFor(key, config);
        lockFor(cacheName).writeLock().lock();
        try {
            for (int position : positions) {
                bitSet.set(position);
            }
        } finally {
            lockFor(cacheName).writeLock().unlock();
        }
        log.debug(
                "Local bloom add: cacheName={}, key={}, positions={}",
                cacheName,
                key,
                Arrays.toString(positions));
    }

    @Override
    public boolean mightContain(String cacheName, String key) {
        if (cacheName == null || key == null) {
            return false;
        }
        BitSet bitSet = localFilters.get(cacheName);
        if (bitSet == null) {
            return false;
        }
        int[] positions = hashStrategy.positionsFor(key, config);
        lockFor(cacheName).readLock().lock();
        try {
            for (int position : positions) {
                if (!bitSet.get(position)) {
                    log.debug(
                            "Local bloom miss: cacheName={}, key={}, position={}",
                            cacheName,
                            key,
                            position);
                    return false;
                }
            }
        } finally {
            lockFor(cacheName).readLock().unlock();
        }
        return true;
    }

    @Override
    public void clear(String cacheName) {
        if (cacheName != null) {
            BitSet bitSet = localFilters.get(cacheName);
            if (bitSet != null) {
                // 清空 BitSet 而不是移除，避免其他线程的竞态条件
                lockFor(cacheName).writeLock().lock();
                try {
                    bitSet.clear();
                } finally {
                    lockFor(cacheName).writeLock().unlock();
                }
                log.debug("Local bloom filter cleared: cacheName={}", cacheName);
            }
        }
    }

    private BitSet bitSetFor(String cacheName) {
        return localFilters.computeIfAbsent(cacheName, name -> new BitSet(config.getBitSize()));
    }
}
