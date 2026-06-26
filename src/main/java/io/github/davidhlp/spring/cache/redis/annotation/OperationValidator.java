package io.github.davidhlp.spring.cache.redis.annotation;

import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.util.StringUtils;

/**
 * 缓存操作合法性校验器(职责3).
 *
 * <p>无状态、线程安全。校验 key/keyGenerator 互斥、cacheManager/cacheResolver 互斥、
 * cacheNames 非空,违例抛 {@link IllegalStateException}。保留实例形式便于未来注入
 * 自定义校验规则。
 */
@Slf4j
final class OperationValidator {

    /**
     * 校验单个缓存操作的合法性.
     *
     * @param target 方法或类对象
     * @param operation 缓存操作
     * @throws IllegalStateException 如果配置无效
     */
    void validate(final Object target, final CacheOperation operation) {
        log.trace("Validating cache operation for target: {}", target);

        if (StringUtils.hasText(operation.getKey())
                && StringUtils.hasText(operation.getKeyGenerator())) {
            final String errorMsg = "Invalid cache annotation configuration on '"
                    + target + "'. Both 'key' and 'keyGenerator' attributes "
                    + "have been set. These attributes are mutually exclusive.";
            log.error(errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        if (StringUtils.hasText(operation.getCacheManager())
                && StringUtils.hasText(operation.getCacheResolver())) {
            final String errorMsg = "Invalid cache annotation configuration on '"
                    + target + "'. Both 'cacheManager' and 'cacheResolver' "
                    + "attributes have been set.";
            log.error(errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        if (operation.getCacheNames().isEmpty()) {
            final String errorMsg = "Invalid cache annotation configuration on '"
                    + target + "'. At least one cache name must be specified.";
            log.error(errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        log.debug("Cache operation validation passed for target: {}", target);
    }
}
