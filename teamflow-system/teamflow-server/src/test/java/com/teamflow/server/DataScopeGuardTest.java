package com.teamflow.server;

import com.teamflow.framework.datascope.DataScope;
import com.teamflow.framework.datascope.DataScopeAspect;
import com.teamflow.framework.datascope.DataScopeContext;
import com.teamflow.framework.datascope.DataScopeCriteria;
import com.teamflow.framework.datascope.DataScopeResolver;
import com.teamflow.framework.datascope.IgnoreDataScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.Type;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G-2 数据权限防呆门禁（本机无 CI，用测试门禁替代）。
 *
 * <p>门禁规则：{@code com.teamflow} 主代码里任何方法只要调用了
 * {@link DataScopeContext#current()}，该方法或其所在类就必须标注
 * {@link DataScope} 或 {@link IgnoreDataScope}；否则测试失败。</p>
 *
 * <p>为什么用字节码扫描而不是正则：{@code current()} 的调用点分散在 Service 方法体内，
 * 正则只能看到"文件里出现过该字符串"，无法判断是哪个方法调用、该方法有没有注解。
 * 这里用 Spring 内嵌的 ASM（{@code org.springframework.asm}，无需新增依赖）读取
 * 每个 {@code .class} 的方法体常量池，定位到"类 + 方法 + 描述符"级别的调用点，
 * 再反射读取方法/类上的注解。</p>
 *
 * <p>门禁有效性自证：{@link #scannerFlagsUnannotatedFixture()} 与
 * {@link #scannerAcceptsAnnotatedFixtures()} 用测试源码里的反例/正例 fixture
 * 走同一套扫描逻辑，证明"漏注解会被抓到、有注解会被放行"，而不是常量空断言。</p>
 */
class DataScopeGuardTest {

    private static final String SCOPE_OWNER = "com/teamflow/framework/datascope/DataScopeContext";
    private static final String SCOPE_METHOD = "current";
    private static final String SCOPE_DESCRIPTOR = "()Lcom/teamflow/framework/datascope/DataScopeCriteria;";
    private static final Pattern LAMBDA_PATTERN = Pattern.compile("^lambda\\$(.+)\\$\\d+$");

    @AfterEach
    void clearContext() {
        DataScopeContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // 一、默认拒绝语义
    // ------------------------------------------------------------------

    @Test
    void currentReturnsEmptyScopeWhenNoContextExists() {
        DataScopeContext.clear();

        DataScopeCriteria criteria = DataScopeContext.current();

        assertThat(criteria.isAllScope()).as("无上下文时不得返回全量（G-2 回归）").isFalse();
        assertThat(criteria.getUserId()).as("无上下文时应退化为空集 selfOnly(null)").isNull();
        assertThat(criteria.getDeptIds()).isEmpty();
    }

    @Test
    void setNullIsTreatedAsEmptyScope() {
        DataScopeContext.set(DataScopeCriteria.limited(7L, List.of(2L)));

        DataScopeContext.set(null);

        DataScopeCriteria criteria = DataScopeContext.current();
        assertThat(criteria.isAllScope()).isFalse();
        assertThat(criteria.getUserId()).isNull();
        assertThat(criteria.getDeptIds()).isEmpty();
    }

    @Test
    void snapshotAndRestoreKeepOuterContext() {
        DataScopeContext.clear();
        DataScopeCriteria outer = DataScopeCriteria.limited(9L, List.of(3L));
        DataScopeContext.set(outer);

        DataScopeCriteria snapshot = DataScopeContext.snapshot();
        DataScopeContext.set(DataScopeCriteria.all());
        DataScopeContext.restore(snapshot);

        assertThat(DataScopeContext.current()).isSameAs(outer);
        DataScopeContext.restore(null);
        assertThat(DataScopeContext.snapshot()).isNull();
    }

    // ------------------------------------------------------------------
    // 二、注解的运行时语义（证明 @IgnoreDataScope 不是空壳）
    // ------------------------------------------------------------------

    @Test
    void ignoreDataScopeSwitchesToAllScopeAndRestoresOuterContext() {
        Fixtures.IgnoreCaller proxy = proxyOf(new Fixtures.IgnoreCaller());
        DataScopeCriteria outer = DataScopeCriteria.limited(7L, List.of(2L));
        DataScopeContext.set(outer);

        DataScopeCriteria inside = proxy.readUnfiltered();

        assertThat(inside.isAllScope()).as("@IgnoreDataScope 方法内应拿到全量").isTrue();
        assertThat(DataScopeContext.snapshot()).as("退出后必须恢复外层上下文").isSameAs(outer);
    }

    @Test
    void dataScopeAnnotationResolvesScopeAndRestoresOuterContext() {
        List<String> resolvedNames = new ArrayList<>();
        Fixtures.DataScopeCaller proxy = proxyOf(new Fixtures.DataScopeCaller(), username -> {
            resolvedNames.add(username);
            return DataScopeCriteria.limited(7L, List.of(2L));
        });

        DataScopeContext.clear();
        DataScopeCriteria inside = proxy.readScoped();

        assertThat(inside.getUserId()).isEqualTo(7L);
        assertThat(inside.getDeptIds()).containsExactly(2L);
        assertThat(resolvedNames).as("切面应按当前登录用户解析").containsExactly("tester");
        assertThat(DataScopeContext.snapshot()).as("无外层上下文时退出后不得残留").isNull();

        DataScopeCriteria outer = DataScopeCriteria.limited(99L, List.of(8L));
        DataScopeContext.set(outer);
        proxy.readScoped();
        assertThat(DataScopeContext.snapshot()).as("嵌套调用后必须恢复外层上下文").isSameAs(outer);
    }

    // ------------------------------------------------------------------
    // 三、全仓门禁：调用 current() 必须有 @DataScope / @IgnoreDataScope
    // ------------------------------------------------------------------

    @Test
    void repositoryHasNoDataScopeCallerWithoutAnnotation() throws Exception {
        List<ScannedClass> scanned = scanMainClasses();
        assertThat(scanned.size()).as("应扫描到全仓 main class（防止扫描器空转）").isGreaterThan(40);

        List<String> violations = new ArrayList<>();
        Set<String> scannedCallers = new LinkedHashSet<>();
        for (ScannedClass scannedClass : scanned) {
            Class<?> type = loadClass(scannedClass.className());
            for (ScopeCaller caller : findCurrentCallers(scannedClass.bytes())) {
                scannedCallers.add(scannedClass.className() + "#" + caller.methodName());
                if (!isAllowed(type, caller)) {
                    violations.add(scannedClass.className() + "#" + caller.methodName() + caller.descriptor());
                }
            }
        }

        assertThat(scannedCallers.size()).as("全仓应至少存在若干个 current() 调用点（防止规则空转）").isGreaterThanOrEqualTo(5);
        assertThat(violations)
                .as("以下方法调用了 DataScopeContext.current()，却既没有 @DataScope 也没有 @IgnoreDataScope：%s", violations)
                .isEmpty();
    }

    @Test
    void scannerFlagsUnannotatedFixture() throws Exception {
        List<String> violations = findViolations(Fixtures.UnannotatedCaller.class);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("UnannotatedCaller#read");
    }

    @Test
    void scannerAcceptsAnnotatedFixtures() throws Exception {
        assertThat(findViolations(Fixtures.DataScopeCaller.class)).as("@DataScope 应放行").isEmpty();
        assertThat(findViolations(Fixtures.IgnoreCaller.class)).as("@IgnoreDataScope 应放行").isEmpty();
    }

    @Test
    void noTypeOrMethodDeclaresBothDataScopeAnnotations() throws Exception {
        List<String> both = new ArrayList<>();
        for (ScannedClass scannedClass : scanMainClasses()) {
            collectBothAnnotations(loadClass(scannedClass.className()), both);
        }

        assertThat(both).as("同一方法/类不得同时标注 @DataScope 与 @IgnoreDataScope：%s", both).isEmpty();
        List<String> fixtureBoth = new ArrayList<>();
        collectBothAnnotations(Fixtures.BothAnnotatedCaller.class, fixtureBoth);
        assertThat(fixtureBoth)
                .as("反例 fixture 必须被检出，证明组合规则有效")
                .hasSize(1);
    }

    // ------------------------------------------------------------------
    // 扫描与断言实现
    // ------------------------------------------------------------------

    private static List<String> findViolations(Class<?> type) throws IOException {
        List<String> violations = new ArrayList<>();
        for (ScopeCaller caller : findCurrentCallers(bytesOf(type))) {
            if (!isAllowed(type, caller)) {
                violations.add(type.getName() + "#" + caller.methodName() + caller.descriptor());
            }
        }
        return violations;
    }

    /** 解析 class 字节码，返回方法体内调用 {@code DataScopeContext.current()} 的方法。 */
    private static List<ScopeCaller> findCurrentCallers(byte[] classBytes) {
        Set<ScopeCaller> callers = new LinkedHashSet<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String calledName, String calledDescriptor, boolean isInterface) {
                        if (SCOPE_OWNER.equals(owner) && SCOPE_METHOD.equals(calledName) && SCOPE_DESCRIPTOR.equals(calledDescriptor)) {
                            callers.add(new ScopeCaller(name, descriptor));
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return new ArrayList<>(callers);
    }

    /** 方法或其类上有 {@link DataScope} / {@link IgnoreDataScope} 即视为合规。 */
    private static boolean isAllowed(Class<?> type, ScopeCaller caller) {
        if (type.isAnnotationPresent(DataScope.class) || type.isAnnotationPresent(IgnoreDataScope.class)) {
            return true;
        }
        String methodName = caller.methodName();
        if ("<init>".equals(methodName) || "<clinit>".equals(methodName)) {
            return constructorAllows(type, caller.descriptor());
        }
        Matcher lambda = LAMBDA_PATTERN.matcher(methodName);
        if (lambda.matches()) {
            // lambda 合成方法归到宿主方法上判断注解
            return lambdaHostAllows(type, lambda.group(1));
        }
        Method method = findMethod(type, methodName, caller.descriptor());
        return method != null && isScopeAnnotated(method);
    }

    private static boolean isScopeAnnotated(Method method) {
        return method.isAnnotationPresent(DataScope.class) || method.isAnnotationPresent(IgnoreDataScope.class);
    }

    private static boolean lambdaHostAllows(Class<?> type, String hostName) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(hostName) && isScopeAnnotated(method)) {
                return true;
            }
        }
        return false;
    }

    private static boolean constructorAllows(Class<?> type, String descriptor) {
        Type[] argumentTypes = Type.getArgumentTypes(descriptor);
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (!matchesParameterTypes(constructor.getParameterTypes(), argumentTypes)) {
                continue;
            }
            return constructor.isAnnotationPresent(DataScope.class) || constructor.isAnnotationPresent(IgnoreDataScope.class);
        }
        return false;
    }

    private static Method findMethod(Class<?> type, String name, String descriptor) {
        Type[] argumentTypes = Type.getArgumentTypes(descriptor);
        Type returnType = Type.getReturnType(descriptor);
        for (Method method : type.getDeclaredMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != argumentTypes.length) {
                continue;
            }
            if (!Type.getType(method.getReturnType()).equals(returnType)) {
                continue;
            }
            if (matchesParameterTypes(method.getParameterTypes(), argumentTypes)) {
                return method;
            }
        }
        return null;
    }

    private static boolean matchesParameterTypes(Class<?>[] actual, Type[] expected) {
        if (actual.length != expected.length) {
            return false;
        }
        for (int i = 0; i < actual.length; i++) {
            if (!Type.getType(actual[i]).equals(expected[i])) {
                return false;
            }
        }
        return true;
    }

    private static void collectBothAnnotations(Class<?> type, List<String> sink) {
        if (type.isAnnotationPresent(DataScope.class) && type.isAnnotationPresent(IgnoreDataScope.class)) {
            sink.add(type.getName() + "（类级）");
        }
        for (Method method : type.getDeclaredMethods()) {
            if (method.isAnnotationPresent(DataScope.class) && method.isAnnotationPresent(IgnoreDataScope.class)) {
                sink.add(type.getName() + "#" + method.getName());
            }
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (constructor.isAnnotationPresent(DataScope.class) && constructor.isAnnotationPresent(IgnoreDataScope.class)) {
                sink.add(type.getName() + "#<init>");
            }
        }
    }

    private static byte[] bytesOf(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("找不到类字节码：" + resource);
            }
            return in.readAllBytes();
        }
    }

    private static Class<?> loadClass(String className) throws ClassNotFoundException {
        return Class.forName(className, false, Thread.currentThread().getContextClassLoader());
    }

    /** 扫描 classpath 上全部 {@code com.teamflow} 主代码（排除 test-classes）。 */
    private static List<ScannedClass> scanMainClasses() throws Exception {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        Map<String, byte[]> classes = new TreeMap<>();
        Set<String> visitedUrls = new LinkedHashSet<>();
        Enumeration<URL> roots = loader.getResources("com/teamflow");
        while (roots.hasMoreElements()) {
            URL url = roots.nextElement();
            if (!visitedUrls.add(url.toString())) {
                continue;
            }
            if ("file".equals(url.getProtocol())) {
                Path dir = Paths.get(url.toURI());
                if (dir.toString().contains("test-classes")) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(dir)) {
                    files.filter(path -> path.toString().endsWith(".class")).forEach(path -> {
                        String relative = dir.relativize(path).toString().replace('\\', '/');
                        String className = "com.teamflow/" + relative;
                        className = className.substring(0, className.length() - ".class".length()).replace('/', '.');
                        try {
                            classes.putIfAbsent(className, Files.readAllBytes(path));
                        } catch (IOException ex) {
                            throw new IllegalStateException("读取 class 失败：" + path, ex);
                        }
                    });
                }
            } else if ("jar".equals(url.getProtocol())) {
                String spec = url.getFile();
                int separator = spec.indexOf("!/");
                if (separator < 0) {
                    continue;
                }
                Path jarPath = Paths.get(URI.create(spec.substring(0, separator)));
                if (jarPath.toString().contains("test-classes")) {
                    continue;
                }
                try (JarFile jar = new JarFile(jarPath.toFile())) {
                    for (JarEntry entry : jar.stream().toList()) {
                        String name = entry.getName();
                        if (entry.isDirectory() || !name.startsWith("com/teamflow/") || !name.endsWith(".class")) {
                            continue;
                        }
                        String className = name.substring(0, name.length() - ".class".length()).replace('/', '.');
                        try (InputStream in = jar.getInputStream(entry)) {
                            classes.putIfAbsent(className, in.readAllBytes());
                        }
                    }
                }
            }
        }
        Map<String, byte[]> filtered = new LinkedHashMap<>();
        classes.forEach((className, bytes) -> {
            if (!className.endsWith("package-info") && !className.endsWith("module-info")) {
                filtered.put(className, bytes);
            }
        });
        List<ScannedClass> scanned = new ArrayList<>();
        filtered.forEach((className, bytes) -> scanned.add(new ScannedClass(className, bytes)));
        return scanned;
    }

    private static <T> T proxyOf(T target) {
        return proxyOf(target, username -> DataScopeCriteria.limited(7L, List.of(2L)));
    }

    private static <T> T proxyOf(T target, DataScopeResolver resolver) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("tester", "n/a"));
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new DataScopeAspect(resolver));
        @SuppressWarnings("unchecked")
        T proxy = (T) factory.getProxy();
        return proxy;
    }

    private record ScopeCaller(String methodName, String descriptor) {
    }

    private record ScannedClass(String className, byte[] bytes) {
    }

    /**
     * 测试用 fixture：只用于证明扫描器/组合规则有效，不参与全仓扫描（位于 test-classes，已被排除）。
     */
    static final class Fixtures {

        private Fixtures() {
        }

        /** 反例：调用 current() 但没有任何数据范围注解。 */
        public static class UnannotatedCaller {
            public DataScopeCriteria read() {
                return DataScopeContext.current();
            }
        }

        /** 正例：方法标注 @DataScope。 */
        public static class DataScopeCaller {
            @DataScope
            public DataScopeCriteria readScoped() {
                return DataScopeContext.current();
            }
        }

        /** 正例：方法标注 @IgnoreDataScope（确需全量）。 */
        public static class IgnoreCaller {
            @IgnoreDataScope
            public DataScopeCriteria readUnfiltered() {
                return DataScopeContext.current();
            }
        }

        /** 反例：同时标注两个互斥注解。 */
        public static class BothAnnotatedCaller {
            @DataScope
            @IgnoreDataScope
            public DataScopeCriteria read() {
                return DataScopeContext.current();
            }
        }
    }
}
