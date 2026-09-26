package org.dyh.learnhub.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML → Markdown（零依赖）。
 *
 * <h3>为什么要做这一步</h3>
 * 抓回来的网页直接塞给模型有两个问题：一是标签噪声会吃掉大量 token；
 * 二是"纯文本化"会把**最有价值的结构**（代码块、列表、标题）一起抹平 ——
 * 而读官方文档/报错页时，恰恰是这几样最要紧。DSH 的 {@code dsh-tool-web} 也是
 * 在抓取层之上单独做 HTML→markdown（抓取与呈现分离），这里对齐它的意图。
 *
 * <h3>实现取舍</h3>
 * 用正则而不是 HTML 解析器：不引依赖、行为可预测，对"文档类页面"够用。
 * 代价是复杂页面（嵌套表格、奇怪的内联样式）转得不完美 —— 但输出只要比纯文本更有结构即可，
 * 模型并不需要像素级还原。为此按**保序流水线**处理，且先把 {@code <pre>} 整段挖出来占位，
 * 避免代码内部的尖括号/星号被后续规则误伤（这是最容易出错的一步）。
 */
public final class HtmlToMarkdown {

    private HtmlToMarkdown() {
    }

    /** 挖掉这些元素：它们的内容不是正文（脚本、样式、隐藏模板、图标 svg） */
    private static final Pattern DROP = Pattern.compile(
            "(?is)<(script|style|noscript|template|svg|iframe|head)\\b[^>]*>.*?</\\1>");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    /** pre 代码块：连 language-xxx 类名一起捕获，用来标语言 */
    private static final Pattern PRE = Pattern.compile(
            "(?is)<pre\\b[^>]*>(.*?)</pre>");
    private static final Pattern PRE_CLASS = Pattern.compile(
            "(?is)<code\\b[^>]*class\\s*=\\s*[\"'][^\"']*(?:language|lang)-([\\w+#-]+)");
    /** 标题、列表、引用等块级元素的边界 */
    private static final Pattern H = Pattern.compile("(?is)<h([1-6])\\b[^>]*>(.*?)</h\\1>");
    private static final Pattern LI = Pattern.compile("(?is)<li\\b[^>]*>(.*?)</li>");
    private static final Pattern BLOCKQUOTE = Pattern.compile("(?is)<blockquote\\b[^>]*>(.*?)</blockquote>");
    private static final Pattern IMG = Pattern.compile("(?is)<img\\b[^>]*?src\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>");
    private static final Pattern A = Pattern.compile("(?is)<a\\b[^>]*?href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>");
    private static final Pattern CODE = Pattern.compile("(?is)<code\\b[^>]*>(.*?)</code>");
    private static final Pattern STRONG = Pattern.compile("(?is)<(strong|b)\\b[^>]*>(.*?)</\\1>");
    private static final Pattern EM = Pattern.compile("(?is)<(em|i)\\b[^>]*>(.*?)</\\1>");
    private static final Pattern DEL = Pattern.compile("(?is)<(del|s)\\b[^>]*>(.*?)</\\1>");
    /** 换行语义：这些标签一律变成换行（段与段之间是空行，其余是单换行） */
    private static final Pattern PARA_BREAK = Pattern.compile(
            "(?i)</?(p|div|section|article|header|footer|main|aside|nav|form|figure|figcaption|tr|table|ul|ol|dl|dd|dt)\\b[^>]*>");
    private static final Pattern LINE_BREAK = Pattern.compile("(?i)<(br|hr)\\b[^>]*>|</?(span|label|time|small|sup|sub|u)\\b[^>]*>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]+>");

