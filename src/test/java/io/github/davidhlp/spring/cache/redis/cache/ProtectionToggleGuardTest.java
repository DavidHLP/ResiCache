package io.github.davidhlp.spring.cache.redis.cache;




import io.github.davidhlp.spring.cache.redis.cache.CacheHandlerChainFactory.Toggle;
import io.github.davidhlp.spring.cache.redis.chain.HandlerOrder;
import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * protection 机制声明守卫 —— 把工厂「新增机制只需追加一行」的说法变成可执行断言。
 *
 * <p>一条机制过去要在三处声明(order 枚举 / properties 字段 / 工厂表),两处字面量与
 * 一处 getter 各自可漂移。收敛为单一 {@code PROTECTION_TOGGLES} 表之后,本测试锁住
 * 表与 {@link RedisProCacheProperties.ProtectionProperties} 的绑定:
 *
 * <ol>
 *   <li>表的 disableName 在 properties 上确有对应的 {@code *Enabled} 字段</li>
 *   <li>该字段是这条 toggle 的 getter 真正读取的对象(按名反射置 FALSE 后读回 FALSE)</li>
 *   <li>表的条目数与 properties 的分项开关数一致 —— 漏加一行即红</li>
 * </ol>
 */
@DisplayName("protection toggle table guard")
class ProtectionToggleGuardTest {

    private static final List<Toggle> TOGGLES = CacheHandlerChainFactory.protectionToggles();

    @Test
    @DisplayName("每个 toggle 的 disableName 都对应 properties 上一个 *Enabled 字段")
    void everyToggle_hasABoundProperty() {
        for (Toggle toggle : TOGGLES) {
            String setter = "set" + camelCase(toggle.order().getDisableName()) + "Enabled";
            assertThat(enabledSetters())
                    .as("机制 %s 的 disableName=%s 必须对应 resi-cache.protection.%s.enabled",
                            toggle.order(), toggle.order().getDisableName(),
                            toggle.order().getDisableName())
                    .contains(setter);
        }
    }

    @Test
    @DisplayName("toggle 的 getter 读的正是该机制自己的属性(按名置 FALSE 后读回 FALSE)")
    void everyToggleGetter_readsItsOwnProperty() throws Exception {
        for (Toggle toggle : TOGGLES) {
            RedisProCacheProperties.ProtectionProperties protection =
                    new RedisProCacheProperties.ProtectionProperties();
            String field = camelCase(toggle.order().getDisableName()) + "Enabled";

            assertThat(toggle.getter().apply(protection))
                    .as("%s:未配置时 getter 必须返回 null(继承总开关)", field)
                    .isNull();

            Method setter = RedisProCacheProperties.ProtectionProperties.class
                    .getMethod("set" + field, Boolean.class);
            setter.invoke(protection, Boolean.FALSE);

            assertThat(toggle.getter().apply(protection))
                    .as("%s:getter 必须读该机制自己的字段(接线错位会让开关静默失效)", field)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("表条目数与 properties 分项开关数一致(漏加一行即红)")
    void toggleCount_matchesBoundPropertyCount() {
        assertThat(TOGGLES)
                .as("新增机制需同时追加 PROTECTION_TOGGLES 一行与 *Enabled 字段")
                .hasSize(enabledSetters().size());
    }

    @Test
    @DisplayName("TTL 不在机制表内(兼担基础 TTL 计算,禁用会产生永久缓存)")
    void ttl_isDeliberatelyNotAToggle() {
        assertThat(TOGGLES)
                .extracting(Toggle::order)
                .doesNotContain(HandlerOrder.TTL, HandlerOrder.ACTUAL_CACHE);
    }

    @Test
    @DisplayName("表内机制与其 disableName 一一对应,无重复")
    void toggles_areUnique() {
        Set<String> names = TOGGLES.stream()
                .map(t -> t.order().getDisableName())
                .collect(Collectors.toSet());

        assertThat(names).hasSize(TOGGLES.size());
    }

    /** {@code bloom-filter} → {@code BloomFilter}。 */
    private static String camelCase(String kebab) {
        StringBuilder sb = new StringBuilder();
        for (String part : kebab.split("-")) {
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    /**
     * {@link RedisProCacheProperties.ProtectionProperties} 上的 per-mechanism 覆盖 setter
     * ({@code setBloomFilterEnabled}…)。显式排除总开关 {@code setEnabled} —— 它不属机制表。
     */
    private static Set<String> enabledSetters() {
        return Arrays.stream(RedisProCacheProperties.ProtectionProperties.class.getMethods())
                .map(Method::getName)
                .filter(name -> name.startsWith("set") && name.endsWith("Enabled"))
                .filter(name -> !"setEnabled".equals(name))
                .collect(Collectors.toSet());
    }
}
