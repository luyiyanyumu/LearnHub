package org.dyh.learnhub.service;

import org.dyh.learnhub.common.PageResult;
import org.dyh.learnhub.vo.NoteVO;
import org.dyh.learnhub.vo.QuickRefVO;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识库统一检索：把「笔记」「速查卡」「资料库」三块知识源合并成一个搜索入口。
 * <p>
 * 资料库是 2026-09 接进来的第三源：上传时抽出的正文（文本/PDF/Office）与手填说明一起参与匹配。
 * 之所以接在这里：前端检索页与智能体的 {@code search_knowledge} 工具都走这个收口，
 * 改一处两处同时生效。
 * <p>个人规模（几百条）用 LIKE 全文匹配足够，不引入全文索引；
 * 返回统一形状 {type, id, title, snippet, categoryName, updatedAt} 供前端聚合展示、
 * 也供 AI 智能体的 search_knowledge 工具直接消费。
 */
@Service
public class KnowledgeService {

    private final NoteService noteService;
    private final QuickRefService quickRefService;
    private final FileStorageService fileStorageService;

    public KnowledgeService(NoteService noteService, QuickRefService quickRefService,
                            FileStorageService fileStorageService) {
        this.noteService = noteService;
        this.quickRefService = quickRefService;
        this.fileStorageService = fileStorageService;
    }

