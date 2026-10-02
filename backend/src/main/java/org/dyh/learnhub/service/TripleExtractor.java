package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.ai.DeepSeekClient;
import org.dyh.learnhub.ai.ModelRouting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 信息抽取（Information Extraction）：从素材里抽出概念三元组 <b>（头实体，关系，尾实体）</b>。
 *
 * <p>对应图谱方法论第二层，三个子任务一起做（**联合抽取**，而不是先 NER 再 RE 分两步）：
 * <ol>
 *   <li><b>NER</b>：认出素材里的技术概念（不限于人名地名 —— 这个库的"实体"是技术概念/工具/语言）。</li>
 *   <li><b>RE</b>：判定两者关系，且关系必须落在 {@link KgOntology} 的封闭词表里。</li>
 *   <li><b>实体链接</b>：抽出来的名字先经 {@link EntityLinker} 归一，再交给 {@link KgGraphService}
 *       解析到已有节点或新建 —— "这次 Git、上次 git"由此收成同一个点。</li>
 * </ol>
 *
 * <p>为什么联合抽取：分两步做的话，NER 认错的名字会直接把 RE 带偏（误差传播），
 * 而且两次调用要各付一次 token。一次问清楚"这两个概念之间是什么关系"反而更省、更准。
 *
 * <p>三道校验，任一条不过就丢弃该三元组：
 * <ol>
 *   <li>关系必须在词表里（认不出来丢弃，**不兜底成 related_to** —— 兜底会让图里全是无信息的虚线）；</li>
 *   <li>头尾必须是像实体的短名词（长度 ≤ {@link EntityLinker#MAX_NAME_CHARS}、不是整句话、头尾不同）；</li>
 *   <li>必须带一句**原文证据**：没有证据的三元组不进图。这条是防幻觉最有效的一道 ——
 *       模型知道要抄原文，就不会凭"常识"编关系。</li>
 * </ol>
 *
 * <p>抽取目标走 {@link ModelRouting#TASK_TRIPLE}（默认云端）。抽 JSON 时强制关思考：
 * 思考 token 与正文共用 max_tokens，实测会把预算吃光导致空内容（见 README 里那节复盘）。
 */
@Service
public class TripleExtractor {

    private static final Logger log = LoggerFactory.getLogger(TripleExtractor.class);

    /** 一次送进去的素材字符数（分批，避免超长导致截断） */
    private static final int BATCH_CHARS = 4000;
    /** 每批最多接受多少条三元组 */
    private static final int MAX_PER_BATCH = 12;
    /** 抽取时的输出预算：思考关了，这个额度对 JSON 足够 */
    private static final int MAX_TOKENS = 2500;

    private final DeepSeekClient client;
    private final ModelRouting routing;
    private final ObjectMapper objectMapper;

    public TripleExtractor(DeepSeekClient client, ModelRouting routing, ObjectMapper objectMapper) {
        this.client = client;
        this.routing = routing;
        this.objectMapper = objectMapper;
    }

    /**
     * 一条通过校验的三元组。
     *
     * @param evidence 原文证据句（必须是素材里的原话）
     * @param cite     来源标注，形如 {@code 笔记#5}
     */
    public record Triple(String head, String relation, String tail, String evidence, String cite, double weight) {
    }

    /** 抽取结果：三元组 + 过程统计（界面要能看出"抽了多少、丢了多少"） */
    public record Result(List<Triple> triples, int batches, int rejectedByOntology, int rejectedByShape,
                         int rejectedByDedup, String model, boolean cloud) {
    }

    /**
     * 从素材里抽三元组。
     *
     * @param material 素材文本，里面用 {@code [笔记#N] 《标题》} 标注每条来源（与 wiki 编译同一套标注）
     * @param known    已知实体名（给模型对齐用；也能显著减少"换个写法又建一个点"）
     */
    public Result extract(String material, List<String> known) {
        ModelRouting.ModelTarget t = routing.forTask(ModelRouting.TASK_TRIPLE);
        boolean cloud = !t.separate();
        List<String> parts = split(material, BATCH_CHARS);
        List<Triple> accepted = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int rejectedOntology = 0;
        int rejectedShape = 0;
        int rejectedDedup = 0;
        int batches = 0;
        for (int i = 0; i < parts.size(); i++) {
            batches++;
            String system = systemPrompt(known);
            String user = "素材（第 " + (i + 1) + "/" + parts.size() + " 段）：\n" + parts.get(i);
            String reply;
            try {
                reply = chat(system, user, t);
            } catch (Exception e) {
                log.warn("三元组抽取失败（跳过这批）：{}", e.toString());
                continue;
            }
            if (!StringUtils.hasText(reply)) {
                log.warn("三元组抽取返回空内容，跳过这批");
                continue;
            }
            JsonNode arr;
            try {
                JsonNode root = objectMapper.readTree(stripFence(reply));
                arr = root.path("triples");
                if (!arr.isArray()) {
                    arr = root.isArray() ? root : objectMapper.createArrayNode();
                }
            } catch (Exception e) {
                log.warn("三元组抽取返回的 JSON 解析失败：{}｜原文前 200 字：{}", e.toString(),
                        reply.length() > 200 ? reply.substring(0, 200) : reply);
                continue;
            }
            int taken = 0;
            for (JsonNode n : arr) {
                if (taken >= MAX_PER_BATCH) {
                    break;
                }
                String head = text(n, "head", "a", "subject", "头实体");
                String rel = text(n, "relation", "rel", "predicate", "relation_type", "关系");
                String tail = text(n, "tail", "b", "object", "尾实体");
                String evidence = text(n, "evidence", "quote", "证据", "原文");
                String cite = text(n, "cite", "source", "来源");
                String canon = KgOntology.canonical(rel);
                if (canon == null) {
                    rejectedOntology++;
                    continue;
                }
                if (!EntityLinker.plausible(head) || !EntityLinker.plausible(tail)
                        || EntityLinker.same(head, tail)) {
                    rejectedShape++;
                    continue;
                }
                // 证据句必须真的在素材里出现过（防编造）：只做宽松包含判断，标点差异不算。
                // 未核对的三元组**不丢**（模型常改写证据而非原样抄，实测 #97 的原话在素材里但引用被改写），
                // 但必须降权：以前一律给 0.9，"有没有原文支持"在数据上完全不可区分（评估报告 P0-D）。
                boolean verified = StringUtils.hasText(evidence) && containsLoose(parts.get(i), evidence);
                if (!verified) {
                    evidence = null;
                }
                String key = EntityLinker.normalize(head) + "|" + canon + "|" + EntityLinker.normalize(tail);
                if (!seen.add(key)) {
                    rejectedDedup++;
                    continue;
                }
                accepted.add(new Triple(EntityLinker.display(head), canon, EntityLinker.display(tail),
                        evidence, cite, verified ? 0.9 : 0.4));
                taken++;
            }
        }
        log.info("三元组抽取：{} 批 → 接受 {} 条（关系不在本体 {} / 形状不合法 {} / 重复 {}），模型={}",
                batches, accepted.size(), rejectedOntology, rejectedShape, rejectedDedup, t.model());
        return new Result(accepted, batches, rejectedOntology, rejectedShape, rejectedDedup, t.model(), cloud);
    }

    /** 只抽一条特定素材（给"增量更新"用） */
    public Result extractOne(String material, List<String> known) {
        return extract(material, known);
    }

    private String systemPrompt(List<String> known) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                你是知识图谱的信息抽取器。从给定素材里抽取**概念之间的三元组**：（头实体，关系，尾实体）。

                硬要求：
                1. 只抽素材里**确实写着**的关系，不得使用你自己的常识补充；抽不出来就少抽，宁缺毋滥；
                2. relation 必须从下面的词表里选一个 id（不是中文标签）：
                """);
        sb.append(KgOntology.promptTable());
        sb.append("""
                3. head / tail 是**技术概念、工具、语言、命令或术语**的短名字（≤20 字），不能是整句话、不能是"这段/本文"这类指代；
                4. 关系方向要看清：「A 属于 B」的头是 A、尾是 B；反过来写就错了；
                5. evidence 必须**原样抄**素材里能支撑这条关系的那句话（可以截短，但字词要一致）；
                6. cite 写成来源标注，形如 笔记#5 / 速查卡#3 / 资料#2；
                7. 只输出严格 JSON：{"triples":[{"head":"Git","relation":"part_of","tail":"版本控制","evidence":"...","cite":"笔记#5"}]}
                最多 12 条；一条都抽不出就输出 {"triples":[]}。不要输出 JSON 之外的内容。
                """);
        if (known != null && !known.isEmpty()) {
            List<String> top = known.size() > 60 ? known.subList(0, 60) : known;
            sb.append("\n已知概念（**优先复用这些写法**，写法一致才能连到同一个点上）：\n");
            sb.append(String.join("、", top)).append('\n');
        }
        return sb.toString();
    }

    private String chat(String system, String user, ModelRouting.ModelTarget t) {
        List<Map<String, String>> msgs = new ArrayList<>();
        Map<String, String> sys = new LinkedHashMap<>();
        sys.put("role", "system");
        sys.put("content", system);
        msgs.add(sys);
        Map<String, String> u = new LinkedHashMap<>();
        u.put("role", "user");
        u.put("content", user);
        msgs.add(u);
        try {
            JsonNode node = client.chat(msgs, null, t.baseUrl(), t.apiKey(), t.model(),
                    MAX_TOKENS, 0.2,
                    // 强制关思考：见 README「空 content 会被静默当成没事发生」
                    "disabled",
                    null,
                    Duration.ofMinutes(3));
            return node.path("content").asText("");
        } catch (Exception e) {
            throw new IllegalStateException("三元组抽取调用失败：" + e.getMessage());
        }
    }

    private static String text(JsonNode n, String... keys) {
        for (String k : keys) {
            JsonNode v = n.path(k);
            if (v.isTextual() && !v.asText().isBlank()) {
                return v.asText().trim();
            }
        }
        return "";
    }

    /** 宽松包含：去掉空白与常见标点后判断子串（证据句常被模型截断或改标点） */
    private static boolean containsLoose(String haystack, String needle) {
        String h = squash(haystack);
        String s = squash(needle);
        if (s.length() < 6) {
            return false;
        }
        if (h.contains(s)) {
            return true;
        }
        // 证据句可能跨了素材里的截断标记，退回用前半句判断
        String half = s.substring(0, Math.max(6, s.length() / 2));
        return h.contains(half);
    }

    private static String squash(String s) {
        return s == null ? "" : s.replaceAll("[\\s\\u00a0]+", "")
                .replaceAll("[，。、；：！？“”‘’（）()\\[\\]【】《》\"'`,.;:!?]", "");
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

    /** 按段落边界切分，尽量不把一句话切断 */
    static List<String> split(String text, int max) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        String[] blocks = text.split("\\n\\s*\\n");
        StringBuilder cur = new StringBuilder();
        for (String b : blocks) {
            if (cur.length() > 0 && cur.length() + b.length() + 2 > max) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            if (b.length() > max) {
                // 单块超长（资料正文常这样）：硬切
                for (int i = 0; i < b.length(); i += max) {
                    out.add(b.substring(i, Math.min(b.length(), i + max)));
                }
                continue;
            }
            if (cur.length() > 0) {
                cur.append("\n\n");
            }
            cur.append(b);
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }
}
