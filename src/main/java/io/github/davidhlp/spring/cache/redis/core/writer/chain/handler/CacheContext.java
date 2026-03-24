package io.github.davidhlp.spring.cache.redis.core.writer.chain.handler;

import io.github.davidhlp.spring.cache.redis.core.writer.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.register.operation.RedisCacheableOperation;
import lombok.Getter;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 缓存操作上下文 - 组合输入和输出
 *
 * <p>设计原则:
 * <ul>
 *   <li>input 是只读的，包含原始请求参数</li>
 *   <li>output 是可变的，由各 Handler 设置处理结果</li>
 *   <li>attributes 用于 Handler 间传递临时数据和后置处理标记</li>
 *   <li>每个 Handler 只访问/修改自己关心的字段</li>
 * </ul>
 *
 * <p>使用方式:
 * <ul>
 *   <li>读取操作参数：context.getOperation(), context.getCacheName() 等</li>
 *   <li>设置处理结果：context.output().setFinalTtl(...), context.output().setStoreValue(...)</li>
 *   <li>检查状态：context.isSkipRemaining(), context.output().isPreRefreshCheckEnabled()</li>
 *   <li>属性传递：context.setAttribute(key, value), context.getAttribute(key)</li>
 * </ul>
 */
public class CacheContext {

    /** 输入参数（不可变） */
    @Getter
    private final CacheInput input;

    /** 输出状态（可变） */
    @Getter
    private final CacheOutput output;

    /**
     * 临时属性（用于 Handler 间传递数据和后置处理标记）
     * 使用 ConcurrentHashMap 支持并发访问
     */
    @Getter
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    public CacheContext(CacheInput input) {
        this.input = input;
        this.output = new CacheOutput();
    }

    // ==================== 便捷访问方法（委托到 input） ====================
    
    public CacheOperation getOperation() { 
        return input.operation(); 
    }
    
    public String getCacheName() { 
        return input.cacheName(); 
    }
    
    public String getRedisKey() { 
        return input.redisKey(); 
    }
    
    public String getActualKey() { 
        return input.actualKey(); 
    }
    
    public byte[] getValueBytes() { 
        return input.valueBytes(); 
    }
    
    public Object getDeserializedValue() { 
        return input.deserializedValue(); 
    }
    
    public Duration getTtl() { 
        return input.ttl(); 
    }
    
    public RedisCacheableOperation getCacheOperation() { 
        return input.cacheOperation(); 
    }

    // ==================== 便捷访问方法（委托到 output） ====================

    // TTL 相关
    public boolean isShouldApplyTtl() { return output.isShouldApplyTtl(); }
    public long getFinalTtl() { return output.getFinalTtl(); }
    public boolean isTtlFromContext() { return output.isTtlFromContext(); }

    // NullValue 相关
    public Object getStoreValue() { return output.getStoreValue(); }

    // 控制标记
    public boolean isSkipRemaining() { return output.isSkipRemaining(); }
    public void markSkipRemaining() { output.markSkipRemaining(); }

    // KeyPattern
    public String getKeyPattern() { return output.getKeyPattern(); }
    public void setKeyPattern(String pattern) { output.setKeyPattern(pattern); }

    // ==================== 属性访问（用于 Handler 间传递数据） ====================

    /**
     * 设置属性
     *
     * @param key 属性键
     * @param value 属性值
     * @param <T> 值类型
     */
    public <T> void setAttribute(String key, T value) {
        attributes.put(key, value);
    }

    /**
     * 获取属性
     *
     * @param key 属性键
     * @param <T> 值类型
     * @return 属性值，不存在返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }

    /**
     * 获取属性（带默认值）
     *
     * @param key 属性键
     * @param defaultValue 默认值
     * @param <T> 值类型
     * @return 属性值，不存在返回默认值
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key, T defaultValue) {
        Object value = attributes.get(key);
        return value != null ? (T) value : defaultValue;
    }

    /**
     * 移除属性
     *
     * @param key 属性键
     */
    public void removeAttribute(String key) {
        attributes.remove(key);
    }

    /**
     * 检查属性是否存在
     *
     * @param key 属性键
     * @return 是否存在
     */
    public boolean hasAttribute(String key) {
        return attributes.containsKey(key);
    }

    // ==================== 静态工厂方法 ====================
    
    public static CacheContext of(CacheInput input) {
        return new CacheContext(input);
    }
    
    /**
     * 创建 Builder（向后兼容）
     */
    public static CacheContextBuilder builder() {
        return new CacheContextBuilder();
    }
    
    /**
     * Builder 类（向后兼容旧代码）
     */
    public static class CacheContextBuilder {
        private CacheOperation operation;
        private String cacheName;
        private String redisKey;
        private String actualKey;
        private byte[] valueBytes;
        private Object deserializedValue;
        private Duration ttl;
        private RedisCacheableOperation cacheOperation;
        
        public CacheContextBuilder operation(CacheOperation operation) { this.operation = operation; return this; }
        public CacheContextBuilder cacheName(String cacheName) { this.cacheName = cacheName; return this; }
        public CacheContextBuilder redisKey(String redisKey) { this.redisKey = redisKey; return this; }
        public CacheContextBuilder actualKey(String actualKey) { this.actualKey = actualKey; return this; }
        public CacheContextBuilder valueBytes(byte[] valueBytes) { this.valueBytes = valueBytes; return this; }
        public CacheContextBuilder deserializedValue(Object value) { this.deserializedValue = value; return this; }
        public CacheContextBuilder ttl(Duration ttl) { this.ttl = ttl; return this; }
        public CacheContextBuilder cacheOperation(RedisCacheableOperation op) { this.cacheOperation = op; return this; }
        
        public CacheContext build() {
            CacheInput input = new CacheInput(
                operation, cacheName, redisKey, actualKey,
                valueBytes, deserializedValue, ttl, cacheOperation
            );
            return new CacheContext(input);
        }
    }
}
