package org.dyh.learnhub.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.dyh.learnhub.entity.KgNode;
import org.dyh.learnhub.entity.WikiPage;
import org.dyh.learnhub.mapper.KbChunkMapper;
import org.dyh.learnhub.mapper.WikiPageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 概念图谱的构建流水线：把方法论第 1~5 层串成一条可跑、可看进度的任务。
 *
 * <pre>
 *   ① 收集素材（带来源标注）        —— 第 1 层：数据获取与预处理
 *   ② 抽三元组（联合抽取+本体校验）  —— 第 2 层：信息抽取
 *   ③ 实体链接入库（别名归一/合并）  —— 第 2、3 层：消歧 + 融合
 *   ④ 推理闭包（传递/对称）          —— 第 4 层：符号推理
 *   ⑤ 实体向量化                    —— 第 4、5 层：向量兜底 + Graph RAG
 * </pre>
 *
 * <p>为什么第 ① 步的标注不能省：三元组的 {@code sources} 靠它回填成「笔记#5」这种可点的来源。
 * 没有来源的图，人没法判断该不该信 —— 这和 wiki 引用标注是同一个道理。
 *
 * <p>这一步**默认不自动跑**：它会花云端 token，而且只有你说要才该动图。
 * 素材变了之后由「重建概念图谱」按钮触发（和 ③ 局部重编译一样，保持人工确认）。
 */
@Service
public class KgPipelineService {

    private static final Logger log = LoggerFactory.getLogger(KgPipelineService.class);

    /** 每条素材送进抽取器的最大字符数（资料正文很长，全送会撑爆 prompt） */
    private static final int PER_SOURCE_CHARS = 4000;
    /** 单次构建最多用多少条素材 */
    private static final int MAX_SOURCES = 40;

    private final KbChunkMapper kbChunkMapper;
    private final WikiPageMapper wikiMapper;
    private final KgGraphService graph;
    private final TripleExtractor extractor;

