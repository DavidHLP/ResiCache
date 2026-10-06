package io.github.davidhlp.spring.cache.redis.cache;

import java.lang.reflect.Method;
import org.springframework.context.expression.AnnotatedElementKey;

/** Owns typed method snapshots; nested and worker activations restore prior state. */
class DefaultMethodMetadataResolver implements MethodMetadataResolver {
    private static final ThreadLocal<MethodSnapshot> CURRENT = new ThreadLocal<>();

    @Override
    public AnnotatedElementKey currentKey() {
        MethodSnapshot snapshot = CURRENT.get();
        return snapshot == null ? null : snapshot.annotatedElementKey();
    }

    @Override
    public Method currentMethod() {
        MethodSnapshot snapshot = CURRENT.get();
        return snapshot == null ? null : snapshot.method();
    }

    @Override
    public Class<?> currentTargetClass() {
        MethodSnapshot snapshot = CURRENT.get();
        return snapshot == null ? null : snapshot.targetClass();
    }

    @Override
    public MethodSnapshot capture() {
        return CURRENT.get();
    }

    @Override
    public ScopedActivation activate(Method method, Class<?> targetClass) {
        MethodSnapshot previous = CURRENT.get();
        CURRENT.set(MethodSnapshot.of(method, targetClass));
        return new ScopedActivation(() -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        });
    }
}
