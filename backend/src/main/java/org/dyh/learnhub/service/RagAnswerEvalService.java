package org.dyh.learnhub.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.dyh.learnhub.ai.DeepSeekClient;
import org.dyh.learnhub.ai.ModelRouting;
import org.dyh.learnhub.entity.KbIndexState;
import org.dyh.learnhub.entity.RagEval;
import org.dyh.learnhub.entity.RagEvalAnswerRun;
import org.dyh.learnhub.mapper.KbIndexStateMapper;
import org.dyh.learnhub.mapper.RagEvalAnswerRunMapper;
import org.dyh.learnhub.mapper.RagEvalMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * **答案级**评测：把"检索变好了"推进到"答得对不对"。
 *
 * <h3>为什么检索评测不够</h3>
 * {@link RagEvalService} 只回答一个问题：期望来源有没有进 top-k。
 * 它看不见另外三类真实缺陷，而这三类恰恰是用户能直接感知的：
 * <ol>
 *   <li><b>答非所问</b> —— 来源召回了，答案却说错了（生成环节的问题）；</li>
 *   <li><b>无据发挥</b> —— 材料里没有的事实被模型用常识补全，听起来对、库里没写；</li>
 *   <li><b>该说不知道时没说</b> —— 库里根本没有这条，模型却硬编一个答案。</li>
 * </ol>
 *
 * <h3>四个维度（对应评估报告的要求）</h3>
 * <ul>
 *   <li>{@code answerAccuracy} 答案正确性：{@code expect_words}（人工标注的关键事实）在答案里的覆盖率。
 *       <b>这是代理指标</b>：覆盖不等于正确（说反了也可能覆盖到词），但它是可复现、零额外成本的信号；
 *       报告要求的"关键差异人工核对"仍然必要，逐条答案都存进了 detail 供人工复核。</li>
 *   <li>{@code citationSupport} 引用支持：用 {@link GroundingService} 判定答案有没有超出注入的证据。</li>
 *   <li>{@code citationCoverage} 引用完整性：多来源题的**必要来源是否全部被召回**（ALL 而非 ANY）。
 *       报告明确要求"不能仅命中任一文件即成功"，ANY 口径会高估跨资料题的表现。</li>
 *   <li>{@code noAnswerScore} 无答案处理：**缺口题**的正确拒答率（该说不知道时到底说了没有）。
 *       "有材料却拒答"是另一种失败模式（实测多源于"来源召回了、注入的证据却没带上那段细节"），
 *       单独记在 {@code falseRefusals} 里，**不并进这个分数** —— 否则一个 0.75 会看起来像
 *       "模型 25% 的时间在瞎答"，而实际是两种问题各占一半，混在一起就解释不清了。</li>
 * </ul>
 * 另外记录耗时与 token（输入/输出/其中思考），并可选用**设置里的单价**估算成本。
 *
 * <h3>可复现性</h3>
 * 每次运行都把**语料指纹 / 模型 / prompt 版本 / 注入臂**一起落库：
 * 这四个量任一变了，两次分数就不可比。没有它们，历史记录会看起来像"退步了"，其实只是换了条件。
 */
@Service
public class RagAnswerEvalService {

    private static final Logger log = LoggerFactory.getLogger(RagAnswerEvalService.class);

    /**
     * 生成/评分 prompt 的版本号。**改了那段 system prompt 就必须改这个号** ——
     * 否则历史里的分数会被误当成"同条件下的变化"。
     */
    public static final String PROMPT_VERSION = "answer-eval-v1";

    /** 注入模型的证据文本上限（检索块本身已有预算，wiki/图谱会再加，这里兜总账） */
    private static final int EVIDENCE_CHARS = 8000;

    /** 单条答案的输出上限 */
    private static final int ANSWER_MAX_TOKENS = 1500;

    /** 单次生成调用的超时 */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(120);

    /** 默认跑多少条：答案级评测每条要真调一次模型，不限量会跑到天荒地老 */
    public static final int DEFAULT_LIMIT = 10;

    /** detail 里每条答案保留的最大字数（人工复核够用，又不至于把 detail 撑爆） */
    private static final int DETAIL_ANSWER_CHARS = 2000;

    /** 单价设置键（每百万 token）。不配就是 0 —— 宁可不给成本，也不要给一个编出来的数。 */
    public static final String KEY_PRICE_IN = "kb.eval.price_in_per_m";
    public static final String KEY_PRICE_OUT = "kb.eval.price_out_per_m";

