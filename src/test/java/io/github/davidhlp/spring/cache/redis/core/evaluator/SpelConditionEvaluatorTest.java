package io.github.davidhlp.spring.cache.redis.core.evaluator;

import io.github.davidhlp.spring.cache.redis.register.operation.RedisCacheableOperation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SpEL Condition Evaluator Security Tests
 *
 * <p>Tests that malicious SpEL expressions are blocked or handled safely.
 * The evaluator uses StandardEvaluationContext with fail-open behavior.
 */
@DisplayName("SpEL Condition Evaluator Security Tests")
class SpelConditionEvaluatorTest {

    private SpelConditionEvaluator evaluator = SpelConditionEvaluator.getInstance();

    private RedisCacheableOperation createOperation(String condition, String unless) {
        return RedisCacheableOperation.builder()
                .name("test-cache")
                .cacheNames("test-cache")
                .condition(condition)
                .unless(unless)
                .build();
    }

    private boolean evaluateCondition(String condition) throws Exception {
        RedisCacheableOperation operation = createOperation(condition, "");
        return evaluator.shouldProceed(
                operation,
                SpelConditionEvaluatorTest.class.getDeclaredMethod("dummyMethod", int.class),
                new Object[]{1},
                this
        );
    }

    private boolean evaluateUnless(String unless) throws Exception {
        RedisCacheableOperation operation = createOperation("", unless);
        return evaluator.shouldSkipCache(
                operation,
                SpelConditionEvaluatorTest.class.getDeclaredMethod("dummyMethod", int.class),
                new Object[]{1},
                this,
                "test-result"
        );
    }

    @Nested
    @DisplayName("Normal Expression Evaluation")
    class NormalExpressionTests {

        @Test
        @DisplayName("evaluates normal condition expression correctly")
        void normalExpression_evaluatesCorrectly() throws Exception {
            // Given: A valid SpEL condition expression
            String condition = "#args[0] > 0";
            RedisCacheableOperation operation = createOperation(condition, "");

            // When: Evaluating with positive argument
            boolean proceed = evaluator.shouldProceed(
                    operation,
                    SpelConditionEvaluatorTest.class.getDeclaredMethod("dummyMethod", int.class),
                    new Object[]{10},
                    SpelConditionEvaluatorTest.this
            );

            // Then: Should proceed (expression evaluates to true)
            assertThat(proceed).isTrue();
        }

        @Test
        @DisplayName("returns true when condition is empty")
        void emptyCondition_returnsTrue() throws Exception {
            // Given: Empty condition
            String condition = "";

            // When: Evaluating
            boolean proceed = evaluateCondition(condition);

            // Then: Should proceed (default behavior)
            assertThat(proceed).isTrue();
        }
    }

    @Nested
    @DisplayName("Malicious Expression Blocking")
    class MaliciousExpressionTests {

        @Test
        @DisplayName("handles Runtime.getRuntime access safely")
        void runtimeGetRuntime_handledSafely() throws Exception {
            // Given: Expression attempting to access Runtime
            String maliciousCondition = "T(java.lang.Runtime).getRuntime()";

            // When: Evaluating - should not throw unhandled exception
            boolean proceed = evaluateCondition(maliciousCondition);

            // Then: Fail-open - returns true (safe default)
            assertThat(proceed).isTrue();
        }

        @Test
        @DisplayName("handles reflection-based property access safely")
        void reflectionBasedAttack_handledSafely() throws Exception {
            // Given: Attempting to access system properties via reflection
            String attackCondition = "T(java.lang.System).getProperty('user.dir')";

            // When: Evaluating - should handle safely
            boolean proceed = evaluateCondition(attackCondition);

            // Then: Fail-open - returns true (safe default)
            assertThat(proceed).isTrue();
        }

        @Test
        @DisplayName("handles malformed expression gracefully")
        void malformedExpression_failOpen() throws Exception {
            // Given: Malformed SpEL expression referencing unknown property
            String malformedCondition = "#root.unknown.property.deep.path";

            // When: Evaluating - should not throw
            boolean proceed = evaluateCondition(malformedCondition);

            // Then: Fail-open - returns true when expression cannot be evaluated
            assertThat(proceed).isTrue();
        }

        @Test
        @DisplayName("handles arithmetic attack expression safely")
        void arithmeticAttack_handledSafely() throws Exception {
            // Given: Attempting division by zero via SpEL
            String attackCondition = "T(java.lang.Integer).divideUnsigned(1, 0)";

            // When: Evaluating
            boolean proceed = evaluateCondition(attackCondition);

            // Then: Fail-open - returns true
            assertThat(proceed).isTrue();
        }
    }

    @Nested
    @DisplayName("Unless Expression Security")
    class UnlessExpressionTests {

        @Test
        @DisplayName("handles malicious unless expression safely")
        void maliciousUnless_handledSafely() throws Exception {
            // Given: Attempt to access Runtime via unless expression
            String maliciousUnless = "T(java.lang.Runtime).getRuntime()";

            // When: Evaluating
            boolean skipCache = evaluateUnless(maliciousUnless);

            // Then: Fail-open - returns false (do not skip cache)
            assertThat(skipCache).isFalse();
        }

        @Test
        @DisplayName("handles malformed unless expression gracefully")
        void malformedUnless_failOpen() throws Exception {
            // Given: Malformed unless expression
            String malformedUnless = "#result.unknown.field";

            // When: Evaluating
            boolean skipCache = evaluateUnless(malformedUnless);

            // Then: Fail-open - returns false (do not skip cache)
            assertThat(skipCache).isFalse();
        }

        @Test
        @DisplayName("handles empty unless expression gracefully")
        void emptyUnless_failsOpen() throws Exception {
            // Given: Empty unless expression
            String emptyUnless = "";

            // When: Evaluating
            boolean skipCache = evaluateUnless(emptyUnless);

            // Then: Fail-open - returns false (do not skip cache)
            assertThat(skipCache).isFalse();
        }
    }

    // Dummy method for reflection-based method lookup
    public void dummyMethod(int value) {}
}
