package io.github.davidhlp.spring.cache.redis.cache;




import java.nio.charset.StandardCharsets;
import org.springframework.lang.Nullable;

/**
 * 失败诊断的 key 隐私 helper(ADR-0001 §15)— 唯一 key 脱敏单点。
 *
 * <p><b>契约</b>:WARN/ERROR 与 typed exception message 不得出现 raw key;配置级低基数的
 * {@code cacheName} 保留用于关联。当一条诊断既没有 cacheName、又需要与 DEBUG 原始日志关联时,
 * 用 {@link #keyFingerprint} 输出内容指纹替代 raw key。
 *
 * <p><b>为什么原样保留 fingerprint 算法</b>:取值是 {@code Integer.toHexString(key.hashCode())},
 * 与既有 serialization migration WARN 输出一致(该路径已先于本 helper 使用同一形式)。
 * 这是<b>关联令牌,不是安全边界</b> —— 它只保证日志与异常消息不携带 raw key,低熵 key 可被暴力
 * 反推;需要强不可逆性时另议(不在 §15 要求内)。
 *
 * <p><b>deletion test</b>:删掉本 helper → fingerprint 形式在 5 个类里各写一遍并各自漂移
 * (migration 引擎的 byte[] 版与锁/刷新路径的 String 版会再次分叉)。
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
     * 字节形态 key 的内容指纹 —— 与 {@link #keyFingerprint(String)} 同源(UTF-8 解码后同算法),
     * 使 serialization migration 路径与字符串 key 路径的指纹可互相印证。
     *
     * @param key key 字节(可为 null)
     * @return 16 进制内容指纹
     */
    static String keyFingerprint(@Nullable byte[] key) {
        return key == null ? "null" : keyFingerprint(new String(key, StandardCharsets.UTF_8));
    }
}
