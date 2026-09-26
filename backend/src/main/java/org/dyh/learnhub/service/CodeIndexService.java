package org.dyh.learnhub.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从代码里抽出**符号**（类/接口/枚举/函数/方法/常量）与**标识符**，用于建索引。
 *
 * <h3>为什么不用 AST</h3>
 * 引 AST 意味着每支持一种语言就多一个重量级依赖（而且要版本对齐）。
 * 这里用**按语言分组的正则表**，目标是覆盖日常语言的常见声明形式（实测够用），
 * 并且**如实承认它的边界**：宏/生成代码/多行签名抽不全、同名重载只记名字不区分参数。
 * 抽不到不影响入库（片段本身仍可检索），只是"按符号跳转"这条路少一个入口 —— 这比引一堆依赖划算。
 *
 * <h3>索引分两层</h3>
 * <ol>
 *   <li>{@link #extractSymbols} → 符号表：有名字有行号，支持**精确定位**（"在哪定义"）；</li>
 *   <li>{@link #extractIdentifiers} → 标识符清单：支持关键词/前缀模糊（零模型成本）。</li>
 * </ol>
 */
@Service
public class CodeIndexService {

    /** 抽到的符号：kind + name + 行号 */
    public record Symbol(String kind, String name, int line) {
    }

    /** 标识符清单上限：防止一坨压缩过的 JS 把 symbols 列撑爆（那反而让检索变慢） */
    private static final int MAX_IDENTIFIERS = 400;

    /**
     * 各语言的声明正则。
     * <p>统一 {@code (?:^|\n)} 开头 + 行首缩进容忍；用 MULTILINE 处理。
     */
    private static final Map<String, List<Pattern>> DECLS = new LinkedHashMap<>();

    private static final Pattern IDENT = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]{2,}");

    static {
        // ---- 花括号家族：Java / C# / C++ / Kotlin / JS / TS / Go / PHP / Swift ----
        List<Pattern> braces = new ArrayList<>();
        braces.add(named("class", "(?:class|struct)\\s+([A-Za-z_$][\\w$]*)"));
        braces.add(named("interface", "interface\\s+([A-Za-z_$][\\w$]*)"));
        braces.add(named("enum", "enum\\s+([A-Za-z_$][\\w$]*)"));
        braces.add(named("record", "record\\s+([A-Za-z_$][\\w$]*)"));
        // 方法：修饰符 + 返回类型 + 名字( —— 故意允许中间有泛型/数组
        braces.add(named("method",
                "(?:public|private|protected|static|final|synchronized|abstract|default|async|export|\\s)+"
                        + "[\\w<>\\[\\],\\s\\.\\?]+?\\s+([A-Za-z_$][\\w$]*)\\s*\\([^;)]*\\)\\s*(?:\\{|=>|throws)"));
        // function 声明 / 箭头函数赋值 / 导出
        braces.add(named("function", "function\\s+([A-Za-z_$][\\w$]*)\\s*\\("));
        braces.add(named("function", "(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*(?:async\\s*)?\\(?[^;=]*\\)?\\s*=>"));
        braces.add(named("function", "(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*(?:async\\s*)?function"));
        // Go 的 func 与方法接收者
        braces.add(named("function", "func\\s+(?:\\([^)]*\\)\\s*)?([A-Za-z_][\\w]*)"));
        // 常量
        braces.add(named("const", "(?:static\\s+final|const|final|readonly)\\s+(?:[\\w<>\\[\\]]+\\s+)?([A-Z_][A-Z0-9_]{2,})\\s*="));
        DECLS.put("braces", braces);

        // ---- Python（缩进语义，单独一套）----
        List<Pattern> py = new ArrayList<>();
        py.add(named("class", "class\\s+([A-Za-z_][\\w]*)"));
        py.add(named("function", "def\\s+([A-Za-z_][\\w]*)\\s*\\("));
        py.add(named("const", "^([A-Z_][A-Z0-9_]{2,})\\s*="));
        DECLS.put("python", py);

        // ---- SQL / Shell / 其它：只抽"看起来像定义"的 ----
        List<Pattern> generic = new ArrayList<>();
        generic.add(named("function", "^(?:function\\s+)?([A-Za-z_][\\w]*)\\s*\\(\\)\\s*\\{"));
        generic.add(named("function", "^(?:create\\s+(?:or\\s+replace\\s+)?(?:table|view|function|procedure)\\s+)([\\w\\.]+)"));
        DECLS.put("generic", generic);
    }

    private static Pattern named(String kind, String regex) {
        // 命名分组不方便动态做 kind，这里用列表顺序 + 正则；kind 由调用处连同 pattern 一起给
        return Pattern.compile(regex, Pattern.MULTILINE);
    }

    /** 语言分组：决定用哪套正则 */
    private static String family(String lang) {
        String l = lang == null ? "" : lang.trim().toLowerCase();
        if (l.startsWith("py")) {
            return "python";
        }
        if (l.isEmpty() || l.equals("sql") || l.equals("sh") || l.equals("shell") || l.equals("bash")
                || l.equals("ps1") || l.equals("txt") || l.equals("text")) {
            return "generic";
        }
        return "braces";
    }

    /** 抽取符号（按语言分组）。行号 1 起；同一位置重复命中会去重 */
    public List<Symbol> extractSymbols(String code, String lang) {
        List<Symbol> out = new ArrayList<>();
        if (code == null || code.isBlank()) {
            return out;
        }
        String fam = family(lang);
        List<Pattern> pats = DECLS.get(fam);
        if (pats == null) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < pats.size(); i++) {
            String kind = KIND_BY_INDEX.get(fam + "#" + i);
            if (kind == null) {
                continue;
            }
            Matcher m = pats.get(i).matcher(code);
            while (m.find()) {
                String name = m.group(1);
                if (name == null || name.isBlank()) {
                    continue;
                }
                // 行号必须取**名字**的位置，而不是整段匹配的起点：
                // 方法正则里包含 \s（可跨行），匹配可能从上一行（修饰符/字段声明）就开始，
                // 用 m.start() 会让"在哪定义"指到别的行去（实测：第 5 行的方法报成 L3）。
                int line = lineOf(code, m.start(1));
                String key = kind + "|" + name + "|" + line;
                if (seen.add(key)) {
                    out.add(new Symbol(kind, name, line));
                }
            }
        }
        return out;
    }

    /**
     * 每个 pattern 对应的 kind（与上面的构建顺序**必须一一对应**）。
     * 放在这里而不是正则里用命名分组，是为了让"加一条正则"时被迫同时写 kind，
     * 漏了会被下面的断言在启动时就发现，而不是静默少一类符号。
     */
    private static final Map<String, String> KIND_BY_INDEX = new LinkedHashMap<>();

    static {
        KIND_BY_INDEX.put("braces#0", "class");
        KIND_BY_INDEX.put("braces#1", "interface");
        KIND_BY_INDEX.put("braces#2", "enum");
        KIND_BY_INDEX.put("braces#3", "record");
        KIND_BY_INDEX.put("braces#4", "method");
        KIND_BY_INDEX.put("braces#5", "function");
        KIND_BY_INDEX.put("braces#6", "function");
        KIND_BY_INDEX.put("braces#7", "function");
        KIND_BY_INDEX.put("braces#8", "function");
        KIND_BY_INDEX.put("braces#9", "const");
        KIND_BY_INDEX.put("python#0", "class");
        KIND_BY_INDEX.put("python#1", "function");
        KIND_BY_INDEX.put("python#2", "const");
        KIND_BY_INDEX.put("generic#0", "function");
        KIND_BY_INDEX.put("generic#1", "function");
        // 启动即校验：正则条数与 kind 条数必须一致（否则上面的"一一对应"就是空话）
        for (Map.Entry<String, List<Pattern>> e : DECLS.entrySet()) {
            for (int i = 0; i < e.getValue().size(); i++) {
                if (!KIND_BY_INDEX.containsKey(e.getKey() + "#" + i)) {
                    throw new IllegalStateException("代码符号抽取：缺 kind 映射 " + e.getKey() + "#" + i);
                }
            }
        }
    }

    /** 标识符清单（去重、保序、限量）：关键词/前缀检索用 */
    public String extractIdentifiers(String code) {
        if (code == null || code.isBlank()) {
            return "";
        }
        Set<String> ids = new LinkedHashSet<>();
        Matcher m = IDENT.matcher(code);
        while (m.find() && ids.size() < MAX_IDENTIFIERS) {
            ids.add(m.group());
        }
        return String.join(" ", ids);
    }

    private static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