    /** 每类知识源最多返回条数 */
    private static final int PER_SOURCE_LIMIT = 8;
    /** 摘要片段最大长度 */
    private static final int SNIPPET_LEN = 120;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 统一检索。
     *
     * @param kw 关键词；空白时返回「最近知识」（最近笔记 + 最近速查卡）
     * @return {keyword, total, items:[{type,id,title,snippet,categoryName,updatedAt}]}
     */
    public Map<String, Object> search(String kw) {
        String keyword = kw == null ? "" : kw.trim();
        List<Map<String, Object>> items = new ArrayList<>();

        PageResult<NoteVO> notePage = noteService.page(null, null, keyword, 1, PER_SOURCE_LIMIT);
        for (NoteVO n : notePage.getList()) {
            // 列表 VO 刻意不含 content（LONGTEXT 优化），但片段窗口需要原文 ——
            // 命中条数被 PER_SOURCE_LIMIT 卡住，逐条取详情的代价可控
            String content = null;
            try {
                content = noteService.detail(n.getId()).getContent();
            } catch (Exception ignored) {
                // 笔记刚被删等极端情况：降级用摘要展示
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "note");
            item.put("id", n.getId());
            item.put("title", n.getTitle());
            item.put("snippet", snippet(n.getSummary(), content, keyword));
            item.put("categoryName", n.getCategoryName());
            item.put("updatedAt", n.getUpdatedAt() == null ? null : TS.format(n.getUpdatedAt()));
            items.add(item);
        }

        List<QuickRefVO> refs = quickRefService.list(null, keyword);
        refs.stream().limit(PER_SOURCE_LIMIT).forEach(r -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "quick_ref");
            item.put("id", r.getId());
            item.put("title", r.getTitle());
            item.put("snippet", snippet(null, r.getContent(), keyword));
            item.put("categoryName", r.getCategoryName());
            item.put("updatedAt", r.getUpdatedAt() == null ? null : TS.format(r.getUpdatedAt()));
            items.add(item);
        });

        // 第三源：资料库（上传时抽取的正文 + 手填说明）
        items.addAll(fileItems(keyword));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("keyword", keyword);
        out.put("total", items.size());
        out.put("items", items);
        return out;
    }

    /** 只跑词面检索（体检用：与语义检索做对照） */
    public List<Map<String, Object>> keywordOnly(String kw) {
        String keyword = kw == null ? "" : kw.trim();
        List<Map<String, Object>> items = new ArrayList<>();
        for (NoteVO n : noteService.page(null, null, keyword, 1, PER_SOURCE_LIMIT).getList()) {
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("type", "note");
            it.put("id", n.getId());
            it.put("title", n.getTitle());
            it.put("snippet", snippet(n.getSummary(), null, keyword));
            items.add(it);
        }
        quickRefService.list(null, keyword).stream().limit(PER_SOURCE_LIMIT).forEach(r -> {
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("type", "quick_ref");
            it.put("id", r.getId());
            it.put("title", r.getTitle());
            it.put("snippet", snippet(null, r.getContent(), keyword));
            items.add(it);
        });
        for (Map<String, Object> f : fileStorageService.searchFiles(keyword, PER_SOURCE_LIMIT)) {
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("type", "file");
            it.put("id", ((Number) f.get("id")).longValue());
            it.put("title", str(f.get("originName")));
            it.put("snippet", snippet(str(f.get("summary")), str(f.get("text")), keyword));
            items.add(it);
        }
        return items;
    }

    /** 资料库来源：文件名 / 手填说明 / 抽取正文三处一起匹配 */
    private List<Map<String, Object>> fileItems(String keyword) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> f : fileStorageService.searchFiles(keyword, PER_SOURCE_LIMIT)) {
            String name = str(f.get("originName"));
            String summary = str(f.get("summary"));
            String text = str(f.get("text"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "file");
            item.put("id", ((Number) f.get("id")).longValue());
            item.put("title", name);
            // 有正文就用正文做命中窗口（能看出"为什么搜到它"），否则退回手填说明
            item.put("snippet", snippet(summary, text, keyword));
            item.put("categoryName", str(f.get("categoryName")));
            item.put("ext", str(f.get("ext")));
            item.put("textChars", f.get("textChars") == null ? 0 : f.get("textChars"));
            item.put("textStatus", str(f.get("textStatus")));
            Object createdAt = f.get("createdAt");
            item.put("updatedAt", createdAt == null ? null : String.valueOf(createdAt).replace('T', ' '));
            out.add(item);
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /**
     * 取展示片段：有关键词且原文能命中时，优先给「命中位置的上下文窗口」——
     * 让用户一眼看出为什么这条结果会被搜到（展示摘要看不出匹配原因）；
     * 否则退回现成摘要（笔记的 plainSummary 已清洗过）；再不行给开头。
     */
    static String snippet(String summary, String content, String keyword) {
        // 窗口搜索统一在「保留代码」的文本上做：LIKE 命中的词可能在代码块里，
        // plainText 会把围栏内容剃掉导致窗口搜索落空；keepCode 只删围栏标记行，更稳妥。
        String plain = keepCode(content);
        boolean hasKw = keyword != null && !keyword.isBlank();
        if (hasKw && plain.length() > SNIPPET_LEN) {
            Matcher m = Pattern.compile(Pattern.quote(keyword), Pattern.CASE_INSENSITIVE).matcher(plain);
            if (m.find()) {
                int start = Math.max(0, m.start() - 40);
                int end = Math.min(plain.length(), start + SNIPPET_LEN);
                return (start > 0 ? "…" : "") + plain.substring(start, end) + (end < plain.length() ? "…" : "");
            }
        }
        if (summary != null && !summary.isBlank()) {
            return summary;
        }
        if (plain.length() <= SNIPPET_LEN) {
            return plain;
        }
        return plain.substring(0, SNIPPET_LEN) + "…";
    }

    /** 保留代码内容的清洗：只删围栏标记行，再走通用清洗（去标签/行首标题/Markdown 记号/压平空白） */
    static String keepCode(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return baseClean(content.replaceAll("(?m)^\\s*(```|~~~).*$", " "));
    }

    private static String baseClean(String s) {
        return s.replaceAll("(?s)<[^<>]{0,300}>", " ")
                .replaceAll("(?m)^\\s*#{1,6}\\s*", "")
                .replaceAll("[`*_~\\[\\]]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
