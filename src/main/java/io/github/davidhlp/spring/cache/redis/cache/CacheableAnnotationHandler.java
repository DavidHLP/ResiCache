package io.github.davidhlp.spring.cache.redis.cache;







import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

/**
 * 处理 {@link RedisCacheable @RedisCacheable} 与 Spring {@link Cacheable @Cacheable} 注解，
 * 为其构建并注册 {@link RedisCacheableOperation}。
 *
 * <p>两条路径都收敛为统一的 {@link AbstractAnnotationHandler#registerOne} 模板：
 * <ul>
 *   <li>{@code @RedisCacheable} —— 走内联 lambda
 *       ({@code projector.from(a) + RedisCacheableOperation.fromAttributes});</li>
 *   <li>Spring {@code @Cacheable} —— 走 {@link SpringCacheableAdapter}。</li>
 * </ul>
 *
 * <p>两条路径都通过 {@link AbstractAnnotationHandler#registerActionFor(OperationKind)}
 * 工厂返回的 lambda 注册到 {@link OperationKind#CACHEABLE} 命名空间,kind 编译期固定。
 *
 * <p>ResiCache 路径以 lambda 直传 {@link RedisCacheAttributesProjector#from} +
 * {@link RedisCacheableOperation#fromAttributes}。{@link OperationFactory} 接口保留 ——
 * 本类的 ResiCache lambda 与 {@link SpringCacheableAdapter} 在 {@code doRegister}
 * 处仍是真多态分叉(两种 annotation 来源 → 同一种 operation)。
 *
 * <p>{@code doHandle} 主路径 = 1 行(select + map + register):
 * <ol>
 *   <li>{@link #selectCacheableSource(Method)} —— 决定走 ResiCache 源还是 Spring 源;
 *       package-private 暴露给单测,「哪种注解被选中」可零 mock 断言</li>
 *   <li>{@link #registerFromSource(Annotation, Method, Object, Object[])} —— 用
 *       选中的注解完成 registerOne + add-to-list 的 1-shot 动作;
 *       内部 instanceof 模式匹配决定走 ResiCache 还是 Spring factory + key 提取器</li>
 * </ol>
 * select / register 单一职责各自单测。
 */
@Slf4j
@Component
class CacheableAnnotationHandler extends AbstractAnnotationHandler {

    private final RedisCacheAttributesProjector projector;
    private final SpringCacheableAdapter springCacheableAdapterFactory;

    public CacheableAnnotationHandler(
            RedisCacheRegister redisCacheRegister,
            KeyGenerator keyGenerator,
            RedisCacheAttributesProjector projector,
            SpringCacheableAdapter springCacheableAdapterFactory) {
        super(redisCacheRegister, keyGenerator);
        this.projector = projector;
        this.springCacheableAdapterFactory = springCacheableAdapterFactory;
    }

    @Override
    protected boolean canHandle(Method method) {
        return selectCacheableSource(method).isPresent();
    }

    @Override
    protected List<CacheOperation> doHandle(Method method, Object target, Object[] args) {
        // select + map + register 三步走 —— 选中源、用源注册;
        // 未选中任一源(无注解)→ empty list。流程控制由 Optional.map 单形态承担。
        return selectCacheableSource(method)
                .map(annotation -> registerFromSource(annotation, method, target, args))
                .orElse(Collections.emptyList());
    }

    /**
     * 选定"此方法上要处理的 Cacheable 注解"的 deep seam.
     *
     * <p><b>职责</b>:在 method 上探测 {@link RedisCacheable} / Spring {@link Cacheable} 注解,
     * 返回首个命中的注解实例(无强类型,内部用 instanceof 区分)。
     * 选中规则:ResiCache 注解优先(带 TTL/布隆/早过期/sync.lock 等增强属性),未命中时
     * 回退 Spring 注解(基线缓存契约);两者不可共存,本方法最多返回 1 个注解。
     *
     * <p><b>为何返回 {@code Optional<Annotation>} 而非 {@code Optional<CacheableSource>}</b>:
     * Java 泛型通配符限制下,泛型 record {@code CacheableSource<A extends Annotation>} 在
     * {@code Optional<CacheableSource<?>>} 上下文中无法把"哪个具体 A"透传给依赖 A 的下游
     * 调用方(registerOne 推断 A/O 失败)。改用 {@code Optional<Annotation>} + 下游 instanceof
     * 模式匹配后,Java 21 pattern matching 在 instanceof 分支内恢复 A 的具体类型,registerOne
     * 调用点的 type inference 正常工作。本 seam 的"决策"价值未损失 —— 测试「哪种注解被选中」
     * 仍可零 mock 断言(只需断言返回的注解类型是 RedisCacheable / Cacheable / empty)。
     *
     * <p><b>package-private</b> — 暴露给单测,直接断言"哪种注解被选中"无需 mock factory/register,
     * 单测 locality 改善。
     *
     * <p><b>deletion test</b>:删本方法、内联回 doHandle → 2 行 findMergedAnnotation 调用
     * + 2 个 null-check 重新出现,主路径回到 15 行浅分叉。seam 挣得起存在代价。
     */
    Optional<Annotation> selectCacheableSource(Method method) {
        // 1) ResiCache 注解优先(带 ResiCache 增强属性,优先级最高)
        RedisCacheable resiCache = AnnotatedElementUtils.findMergedAnnotation(method, RedisCacheable.class);
        if (resiCache != null) {
            return Optional.of(resiCache);
        }
        // 2) 回退 Spring 注解(无增强属性,基线契约)
        Cacheable springCache = AnnotatedElementUtils.findMergedAnnotation(method, Cacheable.class);
        if (springCache != null) {
            return Optional.of(springCache);
        }
        // 3) 两者皆无 — 注解处理器链上其它 handler 处理,本 handler 跳过
        return Optional.empty();
    }

