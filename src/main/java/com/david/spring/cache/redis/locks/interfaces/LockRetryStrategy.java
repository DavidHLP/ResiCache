package com.david.spring.cache.redis.locks.interfaces;

/**
 * 锁重试策略接口
 * 定义获取锁失败时的重试行为
 *
 * @author David
 */
public interface LockRetryStrategy {

    /**
     * 计算下次重试延迟时间
     *
     * @param retryCount 当前重试次数（从0开始）
     * @param baseDelayMillis 基础延迟时间（毫秒）
     * @return 延迟时间（毫秒）
     */
    long calculateDelay(int retryCount, long baseDelayMillis);

    /**
     * 判断是否应该继续重试
     *
     * @param retryCount 当前重试次数
     * @param maxRetries 最大重试次数
     * @param elapsedTimeMillis 已消耗时间（毫秒）
     * @param maxWaitTimeMillis 最大等待时间（毫秒）
     * @return true表示继续重试，false表示停止重试
     */
    boolean shouldRetry(int retryCount, int maxRetries, long elapsedTimeMillis, long maxWaitTimeMillis);

    /**
     * 获取策略名称
     *
     * @return 策略名称
     */
    String getStrategyName();

    /**
     * 固定延迟重试策略
     */
    class FixedDelay implements LockRetryStrategy {
        @Override
        public long calculateDelay(int retryCount, long baseDelayMillis) {
            return baseDelayMillis;
        }

        @Override
        public boolean shouldRetry(int retryCount, int maxRetries, long elapsedTimeMillis, long maxWaitTimeMillis) {
            return retryCount < maxRetries && elapsedTimeMillis < maxWaitTimeMillis;
        }

        @Override
        public String getStrategyName() {
            return "fixed-delay";
        }
    }

    /**
     * 指数退避重试策略
     */
    class ExponentialBackoff implements LockRetryStrategy {
        private final double multiplier;
        private final long maxDelayMillis;

        public ExponentialBackoff(double multiplier, long maxDelayMillis) {
            this.multiplier = multiplier;
            this.maxDelayMillis = maxDelayMillis;
        }

        public ExponentialBackoff() {
            this(2.0, 5000); // 默认2倍增长，最大5秒
        }

        @Override
        public long calculateDelay(int retryCount, long baseDelayMillis) {
            long delay = (long) (baseDelayMillis * Math.pow(multiplier, retryCount));
            return Math.min(delay, maxDelayMillis);
        }

        @Override
        public boolean shouldRetry(int retryCount, int maxRetries, long elapsedTimeMillis, long maxWaitTimeMillis) {
            return retryCount < maxRetries && elapsedTimeMillis < maxWaitTimeMillis;
        }

        @Override
        public String getStrategyName() {
            return "exponential-backoff";
        }
    }

    /**
     * 线性退避重试策略
     */
    class LinearBackoff implements LockRetryStrategy {
        private final long incrementMillis;
        private final long maxDelayMillis;

        public LinearBackoff(long incrementMillis, long maxDelayMillis) {
            this.incrementMillis = incrementMillis;
            this.maxDelayMillis = maxDelayMillis;
        }

        public LinearBackoff() {
            this(100, 3000); // 默认每次增加100ms，最大3秒
        }

        @Override
        public long calculateDelay(int retryCount, long baseDelayMillis) {
            long delay = baseDelayMillis + (retryCount * incrementMillis);
            return Math.min(delay, maxDelayMillis);
        }

        @Override
        public boolean shouldRetry(int retryCount, int maxRetries, long elapsedTimeMillis, long maxWaitTimeMillis) {
            return retryCount < maxRetries && elapsedTimeMillis < maxWaitTimeMillis;
        }

        @Override
        public String getStrategyName() {
            return "linear-backoff";
        }
    }
}
