/**
 * 责任链终端缓存操作及其错误策略实现。
 *
 * <p>防护 Handler 位于各自的 {@code protection} 机制包；本包只承载实际 Redis
 * 操作这一条终端 Handler。
 */
package io.github.davidhlp.spring.cache.redis.chain.handler;
