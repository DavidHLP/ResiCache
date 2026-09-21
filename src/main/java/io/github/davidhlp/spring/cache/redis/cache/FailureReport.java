package io.github.davidhlp.spring.cache.redis.cache;




import org.slf4j.Logger;

/**
 * 失败上报唯一 owner — key-privacy contract 的单点实现。
 *
 * <p><b>契约</b>:一次失败恰好产生「一条 WARN/ERROR + 一条配对 DEBUG」。WARN/ERROR 只携带
 * cacheName(配置级低基数)或 key 内容指纹,以及异常<b>类型链</b>(不含 message —— message 可能
 * 内嵌 raw key);含 message 的完整堆栈只留在 DEBUG。
 *
 * <p><b>调用方契约</b>:只说「什么失败了」—— 描述文本 + cacheName / raw key + 异常。级别选择、
 * 指纹格式化、类型链渲染全部由本类完成:raw key 只能以 raw 形态传入并被
 * {@link FailureDiagnostics#keyFingerprint} 转成指纹,异常只能以 {@link Throwable} 传入并被
 * {@link FailureDiagnostics#sanitizedFailure} 转成类型链 —— raw key 与异常 message 在 API
 * 形态上无法进入 WARN/ERROR。typed exception message 需要指纹时同样经 {@link #fingerprint}
 * 取用,调用点不自行拼装指纹。
 *
 * <p><b>为什么日志走调用方 logger</b>:保留各调用点的 log category(运维按类过滤、测试按类
 * 捕获该失败点),本类不引入自己的 category。
 *
 * <p><b>deletion test</b>:删掉本 seam → 「级别配对 + 指纹 + 类型链」的规则退回各调用点手写,
 * WARN 与 DEBUG 再次各自漂移(见 remediation 提交 e598693 / 34fc53d)。
 */
final class FailureReport {

    private FailureReport() {
    }

    /**
     * key 内容指纹(String 形态) —— 与 WARN/ERROR 上下文同一实现,供 typed exception message 使用。
     *
     * @param key 缓存 key / 锁 key(可为 null → {@code "null"})
     * @return 16 进制内容指纹
     */
    static String fingerprint(String key) {
        return FailureDiagnostics.keyFingerprint(key);
    }

    /**
     * key 内容指纹(byte[] 形态) —— 与 WARN/ERROR 上下文同一实现。
     *
     * @param key key 字节(可为 null → {@code "null"})
     * @return 16 进制内容指纹
     */
    static String fingerprint(byte[] key) {
        return FailureDiagnostics.keyFingerprint(key);
    }

    static void warn(Logger log, String what, String cacheName, Object key) {
        emit(log, false, what, cacheName, key, null);
    }

    static void warn(Logger log, String what, Throwable failure) {
        emit(log, false, what, null, null, failure);
    }

    static void warn(Logger log, String what, String cacheName, Object key, Throwable failure) {
        emit(log, false, what, cacheName, key, failure);
    }

    static void error(Logger log, String what, Throwable failure) {
        emit(log, true, what, null, null, failure);
    }

    static void error(Logger log, String what, String cacheName, Object key, Throwable failure) {
        emit(log, true, what, cacheName, key, failure);
    }

    private static void emit(Logger log,
                             boolean error,
                             String what,
                             String cacheName,
                             Object key,
                             Throwable failure) {
        String context = contextSuffix(cacheName, key);
        String message = failure == null
                ? what + context
                : what + context + (context.isEmpty() ? ": " : ", ")
                        + "cause=" + FailureDiagnostics.sanitizedFailure(failure);
        if (error) {
            log.error(message);
        } else {
            log.warn(message);
        }
        if (failure != null) {
            log.debug("{} detail{}", what, context, failure);
        }
    }

    /** 组装 {@code ": cacheName=..., keyFingerprint=..."};无可用关联字段时返回空串。 */
    private static String contextSuffix(String cacheName, Object key) {
        StringBuilder sb = new StringBuilder();
        if (cacheName != null) {
            sb.append(": cacheName=").append(cacheName);
        }
        String fingerprint = contextFingerprint(key);
        if (fingerprint != null) {
            sb.append(sb.isEmpty() ? ": " : ", ").append("keyFingerprint=").append(fingerprint);
        }
        return sb.toString();
    }

    /**
     * raw key(String / byte[] 两种既有形态)转内容指纹。其他类型视为不可关联 —— 不输出,
     * 避免把任意 {@code toString} 当作令牌打进日志。
     */
    private static String contextFingerprint(Object key) {
        if (key instanceof String stringKey) {
            return FailureDiagnostics.keyFingerprint(stringKey);
        }
        if (key instanceof byte[] byteKey) {
            return FailureDiagnostics.keyFingerprint(byteKey);
        }
        return null;
    }
}
