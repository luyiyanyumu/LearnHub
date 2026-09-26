package org.dyh.learnhub.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dyh.learnhub.ai.DeepSeekClient;
import org.dyh.learnhub.ai.ModelRouting;
import org.dyh.learnhub.entity.WikiPage;
import org.dyh.learnhub.mapper.WikiPageMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语义 Lint：定期/按需给知识库做一次"体检"。
 *
 * <h3>为什么分成"代码检查"和"模型检查"两半</h3>
 * 有些问题**代码能百分之百判定**，就不该交给模型（模型会漏、会说废话、还花钱）：
 * <ul>
 *   <li><b>红链</b>：正文里 `[[某概念]]` 但系统里没有这一页 —— 这是知识缺口的**最硬信号**</li>
 *   <li><b>质量有提示的页</b>：生成时质量校验标了 warn 的</li>
 *   <li><b>覆盖率过低的主题页</b>：素材只进了个位数百分比</li>
 *   <li><b>空页 / 过短页</b></li>
 * </ul>
 * 另一些只有模型能看出来：**跨页矛盾、被素材推翻的过时说法、真正的知识缺口**。
 * 两半各写一节，结论写进一页（`lint`），界面可读、可重跑。
 *
 * <p>模型部分走 {@link ModelRouting#TASK_LINT}（默认云端）：找矛盾属于判断类任务，
 * 本地小模型容易"看不出问题"——那比报错更糟（会让人误以为库是干净的）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LintService {

    private final WikiPageMapper mapper;
    private final DeepSeekClient client;
    private final ModelRouting routing;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /** 双链写法：`[[名字]]` */
    private static final Pattern WIKILINK = Pattern.compile("\\[\\[([^\\[\\]]{1,40})\\]\\]");

    /**
     * 跑一次自检并写入 `lint` 页。
     *
     * @return {codeIssues, modelIssues, pageChars, model, cloud}
     */
    public Map<String, Object> run() {
        List<WikiPage> pages = mapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                .select(WikiPage::getTopicKey, WikiPage::getTopicType, WikiPage::getTitle,
                        WikiPage::getContentMd, WikiPage::getQuality, WikiPage::getQualityNote,
                        WikiPage::getItemCount)
                .ne(WikiPage::getTopicKey, "lint"));

        // ---------- ① 代码检查（确定性，不花 token）----------
        Set<String> pageTitles = new LinkedHashSet<>();
        Set<String> entityKeys = new LinkedHashSet<>();
        for (WikiPage p : pages) {
            if (p.getTitle() != null) {
                pageTitles.add(p.getTitle().trim());
            }
            if ("entity".equals(p.getTopicType())) {
                entityKeys.add(p.getTopicKey());
            }
        }
        List<Map<String, Object>> codeIssues = new ArrayList<>();
        Map<String, Set<String>> redLinks = new LinkedHashMap<>();
        for (WikiPage p : pages) {
            if (p.getContentMd() == null) {
                continue;
            }
            Matcher m = WIKILINK.matcher(p.getContentMd());
            while (m.find()) {
                String name = m.group(1).trim();
                if (!pageTitles.contains(name)) {
                    redLinks.computeIfAbsent(p.getTitle(), k -> new LinkedHashSet<>()).add(name);
                }
            }
        }
        for (Map.Entry<String, Set<String>> e : redLinks.entrySet()) {
            codeIssues.add(issue("红链", e.getKey(), "提到了但还没有页面：" + String.join("、", e.getValue()),
                    "值得成页就补一页，否则改成普通文字"));
        }
        for (WikiPage p : pages) {
            if ("warn".equalsIgnoreCase(p.getQuality())) {
                codeIssues.add(issue("质量", p.getTitle(), p.getQualityNote() == null ? "生成时校验未通过" : p.getQualityNote(),
                        "重新生成这一页，或按提示补素材"));
            }
            int len = p.getContentMd() == null ? 0 : p.getContentMd().length();
            if (len < 200) {
                codeIssues.add(issue("过短", p.getTitle(), "只有 " + len + " 字",
                        "素材可能不足，先补素材再生成"));
            }
        }

        // ---------- ② 模型检查（矛盾 / 过时 / 缺口）----------
        List<Map<String, Object>> modelIssues = new ArrayList<>();
        String modelName;
        boolean cloud;
        try {
            ModelRouting.ModelTarget t = routing.forTask(ModelRouting.TASK_LINT);
            modelName = t.model();
            cloud = !t.separate();
            String material = renderPages(pages);
            String system = """
                    你是知识库的审校者。下面是一个个人 IT 知识库的全部页面（标题 + 正文摘录）。
                    你的任务：只找出**跨页层面**的问题，不要复述内容、不要提排版建议。
                    检查三类：
                    1. contradiction —— 两个页面（或同一页不同小节）互相矛盾；
                    2. outdated —— 某页的说法与另一页的更新信息冲突，或在同一库里已明显过时；
                    3. gap —— 明显缺失、且库里已有材料足以补齐的知识点。
                    只输出严格 JSON：{"issues":[{"kind":"contradiction|outdated|gap","pages":["页标题"],"problem":"问题(≤40字)","suggestion":"怎么改(≤40字)"}]}
                    最多 6 条；没有问题就返回 {"issues":[]}。不要输出 JSON 之外的内容。
                    """;
            String reply = client.chat(List.of(
                            Map.of("role", "system", "content", system),
                            Map.of("role", "user", "content", material)),
                    null, t.baseUrl(), t.apiKey(), t.model(), 3000, 0.2,
                    // 强制关思考：本步骤只输出 JSON，思考 token 与正文共用预算，
                    // 实测 1500/2500 预算下思考都会把预算吃光 → 空内容（这也是本步骤之前的静默失败源）
                    "disabled",
                    t.separate() ? null : client.reasoningEffortOf(t.id()),
                    java.time.Duration.ofMinutes(3)).path("content").asText("");
            if (reply.isBlank()) {
                // 空内容时 readTree("") 返回 MissingNode，遍历它 0 条且不抛异常 ——
                // 结果会被写成"语义检查 0 问题"，等于把失败伪装成"很干净"。必须显式抛错走下面的 catch。
                throw new IllegalStateException("模型返回空内容（通常是思考 token 占满预算），本次自检结果不可信");
            }
            JsonNode arr = objectMapper.readTree(stripFence(reply)).path("issues");
            for (JsonNode n : arr) {
                List<String> involved = new ArrayList<>();
                for (JsonNode x : n.path("pages")) {
                    involved.add(x.asText(""));
                }
                modelIssues.add(issue(n.path("kind").asText("gap"), String.join("、", involved),
                        n.path("problem").asText(""), n.path("suggestion").asText("")));
            }
        } catch (Exception e) {
            modelIssues.add(issue("error", "", "语义检查失败：" + e.getMessage(), "稍后重试"));
            modelName = routing.forTask(ModelRouting.TASK_LINT).model();
            cloud = routing.isCloud(ModelRouting.TASK_LINT);
        }

        // ---------- ③ 写入 lint 页 ----------
        String md = render(codeIssues, modelIssues, pages.size());
        upsert(md, codeIssues.size() + modelIssues.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("codeIssues", codeIssues.size());
        out.put("modelIssues", modelIssues.size());
        out.put("pageChars", md.length());
        out.put("model", modelName);
        out.put("cloud", cloud);
        out.put("code", codeIssues);
        out.put("modelFindings", modelIssues);
        log.info("语义自检完成：代码问题 {} 条，模型发现 {} 条（模型 {}）", codeIssues.size(), modelIssues.size(), modelName);
        return out;
    }

    private static Map<String, Object> issue(String kind, String where, String problem, String suggestion) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("where", where);
        m.put("problem", problem);
        m.put("suggestion", suggestion);
        return m;
    }

    private String render(List<Map<String, Object>> code, List<Map<String, Object>> model, int pageCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("对 **").append(pageCount).append("** 个页面做了自检。")
          .append("代码检查是确定性的，模型检查只报跨页问题。\n\n");
        sb.append("## 代码检查（确定性，不会漏）\n");
        if (code.isEmpty()) {
            sb.append("- 没有问题 ✓（没有红链、没有质量提示页、没有过短页）\n");
        } else {
            for (Map<String, Object> i : code) {
                sb.append("- **").append(i.get("kind")).append("**｜").append(i.get("where"))
                  .append("：").append(i.get("problem"))
                  .append("　→ ").append(i.get("suggestion")).append('\n');
            }
        }
        sb.append("\n## 语义检查（模型，只报跨页问题）\n");
        if (model.isEmpty()) {
            sb.append("- 没有发现跨页矛盾或明显缺口\n");
        } else {
            for (Map<String, Object> i : model) {
                sb.append("- **").append(i.get("kind")).append("**｜").append(i.get("where"))
                  .append("：").append(i.get("problem"))
                  .append("　→ ").append(i.get("suggestion")).append('\n');
            }
        }
        sb.append("\n> 模型部分说\"没问题\"也可能真的没问题 —— 它只读到页面摘录，没读原始素材。\n");
        return sb.toString();
    }

    private String renderPages(List<WikiPage> pages) {
        StringBuilder sb = new StringBuilder();
        int budget = 9000;
        for (WikiPage p : pages) {
            String body = p.getContentMd() == null ? "" : p.getContentMd();
            String excerpt = body.length() > 700 ? body.substring(0, 700) : body;
            if (sb.length() + excerpt.length() > budget) {
                break;
            }
            sb.append("\n### ").append(p.getTitle()).append("（").append(p.getTopicType()).append("）\n")
              .append(excerpt).append('\n');
        }
        return sb.toString();
    }

    private void upsert(String md, int issues) {
        WikiPage page = mapper.selectOne(Wrappers.<WikiPage>lambdaQuery().eq(WikiPage::getTopicKey, "lint"));
        boolean create = page == null;
        if (create) {
            page = new WikiPage();
            page.setTopicKey("lint");
        }
        page.setTopicType("lint");
        page.setTopicId(0L);
        page.setTitle("知识自检");
        page.setContentMd(md);
        page.setSourceHash("lint-" + issues);
        page.setItemCount(issues);
        page.setModel(routing.forTask(ModelRouting.TASK_LINT).model());
        page.setQuality("ok");
        page.setTargetId(routing.targetIdOf(ModelRouting.TASK_LINT));
        page.setGeneratedAt(LocalDateTime.now());
        if (create) {
            mapper.insert(page);
        } else {
            mapper.updateById(page);
        }
    }

    private static String stripFence(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) {
                t = t.substring(nl + 1);
            }
            int end = t.lastIndexOf("```");
            if (end >= 0) {
                t = t.substring(0, end);
            }
        }
        return t.trim();
    }
}
