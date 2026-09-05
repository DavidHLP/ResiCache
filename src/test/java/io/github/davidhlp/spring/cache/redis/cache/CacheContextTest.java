package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CacheContextTest {

    @Test
    void valueBytes_areDefensivelyCopiedAtInputAndContextViews() {
        byte[] source = {1, 2, 3};
        CacheContext context = CacheContext.of(CacheInput.builder().valueBytes(source).build());

        source[0] = 9;
        byte[] exposed = context.getValueBytes();
        exposed[1] = 8;

        assertThat(context.getValueBytes()).containsExactly(1, 2, 3);
    }
}
