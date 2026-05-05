package io.github.davidhlp.spring.cache.redis.core.evaluator;

import io.github.davidhlp.spring.cache.redis.register.operation.RedisCacheableOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.ParseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SpEL Condition Evaluator Security Tests
 *
 * <p>Tests that malicious SpEL expressions are blocked or handled safely.
 * The evaluator uses StandardEvaluationContext with fail-open behavior.
 */
@DisplayName("SpEL Condition Evaluator Security Tests")
class SpelConditionEvaluatorTest {

    private SpelConditionEvaluator evaluator = SpelConditionEvaluator.getInstance();

    @BeforeEach
    void setUp() {
        // Reset to default for each test to avoid singleton state pollution
        evaluator.setFailOnSpelError(true);
    }

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
            String condition = "#root.target != null";
            RedisCacheableOperation operation = createOperation(condition, "");

            // When: Evaluating with non-null target
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
    @DisplayName("SpEL Syntax Error Handling (Configuration Errors)")
    class SyntaxErrorTests {

        @Test
        @DisplayName("syntax error always throws ParseException regardless of failOnSpelError")
        void syntaxError_alwaysThrows() throws Exception {
            // Given: Invalid SpEL syntax (unclosed string)
            String invalidSyntax = "'unclosed string";

            // When/Then: Should throw ParseException
            assertThatThrownBy(() -> evaluateCondition(invalidSyntax))
                    .isInstanceOf(ParseException.class);
        }

        @Test
        @DisplayName("syntax error with failOnSpelError=false still throws")
        void syntaxError_failOnSpelErrorFalse_stillThrows() throws Exception {
            // Given: Invalid SpEL syntax and failOnSpelError=false
            evaluator.setFailOnSpelError(false);
            String invalidSyntax = "'unclosed string";

            // When/Then: Should still throw ParseException (config errors always throw)
            assertThatThrownBy(() -> evaluateCondition(invalidSyntax))
                    .isInstanceOf(ParseException.class);
        }
    }

    @Nested
    @DisplayName("Runtime Error Handling with failOnSpelError=true")
    class RuntimeErrorFailOnErrorTests {

        @Test
        @DisplayName("runtime error throws EvaluationException when failOnSpelError=true")
        void runtimeError_throwsWhenFailOnSpelErrorTrue() throws Exception {
            // Given: Expression that fails at runtime (unknown property) and failOnSpelError=true
            String runtimeErrorCondition = "#root.unknown.property.deep.path";

            // When/Then: Should throw EvaluationException
            assertThatThrownBy(() -> evaluateCondition(runtimeErrorCondition))
                    .isInstanceOf(EvaluationException.class);
        }

        @Test
        @DisplayName("malicious expression throws when failOnSpelError=true")
        void maliciousExpression_throwsWhenFailOnSpelErrorTrue() throws Exception {
            // Given: Expression attempting to access Runtime with failOnSpelError=true
            String maliciousCondition = "T(java.lang.Runtime).getRuntime()";

            // When/Then: Should throw EvaluationException
            assertThatThrownBy(() -> evaluateCondition(maliciousCondition))
                    .isInstanceOf(EvaluationException.class);
        }
    }

    @Nested
    @DisplayName("Runtime Error Handling with failOnSpelError=false")
    class RuntimeErrorFailOpenTests {

        @BeforeEach
        void setUp() {
            evaluator.setFailOnSpelError(false);
        }

        @Test
        @DisplayName("runtime error returns default true for condition when failOnSpelError=false")
        void runtimeError_returnsDefaultTrue() throws Exception {
            // Given: Expression that fails at runtime and failOnSpelError=false
            String runtimeErrorCondition = "#root.unknown.property.deep.path";

            // When: Evaluating
            boolean proceed = evaluateCondition(runtimeErrorCondition);

            // Then: Returns safe default (true - proceed with cache)
            assertThat(proceed).isTrue();
        }

        @Test
        @DisplayName("runtime error returns default false for unless when failOnSpelError=false")
        void runtimeErrorUnless_returnsDefaultFalse() throws Exception {
            // Given: Expression that fails at runtime and failOnSpelError=false
            String runtimeErrorUnless = "#result.unknown.field";

            // When: Evaluating
            boolean skipCache = evaluateUnless(runtimeErrorUnless);

            // Then: Returns safe default (false - do not skip cache)
            assertThat(skipCache).isFalse();
        }

        @Test
        @DisplayName("malicious expression returns default true when failOnSpelError=false")
        void maliciousExpression_returnsDefault() throws Exception {
            // Given: Expression attempting to access Runtime with failOnSpelError=false
            String maliciousCondition = "T(java.lang.Runtime).getRuntime()";

            // When: Evaluating
            boolean proceed = evaluateCondition(maliciousCondition);

            // Then: Fail-open - returns true (safe default)
            assertThat(proceed).isTrue();
        }

        @Test
        @DisplayName("reflection-based access returns default when failOnSpelError=false")
        void reflectionBasedAttack_returnsDefault() throws Exception {
            // Given: Attempting to access system properties with failOnSpelError=false
            String attackCondition = "T(java.lang.System).getProperty('user.dir')";

            // When: Evaluating
            boolean proceed = evaluateCondition(attackCondition);

            // Then: Fail-open - returns true (safe default)
            assertThat(proceed).isTrue();
        }
    }

    @Nested
    @DisplayName("Unless Expression Handling")
    class UnlessExpressionTests {

        @Test
        @DisplayName("malicious unless expression throws when failOnSpelError=true")
        void maliciousUnless_throwsWhenFailOnSpelErrorTrue() throws Exception {
            // Given: Attempt to access Runtime via unless expression
            String maliciousUnless = "T(java.lang.Runtime).getRuntime()";

            // When/Then: Should throw EvaluationException
            assertThatThrownBy(() -> evaluateUnless(maliciousUnless))
                    .isInstanceOf(EvaluationException.class);
        }

        @Test
        @DisplayName("malicious unless expression returns default when failOnSpelError=false")
        void maliciousUnless_returnsDefaultWhenFailOnSpelErrorFalse() throws Exception {
            // Given: Attempt to access Runtime with failOnSpelError=false
            evaluator.setFailOnSpelError(false);
            String maliciousUnless = "T(java.lang.Runtime).getRuntime()";

            // When: Evaluating
            boolean skipCache = evaluateUnless(maliciousUnless);

            // Then: Fail-open - returns false (do not skip cache)
            assertThat(skipCache).isFalse();
        }

        @Test
        @DisplayName("empty unless expression returns false")
        void emptyUnless_returnsFalse() throws Exception {
            // Given: Empty unless expression
            String emptyUnless = "";

            // When: Evaluating
            boolean skipCache = evaluateUnless(emptyUnless);

            // Then: Returns false (do not skip cache)
            assertThat(skipCache).isFalse();
        }
    }

    // Dummy method for reflection-based method lookup
    public void dummyMethod(int value) {}
}