    /**
     * 明确表示"答不了"的说法：出现在**任何位置**都判为拒答。
     *
     * <p>这几个词本身就是"我拒绝作答"的意思，不会作为范围声明出现在一段正常回答的末尾。
     */
    private static final List<String> STRONG_REFUSAL_MARKERS = List.of(
            "无法回答", "不能回答", "无法作答", "无法根据", "无法据此",
            "没有足够", "不足以回答", "不足以确定");

    /**
     * 缺失类说法：**只有出现在回答开头**才算拒答。
     *
     * <p>为什么要加"位置"这个条件 —— 这是实测踩出来的：
     * 第一版只要出现这些词就算拒答，结果三条**完全正确**的回答全被判成"误拒"，
     * noAnswerScore 从真实的 1.0 掉到 0.25。原因是模型习惯在正确回答的**末尾**补一句
     * "材料未覆盖底层实现细节"（范围声明，不是拒答），而"没有相关"正好命中了标志词。
     *
     * <p>判别口径因此改成：<b>态度看开头</b>。一句话以"材料里没有……"起头 = 拒答；
     * 以结论起头、末尾附带说明范围 = 正常回答。答案原文都落进 detail，人工可复核。
     */
    private static final List<String> ABSENCE_MARKERS = List.of(
            "没有找到", "未找到", "没有相关", "无相关", "没有收录", "未收录", "没有记录", "没有提到",
            "未提及", "没有提及", "没有涉及", "未涉及", "资料中没有", "材料中没有", "知识库中没有",
            "库里没有", "没有这方面的", "不包含", "无法确定", "无法从", "没有检索到", "没有查到",
            "材料里没有", "资料里没有", "没有覆盖");

    /**
     * 判定"拒答"时只看回答开头这么多字。
     *
     * <p>取 200 是实测出来的安全值：正确的回答把范围声明放在 350 字之后，
     * 而拒答的结论句都在前 40 字内，两边有充足余量。
     */
    private static final int REFUSAL_HEAD_CHARS = 200;

    /**
     * 生成答案用的 system prompt。
     *
     * <p>刻意与线上对话的 system prompt 分开：线上那份还管着工具调用纪律，
     * 而评测里没有工具（模型看到的材料就是全部）。混用会让"答案变差"无法归因到生成还是工具。
     * 三条规则对应三个评测维度：只用材料（引用支持）、材料不足就说（无答案处理）、先结论后依据（正确性）。
     */
    private static final String ANSWER_SYSTEM = """
            你是知识库问答助手。请遵守三条规则：
            1. 只根据下面提供的【材料】回答，不要用你自己的先验知识补充材料里没有的内容；
            2. 如果材料不足以回答问题，必须明确说明"材料里没有相关内容"，并指出缺什么，**不要编造**；
            3. 回答简洁直接：先给结论，再给依据（依据要能对上材料里的具体说法）。
            """;

    private final RagEvalMapper evalMapper;
    private final RagEvalAnswerRunMapper runMapper;
    private final RagEvalService.Retriever retriever;
    private final DeepSeekClient client;
    private final ModelRouting routing;
    private final GroundingService groundingService;
    private final KbIndexStateMapper stateMapper;
    private final SettingsService settingsService;
    private final ObjectMapper objectMapper;

    public RagAnswerEvalService(RagEvalMapper evalMapper, RagEvalAnswerRunMapper runMapper,
                                RagEvalService.Retriever retriever, DeepSeekClient client,
                                ModelRouting routing, GroundingService groundingService,
                                KbIndexStateMapper stateMapper, SettingsService settingsService,
                                ObjectMapper objectMapper) {
        this.evalMapper = evalMapper;
        this.runMapper = runMapper;
        this.retriever = retriever;
        this.client = client;
        this.routing = routing;
        this.groundingService = groundingService;
        this.stateMapper = stateMapper;
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
    }

