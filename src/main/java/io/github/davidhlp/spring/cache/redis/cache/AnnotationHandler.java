package io.github.davidhlp.spring.cache.redis.cache;





import java.lang.reflect.Method;
import java.util.List;
import org.springframework.cache.interceptor.CacheOperation;

/**
 * 注解处理器抽象节点.
 *
 * <p>链推进 + handler 遍历 + 结果收集由 {@link AnnotationChainEngine} 承担。
 * 本抽象类只保留两个钩子:
 *
 * <ul>
 *   <li>{@link #canHandle(Method)} — 本 handler 是否能处理该方法(命中则调 doHandle)</li>
 *   <li>{@link #doHandle(Method, Object, Object[])} — 实际解析逻辑,返回 0+ 个
 *       {@link CacheOperation}</li>
 * </ul>
 *
 * <p><b>链结构</b>:链结构由 Engine 持有的 List 维护
 * (Spring 启动期一次性注入,运行期无变更),本类不持有 next 指针。
 *
 * <p><b>失败隔离</b>:Engine 调用 {@code doHandle} 时自带 per-handler try/catch,
 * handler 异常不会污染其他 handler 也不阻塞拦截器链(详见
 * {@link AnnotationChainEngine#execute(Method, Object, Object[])}),符合"单个注解
 * 解析失败不应中断整个缓存链路"的本意。
 */
abstract class AnnotationHandler {

    /**
     * 判断当前处理器是否能处理该方法.
     *
     * <p>Engine 在遍历链时对每个 handler 调本方法;返回 true 则调
     * {@link #doHandle(Method, Object, Object[])} 收集结果。
     *
     * @param method 当前解析的目标方法
     * @return true 表示本 handler 可处理(会接着调 doHandle),false 表示跳过
     */
    protected abstract boolean canHandle(Method method);

    /**
     * 执行具体的注解解析与操作注册逻辑.
     *
     * <p>本方法由 {@link AnnotationChainEngine} 在 {@code canHandle} 命中后调用。
     * 实现要点:
     *
     * <ul>
     *   <li>应返回 0+ 个 {@link CacheOperation};返回 null 或空 list 等价</li>
     *   <li>内部异常<strong>无需</strong>捕获(Engine 已在 per-handler 粒度 try/catch);
     *       但本类仍鼓励使用 {@link AbstractAnnotationHandler#registerOne} 模板,
     *       以"单个注解解析失败不得注册 0 个 op 但其他 op 继续"的更细粒度 try/catch</li>
     *   <li>不应对返回 list 做防御性拷贝(Engine 已在收集时把 list 包装为
     *       unmodifiableList,避免复制开销)</li>
     * </ul>
     *
     * @param method 当前解析的目标方法
     * @param target 方法所属的目标对象
     * @param args 方法参数
     * @return 解析得到的缓存操作列表(可能为空,可能为 null — Engine 都按空 list 处理)
     */
    protected abstract List<CacheOperation> doHandle(Method method, Object target, Object[] args);
}
