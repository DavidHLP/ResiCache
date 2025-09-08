package com.david.spring.cache.redis.chain.handlers;

import com.david.spring.cache.redis.chain.AbstractCacheHandler;
import com.david.spring.cache.redis.chain.context.CacheContext;
import com.david.spring.cache.redis.protection.CachePenetrationProtection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 缓存穿透保护处理器（优化版）
 * 在缓存读取后立即进行穿透防护，确保责任链正确流转：
 * - 参数校验和攻击检测（恶意请求直接拒绝）
 * - 空值占位符检查（命中空值缓存时返回null）
 * - 布隆过滤器预检（仅作为优化手段，不阻止方法执行）
 * - 穿透统计和监控
 *
 * @author david
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CachePenetrationHandler extends AbstractCacheHandler {

	private final CachePenetrationProtection cachePenetrationProtection;

	@Override
	public String getName() {
		return "CachePenetrationHandler";
	}

	@Override
	protected Object doHandle(CacheContext context) {
		String cacheKey = context.getCacheKey();

		// 1. 参数校验 - 检查缓存键合理性
		if (!cachePenetrationProtection.isValidCacheKey(cacheKey)) {
			log.warn("缓存键不合法，拒绝处理: {}", cacheKey);
			context.setResult(null);
			context.setProcessed(true);
			return null;
		}

		// 2. 攻击检测 - 检查是否为穿透攻击
		if (cachePenetrationProtection.isPenetrationAttack(cacheKey)) {
			log.warn("检测到穿透攻击，拒绝处理: {}", cacheKey);
			context.setResult(null);
			context.setProcessed(true);
			return null;
		}

		// 3. 空值检查 - 如果缓存命中，检查是否为空值占位符
		if (context.isCacheHit()) {
			Object cacheValue = context.getCacheValue();

			if (cachePenetrationProtection.isNullValuePlaceholder(cacheValue)) {
				log.debug("命中空值缓存，防止缓存穿透: {}", cacheKey);
				context.setResult(null);
				context.setProcessed(true);
				return null;
			}

			// 缓存命中且不是空值，继续下一个处理器
			log.debug("缓存命中有效数据，穿透防护通过: {}", cacheKey);
			return null;
		}

		// 4. 布隆过滤器预检 - 缓存未命中时进行预检，但不阻止方法执行
		if (!cachePenetrationProtection.bloomFilterMightContain(cacheKey)) {
			log.debug("布隆过滤器判定数据可能不存在，但仍允许方法执行: {}", cacheKey);
			
			// 记录穿透尝试（用于统计）
			cachePenetrationProtection.recordPenetrationAttempt(cacheKey);
			
			// 标记为可能的穿透请求，但不阻止继续执行
			context.setPossiblePenetration(true);
		} else {
			log.debug("布隆过滤器判定数据可能存在，继续执行: {}", cacheKey);
		}

		// 继续下一个处理器，让方法执行来确定数据是否真实存在
		return null;
	}
}