    /**
     * 转换入口。
     *
     * @param html 原始 HTML（可为 null）
     * @return 尽量保留结构与代码块的 Markdown 文本
     */
    public static String convert(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        String s = mainContent(html);
        s = COMMENT.matcher(s).replaceAll(" ");
        s = DROP.matcher(s).replaceAll(" ");

        // ① 先把 <pre> 整段挖出来占位：代码里出现 <、*、_、# 都不能被后面任何规则碰到
        List<String> blocks = new ArrayList<>();
        Matcher pre = PRE.matcher(s);
        StringBuilder withPlaceholders = new StringBuilder();
        int last = 0;
        while (pre.find()) {
            withPlaceholders.append(s, last, pre.start());
            String inner = pre.group(1);
            String lang = detectLang(inner) != null ? detectLang(inner) : detectLang(pre.group(0));
            String code = stripTags(inner).replace("\r\n", "\n").replace('\r', '\n');
            code = decodeEntities(code);
            // 去掉每行尾部空格与整体首尾空行；行首缩进保留（代码语义）
            code = code.replaceAll("(?m)[ \t]+$", "").replaceAll("^\\n+", "").replaceAll("\\n+$", "");
            blocks.add("```" + (lang == null ? "" : lang) + "\n" + code + "\n```");
            withPlaceholders.append("\u0000BLOCK").append(blocks.size() - 1).append("\u0000");
            last = pre.end();
        }
        withPlaceholders.append(s.substring(last));
        s = withPlaceholders.toString();

        // ② 行内与块级：顺序有意如此（先具体、后笼统）
        s = IMG.matcher(s).replaceAll(m -> "![](" + m.group(1).trim() + ")");
        s = A.matcher(s).replaceAll(m -> "[" + stripTags(m.group(2)).trim() + "](" + m.group(1).trim() + ")");
        s = CODE.matcher(s).replaceAll(m -> "`" + stripTags(m.group(1)).trim() + "`");
        s = STRONG.matcher(s).replaceAll(m -> "**" + m.group(2).trim() + "**");
        s = EM.matcher(s).replaceAll(m -> "*" + m.group(2).trim() + "*");
        s = DEL.matcher(s).replaceAll(m -> "~~" + m.group(2).trim() + "~~");

        s = H.matcher(s).replaceAll(m -> "\n\n" + "#".repeat(Integer.parseInt(m.group(1))) + " "
                + m.group(2).trim() + "\n\n");
        s = LI.matcher(s).replaceAll(m -> "\n- " + m.group(1).trim());
        s = BLOCKQUOTE.matcher(s).replaceAll(m -> "\n\n> " + m.group(1).trim().replace("\n", "\n> ") + "\n\n");

        s = PARA_BREAK.matcher(s).replaceAll("\n\n");
        s = LINE_BREAK.matcher(s).replaceAll(m -> "<hr".equalsIgnoreCase(m.group(0).substring(0, 3)) ? "\n\n---\n\n" : "\n");
        s = TAG.matcher(s).replaceAll(" ");

        s = decodeEntities(s);
        // ③ 折叠空白：行内多空格合一；三行以上压成两行；列表项之间不留空行
        s = s.replace('\u00a0', ' ').replaceAll("[\\t\\x0B\\f\\r ]+", " ");
        s = s.replaceAll("(?m) *\\n *", "\n").replaceAll("\\n{3,}", "\n\n");
        s = s.replaceAll("(?m)^- \\n", "");
        s = s.replaceAll("\\n- ", "\n- ");

        // ④ 把代码块放回去
        for (int i = 0; i < blocks.size(); i++) {
            s = s.replace("\u0000BLOCK" + i + "\u0000", "\n\n" + blocks.get(i) + "\n\n");
        }
        return s.replaceAll("\\n{3,}", "\n\n").trim();
    }

    /**
     * 只取"主内容"那一块：优先 {@code <main>}，其次 {@code <article>}，都没有才用整个 body。
     * <p>
     * 实测动机：Spring 官方文档整页转出来 6.2 万字，其中 300 多条列表项几乎全是导航菜单 ——
     * 既费 token 又稀释重点。抓"文档/文章页"时这个启发式收益很大；
     * 老站点没有 main/article 就自然回落到整页，不会把内容弄丢。
     */
    private static String mainContent(String html) {
        for (String tag : new String[]{"main", "article"}) {
            Matcher m = Pattern.compile("(?is)<" + tag + "\\b[^>]*>(.*?)</" + tag + ">").matcher(html);
            if (m.find()) {
                String inner = m.group(1);
                // 太短的多半是"页面里恰好有个空的 main"，此时不要它
                if (inner.length() > 400) {
                    return inner;
                }
            }
        }
        Matcher body = Pattern.compile("(?is)<body\\b[^>]*>(.*?)</body>").matcher(html);
        return body.find() ? body.group(1) : html;
    }

    private static String detectLang(String html) {
        Matcher m = PRE_CLASS.matcher(html);
        return m.find() ? m.group(1).toLowerCase() : null;
    }

    private static String stripTags(String s) {
        return TAG.matcher(s).replaceAll(" ");
    }

    /** 只处理最常见的实体；HTML5 命名实体的全集不值得在这里复刻 */
    static String decodeEntities(String s) {
        return s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                .replace("&mdash;", "—").replace("&ndash;", "–").replace("&hellip;", "…")
                .replace("&amp;", "&");
    }
}
