package io.github.davidhlp.spring.cache.redis.chain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HandlerResult SPI 构造边界测试。
 */
@DisplayName("HandlerResult Tests")
class HandlerResultTest {

    @Test
    @DisplayName("canonical ctor 拒绝 null decision 并说明 SPI 协议要求")
    void canonicalConstructor_rejectsNullDecision() {
        assertThatThrownBy(() -> new HandlerResult(null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("SPI protocol")
                .hasMessageContaining("decision");
    }

    @Test
    @DisplayName("合法 decision 仍允许 null result")
    void canonicalConstructor_allowsNullResult() {
        HandlerResult result = new HandlerResult(FlowControl.CONTINUE, null);

        assertThat(result.decision()).isEqualTo(FlowControl.CONTINUE);
        assertThat(result.result()).isNull();
    }
}