    /**
     * 跑一轮答案级评测并落库。
     *
     * @param label   标签（历史里按它对比，例如"答案基线"）
     * @param topK    检索取前几名
     * @param limit   最多评多少条（默认 {@link #DEFAULT_LIMIT}；每条都要真调模型，别一次跑太多）
     * @param mode    检索模式 fused / keyword / vector
     * @param onlyIds 只评这几条（不传则评全部 enabled=1；传了就**忽略 enabled**，方便试草稿题）
     */
    public Map<String, Object> run(String label, int topK, int limit, String mode, List<Long> onlyIds) {
        List<RagEval> cases = loadCases(onlyIds);
        if (cases.isEmpty()) {
            throw new IllegalStateException("没有可评测的用例：先往 rag_eval 里写用例（并把 enabled 置 1）");
        }
        int k = topK <= 0 ? 5 : topK;
        int cap = limit <= 0 ? DEFAULT_LIMIT : limit;
        String m = mode == null || mode.isBlank() ? "fused" : mode.trim();
        if (cases.size() > cap) {
            cases = cases.subList(0, cap);
        }

        // 注入臂：报告要的"基础 / +wiki / +图谱 / 组合"四组对照，靠这两个现有开关切换，
        // 结果连同开关状态一起落库，历史里才分得清是哪一组。
        boolean wiki = settingsService.wikiInjectEnabled();
        boolean kg = settingsService.kgInjectEnabled();
        ModelRouting.ModelTarget target = routing.forTask(ModelRouting.TASK_CHAT);

        long t0 = System.currentTimeMillis();
        long promptTokens = 0;
        long completionTokens = 0;

        int labeled = 0;
        double accSum = 0;
        int supportChecked = 0;
        int supportGrounded = 0;
        int coverageTotal = 0;
        int coverageAll = 0;
        int noAnswerJudged = 0;
        int noAnswerCorrect = 0;
        int falseRefusals = 0;

        ArrayNode detail = objectMapper.createArrayNode();
        for (RagEval c : cases) {
            Set<String> expect = RagEvalService.parseRefs(c.getExpectRefs());
            boolean gap = RagEvalService.isGapCase(expect);
            RagEvalService.Evidence ev = retriever.evidenceFor(c.getQuestion(), k, m, wiki, kg);
            String evidence = clip(ev.text(), EVIDENCE_CHARS);
            List<String> topRefs = ev.refs();

            ObjectNode row = objectMapper.createObjectNode();
            row.put("id", c.getId());
            row.put("question", c.getQuestion());
            row.put("expect", String.join("|", expect));
            row.put("gap", gap);
            row.put("top", String.join("|", topRefs));
            row.put("evidenceChars", evidence.length());

            // ---- 生成答案（计时 + 记 token）----
            String answer = "";
            int latency;
            String finish = "";
            int pTok = 0;
            int cTok = 0;
            int rTok = 0;
            long ts = System.currentTimeMillis();
            try {
                List<Object> messages = List.<Object>of(
                        Map.of("role", "system", "content", ANSWER_SYSTEM),
                        Map.of("role", "user", "content", promptOf(c.getQuestion(), evidence)));
                DeepSeekClient.ChatResult r = client.chatFull(messages, null,
                        target.baseUrl(), target.apiKey(), target.model(),
                        ANSWER_MAX_TOKENS, 0.1, "disabled", null, CALL_TIMEOUT);
                answer = r.message().path("content").asText("").trim();
                finish = r.finishReason();
                pTok = r.promptTokens();
                cTok = r.completionTokens();
                rTok = r.reasoningTokens();
            } catch (Exception e) {
                log.warn("答案级评测生成失败（{}）：{}", c.getQuestion(), e.toString());
                row.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
            }
            latency = (int) (System.currentTimeMillis() - ts);
            promptTokens += pTok;
            completionTokens += cTok;

            // ---- 四个维度 ----
            double acc = keywordCoverage(c.getExpectWords(), answer);
            boolean refusal = isRefusal(answer);
            boolean coveredAll = !gap && coversAll(expect, topRefs);

            row.put("answer", clip(answer, DETAIL_ANSWER_CHARS));
            row.put("latencyMs", latency);
            row.put("promptTokens", pTok);
            row.put("completionTokens", cTok);
            row.put("reasoningTokens", rTok);
            row.put("finishReason", finish);
            row.put("refusal", refusal);
            if (acc >= 0) {
                row.put("accuracy", round(acc));
                labeled++;
                accSum += acc;
            } else {
                row.put("accuracy", (String) null);   // 没标 expect_words，不参与正确性均值
            }
            if (gap) {
                row.put("coverageAll", (String) null);
                // 缺口题：该说不知道时说了，才算对。这是 noAnswerScore 唯一的判定来源。
                row.put("noAnswer", refusal ? "ok" : "bad");
                noAnswerJudged++;
                if (refusal) {
                    noAnswerCorrect++;
                }
            } else {
                coverageTotal++;
                row.put("coverageAll", coveredAll);
                if (coveredAll) {
                    coverageAll++;
                }
                if (evidence.isEmpty()) {
                    // 检索什么都没给：此时"拒答"是合理行为，不该算成误拒，
                    // 否则会把"检索失败"错误地记到"答案处理"头上。
                    row.put("noAnswer", "na");
                } else if (refusal) {
                    // 有材料却拒答。**不并进 noAnswerScore** —— 实测这多半不是模型在乱拒，
                    // 而是"来源进了 top-k、但注入的证据没带上需要的那段细节"（见 id=139）。
                    // 把两种失败模式分开记，分数才解释得清。
                    row.put("noAnswer", "false-refusal");
                    falseRefusals++;
                } else {
                    row.put("noAnswer", "ok");
                }
            }
            if (gap || evidence.isEmpty()) {
                row.put("grounded", (String) null);
            } else if (!groundingService.enabled()) {
                row.put("grounded", (String) null);
                row.put("groundingNote", "未开启答案核对（kb.grounding=0）");
            } else {
                GroundingService.Result gr = groundingService.check(c.getQuestion(), evidence, answer);
                if (gr.checked()) {
                    supportChecked++;
                    if (gr.grounded()) {
                        supportGrounded++;
                    }
                    row.put("grounded", gr.grounded());
                    row.put("unsupported", String.join(" | ", gr.unsupported()));
                } else {
                    row.put("grounded", (String) null);
                    row.put("groundingNote", gr.note());
                }
            }
            detail.add(row);
        }

        long elapsed = System.currentTimeMillis() - t0;
        int n = cases.size();
        double accuracy = labeled == 0 ? 0 : round(accSum / labeled);
        double support = supportChecked == 0 ? 0 : round((double) supportGrounded / supportChecked);
        double coverage = coverageTotal == 0 ? 0 : round((double) coverageAll / coverageTotal);
        double noAnswer = noAnswerJudged == 0 ? 0 : round((double) noAnswerCorrect / noAnswerJudged);
        long totalTokens = promptTokens + completionTokens;
        double cost = estimateCost(promptTokens, completionTokens);

        RagEvalAnswerRun run = new RagEvalAnswerRun();
        run.setLabel(label == null || label.isBlank() ? "未命名" : label.trim());
        run.setCases(n);
        run.setTopK(k);
        run.setMode(m);
        run.setCorpusHash(corpusHash());
        run.setModel(target.model());
        run.setPromptVersion(PROMPT_VERSION);
        run.setWikiInject(wiki ? 1 : 0);
        run.setKgInject(kg ? 1 : 0);
        run.setAnswerAccuracy(accuracy);
        run.setCitationSupport(support);
        run.setCitationCoverage(coverage);
        run.setNoAnswerScore(noAnswer);
        run.setFalseRefusals(falseRefusals);
        run.setElapsedMs(elapsed);
        run.setAvgLatencyMs(n == 0 ? 0 : (int) (elapsed / n));
        run.setPromptTokens(promptTokens);
        run.setCompletionTokens(completionTokens);
        run.setTotalTokens(totalTokens);
        run.setEstCost(cost);
        run.setDetail(detail.toString());
        runMapper.insert(run);

        log.info("答案级评测[{}]：{} 条，正确性 {}（{} 条有标注）、引用支持 {}（核对 {} 条）、"
                        + "引用完整性 {}、缺口题拒答 {}（判 {} 条）、有材料却拒答 {} 条、耗时 {}ms、token {}",
                run.getLabel(), n, accuracy, labeled, support, supportChecked,
                coverage, noAnswer, noAnswerJudged, falseRefusals, elapsed, totalTokens);

        Map<String, Object> o = new LinkedHashMap<>();
        o.put("runId", run.getId());
        o.put("label", run.getLabel());
        o.put("cases", n);
        o.put("topK", k);
        o.put("mode", m);
        o.put("wikiInject", wiki);
        o.put("kgInject", kg);
        o.put("model", target.model());
        o.put("promptVersion", PROMPT_VERSION);
        o.put("corpusHash", run.getCorpusHash());
        o.put("answerAccuracy", accuracy);
        o.put("answerAccuracyCases", labeled);
        o.put("citationSupport", support);
        o.put("citationSupportChecked", supportChecked);
        o.put("citationCoverage", coverage);
        o.put("citationCoverageCases", coverageTotal);
        o.put("noAnswerScore", noAnswer);
        o.put("noAnswerJudged", noAnswerJudged);
        o.put("falseRefusals", falseRefusals);
        o.put("elapsedMs", elapsed);
        o.put("promptTokens", promptTokens);
        o.put("completionTokens", completionTokens);
        o.put("totalTokens", totalTokens);
        o.put("estCost", cost);
        return o;
    }

