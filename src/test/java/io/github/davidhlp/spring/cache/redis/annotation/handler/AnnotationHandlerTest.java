package io.github.davidhlp.spring.cache.redis.annotation.handler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.cache.interceptor.CacheOperation;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link AnnotationHandler} 抽象节点 — ADR-0013 后的精简形态.
 *
 * <p>原 {@code AnnotationHandlerTest} 覆盖了已删除的 {@code setNext} / {@code handle}
 * 递归逻辑(链推进已迁出至 {@link AnnotationChainEngine});本测试聚焦抽象节点的
 * 两个钩子契约:
 *
 * <ul>
 *   <li>{@link AnnotationHandler#canHandle(Method)} — 判定接口</li>
 *   <li>{@link AnnotationHandler#doHandle(Method, Object, Object[])} — 处理接口</li>
 * </ul>
 *
 * <p>链推进 / 失败隔离 / 观测编排契约在 {@code AnnotationChainEngineTest} 中独立验证。
 */
@DisplayName("AnnotationHandler Tests")
class AnnotationHandlerTest {

    /**
     * Concrete implementation of AnnotationHandler for testing the two-hook contract.
     */
    private static class TestAnnotationHandler extends AnnotationHandler {
        private boolean canHandleCalled = false;
        private boolean doHandleCalled = false;
        private boolean shouldHandle = false;
        private final Method handledMethod;
        private List<CacheOperation> doHandleResult = Collections.emptyList();

        TestAnnotationHandler(Method handledMethod) {
            this.handledMethod = handledMethod;
        }

        @Override
        protected boolean canHandle(Method method) {
            canHandleCalled = true;
            return shouldHandle;
        }

        @Override
        protected List<CacheOperation> doHandle(Method method, Object target, Object[] args) {
            doHandleCalled = true;
            return doHandleResult;
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

        public void setDoHandleResult(List<CacheOperation> result) {
            this.doHandleResult = result;
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
    @DisplayName("Hook Contract Tests")
    class HookContractTests {

        @Test
        @DisplayName("canHandle returns the configured boolean")
        void canHandle_returnsConfiguredBoolean() throws NoSuchMethodException {
            TestAnnotationHandler handler = new TestAnnotationHandler(getMethod("noAnnotation"));
            handler.shouldHandle = true;

            assertThat(handler.canHandle(getMethod("noAnnotation"))).isTrue();

            handler.shouldHandle = false;

            assertThat(handler.canHandle(getMethod("noAnnotation"))).isFalse();
        }

        @Test
        @DisplayName("canHandle marks the call flag")
        void canHandle_marksCallFlag() throws NoSuchMethodException {
            TestAnnotationHandler handler = new TestAnnotationHandler(getMethod("noAnnotation"));

            handler.canHandle(getMethod("noAnnotation"));

            assertThat(handler.wasCanHandleCalled()).isTrue();
        }

        @Test
        @DisplayName("doHandle returns the configured result list")
        void doHandle_returnsConfiguredResult() throws NoSuchMethodException {
            TestAnnotationHandler handler = new TestAnnotationHandler(getMethod("noAnnotation"));
            handler.setDoHandleResult(List.of(mock(CacheOperation.class)));

            List<CacheOperation> result =
                    handler.doHandle(getMethod("noAnnotation"), new Object(), new Object[0]);

            assertThat(result).hasSize(1);
            assertThat(handler.wasDoHandleCalled()).isTrue();
        }

        @Test
        @DisplayName("doHandle returns empty list by default")
        void doHandle_returnsEmptyByDefault() throws NoSuchMethodException {
            TestAnnotationHandler handler = new TestAnnotationHandler(getMethod("noAnnotation"));

            List<CacheOperation> result =
                    handler.doHandle(getMethod("noAnnotation"), new Object(), new Object[0]);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("Inheritance / API surface Tests")
    class InheritanceTests {

        @Test
        @DisplayName("AnnotationHandler is abstract — cannot be instantiated directly")
        void annotationHandler_isAbstract() {
            // 通过反射验证 abstract modifier
            assertThat(java.lang.reflect.Modifier.isAbstract(
                    AnnotationHandler.class.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("canHandle and doHandle are abstract (must be implemented by subclass)")
        void hooksAreAbstract() throws NoSuchMethodException {
            assertThat(java.lang.reflect.Modifier.isAbstract(
                    AnnotationHandler.class.getDeclaredMethod("canHandle", Method.class)
                            .getModifiers())).isTrue();
            assertThat(java.lang.reflect.Modifier.isAbstract(
                    AnnotationHandler.class.getDeclaredMethod("doHandle", Method.class, Object.class, Object[].class)
                            .getModifiers())).isTrue();
        }

        @Test
        @DisplayName("next field and setNext method are removed (chain migrated to engine)")
        void nextFieldAndSetNextRemoved() {
            // 验证 next 字段不存在(原 chain 拓扑已迁移到 AnnotationChainEngine)
            assertThatThrownBy(() -> AnnotationHandler.class.getDeclaredField("next"))
                    .isInstanceOf(NoSuchFieldException.class);
        }
    }

    @Nested
    @DisplayName("Edge Case Tests")
    class EdgeCaseTests {

        @Test
        @DisplayName("doHandle can return null (Engine treats null as empty list)")
        void doHandle_canReturnNull() throws NoSuchMethodException {
            TestAnnotationHandler handler = new TestAnnotationHandler(getMethod("noAnnotation")) {
                @Override
                public List<CacheOperation> doHandle(Method method, Object target, Object[] args) {
                    return null;
                }
            };

            List<CacheOperation> result =
                    handler.doHandle(getMethod("noAnnotation"), new Object(), new Object[0]);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("doHandle can return mutable list (Engine wraps in unmodifiable)")
        void doHandle_canReturnMutableList() throws NoSuchMethodException {
            TestAnnotationHandler handler = new TestAnnotationHandler(getMethod("noAnnotation"));
            List<CacheOperation> mutable = new ArrayList<>();
            mutable.add(mock(CacheOperation.class));
            handler.setDoHandleResult(mutable);

            List<CacheOperation> result =
                    handler.doHandle(getMethod("noAnnotation"), new Object(), new Object[0]);

            // 返回的引用就是子类的 mutable list(Engine 在收集时做 unmodifiable 包装,不在子类做)
            assertThat(result).isSameAs(mutable);
        }
    }
}
