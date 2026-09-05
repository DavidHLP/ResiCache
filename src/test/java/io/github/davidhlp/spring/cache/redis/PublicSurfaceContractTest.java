package io.github.davidhlp.spring.cache.redis;





import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Public-surface allowlist Gate(ADR-04)。
 *
 * <p>编译后反射枚举 {@code io.github.davidhlp.spring.cache.redis} 下所有 public 顶层类型,
 * 与审定 allowlist({@code src/test/resources/allowlist/public-surface.txt})精确比较。
 *
 * <p>规则:
 * <ul>
 *   <li>allowlist 中不存在 → 新增 public 类型必须显式加入 allowlist(并说明为何属于
 *       稳定用户/扩展面或 operator 入口);否则内化为 package-private</li>
 *   <li>allowlist 中列出但当前不存在 → 类型已删除/改名,须同步 allowlist(红→提示清理)</li>
 *   <li>{@code $} nested 类与 {@code package-info} 过滤(不视为独立顶层 public 类型)</li>
 * </ul>
 *
 * <p>目标分类见 docs/plans/resicache-framework-hardening-plan.md Appendix A:
 * KEEP-STABLE(用户/扩展 Interface 与最小传递值)/ KEEP-ENTRY(自动配置与 operator 入口)。
 * 本 Gate 是「公开面收敛」的机器真相 — 文档分类不替代编译可见性。
 */
@DisplayName("Public Surface Allowlist Gate")
class PublicSurfaceContractTest {

    private static final String ROOT_PACKAGE = "io.github.davidhlp.spring.cache.redis";
    private static final String ALLOWLIST_RESOURCE = "/allowlist/public-surface.txt";
    private static final String NESTED_ALLOWLIST_RESOURCE =
            "/allowlist/public-surface-nested.txt";
    private static final String IN_PROGRESS_RESOURCE = "/allowlist/internalize-in-progress.txt";

    @Test
    @DisplayName("every public top-level type is allowlisted or in-progress internalization")
    void everyPublicType_isAllowlisted() throws Exception {
        Set<String> actual = scanPublicTopLevelTypes();
        Set<String> accounted = readList(ALLOWLIST_RESOURCE);
        accounted.addAll(readList(IN_PROGRESS_RESOURCE));

        Set<String> unlisted = new TreeSet<>(actual);
        unlisted.removeAll(accounted);

        assertThat(unlisted)
                .as("以下 public 顶层类型既不在 allowlist 也不在 internalize-in-progress 中 — "
                        + "新增 public 类型必须显式加入 %s(稳定用户/扩展面或 operator 入口),"
                        + "或加入 %s(计划内化、由 P1-API-001-B/D 收敛)。",
                        ALLOWLIST_RESOURCE, IN_PROGRESS_RESOURCE)
                .isEmpty();
    }

    @Test
    @DisplayName("allowlist/in-progress entries still exist (no stale lines)")
    void everyAllowlistEntry_stillExists() throws Exception {
        Set<String> actual = scanPublicTopLevelTypes();
        Set<String> allowlisted = readList(ALLOWLIST_RESOURCE);
        allowlisted.addAll(readList(IN_PROGRESS_RESOURCE));

        Set<String> stale = new TreeSet<>(allowlisted);
        stale.removeAll(actual);

        assertThat(stale)
                .as("以下条目对应的类型已不存在(删除/改名/内化完成) — 请从 allowlist 或 "
                        + "internalize-in-progress 中移除")
                .isEmpty();
    }

    @Test
    @DisplayName("internalize-in-progress is shrinking toward empty (B/D 收敛进度)")
    void internalizeInProgress_isAuthoritative() throws Exception {
        Set<String> actual = scanPublicTopLevelTypes();
        Set<String> inProgress = readList(IN_PROGRESS_RESOURCE);

        Set<String> completed = new TreeSet<>(inProgress);
        completed.removeAll(actual);

        assertThat(completed)
                .as("以下条目已不在 public surface(内化完成) — 请从 internalize-in-progress.txt 移除,"
                        + "保持清单即当前真实待办")
                .isEmpty();
    }

