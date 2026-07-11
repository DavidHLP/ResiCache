package io.github.davidhlp.spring.cache.redis.chain.handler;

import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 统一的缓存错误处理器 — per-operation 策略调度 deep seam.
 *
 * <p>职责：
 * <ol>
 *   <li>定义 {@link ErrorStrategy} 三态语义（FAIL_FAST / GRACEFUL_DEGRADATION / SILENT）</li>
 *   <li>维护 per-operation 策略表 — 单一事实源</li>
 *   <li>按 operation 调度策略 + 应用策略（日志级别 + CacheResult 形态）</li>
 * </ol>
 *
 * <p><b>深度化 vs 浅层 — ADR-0067</b>：原设计 5 个 wrapper 方法
 * （{@code handleGetError} / {@code handlePutError} / {@code handlePutIfAbsentError} /
 * {@code handleRemoveError} / {@code handleCleanError}）都是 1 行委派到
 * {@link #handleException(String, String, String, Exception, ErrorStrategy)},
 * 把"per-operation 策略"这个隐含概念拆散到 6 处。调用方
 * （{@link ActualCacheHandler}）必须记忆"PUT 走哪个 wrapper 方法"，
 * 新增 operation 必须加 wrapper 方法 + 测试。
 *
 * <p>deepening：把 5 wrapper 收口成单 {@link #handleError(CacheOperation, String, String, Exception)}
 * 入口，per-operation 策略集中到 {@link #STRATEGIES} 不可变 Map。调用方只需传
 * {@link CacheContext#getOperation() context.getOperation()} 即可，无需记忆具体方法名；
 * 新增 operation 只需在 {@link CacheOperation} 追加枚举值 + 在 {@link #STRATEGIES} 追加一行。
 *
 * <p><b>deletion test</b>：删掉 {@link #STRATEGIES} → 调用方必须自己感知每个 operation 的
 * 策略，per-operation 概念散落，本类的"统一错误处理"语义丢失。删掉新
 * {@link #handleError} 方法 → 必须恢复 5 wrapper，deepening 失败。两条路径都让 seam
 * 失去价值 — 真 seam。
 *
 * <p><b>back-compat</b>：5 wrapper 方法已删除；所有内部 caller
 * （{@link ActualCacheHandler}）同步迁移至 {@link #handleError}。
 * 外部 caller 如依赖具体方法名，改用 {@link #handleException} 显式传策略或
 * {@link #handleError} 传 operation。
 */
@Slf4j
@Component
public class CacheErrorHandler {

    /**
     * 错误处理策略 — CacheResult 返回语义 + 日志级别三元组。
     */
    public enum ErrorStrategy {
        /** 返回失败结果，记录错误日志 */
        FAIL_FAST,

        /** 降级处理，返回 miss，记录警告日志 */
        GRACEFUL_DEGRADATION,

        /** 静默失败，仅记录 debug 日志 */
        SILENT
    }

    /**
     * per-operation 策略表 — 单一事实源。新增 operation 时追加一行即可，
     * 无需新增 wrapper 方法。
     *
     * <p>设计意图：
     * <ul>
     *   <li>GET → GRACEFUL_DEGRADATION：读失败不应阻塞业务，返回 miss 让业务重新加载</li>
     *   <li>PUT / PUT_IF_ABSENT → FAIL_FAST：写失败表示 Redis 不可用，业务需要感知</li>
     *   <li>REMOVE → SILENT：删除失败不应影响业务流程</li>
     *   <li>CLEAN → FAIL_FAST：批量清理失败需要让业务感知</li>
     * </ul>
     */
    private static final Map<CacheOperation, ErrorStrategy> STRATEGIES = Map.of(
            CacheOperation.GET, ErrorStrategy.GRACEFUL_DEGRADATION,
            CacheOperation.PUT, ErrorStrategy.FAIL_FAST,
            CacheOperation.PUT_IF_ABSENT, ErrorStrategy.FAIL_FAST,
            CacheOperation.REMOVE, ErrorStrategy.SILENT,
            CacheOperation.CLEAN, ErrorStrategy.FAIL_FAST);

    /**
     * 按 operation 调度策略并应用 — 入口方法。
     *
     * <p>调用方传入当前 operation，本方法按 {@link #STRATEGIES} 查找策略，
     * 委派到 {@link #handleException(String, String, String, Exception, ErrorStrategy)}
     * 应用策略（unknown operation 退化到 FAIL_FAST）。
     *
     * @param operation 当前缓存操作（非 null）
     * @param cacheName 缓存名（用于日志）
     * @param key       缓存 key / pattern（用于日志）
     * @param e         异常
     * @return 按策略生成的 {@link CacheResult}
     */
    public CacheResult handleError(CacheOperation operation, String cacheName, String key, Exception e) {
        ErrorStrategy strategy = STRATEGIES.getOrDefault(operation, ErrorStrategy.FAIL_FAST);
        return handleException(operation.name(), cacheName, key, e, strategy);
    }

    /**
     * 应用策略生成结果 — 直接策略调用入口（测试 + 显式策略场景）。
     *
     * <p>三条 switch 分支：
     * <ul>
     *   <li>FAIL_FAST：log.error + CacheResult.failure()</li>
     *   <li>GRACEFUL_DEGRADATION：log.warn + CacheResult.miss()</li>
     *   <li>SILENT：log.debug + CacheResult.miss()</li>
     * </ul>
     *
     * <p>ADR-0039：CacheResult.failure() 不再携带 exception（零生产读者）；
     * 异常已在上方 log 记录，此处仅置 success=false。
     *
     * @param operation 操作名（字符串，用于日志）
     * @param cacheName 缓存名（用于日志）
     * @param key       缓存 key / pattern（用于日志）
     * @param e         异常
     * @param strategy  显式策略
     * @return 按策略生成的 {@link CacheResult}
     */
    public CacheResult handleException(
            String operation,
            String cacheName,
            String key,
            Exception e,
            ErrorStrategy strategy) {

        return switch (strategy) {
            case FAIL_FAST -> {
                log.error("Cache {} failed: cacheName={}, key={}",
                          operation, cacheName, key, e);
                yield CacheResult.failure();
            }
            case GRACEFUL_DEGRADATION -> {
                log.warn("Cache {} failed, degrading gracefully: cacheName={}, key={}, error={}",
                         operation, cacheName, key, e.getMessage());
                yield CacheResult.miss();
            }
            case SILENT -> {
                log.debug("Cache {} failed (silent): cacheName={}, key={}",
                          operation, cacheName, key, e);
                yield CacheResult.miss();
            }
        };
    }
}