    private final ExecutorService runner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "kg-build");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    public KgPipelineService(KbChunkMapper kbChunkMapper, WikiPageMapper wikiMapper,
                             KgGraphService graph, TripleExtractor extractor) {
        this.kbChunkMapper = kbChunkMapper;
        this.wikiMapper = wikiMapper;
        this.graph = graph;
        this.extractor = extractor;
    }

    /** 任务进度（界面轮询） */
    public static final class Job {
        public final String id;
        public final long startedAt = System.currentTimeMillis();
        public long finishedAt;
        public String stage = "排队";
        public int percent;
        public int total;
        public int done;
        public String detail = "";
        public String status = "running";
        public String error;
        public String model;
        public int triples;
        public int entities;

        Job(String id) {
            this.id = id;
        }
    }

    public Map<String, Object> start() {
        Job job = new Job(UUID.randomUUID().toString().substring(0, 8));
        jobs.put(job.id, job);
        runner.submit(() -> build(job));
        return view(job.id);
    }

    public Map<String, Object> view(String id) {
        Job j = jobs.get(id);
        if (j == null) {
            throw new IllegalArgumentException("任务不存在或已过期：" + id);
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("jobId", j.id);
        o.put("topicKey", "kg");
        o.put("targetId", "triple");
        o.put("stage", j.stage);
        o.put("percent", j.percent);
        o.put("total", j.total);
        o.put("done", j.done);
        o.put("detail", j.detail);
        o.put("status", j.status);
        o.put("error", j.error);
        o.put("model", j.model);
        o.put("triples", j.triples);
        o.put("entities", j.entities);
        o.put("chars", 0);
        o.put("quality", null);
        o.put("elapsedMs",
                (j.finishedAt == 0 ? System.currentTimeMillis() : j.finishedAt) - j.startedAt);
        return o;
    }

    public Map<String, Object> viewOrNull(String id) {
        return jobs.containsKey(id) ? view(id) : null;
    }

    // ------------------------------------------------------------------

    private void build(Job job) {
        try {
            job.stage = "收集素材";
            job.percent = 4;
            List<Source> sources = collectSources();
            job.total = Math.max(1, sources.size());
            job.detail = "素材 " + sources.size() + " 条";
            if (sources.isEmpty()) {
                finish(job, "没有可用素材：先写笔记或上传资料");
                return;
            }

            // ① 素材拼装（带来源标注）—— 抽取器按来源分块，标注才能回填到三元组上
            // 注意：这里**不用**把所有来源拼成一大段交给抽取器，而是逐个来源送。
            // 原因（实测踩到）：按字符分批 + 每批最多 12 条时，大来源会把预算吃光，
            // 小来源（note#3 只有 109 字）永远排在最后、一条都抽不到 ——
            // 表现是"概念图里根本没有 MQTT"，而 MQTT 是那份笔记的全部内容。
            // 按来源抽取之后，每个来源都有自己的 12 条额度；调用次数与原来相当
            //（12 个来源 vs 原来看字符切出的 ~14 批），但**覆盖不再随篇幅偏斜**。
            job.stage = "抽取概念三元组";
            List<String> known = new ArrayList<>();
            for (KgNode n : graph.nodes()) {
                known.add(n.getName());
            }
            TripleExtractor.Result r = null;
            int batchTotal = 0;
            int rejOnt = 0;
            int rejShape = 0;
            int rejDup = 0;
            List<TripleExtractor.Triple> triples = new ArrayList<>();
            int si = 0;
            for (Source s : sources) {
                si++;
                String one = "[" + s.marker() + "#" + s.id() + "] 《" + s.title() + "》\n"
                        + clip(s.text(), PER_SOURCE_CHARS) + "\n";
                TripleExtractor.Result part = extractor.extract(one, known);
                if (r == null) {
                    r = part;
                }
                batchTotal += part.batches();
                rejOnt += part.rejectedByOntology();
                rejShape += part.rejectedByShape();
                rejDup += part.rejectedByDedup();
                triples.addAll(part.triples());
                // 抽到的新概念立刻进 known，后面的来源就能复用同一写法（减少同义重复节点）
                for (TripleExtractor.Triple t : part.triples()) {
                    if (!known.contains(t.head())) {
                        known.add(t.head());
                    }
                    if (!known.contains(t.tail())) {
                        known.add(t.tail());
                    }
                }
                job.detail = "已处理 " + si + "/" + sources.size() + " 个来源 · 累计三元组 " + triples.size();
                job.percent = 12 + (int) (38.0 * si / Math.max(1, sources.size()));
            }
            if (r == null) {
                finish(job, "没有可用素材");
                return;
            }
            r = new TripleExtractor.Result(triples, batchTotal, rejOnt, rejShape, rejDup, r.model(), r.cloud());
            job.model = r.model();
            job.detail = "模型 " + r.model() + "（" + (r.cloud() ? "云端" : "本地") + "）· " + r.batches()
                    + " 批 · 丢弃：关系不在本体 " + r.rejectedByOntology() + " / 形状不合法 "
                    + r.rejectedByShape() + " / 重复 " + r.rejectedByDedup();
            job.percent = 55;

            // ③ 入库（实体链接在 upsertEntity 里完成）
            job.stage = "实体链接与入库";
            int added = 0;
            for (TripleExtractor.Triple t : r.triples()) {
                // 用抽取层给出的权重（有原文证据 0.9 / 未核对 0.4），
                // 不要在这里再硬编码 —— 否则 TripleExtractor 的"已核对/未核对"区分到不了库里
                if (graph.upsertTriple(t.head(), t.relation(), t.tail(), t.evidence(), t.cite(),
                        t.weight(), "llm", null, r.model())) {
                    added++;
                }
            }
            job.triples = added;
            job.entities = graph.nodes().size();
            job.detail = "新增三元组 " + added + " 条（重复的已合并）· 实体 " + job.entities + " 个";
            job.percent = 72;

            // ③.5 把节点连到 wiki 实体页（两向可达：图上点进去能读长文）
            int linked = linkWikiPages();
            if (linked > 0) {
                job.detail += " · 关联 wiki 实体页 " + linked + " 个";
            }
            job.percent = 80;

            // ④ 推理闭包
            job.stage = "规则推理（传递/对称闭包）";
            Map<String, Object> reason = graph.reason();
            job.detail = "推导出 " + reason.get("derivedAdded") + " 条隐含事实（可溯源、可整批重推）";
            job.percent = 90;

            // ⑤ 实体向量化（Graph RAG 的向量那一半）
            job.stage = "实体向量化";
            try {
                Map<String, Object> emb = graph.embedEntities(wikiTexts());
                job.detail = "已向量化 " + emb.get("embedded") + "/" + emb.get("total") + " 个实体";
            } catch (Exception e) {
                // 向量服务没起不该让整条流水线失败：图已经建好了，只是少一层兜底
                job.detail = "实体向量化跳过（" + e.getMessage() + "）";
                log.warn("实体向量化失败：{}", e.toString());
            }
            finish(job, null);
        } catch (Exception e) {
            log.warn("概念图谱构建失败：{}", e.toString());
            job.status = "failed";
            job.stage = "失败";
            job.error = e.getMessage() == null ? e.toString() : e.getMessage();
            job.finishedAt = System.currentTimeMillis();
        }
    }

    private void finish(Job job, String note) {
        job.percent = 100;
        job.status = "done";
        job.stage = "完成";
        if (note != null) {
            job.detail = note;
        }
        job.finishedAt = System.currentTimeMillis();
        log.info("概念图谱构建完成：实体 {} 个、新增三元组 {} 条、耗时 {}ms",
                graph.nodes().size(), job.triples, job.finishedAt - job.startedAt);
    }

    /** 素材：笔记 + 速查卡 + 资料正文，各自带来源标注前缀 */
    private List<Source> collectSources() {
        List<Source> out = new ArrayList<>();
        for (Map<String, Object> n : kbChunkMapper.allNotes()) {
            out.add(new Source("笔记", num(n.get("id")), str(n.get("title")), str(n.get("content"))));
        }
        for (Map<String, Object> r : kbChunkMapper.allRefs()) {
            out.add(new Source("速查卡", num(r.get("id")), str(r.get("title")), str(r.get("content"))));
        }
        for (Map<String, Object> f : kbChunkMapper.allFiles()) {
            out.add(new Source("资料", num(f.get("id")), str(f.get("title")), str(f.get("content"))));
        }
        out.removeIf(s -> s.text() == null || s.text().isBlank());
        // 内容多的优先（信息量大），限制条数避免一次烧太多 token
        out.sort((a, b) -> Integer.compare(b.text().length(), a.text().length()));
        return out.size() > MAX_SOURCES ? new ArrayList<>(out.subList(0, MAX_SOURCES)) : out;
    }

    /**
     * 重新把图节点关联到 wiki 实体页。
     *
     * <p>关联逻辑原先只在**图谱重建**时跑，所以"先编译出新的实体页、但没重建图谱"
     * 会让新页一直挂不上（评估报告 P1-3：135 个概念里只有 11 个有 Wiki 跳转）。
     * 这个入口让关联可以单独触发，不必重建整张图（不调模型、免费）。
     */
    public int relinkWikiPages() {
        return linkWikiPages();
    }

    /** 把图节点关联到 wiki 实体页（按归一化标题匹配），返回关联上的个数 */
    private int linkWikiPages() {
        List<WikiPage> pages = wikiMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getTopicType, "entity"));
        Map<String, WikiPage> byNorm = new LinkedHashMap<>();
        for (WikiPage p : pages) {
            if (p.getTitle() != null) {
                byNorm.put(EntityLinker.normalize(p.getTitle()), p);
            }
        }
        int linked = 0;
        for (KgNode n : graph.nodes()) {
            if (n.getWikiKey() != null && !n.getWikiKey().isBlank()) {
                continue;
            }
            WikiPage p = byNorm.get(EntityLinker.normalize(n.getName()));
            if (p != null) {
                n.setWikiKey(p.getTopicKey());
                graph.updateNode(n);
                linked++;
            }
        }
        return linked;
    }

    /** wiki 实体页正文（给实体向量化当描述，比只有名字强得多） */
    private Map<String, String> wikiTexts() {
        Map<String, String> m = new LinkedHashMap<>();
        for (WikiPage p : wikiMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getTopicType, "entity"))) {
            if (p.getContentMd() != null) {
                m.put(p.getTopicKey(), p.getContentMd().length() > 800
                        ? p.getContentMd().substring(0, 800) : p.getContentMd());
            }
        }
        return m;
    }

    private record Source(String marker, Long id, String title, String text) {
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static Long num(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return null;
        }
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
