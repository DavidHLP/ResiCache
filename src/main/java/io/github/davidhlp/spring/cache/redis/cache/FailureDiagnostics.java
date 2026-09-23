package io.github.davidhlp.spring.cache.redis.cache;




import org.springframework.lang.Nullable;

/**
 * 失败诊断的 key 隐私 helper(key-privacy contract)— 指纹与类型链的 primitive 单点。
 *
 * <p><b>契约</b>:WARN/ERROR 与 typed exception message 不得出现 raw key;配置级低基数的
 * {@code cacheName} 保留用于关联。当一条诊断既没有 cacheName、又需要与 DEBUG 原始日志关联时,
 * 用 {@link #keyFingerprint} 输出内容指纹替代 raw key。
 *
 * <p><b>唯一生产入口是 {@link FailureReport}</b> —— 级别配对、WARN/ERROR 组装与异常消息里的
 * 指纹取用都在那里;本类只保留两套 primitive(指纹 / 类型链),便于独立阅读与测试。
 *
 * <p><b>为什么原样保留 fingerprint 算法</b>:String 形态取 {@code Integer.toHexString(key.hashCode())},
 * 字节形态取 {@code Integer.toHexString(Arrays.hashCode(key))} —— 两者都是「该形态下的内容哈希」,
 * 且字节形态与既有 serialization migration WARN 输出一致(该路径已先于本 helper 使用同一形式)。
 * 这是<b>关联令牌,不是安全边界</b> —— 它只保证日志与异常消息不携带 raw key,低熵 key 可被暴力
 * 反推;需要强不可逆性时另议(不在 §15 要求内)。
 *
 * <p><b>deletion test</b>:删掉本 helper → 两套 primitive 内联进 {@link FailureReport},
 * 「哪些形态可关联、为何不做解码」的契约埋进日志装配代码,typed exception message 路径
 * 也会再次各自拼装指纹。
 */
final class FailureDiagnostics {

    private FailureDiagnostics() {
    }

    /**
     * key 内容指纹 —— 非 raw key 的关联令牌,null 输入返回 {@code "null"}。
     *
     * @param key 缓存 key / 锁 key(可为 null)
     * @return 16 进制内容指纹
     */
    static String keyFingerprint(@Nullable String key) {
        return key == null ? "null" : Integer.toHexString(key.hashCode());
    }

    /**
     * 字节形态 key 的内容指纹 —— 直接对<b>字节</b>取内容哈希({@link java.util.Arrays#hashCode(byte[])},
     * 与 serialization migration 路径的历史输出一致)。
     *
     * <p>刻意<b>不</b>先解码成 String:UTF-8 解码对非法序列替换为 U+FFFD,两个不同的字节序列会
     * 得到同一个指纹(碰撞),而字节哈希不会。
     *
     * @param key key 字节(可为 null)
     * @return 16 进制内容指纹
     */
    static String keyFingerprint(@Nullable byte[] key) {
        return key == null ? "null" : Integer.toHexString(java.util.Arrays.hashCode(key));
    }

    /**
     * 失败的安全日志描述 —— 只记异常类型链的简单名,不含异常 message
     * (message 可能内嵌 raw key,§15 禁止 WARN/ERROR 外泄)。
     *
     * <p>类型链最多 160 字符,避免深 cause 链刷屏。
     *
     * @param failure 失败原因(可为 null)
     * @return 异常类型链描述
     */
    static String sanitizedFailure(@Nullable Throwable failure) {
        if (failure == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(failure.getClass().getSimpleName());
        Throwable cause = failure.getCause();
        while (cause != null && sb.length() < 160) {
            sb.append(" <- ").append(cause.getClass().getSimpleName());
            cause = cause.getCause();
        }
        return sb.toString();
    }
}
