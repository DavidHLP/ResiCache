package com.david.spring.cache.redis.chain;

import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import jakarta.annotation.Nonnull;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 缓存处理器链构建器。
 * <p>
 * 负责根据上下文动态构建职责链，支持：
 * - 自动处理器选择和排序
 * - 链缓存优化
 * - 支持条件过滤
 * - 链验证和优化
 * </p>
 *
 * @author CacheGuard
 * @since 2.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheHandlerChainBuilder {

	/** 所有可用的处理器 */
	private final List<CacheHandler> allHandlers;
	/** 链缓存，按上下文特征缓存构建好的链 */
	private final Map<String, CacheHandlerChain> chainCache = new ConcurrentHashMap<>();
	/** 处理器映射，按名称索引 */
	private Map<String, CacheHandler> handlerMap;

	@PostConstruct
	public void init() {
		// 构建处理器映射
		handlerMap = allHandlers.stream()
				.collect(Collectors.toMap(
						CacheHandler::getName,
						handler -> handler
				));

		log.info("Initialized handler chain builder with {} handlers: {}",
				handlerMap.size(), handlerMap.keySet());
	}

	/**
	 * 根据上下文构建处理器链。
	 * <p>
	 * 使用缓存优化重复构建，支持自动和手动模式。
	 * </p>
	 *
	 * @param context 缓存调用上下文
	 * @return 构建好的处理器链
	 */
	@Nonnull
	public CacheHandlerChain buildChain(@Nonnull CachedInvocationContext context) {
		String cacheKey = buildCacheKey(context);
		return chainCache.computeIfAbsent(cacheKey, key -> buildChainInternal(context));
	}

	/**
	 * 实际的链构建逻辑。
	 */
	@Nonnull
	private CacheHandlerChain buildChainInternal(@Nonnull CachedInvocationContext context) {
		// 创建临时上下文用于支持性检查
		CacheHandlerContext tempContext = createTempContext(context);

		// 选择并排序适用的处理器
		List<CacheHandler> selectedHandlers = selectAndSortHandlers(tempContext, context);

		if (selectedHandlers.isEmpty()) {
			log.warn("No suitable handlers found for context: {}", context);
			return CacheHandlerChain.empty();
		}

		// 构建链
		CacheHandlerChain chain = linkHandlers(selectedHandlers);

		log.debug("Built handler chain with {} handlers: {}",
				chain.size(),
				selectedHandlers.stream().map(CacheHandler::getName).collect(Collectors.toList()));

		return chain;
	}

	/**
	 * 选择并排序适用的处理器。
	 */
	@Nonnull
	private List<CacheHandler> selectAndSortHandlers(@Nonnull CacheHandlerContext tempContext,
	                                                 @Nonnull CachedInvocationContext invocationContext) {
		return allHandlers.stream()
				.filter(handler -> handler.supports(tempContext))
				.filter(handler -> isHandlerApplicable(handler, invocationContext))
				.sorted(Comparator.comparingInt(CacheHandler::getOrder))
				.collect(Collectors.toList());
	}

	/**
	 * 判断处理器是否适用于给定的上下文。
	 * 现在基于具体的布尔配置来判断而不是策略类型
	 */
	private boolean isHandlerApplicable(@Nonnull CacheHandler handler,
	                                    @Nonnull CachedInvocationContext context) {
		String handlerName = handler.getName();

		// 基于具体的功能配置来判断处理器适用性
		return switch (handlerName) {
			case "Simple" -> true; // Simple处理器总是适用
			case "BloomFilter" -> context.useBloomFilter();
			case "PreRefresh" -> context.enablePreRefresh();
			case "CacheEvict" -> true; // 清除处理器适用于删除操作
			case "CacheLoad" -> context.distributedLock() || context.internalLock();
			default -> false;
		};
	}


	/**
	 * 将处理器列表链接成职责链。
	 */
	@Nonnull
	private CacheHandlerChain linkHandlers(@Nonnull List<CacheHandler> handlers) {
		if (handlers.isEmpty()) {
			return CacheHandlerChain.empty();
		}

		if (handlers.size() == 1) {
			return CacheHandlerChain.single(handlers.get(0));
		}

		// 链接处理器
		for (int i = 0; i < handlers.size() - 1; i++) {
			handlers.get(i).setNext(handlers.get(i + 1));
		}

		// 确保最后一个处理器的next为null
		handlers.get(handlers.size() - 1).setNext(null);

		return new CacheHandlerChain(handlers.get(0), handlers.size());
	}

	/**
	 * 构建缓存键。
	 */
	@Nonnull
	private String buildCacheKey(@Nonnull CachedInvocationContext context) {
		return String.format("%s:%s:%s:%s",
				context.useBloomFilter(),
				context.enablePreRefresh(),
				context.distributedLock(),
				context.internalLock());
	}

	/**
	 * 创建临时上下文用于支持性检查。
	 */
	@Nonnull
	private CacheHandlerContext createTempContext(@Nonnull CachedInvocationContext invocationContext) {
		// 创建最小化的临时上下文，仅用于supports()方法检查
		return new CacheHandlerContext(
				"temp",
				"temp",
				"temp",
				null,
				null,
				null,
				invocationContext,
				null,
				null,
				CacheOperationType.READ);
	}

	/**
	 * 创建自定义处理器链。
	 *
	 * @param handlerNames 处理器名称列表
	 * @return 自定义处理器链
	 */
	@Nonnull
	public CacheHandlerChain createCustomChain(@Nonnull List<String> handlerNames) {
		List<CacheHandler> handlers = handlerNames.stream()
				.map(handlerMap::get)
				.filter(handler -> handler != null)
				.collect(Collectors.toList());

		if (handlers.isEmpty()) {
			log.warn("No valid handlers found for names: {}", handlerNames);
			// 返回包含Simple处理器的默认链
			CacheHandler simpleHandler = handlerMap.get("Simple");
			return simpleHandler != null ? CacheHandlerChain.single(simpleHandler) : CacheHandlerChain.empty();
		}

		return linkHandlers(handlers);
	}

	/**
	 * 清空链缓存。
	 */
	public void clearCache() {
		chainCache.clear();
		log.debug("Handler chain cache cleared");
	}

	/**
	 * 获取缓存统计。
	 */
	@Nonnull
	public Map<String, Object> getCacheStats() {
		return Map.of(
				"chainCacheSize", chainCache.size(),
				"availableHandlers", handlerMap.size(),
				"handlerNames", handlerMap.keySet()
		);
	}

	/**
	 * 获取可用的处理器名称。
	 */
	@Nonnull
	public List<String> getAvailableHandlers() {
		return handlerMap.keySet().stream()
				.sorted()
				.collect(Collectors.toList());
	}
}