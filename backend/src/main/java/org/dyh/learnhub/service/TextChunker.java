package org.dyh.learnhub.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本分块器（纯函数，可脱离 Spring 单测）。
 *
 * <h3>为什么需要专门的切法</h3>
 * 语义检索的效果一半取决于"块切得好不好"：切太碎会丢上下文（一句话被腰斩），
 * 切太长会让向量被稀释（一个 5000 字的块里只有 3 行相关，整块的向量就很泛）。
 * 这里的做法是**按标题分节、节内按段落聚合到目标长度、超长段落再按句号滑动切**，块间保留重叠。
 *
 * <h3>2026-09 补的第二个问题：块要带上下文</h3>
 * 原来只切正文、不带所属小节，于是块开头一句"它包含以下三种"离开标题就无从判断指什么，
 * 检索时也匹配不到标题里的词 —— 这正是 contextual retrieval 要解决的问题。
 * 现在每块都带 {@link Chunk#heading()}（所属小节路径），嵌入时用
 * {@link #embedText} 把「文档标题 · 小节」前置再向量化。
 */
public final class TextChunker {

    private TextChunker() {
    }

    /** 目标块大小（字符）。600~900 是"够上下文、向量又不被稀释"的常见区间 */
    public static final int TARGET = 800;
    /** 相邻块的重叠，避免关键句正好落在切点上被割裂 */
    public static final int OVERLAP = 120;
    /** 单块上限，硬性兜底 */
    public static final int MAX = 1200;

    /** Markdown 标题行（# ~ ######）；PDF/纯文本没有标题时这一层自然为空 */
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s*(.+?)\\s*#*$");

    /** 代码围栏：``` 或 ~~~ */
    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~)");

    /** HTML 标签（笔记里从语雀粘来的 `# <font style="...">标题</font>` 很常见） */
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]{1,200}>");

    /** Markdown 强调标记 */
    private static final Pattern MD_EMPHASIS = Pattern.compile("[*_`~]");

    /**
     * 清洗小节标题：去掉 HTML 标签与 Markdown 强调标记，折叠空白。
     *
     * <p>为什么必须做：实测 note#5（5.9 万字、本库最大来源）的标题是
     * {@code <font style="color:rgb(0, 0, 0)">一、Java核心基础</font>} 这种带标签的写法，
     * 直接把原文丢进嵌入等于往向量里塞了一堆 {@code rgb(0, 0, 0)} 噪声；
     * 存进 {@code kb_chunk.heading} 也没法给人看。
     */
    public static String cleanHeading(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = HTML_TAG.matcher(raw).replaceAll("");
        s = MD_EMPHASIS.matcher(s).replaceAll("");
        return s.replaceAll("[\\s\\u00a0]+", " ").trim();
    }

    /** 块：所属小节路径（可能为空）+ 正文 */
    public record Chunk(String heading, String text) {
    }

    /**
     * 切块（带小节归属）。新代码用这个。
     *
     * @param text 原文（可为 null）
     * @return 块列表；短于目标的整篇作为一块
     */
    public static List<Chunk> splitWithHeadings(String text) {
        List<Chunk> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        String s = text.replace("\r\n", "\n").replace('\r', '\n').trim();

        // ① 先按标题切成"节"，每节记住自己的标题路径（H1 > H2 > H3）
        List<Chunk> sections = new ArrayList<>();
        List<String> stack = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        String currentHeading = "";
        boolean inFence = false;
        for (String line : s.split("\n", -1)) {
            // 代码围栏内部的一切都是代码：里面的 `# 注释` 不是标题。
            // 实测：不判断围栏时，SQL/Bash 示例里的注释会被当成分节标题，
            // 于是"查询表中所有字段"变成了一堆块的小节路径（把真实层级冲掉）。
            if (FENCE.matcher(line).find()) {
                inFence = !inFence;
                body.append(line).append('\n');
                continue;
            }
            Matcher m = inFence ? null : HEADING.matcher(line.trim());
            if (m != null && m.matches()) {
                if (body.length() > 0) {
                    sections.add(new Chunk(currentHeading, body.toString().trim()));
                    body.setLength(0);
                }
                int level = m.group(1).length();
                while (stack.size() >= level) {
                    stack.remove(stack.size() - 1);
                }
                String title = cleanHeading(m.group(2));
                if (title.isEmpty()) {
                    continue;
                }
                stack.add(title);
                currentHeading = String.join(" > ", stack);
                continue;
            }
            body.append(line).append('\n');
        }
        if (body.length() > 0) {
            sections.add(new Chunk(currentHeading, body.toString().trim()));
        }
        if (sections.isEmpty()) {
            sections.add(new Chunk("", s));
        }

        // ② 节内再按长度切（复用段落聚合逻辑），每块继承所属小节
        for (Chunk sec : sections) {
            for (String piece : splitPlain(sec.text())) {
                out.add(new Chunk(sec.heading(), piece));
            }
        }
        return out.stream().filter(c -> !c.text().isBlank()).toList();
    }

    /**
     * 切块（只取正文）。
     *
     * @deprecated 新代码请用 {@link #splitWithHeadings} —— 带上下文的版本检索效果更好
     */
    @Deprecated
    public static List<String> split(String text) {
        return splitPlain(text);
    }

    /**
     * 送进嵌入模型的文本：把「文档标题 · 小节」前置到块正文之前。
     * <p>这一步是 contextual retrieval 的核心：只喂正文时，向量里没有"这段在讲什么主题"的信息；
     * 前置上下文之后，连标题里出现的词也能召回这个块。
     */
    public static String embedText(String docTitle, String heading, String body) {
        StringBuilder sb = new StringBuilder();
        if (docTitle != null && !docTitle.isBlank()) {
            sb.append(docTitle.trim());
        }
        if (heading != null && !heading.isBlank()) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(heading.trim());
        }
        if (sb.length() == 0) {
            return body == null ? "" : body;
        }
        return sb.append('\n').append(body == null ? "" : body).toString();
    }

    private static List<String> splitPlain(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        String s = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (s.length() <= TARGET) {
            out.add(s);
            return out;
        }
        // ① 先按空行切段（Markdown/文档的自然边界）
        List<String> paras = new ArrayList<>();
        for (String p : s.split("\n\\s*\n")) {
            String t = p.trim();
            if (!t.isEmpty()) {
                paras.add(t);
            }
        }
        StringBuilder cur = new StringBuilder();
        for (String p : paras) {
            if (p.length() > MAX) {
                // ② 超长段落（如整篇没有空行的代码/论文）：先把不完整的收尾，再按句子滑窗切
                if (cur.length() > 0) {
                    out.add(cur.toString().trim());
                    cur.setLength(0);
                }
                out.addAll(splitLong(p));
                continue;
            }
            if (cur.length() > 0 && cur.length() + p.length() + 1 > TARGET) {
                out.add(cur.toString().trim());
                // 重叠：把上一块尾部几个字符带进下一块（保持句子连续感）
                String tail = tailOf(cur.toString(), OVERLAP);
                cur.setLength(0);
                if (!tail.isEmpty()) {
                    cur.append(tail).append('\n');
                }
            }
            if (cur.length() > 0) {
                cur.append('\n');
            }
            cur.append(p);
        }
        if (cur.length() > 0) {
            out.add(cur.toString().trim());
        }
        return out.stream().filter(x -> !x.isBlank()).toList();
    }

    /** 超长段落按句子边界滑窗 */
    private static List<String> splitLong(String p) {
        List<String> out = new ArrayList<>();
        // 句子边界：中英文句号/问号/感叹号/分号/换行
        String[] sentences = p.split("(?<=[。！？；!?;\\n])");
        StringBuilder cur = new StringBuilder();
        for (String sen : sentences) {
            if (cur.length() > 0 && cur.length() + sen.length() > TARGET) {
                out.add(cur.toString().trim());
                String tail = tailOf(cur.toString(), OVERLAP);
                cur.setLength(0);
                if (!tail.isEmpty()) {
                    cur.append(tail);
                }
            }
            cur.append(sen);
            // 单句仍然超长（极端情况）：硬切
            while (cur.length() > MAX) {
                out.add(cur.substring(0, MAX).trim());
                String rest = cur.substring(MAX - OVERLAP);
                cur.setLength(0);
                cur.append(rest);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString().trim());
        }
        return out;
    }

    private static String tailOf(String s, int n) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String t = s.trim();
        return t.length() <= n ? t : t.substring(t.length() - n);
    }
}
