package org.dyh.learnhub;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 防"启动才炸"的 bean 装配检查（**不需要 Spring 上下文、不需要数据库**）。
 *
 * <h3>为什么需要</h3>
 * 实测踩过：给 {@code ModelDiscoveryService} 加了一个"测试用"的第二构造器，
 * 本机单元测试全绿（测试是手工 {@code new} 的），但**后端容器启动即挂**：
 * <pre>
 * Failed to instantiate [ModelDiscoveryService]: No default constructor found
 * </pre>
 * Spring 只在"恰好一个构造器"时才自动挑；有多个构造器且都没标注时它去找无参构造器，
 * 找不到就抛异常 —— 这类错误任何单元测试都照不到，只有真正启动才暴露。
 *
 * <p>所以这里做一条静态规则：**被 Spring 管理的类，若声明了多个构造器，
 * 必须有一个带 {@code @Autowired}**。这样同类错误在 {@code mvn test} 阶段就会红。
 */
class BeanConstructorTest {

    /** 被 Spring 扫描的常用注解（够覆盖本项目用到的全部形态） */
    private static final List<String> BEAN_ANNOTATIONS = List.of(
            "org.springframework.stereotype.Service",
            "org.springframework.stereotype.Component",
            "org.springframework.stereotype.Repository",
            "org.springframework.stereotype.Controller",
            "org.springframework.web.bind.annotation.RestController",
            "org.springframework.context.annotation.Configuration");

    @Test
    @DisplayName("被 Spring 管理的类：多构造器时必须有 @Autowired（否则启动报 No default constructor found）")
    void springBeansHaveUnambiguousConstructor() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Class<?> c : loadProjectClasses()) {
            if (!isSpringBean(c) || c.isInterface() || c.isEnum() || java.lang.reflect.Modifier.isAbstract(c.getModifiers())) {
                continue;
            }
            Constructor<?>[] ctors = c.getDeclaredConstructors();
            if (ctors.length <= 1) {
                continue;
            }
            boolean anyAutowired = Stream.of(ctors).anyMatch(BeanConstructorTest::hasAutowired);
            boolean hasNoArg = Stream.of(ctors).anyMatch(x -> x.getParameterCount() == 0);
            if (!anyAutowired && !hasNoArg) {
                offenders.add(c.getName() + "（" + ctors.length + " 个构造器，都没标 @Autowired，也没有无参构造器）");
            }
        }
        assertTrue(offenders.isEmpty(),
                "这些 bean 会让 Spring 启动失败（No default constructor found），请给主构造器加 @Autowired：\n  "
                        + String.join("\n  ", offenders));
    }

    private static boolean hasAutowired(Constructor<?> c) {
        for (Annotation a : c.getAnnotations()) {
            String n = a.annotationType().getName();
            if (n.equals("org.springframework.beans.factory.annotation.Autowired")
                    || n.equals("org.springframework.beans.factory.annotation.Value")
                    || n.equals("jakarta.annotation.PostConstruct")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSpringBean(Class<?> c) {
        for (Annotation a : c.getAnnotations()) {
            if (BEAN_ANNOTATIONS.contains(a.annotationType().getName())) {
                return true;
            }
        }
        return false;
    }

    /** 直接用编译产物扫描，避免引入类路径扫描器 */
    private static List<Class<?>> loadProjectClasses() throws IOException {
        Path root = Path.of("target", "classes");
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Class<?>> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(x -> x.toString().endsWith(".class")).toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith("org/dyh/learnhub/") && !rel.contains("$")) {
                    String fqn = rel.substring(0, rel.length() - ".class".length()).replace('/', '.');
                    try {
                        out.add(Class.forName(fqn, false, BeanConstructorTest.class.getClassLoader()));
                    } catch (Throwable ignored) {
                        // 加载不了的（可选依赖等）跳过：这条规则只关心能加载到的 bean
                    }
                }
            }
        }
        return out;
    }
}
