package com.david.spring.cache.redis.chain;

import com.david.spring.cache.redis.chain.context.CacheContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 缓存处理器责任链管理器
 *
 * @author david
 */
@Slf4j
@Component
public class CacheHandlerChain {

	/** 责任链的第一个处理器 */
	private CacheHandler firstHandler;

	/**
	 * 构造函数，初始化责任链
	 *
	 * @param handlers 处理器列表，按顺序连接成责任链
	 */
	public CacheHandlerChain(Map<String, CacheHandler> handlers) {
		if (handlers == null || handlers.isEmpty()) {
			throw new IllegalArgumentException("处理器列表不能为空");
		}

		buildChain(handlers);
	}

	/**
	 * 构建责任链
	 * 优化后的执行顺序：
	 * 1. 键生成 -> 2. 缓存读取 -> 3. 穿透防护 -> 4. 击穿防护 -> 5. 缓存写入 -> 6. 雪崩防护
	 *
	 * @param handlers 处理器列表
	 */
	private void buildChain(Map<String, CacheHandler> handlers) {
		CacheHandler previousHandler = null;
		// 优化后的责任链顺序：确保防护机制在正确时机触发
		String[] handlerNames = {
				"cacheKeyGeneratorHandler",     // 1. 生成缓存键
				"cacheReadHandler",             // 2. 读取缓存
				"cachePenetrationHandler",      // 3. 穿透防护（读取后立即进行）
				"cacheBreakdownHandler",        // 4. 击穿防护和方法执行
				"cacheWriteHandler",            // 5. 缓存写入
				"cacheAvalancheHandler"         // 6. 雪崩防护（最后执行）
		};

		for (String handlerName : handlerNames) {
			CacheHandler handler = handlers.get(handlerName);
			if (handler == null) {
				throw new IllegalArgumentException("找不到处理器: " + handlerName);
			}

			log.debug("连接处理器: {} -> {}", handler.getName(), handler.getClass().getSimpleName());

			if (this.firstHandler == null) {
				this.firstHandler = handler;
			}

			if (previousHandler != null) {
				previousHandler.setNext(handler);
			}
			previousHandler = handler;
		}

		log.info("缓存处理器责任链构建完成，执行顺序：键生成→读取→穿透防护→击穿防护→写入→雪崩防护（共{}个处理器）", handlers.size());
	}

	/**
	 * 执行责任链处理
	 *
	 * @param context 缓存上下文
	 * @return 处理结果
	 * @throws Throwable 处理过程中的异常
	 */
	public Object execute(CacheContext context) throws Throwable {
		if (firstHandler == null) {
			throw new IllegalStateException("责任链未初始化");
		}

		log.debug("开始执行缓存处理器责任链");
		return firstHandler.handle(context);
	}
}
