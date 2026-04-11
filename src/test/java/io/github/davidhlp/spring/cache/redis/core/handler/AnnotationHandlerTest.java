package io.github.davidhlp.spring.cache.redis.core.handler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for AnnotationHandler abstract class.
 * Since AnnotationHandler is abstract, we test it via a concrete implementation.
 */
@DisplayName("AnnotationHandler Tests")
class AnnotationHandlerTest {

    /**
     * Concrete implementation of AnnotationHandler for testing purposes.
     */
    private static class TestAnnotationHandler extends AnnotationHandler {
        private boolean canHandleCalled = false;
        private boolean doHandleCalled = false;
        private boolean shouldHandle = false;
        private final Method handledMethod;

        TestAnnotationHandler(Method handledMethod) {
            this.handledMethod = handledMethod;
        }

        @Override
        protected boolean canHandle(Method method) {
            canHandleCalled = true;
            return shouldHandle;
        }

        @Override
        protected void doHandle(Method method, Object target, Object[] args) {
            doHandleCalled = true;
        }

        public boolean wasCanHandleCalled() {
            return canHandleCalled;
        }

        public boolean wasDoHandleCalled() {
            return doHandleCalled;
        }

        public void resetFlags() {
            canHandleCalled = false;
            doHandleCalled = false;
        }
    }

    private Method getMethod(String name) throws NoSuchMethodException {
        return TestClass.class.getMethod(name);
    }

    // Test class with methods for reflection testing
    private static class TestClass {
        public void noAnnotation() {
        }

        public void withAnnotation() {
        }
    }

    @Nested
    @DisplayName("setNext() Tests")
    class SetNextTests {

        @Test
        @DisplayName("setNext returns the next handler for chaining")
        void setNext_returnsNextHandler_forChaining() throws NoSuchMethodException {
            AnnotationHandler first = new TestAnnotationHandler(getMethod("noAnnotation"));
            AnnotationHandler second = new TestAnnotationHandler(getMethod("noAnnotation"));

            AnnotationHandler result = first.setNext(second);

            assertThat(result).isSameAs(second);
        }
    }

    @Nested
    @DisplayName("handle() Tests")
    class HandleTests {

        @Test
        @DisplayName("handle calls canHandle and doHandle when canHandle returns true")
        void handle_whenCanHandleReturnsTrue_callsBothMethods() throws NoSuchMethodException {
            TestAnnotationHandler handler = new TestAnnotationHandler(getMethod("noAnnotation"));
            handler.shouldHandle = true;
            Object target = new Object();
            Object[] args = new Object[0];

            handler.handle(getMethod("noAnnotation"), target, args);

            assertThat(handler.wasCanHandleCalled()).isTrue();
            assertThat(handler.wasDoHandleCalled()).isTrue();
        }

        @Test
        @DisplayName("handle only calls canHandle when canHandle returns false")
        void handle_whenCanHandleReturnsFalse_callsOnlyCanHandle() throws NoSuchMethodException {
            TestAnnotationHandler handler = new TestAnnotationHandler(getMethod("noAnnotation"));
            handler.shouldHandle = false;
            Object target = new Object();
            Object[] args = new Object[0];

            handler.handle(getMethod("noAnnotation"), target, args);

            assertThat(handler.wasCanHandleCalled()).isTrue();
            assertThat(handler.wasDoHandleCalled()).isFalse();
        }

        @Test
        @DisplayName("handle delegates to next handler when next is set")
        void handle_withNextSet_delegatesToNext() throws NoSuchMethodException {
            TestAnnotationHandler first = new TestAnnotationHandler(getMethod("noAnnotation"));
            TestAnnotationHandler second = new TestAnnotationHandler(getMethod("noAnnotation"));
            first.setNext(second);
            first.shouldHandle = false;
            second.shouldHandle = true;

            first.handle(getMethod("noAnnotation"), new Object(), new Object[0]);

            assertThat(second.wasCanHandleCalled()).isTrue();
        }

        @Test
        @DisplayName("handle does not throw when next is null")
        void handle_withNullNext_doesNotThrow() throws NoSuchMethodException {
            TestAnnotationHandler handler = new TestAnnotationHandler(getMethod("noAnnotation"));
            handler.shouldHandle = true;

            handler.handle(getMethod("noAnnotation"), new Object(), new Object[0]);

            assertThat(handler.wasDoHandleCalled()).isTrue();
        }
    }

    @Nested
    @DisplayName("Chain Behavior Tests")
    class ChainBehaviorTests {

        @Test
        @DisplayName("handlers are invoked in chain order")
        void handle_chainsInOrder() throws NoSuchMethodException {
            TestAnnotationHandler first = new TestAnnotationHandler(getMethod("noAnnotation"));
            TestAnnotationHandler second = new TestAnnotationHandler(getMethod("noAnnotation"));
            TestAnnotationHandler third = new TestAnnotationHandler(getMethod("noAnnotation"));
            first.setNext(second).setNext(third);

            first.handle(getMethod("noAnnotation"), new Object(), new Object[0]);

            assertThat(first.wasCanHandleCalled()).isTrue();
            assertThat(second.wasCanHandleCalled()).isTrue();
            assertThat(third.wasCanHandleCalled()).isTrue();
        }

        @Test
        @DisplayName("all handlers in chain get called even if one handles")
        void handle_allHandlersCalled_evenWhenOneHandles() throws NoSuchMethodException {
            TestAnnotationHandler first = new TestAnnotationHandler(getMethod("noAnnotation"));
            TestAnnotationHandler second = new TestAnnotationHandler(getMethod("noAnnotation"));
            first.setNext(second);
            first.shouldHandle = true;
            second.shouldHandle = true;

            first.handle(getMethod("noAnnotation"), new Object(), new Object[0]);

            assertThat(first.wasDoHandleCalled()).isTrue();
            assertThat(second.wasCanHandleCalled()).isTrue();
        }
    }
}