    /**
     * 用选中的注解完成 registerOne + add-to-list 1-shot 动作的执行 seam.
     *
     * <p><b>职责</b>:
     * <ol>
     *   <li>{@code instanceof} 模式匹配决定走 ResiCache 还是 Spring 路径(各自派生的
     *       factory + key 提取器 + 日志 tag 收口在单 case 分支内)</li>
     *   <li>调 {@link AbstractAnnotationHandler#registerOne} 完成 key 生成 + 工厂创建 + 注册</li>
     *   <li>把成功的 operation 装入 1-elem list 返回;registerOne 返回 null(异常隔离)→ empty list</li>
     * </ol>
     *
     * <p><b>private 而非 package-private</b>:本方法的测试入口已由 {@code doHandle} 集成测试
     * 覆盖(走完整 register 路径,断言 register 调用);单独单测无新增价值。{@code selectCacheableSource}
     * 保持 package-private 因其单测入口带来单测 locality 提升(零 mock)。
     *
     * <p><b>关于 instanceof 模式匹配</b>:Java 21 的 pattern matching for switch / instanceof
     * 在 ResiCache / Cacheable 分支内把 {@code annotation} 强转为具体类型,在该分支作用域内
     * 调用 {@code registerOne} 时,Java 编译期从强转结果类型 + factory 字段类型推导出
     * {@code A = ResiCacheable} / {@code A = Cacheable},无需 @SuppressWarnings。
     *
     * <p><b>deletion test</b>:删本方法、内联回 doHandle.map(...) → 5 行"key extract + registerOne
     * + Optional.ofNullable(...).map(List::of).orElse(empty)"样板 + 2 case 分支重新出现,
     * doHandle 退回到 15 行浅分叉。seam 挣得起存在代价。
     */
    private List<CacheOperation> registerFromSource(
            Annotation annotation, Method method, Object target, Object[] args) {
        if (annotation instanceof RedisCacheable resiCache) {
            return doRegister(resiCache,
                    (m, a, k) -> RedisCacheableOperation.fromAttributes(m, k, projector.from(a)),
                    RedisCacheable::key, "cacheable",
                    method, target, args);
        }
        if (annotation instanceof Cacheable springCache) {
            return doRegister(springCache, springCacheableAdapterFactory, Cacheable::key, "spring cacheable",
                    method, target, args);
        }
        // 防御性:selectCacheableSource 已保证只返回 RedisCacheable 或 Cacheable,理论不可达。
        log.warn("selectCacheableSource returned unexpected annotation type: {}",
                annotation.getClass().getName());
        return Collections.emptyList();
    }

    /**
     * 通用 registerOne 包装 — registerFromSource 的实际注册 seam.
     *
     * <p>职责:用 keyExtractor 派生 key 表达式 → 调 {@link AbstractAnnotationHandler#registerOne}
     * → 成功装 1-elem list / 失败 empty list。3 个调用方(ResiCache / Spring / 未来新增的
     * ResiCache 路径)共享同一实现,新增路径只需 1 个 instanceof case + 1 行 doRegister 调用。
     */
    private <A extends Annotation> List<CacheOperation> doRegister(
            A annotation,
            OperationFactory<A, RedisCacheableOperation> factory,
            Function<A, String> keyExtractor,
            String logTag,
            Method method, Object target, Object[] args) {
        String keyExpression = keyExtractor.apply(annotation);
        RedisCacheableOperation operation = registerOne(
                method, target, args, annotation, keyExpression,
                factory, registerActionFor(OperationKind.CACHEABLE), logTag);
        return operation != null ? List.of(operation) : Collections.emptyList();
    }
}