    /**
     * Phase-4 完成检查:internalize-in-progress 必须为空,即 public surface 与
     * allowlist 精确相等。
     *
     * <p>P1-API-001-B/D 全部落地前本测试保持红(B–D 收敛中);内化完成、从
     * {@code internalize-in-progress.txt} 删除全部条目后自动转绿。
     */
    @Test
    @DisplayName("Phase-4 completion: public surface exactly equals allowlist")
    void currentSurface_matchesAllowlistExactly() throws Exception {
        Set<String> actual = scanPublicTopLevelTypes();
        Set<String> allowlisted = readList(ALLOWLIST_RESOURCE);

        if (!actual.equals(allowlisted)) {
            Set<String> unlisted = new TreeSet<>(actual);
            unlisted.removeAll(allowlisted);
            Set<String> stale = new TreeSet<>(allowlisted);
            stale.removeAll(actual);
            fail("Public surface drift (Phase-4 not complete):\n"
                    + "  NOT-ALLOWLISTED (" + unlisted.size() + "): " + unlisted + "\n"
                    + "  STALE-ALLOWLIST (" + stale.size() + "): " + stale + "\n"
                    + "  current total=" + actual.size()
                    + ", allowlisted total=" + allowlisted.size());
        }
    }

    @Test
    @DisplayName("every public nested type is allowlisted (RM-007 nested inventory)")
    void everyPublicNestedType_isAllowlisted() throws Exception {
        Set<String> compiled = scanPublicNestedTypes();
        Set<String> allowlist = readList(NESTED_ALLOWLIST_RESOURCE);
        assertThat(compiled)
                .as("public nested types must match the classified manifest; "
                        + "new public nested types need classification in STABILITY.md")
                .isEqualTo(allowlist);
    }

    /**
     * 扫描 public 嵌套类型(RM-007):与顶层扫描同源,仅不过滤 {@code $} 文件;
     * 名格式 {@code pkg.Outer$Nested}。
     */
    private static Set<String> scanPublicNestedTypes() throws Exception {
        Set<String> result = new TreeSet<>();
        List<Path> classRoots = classpathRoots();
        for (Path root : classRoots) {
            Path packageDir = root.resolve(ROOT_PACKAGE.replace('.', '/'));
            if (!Files.isDirectory(packageDir)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(packageDir)) {
                walk.filter(p -> p.toString().endsWith(".class"))
                        .filter(p -> p.getFileName().toString().contains("$"))
                        .filter(p -> !p.getFileName().toString().matches(".*\\$\\d+\\.class"))
                        .forEach(p -> {
                            String relative = root.relativize(p).toString();
                            String className = relative.substring(0, relative.length() - 6)
                                    .replace('/', '.');
                            try {
                                if (isPublicClassFile(p)) {
                                    result.add(className.substring(
                                            ROOT_PACKAGE.length() + 1));
                                }
                            } catch (IOException e) {
                                throw new RuntimeException("Failed reading " + p, e);
                            }
                        });
            }
        }
        return result;
    }

    /**
     * 从 classpath 扫描 public 顶层类型(不触发类初始化)。
     *
     * <p>基于 target/classes 的目录扫描 + 文件系统 + 反斜杠类名判定 public:
     * 直接读 {@code .class} 字节中的 access_flags 太底层;此处改用
     * {@link Class#forName} 会初始化静态块,故用 classpath 目录列举 + 文件名过滤,
     * public 判定委托给 allowlist 语义:凡能出现在编译产物顶层目录的即视为类型,
     * 用 {@code Modifier} 反射需要类加载 — 不可取。实际 public 判定改为:任何非
     * {@code package-info}、非 {@code $} 嵌套的编译产物顶层类型,若其类文件位于
     * access_flags:public=0x0001)。
     */
    private static Set<String> scanPublicTopLevelTypes() throws Exception {
        Set<String> result = new TreeSet<>();
        List<Path> classRoots = classpathRoots();
        for (Path root : classRoots) {
            Path packageDir = root.resolve(ROOT_PACKAGE.replace('.', '/'));
            if (!Files.isDirectory(packageDir)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(packageDir)) {
                walk.filter(p -> p.toString().endsWith(".class"))
                        .filter(p -> !p.getFileName().toString().contains("$"))
                        .filter(p -> !p.getFileName().toString().startsWith("package-info"))
                        .forEach(p -> {
                            String relative = root.relativize(p).toString();
                            String className = relative.substring(0, relative.length() - 6)
                                    .replace('/', '.');
                            try {
                                if (isPublicClassFile(p)) {
                                    int lastDot = className.lastIndexOf('.');
                                    String pkg = lastDot <= ROOT_PACKAGE.length()
                                            ? ""
                                            : className.substring(ROOT_PACKAGE.length() + 1, lastDot);
                                    String simple = className.substring(lastDot + 1);
                                    result.add(pkg + "." + simple);
                                }
                            } catch (IOException e) {
                                throw new RuntimeException("Failed reading " + p, e);
                            }
                        });
            }
        }
        return result;
    }

