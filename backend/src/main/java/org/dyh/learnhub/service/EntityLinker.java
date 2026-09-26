package org.dyh.learnhub.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 实体链接与消歧（Entity Linking &amp; Disambiguation）。
 *
 * <p>这是知识图谱准确率的**最大瓶颈**，也是实测踩过的坑：模型这次写 {@code Git}、上次写 {@code git}，
 * 按原始字符串哈希就得到两个节点，同名两页；{@code DeepSeek Harness} 与 {@code DeepSeek Harness（dsh）}
 * 明明是同一个东西却被当成两个。所以入库前必须先归一、再链接。
 *
 * <p>做法分三步（对应图谱方法论里的"实体消解"）：
 * <ol>
 *   <li><b>归一化</b>：NFKC（全角→半角）、折叠空白、去掉包裹性标点、小写。得到 {@code norm}，它唯一决定节点 id。</li>
 *   <li><b>别名</b>：把括注里的写法（{@code 中文名（别名）} 的"别名"）与"斜杠并列写法"都登记成别名，
 *       下次遇到任一写法都能链接到同一节点。</li>
 *   <li><b>向量兜底</b>（可选）：归一化后仍不同名的相似名（{@code IoC 与 DI} vs {@code IoC}）不做自动合并 ——
 *       自动合并这类"组合概念"错一次就是污染全库；只把它们作为候选提示出来，由人决定。</li>
 * </ol>
 *
 * <p>为什么不做自动模糊合并：合并是**不可逆**的破坏性操作，宁可留两个节点让人点一下删除
 * （图谱已有删除能力），也不要模型猜错把两个概念粘成一个。
 */
public final class EntityLinker {

    private EntityLinker() {
    }

    /** 包裹性标点（**不含括号**）：名字两端的引号/书名号等，不参与身份判定 */
    private static final Pattern WRAP = Pattern.compile("^[\\s\"'“”‘’《》〈〉【】\\[\\]]+|[\\s\"'“”‘’《》〈〉【】\\[\\]]+$");

    /** 括注：中文全角括号 / 半角括号，里面常是别名或限定语。NFKC 已把全角折成半角 */
    private static final Pattern PAREN = Pattern.compile("[（(]([^（()）]{1,40})[)）]");

    /** 并列写法分隔符：A / B、A 与 B、A、B —— 只用于生成别名候选，不直接合并 */
    private static final Pattern SPLIT = Pattern.compile("\\s*[/／|、]\\s*|\\s+与\\s+");

    /** 实体名长度上限：超过这个长度的多半是整句话，不是实体 */
    public static final int MAX_NAME_CHARS = 40;

    /**
     * 归一化：身份判定的唯一依据。
     * <p>NFKC 会把全角字母数字压成半角（{@code ＩｏＣ → IoC}）；
     * **括注整体丢弃**——{@code DeepSeek Harness（dsh）} 与 {@code DeepSeek Harness} 必须是同一个点，
     * 括注里的写法交给 {@link #aliasCandidates} 当别名（否则同一个概念会因为写法不同长出三四个节点）。
     * 再去掉所有空白、包裹标点与连接符，最后小写 —— 于是 {@code " Git "}、{@code git}、{@code Ｇit} 归一为 {@code git}。
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        s = PAREN.matcher(s).replaceAll("");
        s = WRAP.matcher(s).replaceAll("");
        s = s.replaceAll("[\\s\\u00a0]+", "");
        // 中文里的顿号/逗号不该改变身份；下划线与连字符同理（part_of / part-of 是同一个词）
        s = s.replaceAll("[_\\-·・]", "");
        return s.toLowerCase(Locale.ROOT);
    }

    /** 实体 id：{@code e-<sha256(归一化名) 前 10 位>}。稳定、与大小写和括注无关。 */
    public static String id(String name) {
        String n = normalize(name);
        if (n.isEmpty()) {
            return null;
        }
        return "e-" + KgService.sha256(n).substring(0, 10);
    }