    /** 历史运行（用来对比改动前后） */
    public List<RagEvalAnswerRun> history() {
        return runMapper.selectList(Wrappers.<RagEvalAnswerRun>lambdaQuery()
                .orderByDesc(RagEvalAnswerRun::getId).last("LIMIT 20"));
    }

    private List<RagEval> loadCases(List<Long> onlyIds) {
        if (onlyIds != null && !onlyIds.isEmpty()) {
            // 指定 id 时**忽略 enabled** —— 这样才能在正式启用前先试草稿题
            return evalMapper.selectBatchIds(onlyIds).stream()
                    .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                    .toList();
        }
        return evalMapper.selectList(Wrappers.<RagEval>lambdaQuery()
                .eq(RagEval::getEnabled, 1)
                .orderByAsc(RagEval::getId));
    }

    /** 拼给模型的输入：材料在前、问题在后（与线上注入方向一致） */
    static String promptOf(String question, String evidence) {
        return "【材料】\n" + (evidence == null || evidence.isBlank() ? "（本轮没有检索到任何材料）" : evidence)
                + "\n\n【问题】\n" + question;
    }

    // ------------------------------------------------------------------
    // 纯函数评分（可单测，见 RagAnswerEvalTest）
    // ------------------------------------------------------------------

    /**
     * 答案对人工标注关键事实的覆盖率。
     *
     * @param expectWords {@code 关键词1;关键词2} 或 {@code |} 分隔
     * @return {@code [0,1]}；**没有标注时返回 -1**（表示不参与均值，而不是 0 分 ——
     *         把"没标注"当 0 分会把正确性均值拖垮，那是把缺失数据当成了失败）
     */
    static double keywordCoverage(String expectWords, String answer) {
        List<String> words = splitWords(expectWords);
        if (words.isEmpty()) {
            return -1;
        }
        String hay = answer == null ? "" : answer.toLowerCase(Locale.ROOT);
        int hit = 0;
        for (String w : words) {
            if (hay.contains(w.toLowerCase(Locale.ROOT))) {
                hit++;
            }
        }
        return (double) hit / words.size();
    }

