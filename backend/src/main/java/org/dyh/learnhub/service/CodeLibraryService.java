package org.dyh.learnhub.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dyh.learnhub.entity.CodeRepo;
import org.dyh.learnhub.entity.CodeSnippet;
import org.dyh.learnhub.entity.CodeSymbol;
import org.dyh.learnhub.mapper.CodeRepoMapper;
import org.dyh.learnhub.mapper.CodeSnippetMapper;
import org.dyh.learnhub.mapper.CodeSymbolMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 代码库：项目的增删改查 + 片段的增删改查 + **索引维护** + 三种检索。
 *
 * <h3>为什么不复用知识库那套检索</h3>
 * 知识检索（{@code kb_chunk}）是为散文设计的：切块按标题、召回靠向量+词面融合。
 * 代码有三点不同：① "在哪定义"是精确问题，向量答不好；② 相似 ≠ 有用（大量样板互相都像）；
 * ③ 代码进同一个索引会把个人笔记挤下去。所以这里**独立存储、独立索引**，
 * 智能体需要时用 {@code search_code} 显式来查。
 *
 * <h3>三种检索模式（成本递增）</h3>
 * <table>
 *   <tr><th>模式</th><th>依据</th><th>适合</th></tr>
 *   <tr><td>{@code symbol}</td><td>符号表（名字精确/前缀）+ 文件路径</td><td>"<code>splitWithHeadings</code> 在哪定义"</td></tr>
 *   <tr><td>{@code keyword}</td><td>标题/说明/标识符/正文 LIKE</td><td>"MQTT 重连怎么写的"</td></tr>
 *   <tr><td>{@code all}</td><td>上面两者合并（符号优先）</td><td>默认</td></tr>
 * </table>
 * 向量语义检索留到 {@code code_chunk} 那一层（可选开关），默认不参与 —— 没必要为"找得到"付嵌入成本。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeLibraryService {

    private final CodeRepoMapper repoMapper;
    private final CodeSnippetMapper snippetMapper;
    private final CodeSymbolMapper symbolMapper;
    private final CodeIndexService indexService;

    // ------------------------------------------------------------------
    // 项目
    // ------------------------------------------------------------------

    public List<Map<String, Object>> repos() {
        List<CodeRepo> rows = repoMapper.selectList(Wrappers.<CodeRepo>lambdaQuery()
                .orderByDesc(CodeRepo::getUpdatedAt));
        List<Map<String, Object>> out = new ArrayList<>();
        for (CodeRepo r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("name", r.getName());
            m.put("url", r.getUrl());
            m.put("demoUrl", r.getDemoUrl());
            m.put("license", r.getLicense());
            m.put("note", r.getNote());
            Long n = snippetMapper.selectCount(Wrappers.<CodeSnippet>lambdaQuery()
                    .eq(CodeSnippet::getRepoId, r.getId()));
            m.put("snippets", n == null ? 0 : n.intValue());
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> saveRepo(Long id, Map<String, Object> body) {
        CodeRepo r = id == null ? new CodeRepo() : repoMapper.selectById(id);
        if (r == null) {
            throw new IllegalArgumentException("项目不存在：" + id);
        }
        r.setName(str(body.get("name"), id == null ? "未命名项目" : r.getName()));
        r.setUrl(str(body.get("url"), null));
        r.setDemoUrl(str(body.get("demoUrl"), null));
        r.setLicense(str(body.get("license"), null));
        r.setNote(str(body.get("note"), null));
        if (id == null) {
            repoMapper.insert(r);
        } else {
            repoMapper.updateById(r);
        }
        return Map.of("id", r.getId(), "name", r.getName());
    }

    /** 删除项目：**片段不删**，只把它们改成"不属于任何项目"（避免连带删掉别人写的说明） */
    public Map<String, Object> deleteRepo(Long id) {
        List<CodeSnippet> mine = snippetMapper.selectList(Wrappers.<CodeSnippet>lambdaQuery()
                .eq(CodeSnippet::getRepoId, id));
        for (CodeSnippet s : mine) {
            s.setRepoId(null);
            snippetMapper.updateById(s);
        }
        repoMapper.deleteById(id);
        return Map.of("detached", mine.size());
    }

    // ------------------------------------------------------------------
    // 片段（写入时同步维护索引）
    // ------------------------------------------------------------------

    public Map<String, Object> detail(Long id) {
        CodeSnippet s = snippetMapper.selectById(id);
        if (s == null) {
            throw new IllegalArgumentException("片段不存在：" + id);
        }
        Map<String, Object> m = view(s);
        m.put("code", s.getCode());
        m.put("explainText", s.getExplainText());
        m.put("symbols", symbolViews(id));
        return m;
    }

    public Map<String, Object> saveSnippet(Long id, Map<String, Object> body) {
        CodeSnippet s = id == null ? new CodeSnippet() : snippetMapper.selectById(id);
        if (s == null) {
            throw new IllegalArgumentException("片段不存在：" + id);
        }
        if (body.containsKey("title")) {
            s.setTitle(str(body.get("title"), s.getTitle()));
        }
        if (body.containsKey("lang")) {
            s.setLang(str(body.get("lang"), null));
        }
        if (body.containsKey("code")) {
            s.setCode(strOrEmpty(body.get("code")));
        }
        if (body.containsKey("explainText")) {
            s.setExplainText(str(body.get("explainText"), null));
        }
        if (body.containsKey("filePath")) {
            s.setFilePath(str(body.get("filePath"), null));
        }
        if (body.containsKey("sourceUrl")) {
            s.setSourceUrl(str(body.get("sourceUrl"), null));
        }
        if (body.containsKey("repoId")) {
            Object v = body.get("repoId");
            s.setRepoId(v == null || String.valueOf(v).isBlank() ? null : Long.valueOf(String.valueOf(v)));
        }
        if (!StringUtils.hasText(s.getTitle())) {
            throw new IllegalArgumentException("标题不能为空");
        }
        if (!StringUtils.hasText(s.getCode())) {
            throw new IllegalArgumentException("代码不能为空");
        }
        s.setLineCount(s.getCode().split("\n", -1).length);
        // 索引与正文一起维护：两者不同步的库比没有索引更糟（搜到的行号是错的）
        s.setSymbols(indexService.extractIdentifiers(s.getCode()));
        if (id == null) {
            snippetMapper.insert(s);
        } else {
            snippetMapper.updateById(s);
        }
        reindexSymbols(s);
        return view(s);
    }

    public void deleteSnippet(Long id) {
        symbolMapper.delete(Wrappers.<CodeSymbol>lambdaQuery().eq(CodeSymbol::getSnippetId, id));
        snippetMapper.deleteById(id);
    }

    /** 重建某个片段的符号索引（先删后插，幂等） */
    private void reindexSymbols(CodeSnippet s) {
        symbolMapper.delete(Wrappers.<CodeSymbol>lambdaQuery().eq(CodeSymbol::getSnippetId, s.getId()));
        List<CodeIndexService.Symbol> found = indexService.extractSymbols(s.getCode(), s.getLang());
        for (CodeIndexService.Symbol sym : found) {
            CodeSymbol row = new CodeSymbol();
            row.setSnippetId(s.getId());
            row.setKind(sym.kind());
            row.setName(sym.name());
            row.setLine(sym.line());
            symbolMapper.insert(row);
        }
        // 文件名本身也算一个符号：搜文件名要能找到这一段
        if (StringUtils.hasText(s.getFilePath())) {
            String base = s.getFilePath().replace('\\', '/');
            base = base.substring(base.lastIndexOf('/') + 1);
            if (StringUtils.hasText(base)) {
                CodeSymbol row = new CodeSymbol();
                row.setSnippetId(s.getId());
                row.setKind("file");
                row.setName(base);
                row.setLine(1);
                symbolMapper.insert(row);
            }
        }
        log.debug("代码片段 {} 符号索引重建：{} 条（语言 {}）", s.getId(), found.size(), s.getLang());
    }

    // ------------------------------------------------------------------
    // 检索
    // ------------------------------------------------------------------

    /**
     * 检索。{@code mode} = symbol / keyword / all（默认 all）。
     * <p>返回里带 {@code hitSymbol} / {@code hitLine}，界面可以直接说"在 L42 定义"。
     */
    public List<Map<String, Object>> search(String q, String mode, String lang, Long repoId, int limit) {
        return search(q, mode, lang, repoId, false, limit);
    }

    /** @param unassigned true = 只看"未归类"的片段（repoId 为空） */
    public List<Map<String, Object>> search(String q, String mode, String lang, Long repoId, boolean unassigned, int limit) {
        String query = q == null ? "" : q.trim();
        if (query.isEmpty()) {
            return list(lang, repoId, limit);
        }
        String m = StringUtils.hasText(mode) ? mode.trim() : "all";
        // LinkedHashMap 去重：同一个片段可能同时命中符号与关键词
        Map<Long, Map<String, Object>> hit = new LinkedHashMap<>();
        if (m.equals("symbol") || m.equals("all")) {
            searchBySymbol(query, hit);
        }
        if (m.equals("keyword") || m.equals("all")) {
            searchByKeyword(query, hit);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : hit.values()) {
            if (lang != null && !lang.isBlank() && !lang.equalsIgnoreCase(String.valueOf(row.get("lang")))) {
                continue;
            }
            if (unassigned) {
                if (row.get("repoId") != null) {
                    continue;
                }
            } else if (repoId != null && !repoId.equals(row.get("repoId"))) {
                continue;
            }
            out.add(row);
        }
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    private void searchBySymbol(String query, Map<Long, Map<String, Object>> hit) {
        String esc = escapeLike(query);
        List<CodeSymbol> syms = symbolMapper.selectList(Wrappers.<CodeSymbol>lambdaQuery()
                .and(w -> w.eq(CodeSymbol::getName, query).or().likeRight(CodeSymbol::getName, esc))
                .orderByAsc(CodeSymbol::getName)
                .last("LIMIT 100"));
        if (syms.isEmpty()) {
            // 前缀没命中时退一步做包含匹配（"WithHeadings" → splitWithHeadings）
            syms = symbolMapper.selectList(Wrappers.<CodeSymbol>lambdaQuery()
                    .like(CodeSymbol::getName, esc)
                    .last("LIMIT 100"));
        }
        for (CodeSymbol sym : syms) {
            Map<String, Object> row = hit.computeIfAbsent(sym.getSnippetId(), sid -> {
                CodeSnippet s = snippetMapper.selectById(sid);
                return s == null ? null : view(s);
            });
            if (row == null) {
                hit.remove(sym.getSnippetId());
                continue;
            }
            if (row.get("hitSymbol") == null) {
                row.put("hitSymbol", sym.getName());
                row.put("hitKind", sym.getKind());
                row.put("hitLine", sym.getLine());
                row.put("matchBy", "symbol");
            }
        }
    }

    private void searchByKeyword(String query, Map<Long, Map<String, Object>> hit) {
        String esc = escapeLike(query);
        List<CodeSnippet> rows = snippetMapper.selectList(Wrappers.<CodeSnippet>lambdaQuery()
                .and(w -> w.like(CodeSnippet::getTitle, esc)
                        .or().like(CodeSnippet::getExplainText, esc)
                        .or().like(CodeSnippet::getSymbols, esc)
                        .or().like(CodeSnippet::getCode, esc))
                .orderByDesc(CodeSnippet::getUpdatedAt)
                .last("LIMIT 100"));
        for (CodeSnippet s : rows) {
            Map<String, Object> row = hit.computeIfAbsent(s.getId(), id -> view(s));
            if (row.get("matchBy") == null) {
                row.put("matchBy", "keyword");
            }
        }
    }

    /**
     * 转义 LIKE 的通配符。
     *
     * <p>不转义会**误命中**：{@code _} 在 LIKE 里是"任意单个字符"，
     * 于是搜 {@code chunk_split} 会命中符号表里 "Chunk" 后跟 "splitWithHeadings" 的片段
     *（中间那个空格被 {@code _} 吃了）—— 实测踩到。同理 {@code %} 会匹配任意串。
     * MySQL 默认用反斜杠做转义符，所以这里加反斜杠即可，无需额外 ESCAPE 子句。
     */
    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public List<Map<String, Object>> list(String lang, Long repoId, int limit) {
        return list(lang, repoId, false, limit);
    }

    public List<Map<String, Object>> list(String lang, Long repoId, boolean unassigned, int limit) {
        List<CodeSnippet> rows = snippetMapper.selectList(Wrappers.<CodeSnippet>lambdaQuery()
                .eq(StringUtils.hasText(lang), CodeSnippet::getLang, lang)
                .isNull(unassigned, CodeSnippet::getRepoId)
                .eq(!unassigned && repoId != null, CodeSnippet::getRepoId, repoId)
                .orderByDesc(CodeSnippet::getUpdatedAt)
                .last("LIMIT " + Math.max(1, Math.min(200, limit))));
        List<Map<String, Object>> out = new ArrayList<>();
        for (CodeSnippet s : rows) {
            out.add(view(s));
        }
        return out;
    }

    /** 统计：给界面显示"这块有多少东西"，也给智能体的工具描述用 */
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("snippets", snippetMapper.selectCount(null));
        m.put("repos", repoMapper.selectCount(null));
        m.put("symbols", symbolMapper.selectCount(null));
        List<Map<String, Object>> langs = new ArrayList<>();
        for (CodeSnippet s : snippetMapper.selectList(Wrappers.<CodeSnippet>lambdaQuery()
                .select(CodeSnippet::getLang))) {
            if (!StringUtils.hasText(s.getLang())) {
                continue;
            }
            String key = s.getLang().toLowerCase();
            langs.stream().filter(x -> key.equals(x.get("lang"))).findFirst().ifPresentOrElse(
                    x -> x.put("count", ((Number) x.get("count")).intValue() + 1),
                    () -> {
                        Map<String, Object> one = new LinkedHashMap<>();
                        one.put("lang", key);
                        one.put("count", 1);
                        langs.add(one);
                    });
        }
        langs.sort((a, b) -> ((Number) b.get("count")).intValue() - ((Number) a.get("count")).intValue());
        m.put("langs", langs);
        return m;
    }

    private List<Map<String, Object>> symbolViews(Long snippetId) {
        List<CodeSymbol> rows = symbolMapper.selectList(Wrappers.<CodeSymbol>lambdaQuery()
                .eq(CodeSymbol::getSnippetId, snippetId)
                .orderByAsc(CodeSymbol::getLine));
        List<Map<String, Object>> out = new ArrayList<>();
        for (CodeSymbol s : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", s.getKind());
            m.put("name", s.getName());
            m.put("line", s.getLine());
            out.add(m);
        }
        return out;
    }

    /** 列表/检索用的轻量视图：**不带 code 正文**（可能很长），列表不需要 */
    private Map<String, Object> view(CodeSnippet s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("title", s.getTitle());
        m.put("lang", s.getLang());
        m.put("repoId", s.getRepoId());
        m.put("filePath", s.getFilePath());
        m.put("sourceUrl", s.getSourceUrl());
        m.put("lineCount", s.getLineCount());
        m.put("explainBrief", brief(s.getExplainText(), 120));
        m.put("updatedAt", s.getUpdatedAt() == null ? null : String.valueOf(s.getUpdatedAt()).replace('T', ' '));
        if (s.getRepoId() != null) {
            CodeRepo r = repoMapper.selectById(s.getRepoId());
            if (r != null) {
                m.put("repoName", r.getName());
                m.put("repoUrl", r.getUrl());
                m.put("demoUrl", r.getDemoUrl());
            }
        }
        return m;
    }

    private static String brief(String text, int max) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String t = text.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private static String str(Object o, String def) {
        if (o == null) {
            return def;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? def : s;
    }

    private static String strOrEmpty(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** 供智能体工具使用：把命中压成一行行文本 */
    public String searchForAgent(String q, String lang, String mode, int limit) {
        String family = StringUtils.hasText(lang) ? lang : null;
        List<Map<String, Object>> rows = search(q, StringUtils.hasText(mode) ? mode : "all", family, null, limit);
        if (rows.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> r : rows) {
            sb.append("· [id=").append(r.get("id")).append("] ").append(r.get("title"));
            if (r.get("lang") != null) {
                sb.append("（").append(r.get("lang")).append("）");
            }
            if (r.get("hitSymbol") != null) {
                sb.append("  命中符号 ").append(r.get("hitSymbol"))
                        .append(" @ 第").append(r.get("hitLine")).append("行");
            }
            if (r.get("repoName") != null) {
                sb.append("  项目：").append(r.get("repoName"));
            }
            if (r.get("explainBrief") != null && !String.valueOf(r.get("explainBrief")).isBlank()) {
                sb.append("\n    ").append(r.get("explainBrief"));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 供智能体工具使用：一段代码的全文 + 说明 + 出处 + 符号表 */
    public String detailForAgent(Long id) {
        CodeSnippet s = snippetMapper.selectById(id);
        if (s == null) {
            return "没有 id=" + id + " 的代码片段";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(s.getTitle()).append("】");
        if (StringUtils.hasText(s.getLang())) {
            sb.append("  语言：").append(s.getLang());
        }
        if (StringUtils.hasText(s.getFilePath())) {
            sb.append("  原文件：").append(s.getFilePath());
        }
        sb.append('\n');
        if (StringUtils.hasText(s.getExplainText())) {
            sb.append("说明：").append(s.getExplainText().trim()).append('\n');
        }
        if (s.getRepoId() != null) {
            CodeRepo r = repoMapper.selectById(s.getRepoId());
            if (r != null) {
                sb.append("项目：").append(r.getName());
                if (StringUtils.hasText(r.getUrl())) {
                    sb.append("  仓库：").append(r.getUrl());
                }
                if (StringUtils.hasText(r.getDemoUrl())) {
                    sb.append("  演示：").append(r.getDemoUrl());
                }
                if (StringUtils.hasText(r.getLicense())) {
                    sb.append("  许可：").append(r.getLicense());
                }
                sb.append('\n');
            }
        }
        List<Map<String, Object>> syms = symbolViews(id);
        if (!syms.isEmpty()) {
            sb.append("符号：");
            for (int i = 0; i < syms.size() && i < 20; i++) {
                sb.append(syms.get(i).get("name"));
                if (i < syms.size() - 1 && i < 19) {
                    sb.append("、");
                }
            }
            if (syms.size() > 20) {
                sb.append(" …共 ").append(syms.size()).append(" 个");
            }
            sb.append('\n');
        }
        sb.append("```").append(s.getLang() == null ? "" : s.getLang()).append('\n')
                .append(s.getCode()).append('\n').append("```\n");
        return sb.toString();
    }

    /** 给"删除后是否还有孤儿引用"这类检查用 */
    public Set<Long> snippetIdsOfRepo(Long repoId) {
        Set<Long> ids = new LinkedHashSet<>();
        for (CodeSnippet s : snippetMapper.selectList(Wrappers.<CodeSnippet>lambdaQuery()
                .eq(CodeSnippet::getRepoId, repoId).select(CodeSnippet::getId))) {
            ids.add(s.getId());
        }
        return ids;
    }
}