    /**
     * 显示名：**去掉括注**后的主名，去掉两端包裹标点、折叠空白，保留原始大小写。
     * <p>例：{@code DeepSeek Harness（dsh） → DeepSeek Harness}。
     * 标签短、可读，而 {@code dsh} 不会丢 —— 它进了别名表，照样能链接到这个点。
     */
    public static String display(String raw) {
        if (raw == null) {
            return "";
        }
        String s = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        s = PAREN.matcher(s).replaceAll("");
        s = WRAP.matcher(s).replaceAll("");
        return s.replaceAll("[\\s\\u00a0]+", " ").trim();
    }

    /**
     * 抽取别名候选：括注里的写法 + 并列写法。
     * <p>例：{@code DeepSeek Harness（dsh）} → {@code [dsh]}（主名由 display 得到，不重复列）；
     * {@code IoC / DI} → {@code [IoC, DI]}（两个都是独立概念，所以它们只会成为**别名候选**，
     * 由调用方按"是否已有同名节点"决定链接到谁，绝不做拼接合并）。
     * <p>实现上必须**先抽括注再删**：NFKC 会把 {@code （} 折成 {@code (}，
     * 若先做"去掉两端标点"就会把收尾的 {@code )} 吃掉，括注结构没了、别名一个都抽不出来（这个坑踩过）。
     */
    public static List<String> aliasCandidates(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String base = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        Matcher m = PAREN.matcher(base);
        while (m.find()) {
            String inner = display(m.group(1));
            if (!inner.isEmpty()) {
                out.add(inner);
            }
        }
        String main = display(base);
        if (main.isEmpty()) {
            return List.of();
        }
        for (String part : SPLIT.split(main)) {
            String p = display(part);
            if (!p.isEmpty() && !p.equals(main)) {
                out.add(p);
            }
        }
        out.remove(main);
        List<String> list = new ArrayList<>(out);
        list.removeIf(a -> a.isEmpty() || a.length() > MAX_NAME_CHARS);
        return list;
    }

    /** 名字是否像个实体：非空、不超长、至少含一个字母或汉字、不是纯标点/纯数字、不含句读 */
    public static boolean plausible(String name) {
        String n = display(name);
        if (n.isEmpty() || n.length() > MAX_NAME_CHARS) {
            return false;
        }
        if (!n.matches(".*[\\p{IsHan}A-Za-z].*")) {
            return false;
        }
        if (n.matches("[0-9.]+")) {
            return false;
        }
        // 句读是"这是一句话"的强信号：概念名里不会出现逗号、句号、分号。
        // 实测模型在长句上会给出"堆、栈、方法区、GC 分代"这种答案，靠这条直接挡掉。
        return !n.matches(".*[，。、；：！？,.!?;:].*");
    }

    /** 两个名字是否指向同一实体（归一化后相等） */
    public static boolean same(String a, String b) {
        String na = normalize(a);
        return !na.isEmpty() && na.equals(normalize(b));
    }

    /**
     * 相似度（0~1）：给"要不要提示这两个节点可能是同一个"用。
     * <p>刻意用简单可解释的规则而不是向量：这里只是在**提示**，规则足够，且不会引入模型不确定性。
     * 归一化名互相包含（{@code ioc} ⊂ {@code ioc与di}）给 0.6；编辑距离相近给更低的分。
     */
    public static double suggestScore(String a, String b) {
        String na = normalize(a);
        String nb = normalize(b);
        if (na.isEmpty() || nb.isEmpty()) {
            return 0;
        }
        if (na.equals(nb)) {
            return 1;
        }
        if (na.contains(nb) || nb.contains(na)) {
            return 0.6;
        }
        int d = levenshtein(na, nb);
        int max = Math.max(na.length(), nb.length());
        double sim = max == 0 ? 0 : 1.0 - (double) d / max;
        return sim >= 0.75 ? sim : 0;
    }

    /** 编辑距离（短字符串足够用；这里只服务于上面的提示逻辑） */
    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
