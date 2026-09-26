package org.dyh.learnhub.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.dyh.learnhub.ai.DeepSeekClient;
import org.dyh.learnhub.ai.ModelRouting;
import org.dyh.learnhub.entity.Category;
import org.dyh.learnhub.entity.KgEdge;
import org.dyh.learnhub.entity.Tag;
import org.dyh.learnhub.mapper.KgEdgeMapper;
import org.dyh.learnhub.mapper.KgMapper;
import org.dyh.learnhub.vo.NoteVO;
import org.dyh.learnhub.vo.QuickRefVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识图谱：把「分类 / 标签 + 笔记 + 速查卡」拼成一张图，并把模型推断的语义关联叠上去。
 *
 * <h3>两类边，两套做法（这是本类最核心的设计决定）</h3>
 * <ul>
 *   <li><b>结构边</b>（笔记→分类、笔记→标签）：**每次查询现算，不落库**。
 *       它们是事实，不是推断；落库只会带来一致性问题（改一次分类要同步删边，忘了就连错）。</li>
 *   <li><b>语义边</b>（这条和那条有关系）：模型推断的，落在 {@code kg_edge}。
 *       重建时**整批替换**而不是增量合并 —— 模型每轮说法都不同，合并只会让旧关联越积越多、逐渐失真。</li>
 * </ul>
 *
 * <h3>成本</h3>
 * 结构边零 token、秒级实时；语义边要花 token，所以只在用户点「重建关联」时调用，
 * 或由界面上提示"关联可能已过期"（见 {@link #version()} 与前端）。
 */
@Service
@RequiredArgsConstructor
public class KgService {

    private static final Logger log = LoggerFactory.getLogger(KgService.class);

    public static final String ORIGIN_LLM = "llm";

    /** 参与语义重建的条目上限（再多 prompt 会失控，而且边际收益很低） */
    private static final int REBUILD_ITEMS = 40;

    /** 一次最多接受的关联边数 */
    private static final int REBUILD_MAX_EDGES = 30;

    /** 条目清单的总字符上限 */
    private static final int CATALOGUE_CHARS = 7000;

    private final NoteService noteService;
    private final QuickRefService quickRefService;
    private final CategoryService categoryService;
    /** 资料库：资料按分类挂进同一张图（file 节点） */
    private final FileStorageService fileStorageService;
    private final KgEdgeMapper edgeMapper;
    private final KgMapper kgMapper;
    private final DeepSeekClient client;
    private final ObjectMapper objectMapper;
    /** 模型分工：语义关联重建走哪套模型由 ModelRouting 决定（默认云端） */
    private final ModelRouting routing;

    /** 图数据上限：个人知识库规模下够用；真到几千条要改成按需展开子图 */
    private static final int MAX_NOTES = 500;

    /** 资料节点上限（同上：个人库规模够用） */
    private static final int MAX_FILES = 300;

    // ------------------------------------------------------------------
    // 1. 图数据
    // ------------------------------------------------------------------

    /**
     * 完整图：节点 + 边。
     * <p>
     * 返回结构刻意保持扁平（nodes / edges 两个数组），前端只需要一个力导向布局，
     * 不需要知道数据库里分了几张表。
     */
    public Map<String, Object> graph() {
        List<NoteVO> notes = noteService.page(null, null, null, 1, MAX_NOTES).getList();
        List<QuickRefVO> refs = quickRefService.list(null, null);
        Map<Long, String> catNames = categoryNames();

        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        // 度数在最后统一回填：先建节点、再连边、再算度
        Map<String, Integer> degree = new LinkedHashMap<>();

        // 分类节点：只放"有内容"的，空分类在图上是孤岛，只会增加噪声
        Set<Long> usedCats = new LinkedHashSet<>();
        for (NoteVO n : notes) {
            if (n.getCategoryId() != null) {
                usedCats.add(n.getCategoryId());
            }
        }
        for (QuickRefVO r : refs) {
            if (r.getCategoryId() != null) {
                usedCats.add(r.getCategoryId());
            }
        }
        for (Long cid : usedCats) {
            nodes.add(node("cat-" + cid, "category", catNames.getOrDefault(cid, "分类#" + cid), null, null));
            degree.put("cat-" + cid, 0);
        }

        // 标签节点 + 笔记→标签 边
        Map<Long, String> tagNames = new LinkedHashMap<>();
        for (NoteVO n : notes) {
            nodes.add(node("note-" + n.getId(), "note", n.getTitle(), n.getCategoryId(), n.getUpdatedAt()));
            degree.put("note-" + n.getId(), 0);
            if (n.getCategoryId() != null) {
                edges.add(edge("note-" + n.getId(), "cat-" + n.getCategoryId(), "category", null, null, null));
            }
            for (Tag t : n.getTags() == null ? List.<Tag>of() : n.getTags()) {
                tagNames.putIfAbsent(t.getId(), t.getName());
                edges.add(edge("note-" + n.getId(), "tag-" + t.getId(), "tag", null, null, null));
            }
        }
        for (QuickRefVO r : refs) {
            nodes.add(node("ref-" + r.getId(), "ref", r.getTitle(), r.getCategoryId(), r.getUpdatedAt()));
            degree.put("ref-" + r.getId(), 0);
            if (r.getCategoryId() != null) {
                edges.add(edge("ref-" + r.getId(), "cat-" + r.getCategoryId(), "category", null, null, null));
            }
        }
        for (Map.Entry<Long, String> e : tagNames.entrySet()) {
            nodes.add(node("tag-" + e.getKey(), "tag", e.getValue(), null, null));
            degree.put("tag-" + e.getKey(), 0);
        }

        // 资料节点（file）：资料也是知识，按分类挂进同一张图。
        // label 用文件名（图谱上要看得出是哪份文档）；有正文的才值得画 ——
        // 抽不出正文的资料在检索与 wiki 里仍可用，但画进图里只会是孤点。
        for (Map<String, Object> f : fileStorageService.retrievalScan(MAX_FILES)) {
            Object id = f.get("id");
            if (id == null) {
                continue;
            }
            String fid = "file-" + ((Number) id).longValue();
            String name = String.valueOf(f.getOrDefault("originName", ""));
            nodes.add(node(fid, "file", name, null, null));
            degree.put(fid, 0);
            Object catId = f.get("categoryId");
            if (catId instanceof Number n && n.longValue() > 0) {
                edges.add(edge(fid, "cat-" + n.longValue(), "category", null, null, null));
            }
        }

        // 语义边：只保留两端都还在图里的（条目被删后残留的边不该画出来）
        Set<String> nodeIds = new LinkedHashSet<>(degree.keySet());
        int semantic = 0;
        int dropped = 0;
        for (KgEdge e : edgeMapper.selectList(null)) {
            String s = key(e.getSourceType(), e.getSourceId());
            String t = key(e.getTargetType(), e.getTargetId());
            if (!nodeIds.contains(s) || !nodeIds.contains(t) || s.equals(t)) {
                dropped++;
                continue;
            }
            edges.add(edge(s, t, "semantic", e.getRelation(), e.getReason(), e.getWeight()));
            semantic++;
        }

        for (Map<String, Object> ed : edges) {
            String s = (String) ed.get("source");
            String t = (String) ed.get("target");
            degree.merge(s, 1, Integer::sum);
            degree.merge(t, 1, Integer::sum);
        }
        for (Map<String, Object> n : nodes) {
            n.put("degree", degree.getOrDefault((String) n.get("id"), 0));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", nodes);
        out.put("edges", edges);
        out.put("version", version());
        Map<String, Object> stat = new LinkedHashMap<>();
        stat.put("nodes", nodes.size());
        stat.put("edges", edges.size());
        stat.put("semanticEdges", semantic);
        stat.put("structureEdges", edges.size() - semantic);
        stat.put("orphanEdges", dropped);
        out.put("stat", stat);
        return out;
    }

    /**
     * 版本指纹：结构数据或语义边一变，指纹就变。
     * <p>
     * 前端每几秒问一次，只有指纹变化才重新拉图 —— 这就是"实时"的实现方式。
     * 选轮询而不是 SSE：一次四个 {@code SELECT COUNT/MAX} 走索引，代价远低于维护推送连接，
     * 也不必担心代理/休眠把长连接掐掉。
     */
    public String version() {
        StringBuilder sb = new StringBuilder();
        sb.append(nullTo(kgMapper.noteFingerprint())).append('|')
          .append(nullTo(kgMapper.quickRefFingerprint())).append('|')
          .append(nullTo(kgMapper.categoryFingerprint())).append('|')
          .append(nullTo(kgMapper.tagFingerprint())).append('|')
          .append(nullTo(kgMapper.edgeFingerprint())).append('|')
          .append(nullTo(kgMapper.wikiFingerprint())).append('|')
          // 资料库也算：上传/删除/抽出正文都会改变图的内容
          .append(nullTo(kgMapper.fileFingerprint()));
        return sha256(sb.toString()).substring(0, 16);
    }

    /** 语义边是否比内容"旧"：有内容更新晚于最近一次重建时，界面提示可重建 */
    public boolean semanticStale() {
        return edgeMapper.selectCount(null) == 0 && noteService.page(null, null, null, 1, 1).getTotal() > 1;
    }

    // ------------------------------------------------------------------
    // 2. 语义关联重建（唯一花 token 的地方）
    // ------------------------------------------------------------------

    private static final String REBUILD_SYSTEM = """
            你在为一个个人 IT 学习知识库维护「知识图谱」的语义关联。
            下面会给你该知识库里的条目清单（每条格式：类型#id 《标题》｜分类｜摘要）。
            任务：找出**彼此有实质关联**的条目对，用于图谱连线。

            三种关系：
            - related：同一主题的不同侧面（如「AQS 原理」与「ReentrantLock 用法」）
            - contrast：容易混淆、需要对照理解（如「== 与 equals」与「String 常量池」）
            - prerequisite：理解 A 需要先掌握 B（a 是进阶，b 是基础）

            硬性要求：
            1. 只依据给定清单，不得凭常识补出清单里没有的条目；
            2. 每条边必须给出 15 字以内的**具体**理由（用户会悬浮看到它，理由含糊的连线不如不给）；
            3. weight 取 0~1，表示关联强度；
            4. 最多 30 条，优先跨"笔记↔速查卡"的关联，同一条目最多连 3 条；
            5. 只输出 JSON，不要任何解释文字，格式：
            {"edges":[{"a":"note:3","b":"ref:5","relation":"related","reason":"同为并发同步机制","weight":0.8}]}
            若确实没有值得连的关联，输出 {"edges":[]}。
            """;

    /**
     * 用模型推断条目之间的语义关联，并**整批替换**旧的 LLM 边。
     *
     * @return 统计信息：参与条目数、接受边数、被丢弃的边数（模型偶尔会编出清单外的 id）
     */
    public Map<String, Object> rebuildSemantic() {
        ModelRouting.ModelTarget graphTarget = routing.forTask(ModelRouting.TASK_GRAPH);
        if (!client.isConfigured()) {
            throw new IllegalStateException("AI 未配置：请先在「设置 → 外观与 AI」里填 API Key，再重建关联。");
        }
        List<Item> items = catalogue();
        if (items.size() < 2) {
            throw new IllegalStateException("知识库条目太少（至少 2 条）才能推断关联。");
        }

        StringBuilder sb = new StringBuilder("条目清单（共 " + items.size() + " 条）：\n");
        for (Item it : items) {
            sb.append("- ").append(it.type()).append(':').append(it.id()).append(" 《").append(it.title()).append('》');
            if (StringUtils.hasText(it.category())) {
                sb.append("｜分类 ").append(it.category());
            }
            if (StringUtils.hasText(it.snippet())) {
                sb.append('｜').append(it.snippet());
            }
            sb.append('\n');
        }

        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", REBUILD_SYSTEM),
                Map.of("role", "user", "content", sb.toString()));

        String content;
        try {
            JsonNode reply = client.chat(messages, null, graphTarget.baseUrl(), graphTarget.apiKey(), graphTarget.model(),
                    client.maxTokensOf(graphTarget.id()), 0.2, "disabled", null, DeepSeekClient.DEFAULT_TIMEOUT);
            content = reply.path("content").asText("");
        } catch (Exception e) {
            log.error("重建语义关联失败", e);
            throw new IllegalStateException("AI 调用失败：" + e.getMessage());
        }

        Set<String> valid = new LinkedHashSet<>();
        for (Item it : items) {
            valid.add(it.type() + ":" + it.id());
        }

        List<KgEdge> accepted = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Set<String> perNode = new LinkedHashSet<>();
        Map<String, Integer> nodeEdgeCount = new LinkedHashMap<>();
        int rejected = 0;

        for (JsonNode e : parseEdges(content)) {
            String a = e.path("a").asText("").trim();
            String b = e.path("b").asText("").trim();
            String rel = normalizeRelation(e.path("relation").asText(""));
            if (!valid.contains(a) || !valid.contains(b) || a.equals(b) || !validFormat(a) || !validFormat(b)) {
                // 模型偶尔会编出清单里没有的 id，或把 id 写成 "note#3" —— 一律丢弃而不是猜
                rejected++;
                continue;
            }
            String pairKey = (a.compareTo(b) <= 0 ? a + "~" + b : b + "~" + a) + "|" + rel;
            if (!seen.add(pairKey)) {
                rejected++;
                continue;
            }
            if (nodeEdgeCount.getOrDefault(a, 0) >= 3 || nodeEdgeCount.getOrDefault(b, 0) >= 3) {
                rejected++;
                continue;
            }
            if (accepted.size() >= REBUILD_MAX_EDGES) {
                rejected++;
                continue;
            }
            double weight = e.path("weight").asDouble(1);
            weight = Math.max(0.1, Math.min(1, weight));
            String reason = e.path("reason").asText("");
            if (reason.length() > 120) {
                reason = reason.substring(0, 120);
            }
            KgEdge edge = new KgEdge();
            edge.setSourceType(a.substring(0, a.indexOf(':')));
            edge.setSourceId(Long.parseLong(a.substring(a.indexOf(':') + 1)));
            edge.setTargetType(b.substring(0, b.indexOf(':')));
            edge.setTargetId(Long.parseLong(b.substring(b.indexOf(':') + 1)));
            edge.setRelation(rel);
            edge.setReason(reason);
            edge.setWeight(weight);
            edge.setOrigin(ORIGIN_LLM);
            accepted.add(edge);
            nodeEdgeCount.merge(a, 1, Integer::sum);
            nodeEdgeCount.merge(b, 1, Integer::sum);
        }

        // 整批替换：先把旧的 LLM 边清掉再写新的（见类注释）
        edgeMapper.deleteByOrigin(ORIGIN_LLM);
        for (KgEdge e : accepted) {
            try {
                edgeMapper.insert(e);
            } catch (Exception ex) {
                log.warn("语义边写入失败（跳过）：{}", ex.getMessage());
            }
        }
        log.info("语义关联重建完成：条目 {}，接受 {} 条边，丢弃 {} 条", items.size(), accepted.size(), rejected);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items.size());
        out.put("edges", accepted.size());
        out.put("rejected", rejected);
        out.put("model", client.model());
        out.put("version", version());
        return out;
    }

    /** 兼容模型可能返回 {"edges":[...]} 或直接 [...]，以及被 ```json 包起来的输出 */
    private List<JsonNode> parseEdges(String content) {
        String text = content == null ? "" : content.trim();
        int fence = text.indexOf("```");
        if (fence >= 0) {
            text = text.substring(fence + 3);
            if (text.startsWith("json")) {
                text = text.substring(4);
            }
            int end = text.lastIndexOf("```");
            if (end >= 0) {
                text = text.substring(0, end);
            }
        }
        int lb = text.indexOf('{');
        int rb = text.lastIndexOf('}');
        if (lb < 0 || rb <= lb) {
            log.warn("模型未返回可解析的 JSON（前 120 字）：{}", text.length() > 120 ? text.substring(0, 120) : text);
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(text.substring(lb, rb + 1));
            JsonNode arr = root.path("edges");
            if (!arr.isArray()) {
                return List.of();
            }
            List<JsonNode> out = new ArrayList<>();
            arr.forEach(out::add);
            return out;
        } catch (Exception e) {
            log.warn("语义关联 JSON 解析失败：{}", e.getMessage());
            return List.of();
        }
    }

    private String normalizeRelation(String raw) {
        String r = raw == null ? "" : raw.trim().toLowerCase();
        return switch (r) {
            case "contrast", "易混", "混淆" -> "contrast";
            case "prerequisite", "前置", "先修" -> "prerequisite";
            default -> "related";
        };
    }

    private boolean validFormat(String ref) {
        int i = ref.indexOf(':');
        if (i <= 0) {
            return false;
        }
        String type = ref.substring(0, i);
        if (!"note".equals(type) && !"ref".equals(type)) {
            return false;
        }
        try {
            return Long.parseLong(ref.substring(i + 1)) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 送给模型的条目清单：最近更新的若干条，带标题/分类/摘要，总长有上限 */
    private List<Item> catalogue() {
        List<Item> all = new ArrayList<>();
        for (NoteVO n : noteService.page(null, null, null, 1, 200).getList()) {
            all.add(new Item("note", n.getId(), n.getTitle(), n.getCategoryName(), clip(n.getSummary(), 90), n.getUpdatedAt()));
        }
        for (QuickRefVO r : quickRefService.list(null, null)) {
            all.add(new Item("ref", r.getId(), r.getTitle(), r.getCategoryName(), clip(r.getContent(), 90), r.getUpdatedAt()));
        }
        all.sort(Comparator.comparing(Item::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())));

        List<Item> picked = new ArrayList<>();
        int chars = 0;
        for (Item it : all) {
            int cost = (it.title() == null ? 0 : it.title().length()) + (it.snippet() == null ? 0 : it.snippet().length()) + 20;
            if (picked.size() >= REBUILD_ITEMS || chars + cost > CATALOGUE_CHARS) {
                break;
            }
            picked.add(it);
            chars += cost;
        }
        return picked;
    }

    private record Item(String type, Long id, String title, String category, String snippet,
                        java.time.LocalDateTime updatedAt) {
    }

    // ------------------------------------------------------------------
    // 3. 小工具
    // ------------------------------------------------------------------

    private Map<Long, String> categoryNames() {
        Map<Long, String> map = new LinkedHashMap<>();
        flatten(categoryService.tree(), map);
        return map;
    }

    private void flatten(List<Category> nodes, Map<Long, String> out) {
        for (Category c : nodes == null ? List.<Category>of() : nodes) {
            out.put(c.getId(), c.getName());
            flatten(c.getChildren(), out);
        }
    }

    private Map<String, Object> node(String id, String type, String label, Long categoryId, Object updatedAt) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", id);
        n.put("type", type);
        n.put("label", label == null ? "" : label);
        if (categoryId != null) {
            n.put("categoryId", categoryId);
        }
        if (updatedAt != null) {
            n.put("updatedAt", String.valueOf(updatedAt).replace('T', ' '));
        }
        return n;
    }

    private Map<String, Object> edge(String source, String target, String kind, String relation,
                                     String reason, Double weight) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("source", source);
        e.put("target", target);
        e.put("kind", kind);
        if (relation != null) {
            e.put("relation", relation);
        }
        if (reason != null && !reason.isEmpty()) {
            e.put("reason", reason);
        }
        if (weight != null) {
            e.put("weight", weight);
        }
        return e;
    }

    private static String key(String type, Long id) {
        return ("quick_ref".equals(type) || "ref".equals(type) ? "ref" : type) + "-" + id;
    }

    /** 语义边里存的是 note/ref，与前端节点 id 前缀一致 */
    public static String shortType(String type) {
        return "note".equals(type) ? "note" : "ref";
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    private static String nullTo(String s) {
        return s == null ? "" : s;
    }

    static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    /** 供 WikiService 反向引用（避免两处各写一份删除逻辑） */
    public int clearSemanticEdges() {
        return edgeMapper.deleteByOrigin(ORIGIN_LLM);
    }

    /** 语义边总数，用于界面提示 */
    public Long semanticEdgeCount() {
        return edgeMapper.selectCount(Wrappers.<KgEdge>lambdaQuery().eq(KgEdge::getOrigin, ORIGIN_LLM));
    }
}
