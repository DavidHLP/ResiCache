package io.github.davidhlp.spring.cache.redis.serialization;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WhitelistPolicy 单元测试
 *
 * <p>验证 JSON 侧白名单判断的单一来源:前缀匹配 / java.lang / java.time / java.math /
 * 20 条 java.util 集合枚举 / 拒绝路径。这是 C01 抽出 WhitelistPolicy 的核心可测性收益——
 * 原本散布在 SecureJacksonRedisSerializer 三处的规则现可独立断言。
 */
@DisplayName("WhitelistPolicy Tests")
class WhitelistPolicyTest {

    private final WhitelistPolicy policy =
            new WhitelistPolicy(List.of(WhitelistPolicy.DEFAULT_ALLOWED_PACKAGE_PREFIX));

    @Test
    @DisplayName("allows configured package prefix")
    void isClassNameAllowed_configuredPrefix_allowed() {
        assertThat(policy.isClassNameAllowed(
                "io.github.davidhlp.spring.cache.redis.cache.CachedValue")).isTrue();
    }

    @Test
    @DisplayName("allows java.lang prefix")
    void isClassNameAllowed_javaLang_allowed() {
        assertThat(policy.isClassNameAllowed("java.lang.String")).isTrue();
    }

    @Test
    @DisplayName("allows java.time and java.math prefixes")
    void isClassNameAllowed_javaTimeAndMath_allowed() {
        assertThat(policy.isClassNameAllowed("java.time.LocalDateTime")).isTrue();
        assertThat(policy.isClassNameAllowed("java.math.BigDecimal")).isTrue();
    }

    @Test
    @DisplayName("allows whitelisted java.util collections")
    void isClassNameAllowed_javaUtilWhitelist_allowed() {
        assertThat(policy.isClassNameAllowed("java.util.ArrayList")).isTrue();
        assertThat(policy.isClassNameAllowed("java.util.LinkedHashMap")).isTrue();
        assertThat(policy.isClassNameAllowed("java.util.Collections$UnmodifiableList")).isTrue();
    }

    @Test
    @DisplayName("rejects non-enumerated java.util classes (e.g. concurrent, Date)")
    void isClassNameAllowed_nonWhitelistedJavaUtil_rejected() {
        assertThat(policy.isClassNameAllowed("java.util.concurrent.ConcurrentHashMap")).isFalse();
        assertThat(policy.isClassNameAllowed("java.util.Date")).isFalse();
    }

    @Test
    @DisplayName("rejects unknown package (gadget-chain protection)")
    void isClassNameAllowed_unknownPackage_rejected() {
        assertThat(policy.isClassNameAllowed("com.attacker.MaliciousGadget")).isFalse();
        assertThat(policy.isClassNameAllowed(
                "org.springframework.context.support.ClassPathXmlApplicationContext")).isFalse();
    }

    @Test
    @DisplayName("isClassAllowed delegates to isClassNameAllowed via getName")
    void isClassAllowed_delegatesToClassName() {
        assertThat(policy.isClassAllowed(String.class)).isTrue();
        assertThat(policy.isClassAllowed(java.util.ArrayList.class)).isTrue();
        assertThat(policy.isClassAllowed(java.util.concurrent.ConcurrentHashMap.class)).isFalse();
    }

    @Test
    @DisplayName("isClassAllowed returns false for null")
    void isClassAllowed_null_returnsFalse() {
        assertThat(policy.isClassAllowed(null)).isFalse();
    }
}
