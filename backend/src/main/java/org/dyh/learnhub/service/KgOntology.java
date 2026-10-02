package org.dyh.learnhub.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 知识图谱的**本体**（Ontology）：一张封闭的关系词表 + 每条关系的代数属性。
 *
 * <p>为什么必须封闭：如果让模型自由输出关系名，同一件事会得到"属于/是…的一部分/包含于 / part_of / 隶属于"
 * 七八种写法，图立刻退化成一团各自命名的线 —— 既没法按关系过滤，也没法做任何推理。
 * 所以关系只允许取自这里，模型的自由文本先经过 {@link #canonical} 归一，认不出来就丢弃。
 *
 * <p>属性（transitive / symmetric）不是摆设：{@link KgReasoner} 直接按它们做闭包推导，
 * 把"没有直接写出来但一定成立"的事实补出来 —— 这正是知识图谱相对向量检索的价值所在。
 *
 * <p>词表是**刻意做小**的：能覆盖学习类知识的骨架关系即可。关系越多，抽取准确率越低、越需要维护。
 */
public final class KgOntology {

    private KgOntology() {
    }

    /**
     * 一条关系的定义。
     *
     * @param id     规范 id（落库值）
     * @param label  界面显示名
     * @param transitive 是否传递：A→B、B→C 时推导 A→C
     * @param symmetric  是否对称：A→B 等价于 B→A（只存一条，查询时两个方向都算）
     * @param hint   给抽取模型的判据，必须写清"什么时候才用这条"
     */
    public record Rel(String id, String label, boolean transitive, boolean symmetric, String hint) {
    }

    /** 规范关系表。顺序即界面展示顺序。 */
    public static final List<Rel> RELATIONS = List.of(
            new Rel("is_a", "是一种", true, false,
                    "A 是 B 的一个种类/一种实现（Git 是一种版本控制系统）"),
            new Rel("part_of", "属于", true, false,
                    "A 是 B 的一个组成部分或一个环节（栈属于 JVM 内存结构；自动装配属于启动流程的一部分）"),
            new Rel("prerequisite", "前置知识", true, false,
                    "要理解 A 必须先掌握 B（学 GC 之前要先懂堆与栈）"),
            new Rel("used_for", "用于", false, false,
                    "A 是用来做 B 的手段（Maven 用于依赖管理与构建）"),
            new Rel("contrast_with", "易混", false, true,
                    "A 与 B 容易被混为一谈，需要对比着记（== 与 equals、IoC 与 DI）"),
            new Rel("related_to", "相关", false, true,
                    "确实相关，但说不清属于上面哪一种。**兜底关系，能归到具体关系时不要用它**")
    );

    private static final Map<String, Rel> BY_ID = new LinkedHashMap<>();

    /** 同义写法 → 规范 id。模型输出的自由文本先过这张表。 */
    private static final Map<String, String> SYNONYMS = new LinkedHashMap<>();

    static {
        for (Rel r : RELATIONS) {
            BY_ID.put(r.id(), r);
        }
        alias("is_a", "isa", "instance_of", "subclass_of", "kind_of", "type_of", "belongs_to_type",
                "是一种", "属于一种", "是一类", "是某种", "子类", "继承");
        alias("part_of", "partof", "component_of", "member_of", "belongs_to", "composed_of",
                "属于", "是…的一部分", "是一部分", "组成部分", "包含于", "环节");
        alias("prerequisite", "prereq", "requires", "depends_on", "depends", "before", "need_first",
                "前置", "前置知识", "前提", "依赖", "先修", "需要先学");
        alias("used_for", "use_for", "used_to", "purpose", "for",
                "用于", "用来", "用途", "作用");
        alias("contrast_with", "contrast", "confusable_with", "confuse_with", "similar_but_different", "vs",
                "易混", "对比", "区别", "容易混淆");
        alias("related_to", "related", "relates_to", "relevant_to", "see_also", "association",
                "相关", "有关", "关联", "参考");
    }

    private static void alias(String id, String... names) {
        SYNONYMS.put(id, id);
        for (String n : names) {
            SYNONYMS.put(n, id);
        }
    }

    public static boolean known(String id) {
        return id != null && BY_ID.containsKey(id);
    }

    public static Rel byId(String id) {
        return id == null ? null : BY_ID.get(id);
    }

    public static Set<String> ids() {
        return BY_ID.keySet();
    }

    /** 参数顺序即界面展示顺序，供前端渲染关系图例与过滤器 */
    public static List<Map<String, Object>> describe() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Rel r : RELATIONS) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("label", r.label());
            m.put("transitive", r.transitive());
            m.put("symmetric", r.symmetric());
            m.put("hint", r.hint());
            out.add(m);
        }
        return out;
    }

    /**
     * 把模型给的自由文本关系名归一到规范 id。
     *
     * <p>归一策略依次是：去空白/下划线/连字符 → 小写 → 同义词表 →
     * 中文词表按"包含"再兜一次（模型可能写"与…易混"这种带上下文的短语）。
     *
     * @return 规范 id；认不出来返回 {@code null}（调用方应丢弃该三元组，而不是塞个 related_to 了事）
     */
    public static String canonical(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String k = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-]+", "");
        String hit = SYNONYMS.get(k);
        if (hit != null) {
            return hit;
        }
        // 兜底：模型爱写 "A 与 B 易混" 这类短语，看它包含哪个关系的标志词
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        for (String id : new String[]{"contrast_with", "prerequisite", "part_of", "is_a", "used_for", "related_to"}) {
            for (Map.Entry<String, String> e : SYNONYMS.entrySet()) {
                if (e.getValue().equals(id) && lower.contains(e.getKey())) {
                    return id;
                }
            }
        }
        return null;
    }

    /** 给抽取模型看的关系清单（拼进提示词） */
    public static String promptTable() {
        StringBuilder sb = new StringBuilder();
        Set<String> seen = new LinkedHashSet<>();
        for (Rel r : RELATIONS) {
            if (!seen.add(r.id())) {
                continue;
            }
            sb.append("- ").append(r.id()).append("（").append(r.label()).append("）：")
                    .append(r.hint()).append('\n');
        }
        return sb.toString();
    }

    /**
     * 证据读起来像"依赖 / 基于 / 运行在"（而不是"属于 / 组成部分"）。
     *
     * <p>用途：实测最常见的关系类型错误是把"依赖"抽成"属于"——
     * 证据原话「Spring 是基于 Java 的，自然依赖于 JVM」被抽成 {@code Spring -属于→ JVM}，
     * 再经传递闭包放大成 {@code Spring -属于→ JRE}（评估报告 P0-D）。
     *
     * <p><b>这是词面启发式，不是语义判定</b>：证据里同时出现"依赖…的一部分"时可能误伤，
     * 所以调用方必须配合 {@link #looksLikeComposition} 一起判断（有依赖词且**无**组成词才改判）。
     */
    public static boolean looksLikeDependency(String text) {
        String t = text == null ? "" : text;
        return t.contains("依赖") || t.contains("基于") || t.contains("运行在")
                || t.contains("需要") || t.contains("才能");
    }

    /**
     * 证据读起来像"组成 / 归属"（才配得上 {@code part_of} / {@code is_a}）。
     *
     * @see #looksLikeDependency
     */
    public static boolean looksLikeComposition(String text) {
        String t = text == null ? "" : text;
        return t.contains("属于") || t.contains("一部分") || t.contains("组成部分")
                || t.contains("包含") || t.contains("一种") || t.contains("环节") || t.contains("子类");
    }
}
