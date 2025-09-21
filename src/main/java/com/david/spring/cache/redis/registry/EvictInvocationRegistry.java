package com.david.spring.cache.redis.registry;

import com.david.spring.cache.redis.reflect.EvictInvocation;
import org.springframework.stereotype.Component;

/**
 * 驱逐调用注册表
 * 注册 (cacheName, key) -> EvictInvocation 的映射，并提供细粒度的 Key 级锁
 * 支持 allEntries=true 的场景，key 为空时使用通配符 "*"
 */
@Component
public class EvictInvocationRegistry extends AbstractInvocationRegistry<EvictInvocation> {

	@Override
	protected Object normalizeKey(Object key) {
		// 允许 key 为空（例如 allEntries=true 的场景，以通配符表示）
		return key == null ? "*" : key;
	}
}