    /**
     * 读取 class 文件 access_flags — 位于定长头部(magic/minor/major)与
     * <b>变长 constant pool</b> 之后,必须遍历 constant pool 才能定位。
     *
     * <p>布局:u4 magic, u2 minor, u2 major, u2 constant_pool_count,
     * cp_info[count-1](各条目按 tag 定长/变长), 随后 u2 access_flags。
     * ACC_PUBLIC = 0x0001;顶层类型必含 class/interface/enum/annotation 之一,
     * 故仅判 public bit 即可。常量池条目按 JVMS 4.4 遍历。
     */
    private static boolean isPublicClassFile(Path classFile) throws IOException {
        byte[] bytes = Files.readAllBytes(classFile);
        if (bytes.length < 10) {
            return false;
        }
        // magic 0xCAFEBABE
        if ((bytes[0] & 0xFF) != 0xCA || (bytes[1] & 0xFF) != 0xFE
                || (bytes[2] & 0xFF) != 0xBA || (bytes[3] & 0xFF) != 0xBE) {
            return false;
        }
        int pos = 10; // skip magic(4) + minor(2) + major(2) + constant_pool_count(2)
        int constantPoolCount = ((bytes[8] & 0xFF) << 8) | (bytes[9] & 0xFF);
        for (int i = 1; i < constantPoolCount; i++) {
            if (pos >= bytes.length) {
                return false;
            }
            int tag = bytes[pos++] & 0xFF;
            switch (tag) {
                case 1: // Utf8: u2 length + bytes
                    if (pos + 2 > bytes.length) {
                        return false;
                    }
                    int len = ((bytes[pos] & 0xFF) << 8) | (bytes[pos + 1] & 0xFF);
                    pos += 2 + len;
                    break;
                case 3: case 4: // Integer / Float: 4 bytes
                    pos += 4;
                    break;
                case 5: case 6: // Long / Double: 8 bytes, 占 2 槽
                    pos += 8;
                    i++;
                    break;
                case 7: case 8: case 16: case 19: case 20: // Class/String/MethodType/Module/Package: u2
                    pos += 2;
                    break;
                case 9: case 10: case 11: case 12: case 17: case 18: // *ref / NameAndType / Dynamic: u2+u2
                    pos += 4;
                    break;
                case 15: // MethodHandle: u1+u2
                    pos += 3;
                    break;
                default:
                    return false; // 未知 tag — 不是合法 class
            }
        }
        if (pos + 2 > bytes.length) {
            return false;
        }
        int accessFlags = ((bytes[pos] & 0xFF) << 8) | (bytes[pos + 1] & 0xFF);
        return (accessFlags & 0x0001) != 0; // ACC_PUBLIC
    }

    private static List<Path> classpathRoots() throws URISyntaxException {
        String classpath = System.getProperty("java.class.path");
        List<Path> roots = new ArrayList<>();
        for (String entry : classpath.split(java.io.File.pathSeparator)) {
            if (entry.isEmpty()) {
                continue;
            }
            Path p = Paths.get(entry);
            // 只扫描生产编译产物(target/classes);test-classes 的 *Test 类不属公开面
            if (Files.isDirectory(p) && p.endsWith("target/classes")) {
                roots.add(p);
            }
        }
        return roots;
    }

    private static Set<String> readList(String resource) throws IOException {
        Set<String> lines = new TreeSet<>();
        try (InputStream is = PublicSurfaceContractTest.class.getResourceAsStream(resource)) {
            assertThat(is)
                    .as("allowlist resource %s 必须存在", resource)
                    .isNotNull();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        lines.add(trimmed);
                    }
                }
            }
        }
        return lines;
    }
}
