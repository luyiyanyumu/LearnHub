package org.dyh.learnhub.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.dyh.learnhub.ai.EmbeddingClient;
import org.dyh.learnhub.entity.KgNode;
import org.dyh.learnhub.entity.KgRelation;
import org.dyh.learnhub.mapper.KgNodeMapper;
import org.dyh.learnhub.mapper.KgRelationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 概念层知识图谱（属性图）的读写与查询引擎。
 *
 * <p>对应图谱方法论里的第三、四、五层：
 * <ul>
 *   <li><b>存储与融合</b>：节点表 {@code kg_node} + 三元组表 {@code kg_relation}（唯一键 head|relation|tail
 *       保证重复抽取自然合并，不会越积越多）；别名在 {@link EntityLinker} 里归一后落到 {@code norm}，
 *       于是"这次 Git、上次 git"收成同一个节点。</li>
 *   <li><b>推理与查询</b>：{@link KgReasoner} 按本体属性做传递/对称闭包；
 *       查询提供邻居展开、两点间路径、按关系过滤。</li>
 *   <li><b>Graph RAG</b>：{@link #recognize} 从问题里认出实体（名字/别名精确匹配 + 向量兜底），
 *       {@link #retrievalBlock} 做 1~2 跳展开并把三元组、证据句、推导来源拼成上下文注入对话。</li>
 * </ul>
 *
 * <p>规模假设写在明处：这是个人知识库，实体数十、三元组数百。所以遍历是**整图载入内存 + BFS**，
 * 不做图数据库。等实体上千、需要亿级路径查询时再换 Neo4j，现在换只是多一个要运维的服务。
 */
@Service
public class KgGraphService {

    private static final Logger log = LoggerFactory.getLogger(KgGraphService.class);

    /** 邻居展开默认跳数 */
    public static final int DEFAULT_HOPS = 2;
    /** 邻居展开最大跳数（再多结果会爆炸） */
    public static final int MAX_HOPS = 4;
    /** Graph RAG 注入上下文最多带多少条三元组 */
    private static final int BLOCK_MAX_TRIPLES = 6;
    /** 向量兜底识别的相似度下限。实测 0.5 太松：问"容器编排该用什么"会拉进"打印合成后的配置树"这种弱相关概念，
     *  而注入的每一条都会挤占模型上下文，宁缺毋滥。 */
    private static final double VECTOR_MIN_SCORE = 0.62;

    private final KgNodeMapper nodeMapper;
    private final KgRelationMapper relMapper;
    private final EmbeddingClient embedder;

    public KgGraphService(KgNodeMapper nodeMapper, KgRelationMapper relMapper,
                          EmbeddingClient embedder) {
        this.nodeMapper = nodeMapper;
        this.relMapper = relMapper;
        this.embedder = embedder;
    }

    // ==================================================================
    // ① 写入与融合
    // ==================================================================

    /**
     * 建立或更新一个实体节点（实体消解就发生在这里：id 由归一化名决定）。
     *
     * @return 节点 id；名字不像实体时返回 {@code null}
     */
    public String upsertEntity(String name, String type, String brief, String wikiKey) {
        if (!EntityLinker.plausible(name)) {
            return null;
        }
        String id = EntityLinker.id(name);
        String disp = EntityLinker.display(name);
        String norm = EntityLinker.normalize(name);
        KgNode node = nodeMapper.selectById(id);
        List<String> aliases = new ArrayList<>(EntityLinker.aliasCandidates(name));
        if (node == null) {
            node = new KgNode();
            node.setId(id);
            node.setName(disp);
            node.setNorm(norm);
            node.setType(StringUtils.hasText(type) ? type : "concept");
            node.setBrief(clip(brief, 480));
            node.setWikiKey(wikiKey);
            node.setSourceCount(1);
            node.setAliases(join(aliases));
            nodeMapper.insert(node);
            return id;
        }
        // 已存在：并集别名、补空字段，**不覆盖**已有值（先写的通常是更完整的证据）
        Set<String> merged = new LinkedHashSet<>(splitAliases(node.getAliases()));
        merged.addAll(aliases);
        node.setAliases(join(new ArrayList<>(merged)));
        if (!StringUtils.hasText(node.getBrief()) && StringUtils.hasText(brief)) {
            node.setBrief(clip(brief, 480));
        }
        if (!StringUtils.hasText(node.getWikiKey()) && StringUtils.hasText(wikiKey)) {
            node.setWikiKey(wikiKey);
        }
        if (!StringUtils.hasText(node.getType()) || "concept".equals(node.getType())) {
            if (StringUtils.hasText(type)) {
                node.setType(type);
            }
        }
        node.setSourceCount((node.getSourceCount() == null ? 0 : node.getSourceCount()) + 1);
        nodeMapper.updateById(node);
        return id;
    }

    /**
     * 写入一条三元组。重复的 (头,关系,尾) 走合并：证据保留第一条、来源取并集、权重取最大。
     *
     * @param origin llm / derived
     * @return true=新增，false=合并到已有边（或参数不合法被丢弃）
     */
    public boolean upsertTriple(String headName, String relationRaw, String tailName,
                                String evidence, String sources, double weight,
                                String origin, String derivedFrom, String model) {
        String rel = KgOntology.canonical(relationRaw);
        if (rel == null) {
            log.debug("丢弃关系不在本体里的三元组：{} -{}-> {}", headName, relationRaw, tailName);
            return false;
        }
        if (!EntityLinker.plausible(headName) || !EntityLinker.plausible(tailName)) {
            return false;
        }
        // 图谱节点准入规则：命令行片段/选项名/句子碎片不是概念，整条三元组丢弃，
        // 否则它们会以节点的形式留在图里（清理前 135 个节点里有 23 个是
        // docker ps -a / --profile 这类垃圾）。
        // 注意：只管**抽取路径**；人工手动加的概念不走这里，不该被拦。
        if (!admissibleConceptName(headName) || !admissibleConceptName(tailName)) {
            log.debug("丢弃非概念节点：{} -{}-> {}", headName, relationRaw, tailName);
            return false;
        }
        // 头尾归一化后相同 = 自环，多半是模型抽错了
        if (EntityLinker.same(headName, tailName)) {
            return false;
        }
        boolean derived = "derived".equals(origin);
        String headId = derived ? resolveId(headName) : upsertEntity(headName, null, null, null);
        String tailId = derived ? resolveId(tailName) : upsertEntity(tailName, null, null, null);
        if (headId == null || tailId == null) {
            return false;
        }
        return upsertTripleById(headId, rel, tailId, evidence, sources, weight, origin, derivedFrom, model);
    }

    /**
     * 图谱节点准入规则（评估报告"重新设定规则"，用户已确认）。
     *
     * <p>概念 = 可被独立命名、可被再次提及、可被追问"它是什么"的实体（技术/框架/协议/
     * 工具/语言特性/模式/算法/指标/项目名）。反面：一次具体操作、一段命令行、一句陈述、
     * 一个文档标题。
     *
     * <p>规则先拿清理前的 135 个存量节点验证过误伤率：R1（选项/参数）23 条**零误杀**；
     * R3 最初用裸的「的/了」会误杀 `IoC 的实现方式`、`JDBC 的封装` 这类合法的
     * "X 的 Y"概念名，已收窄为"动词开头 + 一个/怎么/如何/重新"；
     * R5 的长度上限对**不含空格的纯 ASCII 标识符豁免**
     * （`AutoConfigurationImportSelector` 是真实类名，30 字符）。
     *
     * <p>⚠️ R1 与清理时用的 SQL 相比**收紧了**：清理脚本用的是裸的 `-[A-Za-z]`，
     * 那会误杀 `Flow-GRPO` 这类合法的连字符概念名（当前图里恰好没有这种节点，
     * 属于侥幸）。这里改成"以 - 开头 / 含 ` --` / **空格后跟 -**"，
     * 连字符两侧都是词字符的名字（Flow-GRPO）可以存活。
     */
    static boolean admissibleConceptName(String name) {
        if (name == null) {
            return false;
        }
        String n = name.trim();
        if (n.isEmpty()) {
            return false;
        }
        // R6：至少含一个字母或汉字（排除纯符号、纯数字）
        if (!n.matches("(?s).*[A-Za-z\\u4e00-\\u9fa5].*")) {
            return false;
        }
        // R1：选项 / 开关 / 带参数的命令行
        if (n.startsWith("-") || n.contains("--") || n.matches("(?s).*\\s-[A-Za-z].*")) {
            return false;
        }
        // R3（收窄）：动词开头，或口语化描述。
        // ⚠️ 只列**名动不同形**的动词 —— 「配置/设置/运行/安装」是名动同形词
        // （to configure / configuration），会把「配置叠加顺序」这种名词性概念误杀
        // （实测：这条确实在清理中被误删过，已从备份恢复回图）。
        // 宁可漏收一点垃圾，也不能误杀真实概念。
        if (n.matches("^(修改|打印|生成|删除|查看|添加|获取|创建|使用|执行).*")
                || n.contains("一个") || n.contains("怎么") || n.contains("如何") || n.contains("重新")) {
            return false;
        }
        // R5：长度上限 24；不含空格的纯 ASCII 标识符（类名/API 名）豁免
        if (!n.matches("[A-Za-z0-9_$.]+") && n.length() > 24) {
            return false;
        }
        return true;
    }

    /** 按 id 写三元组（推导结果走这里，不会顺带创建节点） */
    public boolean upsertTripleById(String headId, String rel, String tailId, String evidence,
                                    String sources, double weight, String origin,
                                    String derivedFrom, String model) {
        KgRelation exist = relMapper.selectOne(Wrappers.<KgRelation>lambdaQuery()
                .eq(KgRelation::getHeadId, headId)
                .eq(KgRelation::getRelation, rel)
                .eq(KgRelation::getTailId, tailId));
        if (exist != null) {
            Set<String> src = new LinkedHashSet<>(splitAliases(exist.getSources()));
            src.addAll(splitAliases(sources));
            exist.setSources(join(new ArrayList<>(src)));
            if (!StringUtils.hasText(exist.getEvidence()) && StringUtils.hasText(evidence)) {
                exist.setEvidence(clip(evidence, 480));
            }
            if (weight > (exist.getWeight() == null ? 0 : exist.getWeight())) {
                exist.setWeight(weight);
            }
            relMapper.updateById(exist);
            return false;
        }
        KgRelation r = new KgRelation();
        r.setHeadId(headId);
        r.setRelation(rel);
        r.setTailId(tailId);
        r.setEvidence(clip(evidence, 480));
        r.setSources(clip(sources, 250));
        r.setWeight(weight <= 0 ? 1.0 : Math.min(1.0, weight));
        r.setOrigin(origin == null ? "llm" : origin);
        r.setDerivedFrom(clip(derivedFrom, 250));
        r.setModel(model);
        relMapper.insert(r);
        return true;
    }

    // ==================================================================
    // ② 读取与查询
    // ==================================================================

    public List<KgNode> nodes() {
        return nodeMapper.selectList(null);
    }

    public List<KgRelation> relations() {
        return relMapper.selectList(null);
    }

    /** 更新节点（给"回填 wiki_key / 别名"这类局部修改用） */
    public void updateNode(KgNode node) {
        nodeMapper.updateById(node);
    }

    public KgNode node(String id) {
        return nodeMapper.selectById(id);
    }

    /**
     * 把「实体名 / 别名 / 节点 id」都解析成节点 id。
     * <p>三种都认很重要：界面上点节点拿到的是 **id**，而模型/人给的是**名字**
     * （实测漏了 id 这一路时，"展开 2 跳"直接报"图谱里没有这个概念"）。
     */
    public String resolveId(String anyName) {
        if (anyName == null || anyName.isBlank()) {
            return null;
        }
        // ① 直接就是节点 id
        String raw = anyName.trim();
        if (raw.startsWith("e-") && nodeMapper.selectById(raw) != null) {
            return raw;
        }
        // ② 归一化名命中
        String id = EntityLinker.id(anyName);
        if (id != null && nodeMapper.selectById(id) != null) {
            return id;
        }
        // ③ 别名命中（写法不同但指向同一个点）
        String want = EntityLinker.normalize(anyName);
        for (KgNode n : nodes()) {
            if (want.equals(n.getNorm())) {
                return n.getId();
            }
            for (String a : splitAliases(n.getAliases())) {
                if (want.equals(EntityLinker.normalize(a))) {
                    return n.getId();
                }
            }
        }
        return null;
    }

    /** 图的完整载荷（给前端画图用） */
    public Map<String, Object> graph() {
        List<KgNode> ns = nodes();
        List<KgRelation> rs = relations();
        Map<String, Integer> degree = new HashMap<>();
        for (KgRelation r : rs) {
            degree.merge(r.getHeadId(), 1, Integer::sum);
            degree.merge(r.getTailId(), 1, Integer::sum);
        }
        List<Map<String, Object>> outNodes = new ArrayList<>();
        for (KgNode n : ns) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.getId());
            m.put("label", n.getName());
            m.put("type", n.getType());
            m.put("brief", n.getBrief());
            m.put("wikiKey", n.getWikiKey());
            m.put("degree", degree.getOrDefault(n.getId(), 0));
            m.put("sourceCount", n.getSourceCount());
            m.put("aliases", splitAliases(n.getAliases()));
            m.put("embedded", n.getEmbedding() != null && n.getEmbedding().length > 0);
            outNodes.add(m);
        }
        List<Map<String, Object>> outEdges = new ArrayList<>();
        for (KgRelation r : rs) {
            KgOntology.Rel def = KgOntology.byId(r.getRelation());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("source", r.getHeadId());
            m.put("target", r.getTailId());
            m.put("relation", r.getRelation());
            m.put("label", def == null ? r.getRelation() : def.label());
            m.put("weight", r.getWeight());
            m.put("origin", r.getOrigin());
            m.put("evidence", r.getEvidence());
            m.put("sources", splitAliases(r.getSources()));
            m.put("derivedFrom", r.getDerivedFrom());
            outEdges.add(m);
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("nodes", outNodes);
        o.put("edges", outEdges);
        o.put("ontology", KgOntology.describe());
        o.put("stat", stats());
        return o;
    }

    public Map<String, Object> stats() {
        List<KgRelation> rs = relations();
        long derived = rs.stream().filter(r -> "derived".equals(r.getOrigin())).count();
        long embedded = nodes().stream().filter(n -> n.getEmbedding() != null && n.getEmbedding().length > 0).count();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodes", nodes().size());
        m.put("edges", rs.size());
        m.put("derived", derived);
        m.put("embedded", embedded);
        m.put("relations", KgOntology.RELATIONS.size());
        return m;
    }

    /**
     * 从某个实体出发展开邻居（多跳子图）。
     *
     * <p>方向处理：对称关系（易混/相关）两个方向都跟；非对称关系只跟正向 ——
     * 否则"栈 属于 JVM"会被反向当成"JVM 属于栈"。
     */
    public Map<String, Object> neighbors(String idOrName, int hops) {
        String start = resolveId(idOrName);
        int h = Math.max(1, Math.min(MAX_HOPS, hops <= 0 ? DEFAULT_HOPS : hops));
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("anchor", idOrName);
        if (start == null) {
            o.put("found", false);
            o.put("hint", "图里没有这个实体");
            return o;
        }
        Map<String, KgNode> byId = index();
        List<KgRelation> rs = relations();

        Set<String> visited = new LinkedHashSet<>();
        visited.add(start);
        List<Map<String, Object>> out = new ArrayList<>();
        Deque<String> frontier = new ArrayDeque<>();
        frontier.add(start);
        for (int d = 1; d <= h && !frontier.isEmpty(); d++) {
            Deque<String> next = new ArrayDeque<>();
            while (!frontier.isEmpty()) {
                String cur = frontier.poll();
                for (KgRelation r : rs) {
                    boolean fwd = r.getHeadId().equals(cur);
                    boolean back = r.getTailId().equals(cur);
                    if (!fwd && !back) {
                        continue;
                    }
                    if (!KgReasoner.traversable(r.getRelation(), fwd)) {
                        continue;
                    }
                    String other = fwd ? r.getTailId() : r.getHeadId();
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("hop", d);
                    m.put("from", label(byId, cur));
                    m.put("to", label(byId, other));
                    m.put("relation", r.getRelation());
                    KgOntology.Rel def = KgOntology.byId(r.getRelation());
                    m.put("label", def == null ? r.getRelation() : def.label());
                    m.put("direction", fwd ? "→" : "←");
                    m.put("weight", r.getWeight());
                    m.put("origin", r.getOrigin());
                    m.put("evidence", r.getEvidence());
                    m.put("sources", splitAliases(r.getSources()));
                    out.add(m);
                    if (visited.add(other)) {
                        next.add(other);
                    }
                }
            }
            frontier = next;
        }
        o.put("found", true);
        o.put("anchorId", start);
        o.put("anchorName", label(byId, start));
        o.put("hops", h);
        o.put("reachable", visited.size() - 1);
        o.put("triples", out);
        return o;
    }

    /** 两个实体之间的最短路径（回答"A 和 B 什么关系"最有用） */
    public Map<String, Object> path(String a, String b) {
        Map<String, Object> o = new LinkedHashMap<>();
        String from = resolveId(a);
        String to = resolveId(b);
        if (from == null || to == null) {
            o.put("found", false);
            o.put("hint", from == null ? "图里没有实体：" + a : "图里没有实体：" + b);
            return o;
        }
        Map<String, KgNode> byId = index();
        List<KgRelation> rs = relations();
        Map<String, String> prev = new HashMap<>();
        Map<String, KgRelation> via = new HashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> q = new ArrayDeque<>();
        q.add(from);
        seen.add(from);
        boolean ok = from.equals(to);
        while (!q.isEmpty() && !ok) {
            String cur = q.poll();
            for (KgRelation r : rs) {
                boolean fwd = r.getHeadId().equals(cur);
                boolean back = r.getTailId().equals(cur);
                if (!fwd && !back) {
                    continue;
                }
                if (!KgReasoner.traversable(r.getRelation(), fwd)) {
                    continue;
                }
                String other = fwd ? r.getTailId() : r.getHeadId();
                if (seen.add(other)) {
                    prev.put(other, cur);
                    via.put(other, r);
                    if (other.equals(to)) {
                        ok = true;
                        break;
                    }
                    q.add(other);
                }
            }
        }
        o.put("found", ok);
        o.put("from", label(byId, from));
        o.put("to", label(byId, to));
        if (!ok) {
            o.put("hint", "这两个概念之间没有连通的路径");
            return o;
        }
        List<String> chain = new ArrayList<>();
        List<Map<String, Object>> steps = new ArrayList<>();
        String cur = to;
        while (cur != null && !cur.equals(from)) {
            KgRelation r = via.get(cur);
            if (r == null) {
                break;
            }
            KgOntology.Rel def = KgOntology.byId(r.getRelation());
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("from", label(byId, prev.get(cur)));
            s.put("to", label(byId, cur));
            s.put("relation", r.getRelation());
            s.put("label", def == null ? r.getRelation() : def.label());
            s.put("origin", r.getOrigin());
            steps.add(0, s);
            cur = prev.get(cur);
        }
        for (Map<String, Object> s : steps) {
            chain.add(String.valueOf(s.get("from")));
        }
        chain.add(String.valueOf(o.get("to")));
        o.put("length", steps.size());
        o.put("steps", steps);
        o.put("chain", chain);
        return o;
    }

    // ==================================================================
    // ③ Graph RAG：实体识别 + 多跳子图注入
    // ==================================================================

    /**
     * 实体识别（查询期第一步）：在问题文本里找出已知实体。
     * <p>先按名字/别名做**长串优先**的精确匹配（"DeepSeek Harness" 命中就不该再命中 "Harness"），
     * 命中不足时用向量兜底（见 {@link #recognizeByVector}）。
     */
    public List<KgNode> recognize(String text, int limit) {
        List<KgNode> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        String normText = EntityLinker.normalize(text);
        // 候选：显示名 + 所有别名，长的先试
        List<Map.Entry<String, KgNode>> cands = new ArrayList<>();
        for (KgNode n : nodes()) {
            cands.add(Map.entry(n.getName(), n));
            for (String a : splitAliases(n.getAliases())) {
                cands.add(Map.entry(a, n));
            }
        }
        cands.sort(Comparator.comparingInt((Map.Entry<String, KgNode> e) -> e.getKey().length()).reversed());
        Set<String> taken = new LinkedHashSet<>();
        Set<String> consumed = new LinkedHashSet<>();
        for (Map.Entry<String, KgNode> e : cands) {
            if (out.size() >= limit) {
                break;
            }
            String norm = EntityLinker.normalize(e.getKey());
            // 归一化名太短（1 个字）容易误命中，要求至少 2 个字符
            if (norm.length() < 2 || consumed.contains(norm)) {
                continue;
            }
            if (normText.contains(norm) && taken.add(e.getValue().getId())) {
                out.add(e.getValue());
                consumed.add(norm);
            }
        }
        return out;
    }

    /**
     * 向量兜底识别：问题里没有出现任何已知名字时，用 bge-m3 找语义上最接近的实体。
     * <p>这是 Graph RAG 里"向量"那一半：靠向量处理**换一种说法**，靠图处理**关系与多跳**，两者互补。
     */
    public List<KgNode> recognizeByVector(String question, int limit) {
        List<KgNode> out = new ArrayList<>();
        if (question == null || question.isBlank()) {
            return out;
        }
        List<KgNode> all = nodes();
        boolean any = all.stream().anyMatch(n -> n.getEmbedding() != null && n.getEmbedding().length > 0);
        if (!any) {
            return out;
        }
        float[] qv;
        try {
            qv = embedder.embed(question);
        } catch (Exception e) {
            log.debug("问题向量化失败，跳过向量兜底：{}", e.getMessage());
            return out;
        }
        record Scored(KgNode n, double score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (KgNode n : all) {
            if (n.getEmbedding() == null || n.getEmbedding().length == 0) {
                continue;
            }
            double s = EmbeddingClient.cosine(qv, EmbeddingClient.toVector(n.getEmbedding()));
            if (s >= VECTOR_MIN_SCORE) {
                scored.add(new Scored(n, s));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        for (Scored s : scored) {
            if (out.size() >= limit) {
                break;
            }
            out.add(s.n());
        }
        return out;
    }

    /**
     * Graph RAG 的上下文块：识别实体 → 取它们的 1~2 跳子图 → 拼成可注入的文本。
     * <p>形状刻意与既有的 {@code retrievalBlock} 一致（编号列表 + 提示可以继续用工具展开），
     * 这样它在提示词里就是一个"同类证据源"，而不是一个需要模型重新理解的新格式。
     */
    public String retrievalBlock(String question) {
        return retrievalBlock(question, Integer.MAX_VALUE);
    }

    /**
     * 这个问题是不是在**问关系**（决定要不要注入概念图谱）。
     *
     * <p>判据来自 A/B 实测（见 {@link #retrievalBlock} 的注释）：图谱块只在"关系型问题"上有信息量，
     * 对常识型问题（"MQTT 适合什么场景"）既不改变答案、又白占上下文预算。
     *
     * @param conceptHits 精确识别到的概念个数
     */
    private static boolean asksRelation(String question, int conceptHits) {
        if (conceptHits >= 2) {
            return true;                     // 两个概念同时出现，问的多半就是它们之间的关系
        }
        for (String w : RELATION_WORDS) {
            if (question.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /** 出现这些词就认为用户在问关系（宁可多注入几题，也不要把关系题漏掉） */
    private static final List<String> RELATION_WORDS = List.of(
            "关系", "区别", "区别在哪", "不同", "差异", "易混", "混淆",
            // "学前置知识"的几种常见问法：实测漏掉过"学 GC 之前要先掌握什么"
            // （只有一个概念命中 + 不含"先学"，于是这条**前置知识**问题反而不注入图谱）
            "先学", "先掌握", "要先", "该先", "前置", "之前要", "之前先",
            "属于", "包含", "依赖");

    /** 排序时优先展示的关系类型：定向、可推理、最能回答问题里那个"什么关系" */
    private static final Set<String> VALUABLE_RELATIONS =
            Set.of("prerequisite", "contrast_with", "is_a", "part_of");

    /**
     * 同一对实体只留一条边（对称关系两向注入是纯浪费）。
     * <p>优先留**有原文证据**的那条；都没证据就留先遇到的（抽取边通常先于推导边）。
     */
    private static List<Map<String, Object>> dedupePairs(List<Map<String, Object>> lines) {
        Map<String, Map<String, Object>> best = new LinkedHashMap<>();
        for (Map<String, Object> m : lines) {
            String a = String.valueOf(m.get("head"));
            String b = String.valueOf(m.get("tail"));
            String pair = a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
            Map<String, Object> cur = best.get(pair);
            if (cur == null) {
                best.put(pair, m);
                continue;
            }
            boolean curHasEvidence = hasEvidence(cur);
            boolean newHasEvidence = hasEvidence(m);
            if (!curHasEvidence && newHasEvidence) {
                best.put(pair, m);
            }
        }
        return new ArrayList<>(best.values());
    }

    private static boolean hasEvidence(Map<String, Object> m) {
        return m.get("evidence") instanceof String s && !s.isBlank();
    }

    /** 同上，带上下文预算（见 WikiService#retrievalBlock 里对"四路预算"的说明） */
    public String retrievalBlock(String question, int maxChars) {
        if (question == null || question.isBlank() || maxChars <= 0) {
            return "";
        }
        List<KgNode> hits = recognize(question, 6);
        boolean byVector = false;
        // **只用精确/别名匹配，不用向量兜底**（实测结论）。
        // 曾经在精确识别为空时退回向量识别，结果：问"让容器帮我管理对象有什么好处"
        // 认成了「进入容器」（Docker/Git 语境的那个），注入的是**另一个概念的关联** ——
        // 给模型一段错概念的图关系，比不给更糟（它会顺着错误关系答）。
        // 向量实体识别本身没坏（保留在 recognizeByVector 里供探针用），
        // 但实体描述太短、阈值不好标定，当前准确率不足以驱动注入。
        // 要重新启用，得先用"问题→概念"的标注集把它标定出来。
        if (hits.isEmpty()) {
            return "";
        }
        // **只回答"关系型问题"**：一跳邻域不是通用的上下文增强。
        // 2026-09-29 的 A/B 实测（12 题 × 开/关注入、同模型同 prompt）：关键短语命中 46/48 vs 45/48，
        // 逐题 10 题无差异 —— 对"MQTT 适合什么场景"这类常识问题，注入既不改变答案、又白占预算。
        // 所以只在问题**确实在问关系**时才注入：
        //   ① 命中 ≥2 个概念（"JVM 和 JDK 是什么关系"）：块里的共同邻域与最短路径才是答案骨架；
        //   ② 或问题里出现"关系 / 区别 / 易混 / 先学 / 属于 / 包含 / 依赖"这类关系词。
        if (!asksRelation(question, hits.size())) {
            return "";
        }
        Map<String, KgNode> byId = index();
        List<KgRelation> rs = relations();
        // 兜底命中的实体如果**一条关系都没有**，它对"概念关联"这一块毫无贡献，只会占上下文
        if (byVector) {
            Set<String> linkedIds = new LinkedHashSet<>();
            for (KgRelation r : rs) {
                linkedIds.add(r.getHeadId());
                linkedIds.add(r.getTailId());
            }
            List<KgNode> kept = new ArrayList<>();
            for (KgNode n : hits) {
                if (linkedIds.contains(n.getId())) {
                    kept.add(n);
                }
            }
            hits = kept;
            if (hits.isEmpty()) {
                return "";
            }
        }
        // 只取与命中实体直接相连的边（1 跳）；关系数很少，直接扫
        Set<String> hitIds = new LinkedHashSet<>();
        for (KgNode n : hits) {
            hitIds.add(n.getId());
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        Set<String> seenLine = new LinkedHashSet<>();
        for (KgNode h : hits) {
            for (KgRelation r : rs) {
                // 未核对的抽取边不进自动回答：没有原文证据 = 模型编造、或引用被改写后没核对上。
                // 自动注入把它当事实会直接制造错误前提（评估报告 P0-D：132 条抽取边里 9 条无证据，
                // 却与其余边同样权重、同样被注入）。推导边另有 origin=derived 与「（推导）」标注，
                // 不受这条影响。
                if ("llm".equals(r.getOrigin()) && !StringUtils.hasText(r.getEvidence())) {
                    continue;
                }
                boolean fwd = r.getHeadId().equals(h.getId());
                boolean back = r.getTailId().equals(h.getId());
                if (!fwd && !back) {
                    continue;
                }
                String other = fwd ? r.getTailId() : r.getHeadId();
                // 两端都在命中集合里的边优先展示（多跳路径的骨架）
                boolean both = hitIds.contains(other);
                String dir = fwd ? "→" : (KgReasoner.traversable(r.getRelation(), false) ? "←" : null);
                if (dir == null) {
                    continue;
                }
                String head = fwd ? h.getName() : label(byId, other);
                String tail = fwd ? label(byId, other) : h.getName();
                String keyLine = head + "|" + r.getRelation() + "|" + tail;
                if (!seenLine.add(keyLine)) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("head", head);
                m.put("label", relationLabel(r.getRelation()));
                m.put("tail", tail);
                m.put("origin", r.getOrigin());
                m.put("evidence", r.getEvidence());
                m.put("sources", splitAliases(r.getSources()));
                m.put("bridging", both);
                m.put("rel", r.getRelation());
                lines.add(m);
            }
        }
        // **对称补齐边是零信息**：`A 易混 B` 与 `B 易混 A（推导）` 说的是同一件事，
        // 两向都注入等于白占一半预算（实测 JVM/JDK 那题的块里有一半是这种补齐边）。
        // 规则：同一对实体只留**有证据**的那条；两边都没证据（都是推导）时留正向那条。
        lines = dedupePairs(lines);
        // 命中多个实体时，把两两之间最短路径的结论也算出来（这就是"多跳"回答的依据）
        List<Map<String, Object>> paths = new ArrayList<>();
        for (int i = 0; i < hits.size() && paths.size() < 3; i++) {
            for (int j = i + 1; j < hits.size() && paths.size() < 3; j++) {
                Map<String, Object> p = path(hits.get(i).getName(), hits.get(j).getName());
                if (Boolean.TRUE.equals(p.get("found")) && p.get("steps") != null
                        && ((List<?>) p.get("steps")).size() > 1) {
                    paths.add(p);
                }
            }
        }
        // 排序 = 信息量：① 两端都命中（多跳骨架）② 有原文证据的抽取边 ③ 高价值关系 ④ 其余
        lines.sort(Comparator
                .comparing((Map<String, Object> m) -> !Boolean.TRUE.equals(m.get("bridging")))
                .thenComparing(m -> "derived".equals(m.get("origin")))
                .thenComparing(m -> !VALUABLE_RELATIONS.contains(String.valueOf(m.get("rel")))));
        // 命中了实体但一条边都取不到 → 这一块没有信息量，不如不注入（避免模型对着空块瞎猜）
        if (lines.isEmpty() && paths.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【知识图谱里的概念关联】从你的问题里识别到这些概念：");
        sb.append(String.join("、", hits.stream().map(KgNode::getName).toList()));
        sb.append("。三元组格式是「头实体 -关系→ 尾实体」，标着「推导」的是按本体规则推出来的（不是直接写着的）。\n");
        int i = 0;
        for (Map<String, Object> m : lines) {
            if (++i > BLOCK_MAX_TRIPLES) {
                break;
            }
            String head = String.valueOf(m.get("head"));
            String tail = String.valueOf(m.get("tail"));
            String label = String.valueOf(m.get("label"));
            String ev = m.get("evidence") instanceof String s && !s.isBlank()
                    ? ((String) s).replaceAll("\\s+", " ").trim() : "";
            String srcLine = m.get("sources") instanceof List<?> l && !l.isEmpty()
                    ? String.join("、", l.stream().map(String::valueOf).toList()) : "";
            String one = i + ". " + head + " -" + label + "→ " + tail
                    + ("derived".equals(m.get("origin")) ? "（推导）" : "")
                    + (ev.isEmpty() ? "" : "\n   证据：" + ev)
                    + (srcLine.isEmpty() ? "" : "　来源：" + srcLine) + "\n";
            // 预算不够就停：证据块的价值在于短而准，塞满只会挤掉后面的正文
            if (sb.length() + one.length() > maxChars) {
                i--;
                break;
            }
            sb.append(one);
        }
        for (Map<String, Object> p : paths) {
            sb.append("路径：").append(String.join(" → ", ((List<?>) p.get("chain")).stream().map(String::valueOf).toList()))
                    .append("（").append(p.get("length")).append(" 跳）\n");
        }
        sb.append("以上只是该实体的**一跳邻域**。要按关系精确查用 graph_query，要展开更多跳用 graph_neighbors。\n");
        return sb.toString();
    }

    /**
     * 图谱辅助的**查询扩展**：从问题里认出概念，把它们一跳邻域的概念名也作为检索词返回。
     *
     * <p>解决的是词面检索怎么都跨不过去的那一步：用户问"物联网设备上报数据一般怎么做"，
     * 资料里写的是"MQTT 适合 IoT 低带宽/弱网场景" —— 两者**没有任何共同词**，
     * 词面必然 0 命中，而向量对这个 98 字的短条打分又落在阈值边缘。
     * 但如果图谱里"物联网"和"MQTT"是连着的，就能把 {@code MQTT} 补成检索词，
     * 词面那一路立刻能命中。
     *
     * <p>这就是图结构与文本检索的互补：**图知道"该用哪个词去搜"**，文本检索负责找原文。
     *
     * <p>返回的顺序带一点偏好：先放命中概念自己的名字（总是相关），再放邻域名。
     *
     * @return 额外检索词（可能为空）；不做任何模型调用，纯图查询，免费
     */
    public List<String> expandTerms(String question, int limit) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        List<KgNode> hits = recognize(question, 4);
        if (hits.isEmpty()) {
            return List.of();
        }
        Map<String, KgNode> byId = index();
        Set<String> out = new LinkedHashSet<>();
        Set<String> hitNames = new LinkedHashSet<>();
        for (KgNode h : hits) {
            hitNames.add(h.getName());
        }
        // 自身名字先来（用户可能只说了别名，主名会带来更多命中）
        out.addAll(hitNames);
        for (KgRelation r : relations()) {
            String head = r.getHeadId();
            String tail = r.getTailId();
            boolean hHit = hitNames.contains(label(byId, head));
            boolean tHit = hitNames.contains(label(byId, tail));
            if (!hHit && !tHit) {
                continue;
            }
            // 只补"另一端"的名字：两端都命中的说明用户已经说到了，不必再补
            if (hHit && !tHit) {
                out.add(label(byId, tail));
            } else if (tHit && !hHit) {
                out.add(label(byId, head));
            }
        }
        // 太短或太长的名字当检索词没意义（2 个字符以下命中率极低、噪声大）
        List<String> list = new ArrayList<>();
        for (String s : out) {
            if (s != null && s.length() >= 2 && s.length() <= 24 && !question.contains(s)) {
                list.add(s);
            } else if (s != null && question.contains(s)) {
                // 问题里已经有的词，检索词扩展不必重复（词面本来就切得出来）
                continue;
            }
        }
        return list.size() > limit ? new ArrayList<>(list.subList(0, limit)) : list;
    }

    // ==================================================================
    // ④ 推理：闭包推导并落库
    // ==================================================================

    /** 跑一轮推理：先清掉上次的推导边，再从直接事实重推（保证可重复、不会叠着长） */
    public Map<String, Object> reason() {
        int cleared = clearDerived();
        List<KgRelation> direct = relMapper.selectList(Wrappers.<KgRelation>lambdaQuery()
                .ne(KgRelation::getOrigin, "derived"));
        List<KgReasoner.Edge> edges = new ArrayList<>();
        for (KgRelation r : direct) {
            // 未核对的边（无原文证据）不参与传递闭包：推理会把错误放大。
            // 实测 #67 Spring-属于→JVM（证据是"依赖 JVM"）叠加 #14 JVM-属于→JRE
            // 推出 #289 Spring-属于→JRE，错误从一条变成三条（评估报告 P0-D）。
            if (!StringUtils.hasText(r.getEvidence())) {
                continue;
            }
            edges.add(new KgReasoner.Edge(String.valueOf(r.getId()), r.getHeadId(), r.getRelation(),
                    r.getTailId(), r.getWeight() == null ? 1.0 : r.getWeight()));
        }
        List<KgReasoner.Edge> fresh = KgReasoner.derive(edges);
        int added = 0;
        for (KgReasoner.Edge d : fresh) {
            if (upsertTripleById(d.head(), d.relation(), d.tail(), null, null, d.weight(),
                    "derived", d.key(), "rule")) {
                added++;
            }
        }
        log.info("图谱推理：直接事实 {} 条 → 新增推导 {} 条（清理旧推导 {} 条）", edges.size(), added, cleared);
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("direct", edges.size());
        o.put("derivedAdded", added);
        o.put("derivedCleared", cleared);
        o.put("stat", stats());
        return o;
    }

    /** 清空所有推导边（留着直接抽取的事实） */
    public int clearDerived() {
        return relMapper.delete(Wrappers.<KgRelation>lambdaQuery().eq(KgRelation::getOrigin, "derived"));
    }

    // ==================================================================
    // ⑤ 向量：实体描述向量化（供换一种说法的实体识别）
    // ==================================================================

    /**
     * 给每个实体算一次向量（描述 = 名字 + 说明 + 对应 wiki 页开头）。
     * <p>刻意**不做** TransE/RotatE 那类知识图谱嵌入：那需要**成千上万条**三元组才能训出有意义的空间，
     * 这个库只有几十条，训出来的向量是噪声。实体级文本向量能解决同一件事（换一种说法也能找到概念），
     * 而且复用了已有的 bge-m3。
     */
    public Map<String, Object> embedEntities(Map<String, String> wikiTexts) {
        List<KgNode> all = nodes();
        int done = 0;
        int failed = 0;
        for (KgNode n : all) {
            String desc = descriptionOf(n, wikiTexts == null ? null : wikiTexts.get(n.getWikiKey()));
            if (desc.length() < 4) {
                continue;
            }
            try {
                float[] v = embedder.embed(desc);
                if (v != null && v.length > 0) {
                    n.setEmbedding(EmbeddingClient.toBytes(v));
                    nodeMapper.updateById(n);
                    done++;
                }
            } catch (Exception e) {
                failed++;
                log.debug("实体 {} 向量化失败：{}", n.getName(), e.getMessage());
            }
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("embedded", done);
        o.put("total", all.size());
        o.put("failed", failed);
        return o;
    }

    private String descriptionOf(KgNode n, String wiki) {
        StringBuilder sb = new StringBuilder(n.getName());
        if (StringUtils.hasText(n.getBrief())) {
            sb.append("。").append(n.getBrief());
        }
        if (StringUtils.hasText(wiki)) {
            sb.append("。").append(wiki.replaceAll("[#*`>\\[\\]]", " ").replaceAll("\\s+", " "));
        }
        return clip(sb.toString(), 1200);
    }

    // ==================================================================
    // ⑥ 删除（图上错的东西要能拿掉）
    // ==================================================================

    /** 删实体：连同它的所有三元组一起删（避免留下悬空边） */
    public Map<String, Object> deleteNode(String id) {
        KgNode n = nodeMapper.selectById(id);
        Map<String, Object> o = new LinkedHashMap<>();
        if (n == null) {
            o.put("found", false);
            return o;
        }
        int edges = relMapper.delete(Wrappers.<KgRelation>lambdaQuery()
                .eq(KgRelation::getHeadId, id).or().eq(KgRelation::getTailId, id));
        nodeMapper.deleteById(id);
        o.put("found", true);
        o.put("name", n.getName());
        o.put("edgesDeleted", edges);
        return o;
    }

    /** 删一条三元组 */
    public Map<String, Object> deleteRelation(Long id) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("deleted", relMapper.deleteById(id));
        return o;
    }

    /** 合并两个实体（人工消歧：A 并到 B，A 的边转到 B 再删 A） */
    public Map<String, Object> mergeNodes(String fromId, String toId) {
        Map<String, Object> o = new LinkedHashMap<>();
        KgNode from = nodeMapper.selectById(fromId);
        KgNode to = nodeMapper.selectById(toId);
        if (from == null || to == null || fromId.equals(toId)) {
            o.put("ok", false);
            o.put("hint", "实体不存在或相同");
            return o;
        }
        List<KgRelation> rs = relMapper.selectList(Wrappers.<KgRelation>lambdaQuery()
                .eq(KgRelation::getHeadId, fromId).or().eq(KgRelation::getTailId, fromId));
        int moved = 0;
        for (KgRelation r : rs) {
            String head = fromId.equals(r.getHeadId()) ? toId : r.getHeadId();
            String tail = fromId.equals(r.getTailId()) ? toId : r.getTailId();
            if (head.equals(tail)) {
                relMapper.deleteById(r.getId());
                continue;
            }
            relMapper.deleteById(r.getId());
            if (upsertTripleById(head, r.getRelation(), tail, r.getEvidence(), r.getSources(),
                    r.getWeight() == null ? 1.0 : r.getWeight(), r.getOrigin(), r.getDerivedFrom(), r.getModel())) {
                moved++;
            }
        }
        // 别名并过去，下次遇到旧写法还能链接到规范节点
        Set<String> merged = new LinkedHashSet<>(splitAliases(to.getAliases()));
        merged.add(from.getName());
        merged.addAll(splitAliases(from.getAliases()));
        to.setAliases(join(new ArrayList<>(merged)));
        to.setSourceCount((to.getSourceCount() == null ? 0 : to.getSourceCount())
                + (from.getSourceCount() == null ? 0 : from.getSourceCount()));
        nodeMapper.updateById(to);
        nodeMapper.deleteById(fromId);
        o.put("ok", true);
        o.put("from", from.getName());
        o.put("to", to.getName());
        o.put("edgesMoved", moved);
        return o;
    }

    /** 疑似重复的实体对（只提示，不自动合并 —— 合并不可逆） */
    public List<Map<String, Object>> duplicates(double minScore) {
        List<KgNode> ns = nodes();
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < ns.size(); i++) {
            for (int j = i + 1; j < ns.size(); j++) {
                double s = EntityLinker.suggestScore(ns.get(i).getName(), ns.get(j).getName());
                if (s >= minScore && s < 1) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("a", ns.get(i).getName());
                    m.put("aId", ns.get(i).getId());
                    m.put("b", ns.get(j).getName());
                    m.put("bId", ns.get(j).getId());
                    m.put("score", Math.round(s * 100) / 100.0);
                    out.add(m);
                }
            }
        }
        out.sort(Comparator.comparingDouble((Map<String, Object> m) -> -(Double) m.get("score")));
        return out;
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private Map<String, KgNode> index() {
        Map<String, KgNode> m = new HashMap<>();
        for (KgNode n : nodes()) {
            m.put(n.getId(), n);
        }
        return m;
    }

    private static String label(Map<String, KgNode> byId, String id) {
        KgNode n = byId.get(id);
        return n == null ? id : n.getName();
    }

    private static String relationLabel(String rel) {
        KgOntology.Rel def = KgOntology.byId(rel);
        return def == null ? rel : def.label();
    }

    static List<String> splitAliases(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String p : raw.split("\\|")) {
            String s = p.trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private static String join(List<String> parts) {
        if (parts == null || parts.isEmpty()) {
            return null;
        }
        return String.join("|", parts);
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** 关系 id → 中文标签（界面与提示词共用） */
    public static String labelOf(String relation) {
        KgOntology.Rel r = KgOntology.byId(relation);
        return r == null ? relation : r.label();
    }
}
