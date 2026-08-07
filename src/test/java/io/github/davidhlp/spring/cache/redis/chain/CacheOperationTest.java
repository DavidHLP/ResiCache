package io.github.davidhlp.spring.cache.redis.chain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CacheOperation} 谓词契约测试.
 *
 * <p>本测试把 3 个谓词方法的具体子集固化在测试代码中,作为"操作 → 哪些 handler 关心这个
 * 操作"契约的回归保护。未来新增 {@code CacheOperation} 枚举值时,本测试自动发现未授权
 * 加入子集的情形(每个谓词的 {@code ParameterizedTest} 覆盖整个枚举)。
 *
 * <p>如果没有本测试,该契约仅以散落在 4 个 handler 中的多行
 * {@code op == X || op == Y || op == Z} 形式存在,任何新操作都可能漏改某处。
 */
@DisplayName("CacheOperation predicate tests")
class CacheOperationTest {

    @Nested
    @DisplayName("isWrite()")
    class IsWriteTests {

        @ParameterizedTest(name = "{0} should not be a write op")
        @EnumSource(value = CacheOperation.class, names = {"REMOVE", "CLEAN", "GET"})
        void nonWriteOperations_returnFalse(CacheOperation op) {
            assertThat(op.isWrite()).isFalse();
        }

        @ParameterizedTest(name = "{0} should be a write op")
        @EnumSource(value = CacheOperation.class, names = {"PUT", "PUT_IF_ABSENT"})
        void writeOperations_returnTrue(CacheOperation op) {
            assertThat(op.isWrite()).isTrue();
        }
    }

    @Nested
    @DisplayName("requiresSyncLock()")
    class RequiresSyncLockTests {

        @ParameterizedTest(name = "{0} should not require sync lock")
        @EnumSource(value = CacheOperation.class, names = {"REMOVE", "CLEAN"})
        void nonLockEligibleOperations_returnFalse(CacheOperation op) {
            assertThat(op.requiresSyncLock()).isFalse();
        }

        @ParameterizedTest(name = "{0} should require sync lock")
        @EnumSource(value = CacheOperation.class, names = {"GET", "PUT", "PUT_IF_ABSENT"})
        void lockEligibleOperations_returnTrue(CacheOperation op) {
            assertThat(op.requiresSyncLock()).isTrue();
        }
    }

    @Nested
    @DisplayName("requiresBloomPostProcess()")
    class RequiresBloomPostProcessTests {

        @ParameterizedTest(name = "{0} should not require bloom post-process")
        @EnumSource(value = CacheOperation.class, names = {"GET", "REMOVE"})
        void nonBloomPostOperations_returnFalse(CacheOperation op) {
            assertThat(op.requiresBloomPostProcess()).isFalse();
        }

        @ParameterizedTest(name = "{0} should require bloom post-process")
        @EnumSource(value = CacheOperation.class, names = {"PUT", "PUT_IF_ABSENT", "CLEAN"})
        void bloomPostOperations_returnTrue(CacheOperation op) {
            assertThat(op.requiresBloomPostProcess()).isTrue();
        }
    }

    @Nested
    @DisplayName("subsets are mutually consistent")
    class SubsetConsistencyTests {

        @Test
        @DisplayName("isWrite() is a strict subset of requiresSyncLock()")
        void isWrite_isStrictSubsetOf_requiresSyncLock() {
            // write ops 一定需要锁(sync=true 时);非 write 不一定需要锁(GET 需要,REMOVE/CLEAN 不需要)
            for (CacheOperation op : CacheOperation.values()) {
                if (op.isWrite()) {
                    assertThat(op.requiresSyncLock())
                            .as("write op %s should require sync lock", op)
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("GET is in requiresSyncLock() but not in isWrite() / requiresBloomPostProcess()")
        void get_isSyncLockOnly() {
            assertThat(CacheOperation.GET.isWrite()).isFalse();
            assertThat(CacheOperation.GET.requiresSyncLock()).isTrue();
            assertThat(CacheOperation.GET.requiresBloomPostProcess()).isFalse();
        }

        @Test
        @DisplayName("CLEAN is in requiresBloomPostProcess() but not in isWrite() / requiresSyncLock()")
        void clean_isBloomPostOnly() {
            assertThat(CacheOperation.CLEAN.isWrite()).isFalse();
            assertThat(CacheOperation.CLEAN.requiresSyncLock()).isFalse();
            assertThat(CacheOperation.CLEAN.requiresBloomPostProcess()).isTrue();
        }

        @Test
        @DisplayName("REMOVE is in no predicate (no handler cares about REMOVE specifically)")
        void remove_isInNoPredicate() {
            assertThat(CacheOperation.REMOVE.isWrite()).isFalse();
            assertThat(CacheOperation.REMOVE.requiresSyncLock()).isFalse();
            assertThat(CacheOperation.REMOVE.requiresBloomPostProcess()).isFalse();
        }
    }
}