    static List<String> splitWords(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String p : raw.split("[;；|,，]")) {
            String s = p.trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * 答案是否在"承认自己不知道"（拒答）。
     *
     * <p>口径：**强标志任意位置命中**，或**缺失类说法出现在开头 {@link #REFUSAL_HEAD_CHARS} 字内**。
     * 判决理由是实测出来的，见 {@link #ABSENCE_MARKERS} 的注释：不加位置条件会把
     * "正确回答 + 末尾范围声明"误判成拒答。
     */
    static boolean isRefusal(String answer) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        for (String mk : STRONG_REFUSAL_MARKERS) {
            if (answer.contains(mk)) {
                return true;
            }
        }
        String head = answer.length() > REFUSAL_HEAD_CHARS
                ? answer.substring(0, REFUSAL_HEAD_CHARS)
                : answer;
        for (String mk : ABSENCE_MARKERS) {
            if (head.contains(mk)) {
                return true;
            }
        }
        return false;
    }

    /** 多来源题的**必要来源是否全部**进了 top-k（ALL 口径，不是命中任一） */
    static boolean coversAll(Set<String> expect, List<String> refs) {
        if (expect == null || expect.isEmpty() || refs == null || refs.isEmpty()) {
            return false;
        }
        for (String e : expect) {
            if (!refs.contains(e)) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /**
     * 语料指纹：把所有已索引来源的（键 + 内容哈希）排序后哈希。
     *
     * <p>语料一变（加/删/改资料），历史分数就不可比 —— 存下指纹，
     * 才能一眼看出"这次分数变化到底是因为改了检索，还是因为语料变了"。
     */
    String corpusHash() {
        try {
            List<KbIndexState> all = stateMapper.selectList(null);
            List<String> parts = new ArrayList<>();
            for (KbIndexState st : all) {
                parts.add(st.getSourceType() + ":" + st.getSourceId() + ":" + st.getContentHash());
            }
            parts.sort(String::compareTo);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(String.join("\n", parts).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, 16);
        } catch (Exception e) {
            log.warn("语料指纹计算失败（不影响评测）：{}", e.toString());
            return null;
        }
    }

    /** 按设置里的单价估算成本；未配置单价则返回 0（不编造价格） */
    double estimateCost(long promptTokens, long completionTokens) {
        double pin = parsePrice(settingsService.raw(KEY_PRICE_IN));
        double pout = parsePrice(settingsService.raw(KEY_PRICE_OUT));
        if (pin <= 0 && pout <= 0) {
            return 0;
        }
        return round(promptTokens / 1_000_000.0 * pin + completionTokens / 1_000_000.0 * pout);
    }

    private static double parsePrice(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
