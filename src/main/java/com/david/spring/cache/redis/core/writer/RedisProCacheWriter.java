package com.david.spring.cache.redis.core.writer;

import org.springframework.data.redis.cache.CacheStatistics;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.cache.RedisCacheWriter;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class RedisProCacheWriter implements RedisCacheWriter {

	@Override
	public byte[] get(String name, byte[] key) {
		return new byte[0];
	}

	@Override
	public byte[] get(String name, byte[] key, Duration ttl) {
		return RedisCacheWriter.super.get(name, key, ttl);
	}

	@Override
	public boolean supportsAsyncRetrieve() {
		return RedisCacheWriter.super.supportsAsyncRetrieve();
	}

	@Override
	public CompletableFuture<byte[]> retrieve(String name, byte[] key) {
		return RedisCacheWriter.super.retrieve(name, key);
	}

	@Override
	public CompletableFuture<byte[]> retrieve(String name, byte[] key, Duration ttl) {
		return null;
	}

	@Override
	public void put(String name, byte[] key, byte[] value, Duration ttl) {

	}

	@Override
	public CompletableFuture<Void> store(String name, byte[] key, byte[] value, Duration ttl) {
		return null;
	}

	@Override
	public byte[] putIfAbsent(String name, byte[] key, byte[] value, Duration ttl) {
		return new byte[0];
	}

	@Override
	public void remove(String name, byte[] key) {

	}

	@Override
	public void clean(String name, byte[] pattern) {

	}

	@Override
	public void clearStatistics(String name) {

	}

	@Override
	public RedisCacheWriter withStatisticsCollector(CacheStatisticsCollector cacheStatisticsCollector) {
		return null;
	}

	@Override
	public CacheStatistics getCacheStatistics(String cacheName) {
		return null;
	}
}
