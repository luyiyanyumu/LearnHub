package org.dyh.learnhub.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 实体/概念页的编译（"摄入时编译"的跨页那一半）。
 *
 * <h3>它补的是哪块</h3>
 * 主题页（{@link WikiService}）是"一个分类一页"的摘要，粒度太粗、也只在主题内部更新。
 * 这一层把素材里的**概念/实体**抽出来，一页一个、跨主题复用，并互相用 {@code [[名字]]} 链接成网，
 * 再生成一份 {@code index.md} 作为入口 —— 也就是"编译产物"该有的那种结构化与可导航。
 *
 * <h3>为什么单独一个类</h3>
 * 编译逻辑与主题页生成只有"调模型 + 落 wiki_page"两处相同，其余（分批抽取、归并去重、选页、写索引）
 * 完全不同；塞进 WikiService 会让那个类继续膨胀。
 *
 * <h3>刻意的两点</h3>
 * <ul>
 *   <li><b>抽取分批、写页按实体</b>：抽取要覆盖尽量多的素材（分批做 map），
 *       写页则每页独立、篇幅有界（≤400 字），避免一次性让 8B 模型吞下 18 万字。</li>
 *   <li><b>index.md 由代码生成，不调模型</b>：索引是确定性的结构，交给模型反而会漏项、格式漂移。</li>
 * </ul>
 */
@lombok.extern.slf4j.Slf4j
@org.springframework.stereotype.Service
@lombok.RequiredArgsConstructor
public class EntityCompileService {

    /** 每个来源送进抽取环节的字符上限（抽样：概念名通常出现在开头与小标题处） */
    private static final int SOURCE_CHARS = 2400;
    /** 一次抽取调用塞几份素材 */
    private static final int BATCH = 4;
    /** 最多编译多少个实体页（控制时间与页面噪声；本地 8B 每页 20~35 秒） */
    public static final int MAX_ENTITY_PAGES = 10;
    /** 实体页正文上限 */
    private static final int PAGE_CHARS = 900;

    private final org.dyh.learnhub.mapper.KbChunkMapper kbMapper;
    private final org.dyh.learnhub.mapper.WikiPageMapper wikiMapper;
    private final org.dyh.learnhub.ai.DeepSeekClient client;
    /** 任务分工：实体编译用哪个**模型档案**由它决定（档案可无限新增） */
    private final org.dyh.learnhub.ai.ModelRouting routing;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final SkillService skillService;

    // ------------------------------------------------------------------
    // 任务（与主题页生成同一套"后台 + 进度"体验）
    // ------------------------------------------------------------------

    public static final class Job {
        public final String id;
        public final long startedAt = System.currentTimeMillis();
        public volatile String stage = "收集素材";
        public volatile int total;
        public volatile int done;
        public volatile int pages;
        public volatile int percent;
        public volatile int chars;
        public volatile long finishedAt;
        public volatile String status = "running";
        public volatile String error;
        public volatile String detail = "";
        public volatile String model;

        Job(String id) {
            this.id = id;
        }
    }

    private final ExecutorService runner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "entity-compile");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    /**
     * 启动编译。
     *
     * @param useMain true = 用主模型（云端，质量高、花 token）；false = 用本地模型（免费，但实测纪律不足）
     */
    public Map<String, Object> start(String profileId) {
        Job job = new Job(UUID.randomUUID().toString().substring(0, 8));
        jobs.put(job.id, job);
        runner.submit(() -> compile(job, profileId));
        return view(job.id);
    }

    public Map<String, Object> view(String id) {
        Map<String, Object> o = viewOrNull(id);
        if (o == null) {
            throw new IllegalArgumentException("任务不存在或已过期: " + id);
        }
        return o;
    }

    /**
     * 同 {@link #view}，但任务不存在时返回 {@code null} 而不是抛异常。
     * <p>给 {@code WikiService.jobView} 做**统一轮询端点**用：重编译会派生一个知识页任务，
     * 它的 id 不在 wiki 的任务表里，需要回落到这里查。
     */
    public Map<String, Object> viewOrNull(String id) {
        Job j = jobs.get(id);
        if (j == null) {
            return null;
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("jobId", j.id);
        // 与 WikiService.jobView 保持同一套字段名，前端只写一套轮询逻辑
        o.put("topicKey", "entities");
        o.put("targetId", "entity");
        o.put("quality", null);
        o.put("stage", j.stage);
        o.put("total", j.total);
        o.put("done", j.done);
        o.put("pages", j.pages);
        o.put("chars", j.chars);
        o.put("detail", j.detail);
        o.put("model", j.model);
        o.put("percent", j.percent);
        o.put("status", j.status);
        o.put("error", j.error);
        o.put("elapsedMs", (j.finishedAt == 0 ? System.currentTimeMillis() : j.finishedAt) - j.startedAt);
        return o;
    }

    // ------------------------------------------------------------------
    // 编译流水线：抽取(map) → 归并 → 写页(reduce) → 生成索引
    // ------------------------------------------------------------------

    /** 本次编译用哪套模型目标 */
    private String modelBaseUrl;
    private String modelApiKey;
    private String modelName;
    private boolean modelSeparate;
    /** 本次编译用的档案 id：生成参数（输出上限/思考/强度）要按**这个档案**取，而不是当前激活档案 */
    private String modelProfileId;

    /**
     * 解析本次编译用哪个**模型档案**。
     * <p>原来是 {@code boolean useMain}（主模型 / 本地目标二选一）；现在档案可以有很多个，
     * 所以参数是档案 id —— null 表示"按分工表里"实体编译"这个任务走"。
     */
    private void setTarget(String profileId) {
        org.dyh.learnhub.ai.ModelRouting.ModelTarget t = routing.forProfile(profileId == null
                ? routing.targetIdOf(org.dyh.learnhub.ai.ModelRouting.TASK_ENTITY) : profileId);
        modelBaseUrl = t.baseUrl();
        modelApiKey = t.apiKey();
        modelName = t.model();
        modelSeparate = t.separate();
        modelProfileId = t.id();
    }

    private void compile(Job job, String profileId) {
        setTarget(profileId);
        job.model = modelName;
        try {
            job.stage = "收集素材";
            job.percent = 2;
            List<SourceDoc> docs = sourceDocs();
            if (docs.isEmpty()) {
                job.stage = "没有可编译的内容";
                job.status = "done";
                job.finishedAt = System.currentTimeMillis();
                return;
            }
            job.total = docs.size();

            // ① map：分批抽取"值得单独成页"的概念/实体
            job.stage = "抽取概念/实体";
            Map<String, Entity> pool = new LinkedHashMap<>();
            for (int i = 0; i < docs.size(); i += BATCH) {
                List<SourceDoc> group = docs.subList(i, Math.min(docs.size(), i + BATCH));
                for (Entity e : extract(group)) {
                    merge(pool, e, group);
                }
                job.done = Math.min(docs.size(), i + BATCH);
                job.percent = 4 + (int) (40.0 * job.done / docs.size());
                job.detail = "已抽到候选 " + pool.size() + " 个";
            }

            // ② 选页：先按"命令类不成页"过滤，再按出现次数取前 N。
            // 为什么用代码强制而不是靠提示词：Schema 里已明确写了"同一工具的子命令不要各建一页"，
            // 但实测本地 8B **照旧**把 git add/branch/commit/config/status 各建了一页 ——
            // 跨页判断这种纪律，小模型守不住。规则下沉到代码，才真的成立。
            // （信息不会丢：该工具的页面正文里本来就会写到这些常用命令。）
            // 判定"某工具的子命令"：用**名字前缀**在代码里判，而不是信模型给的 kind ——
            // 实测同一份素材两次运行，8B 第一次把 Maven/Gradle 标成 framework、第二次全标成 command，
            // 靠标签过滤会把整批误杀（第三次编译就是这样变成 0 页的）。
            // 规则：若某个候选的名字 = 另一个候选 + 分隔符 + **小写**开头，它就是子条目，不单独成页。
            // 为什么要求"小写开头"：光看前缀会把独立产品名一起吞掉 ——
            //   "git commit" / "git-config" → 小写开头，是 git 的子命令，合并
            //   "Spring Boot" / "Docker Compose" → 大写开头，是独立产品名，必须保留
            // 这里曾经写反过：条件写成"分隔符后必须是字母或数字"，于是带空格的 "git commit" 一个都匹配不上，
            // 上一轮编译就真的建了 git commit / git config / git push 三页。
            int dropped = 0;
            List<Entity> candidates = new ArrayList<>();
            for (Entity e : pool.values()) {
                boolean sub = false;
                for (Entity other : pool.values()) {
                    if (other == e) {
                        continue;
                    }
                    String o = other.name().toLowerCase().trim();
                    String n = e.name().toLowerCase().trim();
                    if (o.isEmpty() || n.length() <= o.length() || !n.startsWith(o)) {
                        continue;
                    }
                    // 跳过前缀后的一串分隔符，看第一个有效字符
                    int k = o.length();
                    while (k < n.length() && !Character.isLetterOrDigit(n.charAt(k))) {
                        k++;
                    }
                    if (k >= n.length() || Character.isLowerCase(n.charAt(k))) {
                        sub = true;
                        break;
                    }
                }
                if (sub) {
                    dropped++;
                    continue;
                }
                candidates.add(e);
            }
            if (dropped > 0) {
                log.info("按 Schema 规则过滤掉 {} 个子条目词条（不单独成页）", dropped);
            }
            // 大小写不敏感归一：模型这次写 "Git"、上次写 "git"，按原样哈希会得到两个 topic_key → 同名长出两页。
            // 做法是**沿用库里已有的写法**，这样 key 不变、页面被就地更新，而不是又长一页。
            Map<String, String> existingByLower = new LinkedHashMap<>();
            for (org.dyh.learnhub.entity.WikiPage p : wikiMapper.selectList(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<org.dyh.learnhub.entity.WikiPage>lambdaQuery()
                            .select(org.dyh.learnhub.entity.WikiPage::getTopicKey,
                                    org.dyh.learnhub.entity.WikiPage::getTitle)
                            .eq(org.dyh.learnhub.entity.WikiPage::getTopicType, "entity"))) {
                if (p.getTitle() != null && !p.getTitle().isBlank()) {
                    existingByLower.putIfAbsent(p.getTitle().trim().toLowerCase(), p.getTitle().trim());
                }
            }
            List<Entity> normalized = new ArrayList<>();
            Set<String> seenLower = new LinkedHashSet<>();
            for (Entity e : candidates) {
                String lower = e.name().trim().toLowerCase();
                String keepName = existingByLower.get(lower);
                Entity node = (keepName == null || keepName.equals(e.name())) ? e : e.withName(keepName);
                if (seenLower.add(lower)) {
                    normalized.add(node);
                }
            }
            candidates = normalized;

            List<Entity> chosen = candidates.stream()
                    .sorted(Comparator.comparingInt(Entity::count).reversed()
                            .thenComparing(Entity::name))
                    .limit(MAX_ENTITY_PAGES)
                    .toList();
            // 规则违规的遗留子条目页要清 —— 这一步和"本次抽到几个概念"无关，所以放在提前返回之前
            int fixedSub = pruneSubEntryPages();
            if (fixedSub > 0) {
                log.info("清理规则违规的子条目实体页 {} 个", fixedSub);
            }

            if (chosen.isEmpty()) {
                job.stage = "素材里没有可成页的概念";
                job.detail = fixedSub > 0 ? "本次没抽到概念；顺带清理了 " + fixedSub + " 个子条目重复页" : "";
                job.status = "done";
                job.finishedAt = System.currentTimeMillis();
                return;
            }
            Set<String> allNames = new LinkedHashSet<>();
            for (Entity e : chosen) {
                allNames.add(e.name());
            }

            // ③ reduce：一页一个实体
            job.stage = "编写实体页";
            job.total = chosen.size();
            job.done = 0;
            for (Entity e : chosen) {
                String md = writePage(e, allNames);
                // 复用主题页那套质量校验：引用必须是真实存在的素材编号，结构要完整
                WikiQuality.Result q = WikiQuality.check(md, e.validIds());
                upsertEntityPage(e, q.content(), q.ok() ? "ok" : "warn", q.summary());
                job.pages++;
                job.chars += q.content().length();
                job.done++;
                job.percent = 46 + (int) (48.0 * job.done / chosen.size());
                job.detail = "已写 " + job.pages + " 页 · 当前：" + e.name();
            }

            // ③.5 旧实体页：**不再自动删除**。
            // 这里原来会把"本次没被选中"的实体页全部删掉（为了不越积越多）。但实测抽取本身是不确定的：
            // 同一份素材连跑两次，选出的概念集合就不同 —— 于是"整批替换"变成每编译一次就丢掉上一次的一批好页。
            // 实测证据：一次编译从 10 页换成 8 页，Spring Framework / @GetMapping / @PostMapping / Docker / IoC
            // 全部消失，而它们的内容并没有错。
            // 现在的规则：编译只做**新增 / 更新**（增量为准），删页交给人 —— 界面上每页都有「删除」，
            // 而且是人在看一眼之后按下去，比模型按不确定的结果批量删安全得多。

            // ④ 索引页：确定性生成，不调模型（模型做这个会漏项）
            job.stage = "生成索引页";
            job.percent = 96;
            upsertIndexPage(chosen);

            job.stage = "完成";
            job.percent = 100;
            job.status = "done";
            job.finishedAt = System.currentTimeMillis();
            log.info("实体页编译完成：{} 个来源 → 候选 {} → 成页 {}（{} 字），耗时 {} ms",
                    docs.size(), pool.size(), chosen.size(), job.chars, job.finishedAt - job.startedAt);
        } catch (Exception e) {
            log.warn("实体页编译失败：{}", e.toString());
            job.status = "failed";
            job.error = e.getMessage() == null ? e.toString() : e.getMessage();
            job.stage = "失败";
            job.finishedAt = System.currentTimeMillis();
        }
    }

    // ------------------------------------------------------------------
    // 素材与抽取
    // ------------------------------------------------------------------

    private record SourceDoc(String type, Long id, String title, String text) {
    }

    private record Entity(String name, String kind, String brief, List<String> aliases,
                          List<String> sources, Set<String> validIds, int count, String evidence) {
        Entity withCount(int c) {
            return new Entity(name, kind, brief, aliases, sources, validIds, c, evidence);
        }

        Entity withName(String n) {
            return new Entity(n, kind, brief, aliases, sources, validIds, count, evidence);
        }
    }

    /** 素材抽样：跨段取 3 段（只看开头时，11.8 万字的书只有前几章的概念能被抽到） */
    private List<SourceDoc> sourceDocs() {
        List<SourceDoc> out = new ArrayList<>();
        for (Map<String, Object> r : kbMapper.allNotes()) {
            out.add(new SourceDoc("note", num(r.get("id")), str(r.get("title")), WikiService.sampleSpread(clip(str(r.get("content")), SOURCE_CHARS * 6), SOURCE_CHARS, 3)));
        }
        for (Map<String, Object> r : kbMapper.allRefs()) {
            out.add(new SourceDoc("quick_ref", num(r.get("id")), str(r.get("title")), WikiService.sampleSpread(clip(str(r.get("content")), SOURCE_CHARS * 6), SOURCE_CHARS, 3)));
        }
        for (Map<String, Object> r : kbMapper.allFiles()) {
            out.add(new SourceDoc("file", num(r.get("id")), str(r.get("title")), WikiService.sampleSpread(clip(str(r.get("content")), SOURCE_CHARS * 6), SOURCE_CHARS, 3)));
        }
        return out;
    }

    private List<Entity> extract(List<SourceDoc> group) {
        StringBuilder sb = new StringBuilder();
        for (SourceDoc d : group) {
            sb.append("\n【").append(marker(d.type())).append("#").append(d.id()).append("】《")
              .append(d.title()).append("》\n").append(d.text()).append('\n');
        }
        String system = """
                你在为一个个人技术知识库抽取"值得单独成页"的概念/实体。
                判断标准：在素材里反复出现，或有一次明确完整的定义/用法。
                范围：框架、工具、库、API、协议、机制、命令、术语、人物/公司。
                只输出严格 JSON：{"entities":[{"name":"概念名","kind":"framework|tool|api|protocol|concept|command|other","brief":"一句话说明(≤40字)","aliases":["别名"]}]}
                最多 8 个；没有就返回 {"entities":[]}。不要输出 JSON 之外的内容。
                """;
        Map<String, String> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", "素材：\n" + sb);
        List<Map<String, String>> msgs = new ArrayList<>();
        Map<String, String> sys = new LinkedHashMap<>();
        sys.put("role", "system");
        sys.put("content", system);
        msgs.add(sys);
        msgs.add(userMsg);
        try {
            boolean separate = client.wikiUsesSeparateTarget();
            com.fasterxml.jackson.databind.JsonNode reply = client.chat(msgs, null,
                    modelBaseUrl, modelApiKey, modelName,
                    3000, 0.2,
                    // 强制关思考：抽取只是"读素材 → 出 JSON"，而思考 token 与正文共用 max_tokens。
                    // 实测（云端 deepseek-flash）开思考时单批 6978 字思考直接把 2000 预算吃光 →
                    // finish_reason=length、content 为空、整批候选丢失。这是实测证据，不是预防性猜测。
                    "disabled",
                    null,
                    java.time.Duration.ofMinutes(3));
            String content = reply.path("content").asText("");
            if (content.isBlank()) {
                // 同 chat()：空内容不能静默当成"这批素材没有实体"
                int reasoning = reply.path("reasoning_content").asText("").length();
                log.warn("抽取实体返回空内容：model={} 思考内容 {} 字", modelName, reasoning);
                throw new IllegalStateException("抽取实体返回空内容（思考内容 " + reasoning + " 字）");
            }
            com.fasterxml.jackson.databind.JsonNode arr = objectMapper.readTree(stripFence(content)).path("entities");
            List<Entity> out = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode n : arr) {
                String name = n.path("name").asText("").trim();
                if (name.isEmpty() || name.length() > 40) {
                    continue;
                }
                List<String> aliases = new ArrayList<>();
                for (com.fasterxml.jackson.databind.JsonNode a : n.path("aliases")) {
                    String v = a.asText("").trim();
                    if (!v.isEmpty() && v.length() <= 30) {
                        aliases.add(v);
                    }
                }
                // 证据：把该实体名（或别名）在素材里出现处的前后文摘出来 ——
                // 没有证据就写页，模型只能照名字编（正是我们要防的幻觉）。
                StringBuilder ev = new StringBuilder();
                Set<String> valid = new LinkedHashSet<>();
                List<String> needles = new ArrayList<>();
                needles.add(name);
                needles.addAll(aliases);
                for (SourceDoc d : group) {
                    String found = snippetAround(d.text(), needles, 260);
                    if (found != null) {
                        valid.add(d.type() + "-" + d.id());
                        ev.append('[').append(marker(d.type())).append('#').append(d.id()).append("] 《")
                          .append(d.title()).append("》\n").append(found).append("\n\n");
                    }
                }
                out.add(new Entity(name, n.path("kind").asText("concept"), n.path("brief").asText(""),
                        aliases, List.of(), valid, 1, clip(ev.toString(), 1600)));
            }
            return out;
        } catch (Exception e) {
            log.warn("抽取实体失败（跳过这批）：{}", e.toString());
            return List.of();
        }
    }

    /** 归并：同名（含别名命中）合并出现次数与来源 */
    private void merge(Map<String, Entity> pool, Entity e, List<SourceDoc> group) {
        String key = e.name().toLowerCase();
        Entity exist = pool.get(key);
        if (exist == null) {
            // 别名也当作候选落在同一 key 上，避免 "JVM" 与 "Java 虚拟机" 变成两页
            for (String a : e.aliases()) {
                if (pool.containsKey(a.toLowerCase())) {
                    exist = pool.get(a.toLowerCase());
                    break;
                }
            }
        }
        if (exist == null) {
            pool.put(key, e.withCount(1));
        } else {
            // 累积证据与来源（同一个概念在不同素材里出现 → 证据越多，写页越有依据）
            Set<String> valid = new LinkedHashSet<>(exist.validIds());
            valid.addAll(e.validIds());
            String evidence = clip(exist.evidence() + "\n" + e.evidence(), 1800);
            pool.put(key, new Entity(exist.name(), exist.kind(),
                    exist.brief().isBlank() ? e.brief() : exist.brief(),
                    exist.aliases(), exist.sources(), valid, exist.count() + 1, evidence));
        }
    }

    // ------------------------------------------------------------------
    // 写页
    // ------------------------------------------------------------------

    private String writePage(Entity e, Set<String> allNames) {
        StringBuilder others = new StringBuilder();
        for (String n : allNames) {
            if (!n.equalsIgnoreCase(e.name())) {
                others.append("- ").append(n).append('\n');
            }
        }
        String system = replaceSchema("""
                你在为个人知识库写一个「实体页」——一个概念/工具/API 独立成页。
                硬要求：
                1. 只用给定素材，不得引入素材之外的技术事实；
                2. 400 字以内，开头 1~2 句定义它是什么，然后按需分 ## 小节（用法 / 关系 / 注意 / 易错点）；
                3. 每条具体事实后标来源：[笔记#N] / [速查卡#N] / [资料#N]；
                4. 提到下面列出的其他概念时，写成 [[概念名]]（双方括号，前端会变成跳转链接）；
                5. 只输出正文 Markdown，不要前言结语。
                """);
        String user = "实体：" + e.name() + "（" + e.kind() + "，" + e.brief() + "）\n"
                + "其他已有概念（可 [[链接]]）：\n" + (others.length() == 0 ? "（无）" : others.toString())
                + "\n该实体在素材中的位置：\n" + e.evidence();
        // 预算是 3000 而不是 1200：思考 token 与正文共用这个额度，实测 1200 时
        // 出现过一次 finish_reason=length（思考还没写完、正文没地方放）→ 该页内容残缺或整页丢失。
        // 正文本身只要 ≤400 字，多出来的额度是留给思考的。
        return chat(system, user, 3000);
    }

    private void upsertEntityPage(Entity e, String content, String quality, String note) {
        String key = entityKey(e.name());
        org.dyh.learnhub.entity.WikiPage page = wikiMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<org.dyh.learnhub.entity.WikiPage>lambdaQuery()
                        .eq(org.dyh.learnhub.entity.WikiPage::getTopicKey, key));
        boolean create = page == null;
        if (create) {
            page = new org.dyh.learnhub.entity.WikiPage();
            page.setTopicKey(key);
        }
        page.setTopicType("entity");
        page.setTopicId(0L);
        page.setTitle(e.name());
        page.setContentMd(content);
        page.setSourceHash(e.kind() + "|" + e.count());
        page.setItemCount(e.count());
        page.setModel(modelName);
        page.setQuality(quality);
        page.setQualityNote(note == null || note.isBlank() ? null : clip(note, 250));
        page.setTargetId("compile");
        page.setGeneratedAt(java.time.LocalDateTime.now());
        if (create) {
            wikiMapper.insert(page);
        } else {
            wikiMapper.updateById(page);
        }
    }

    /** 索引页：确定性生成（按类别分组，列出可点链接与一句话说明） */
    private void upsertIndexPage(List<Entity> entities) {
        Map<String, List<Entity>> byKind = new LinkedHashMap<>();
        for (Entity e : entities) {
            byKind.computeIfAbsent(e.kind(), k -> new ArrayList<>()).add(e);
        }
        StringBuilder md = new StringBuilder();
        md.append("知识库共编译出 **").append(entities.size()).append("** 个概念/实体页。\n\n");
        for (Map.Entry<String, List<Entity>> en : byKind.entrySet()) {
            md.append("## ").append(kindLabel(en.getKey())).append('\n');
            for (Entity e : en.getValue()) {
                md.append("- [").append(e.name()).append("](#").append(entityKey(e.name()))
                  .append(") — ").append(e.brief().isBlank() ? "（无说明）" : e.brief()).append('\n');
            }
            md.append('\n');
        }
        md.append("> 这张索引由代码生成（不经过模型），所以不会漏项；页面之间的关联写在各自正文里（双方括号写法）。\n");
        String key = "index";
        org.dyh.learnhub.entity.WikiPage page = wikiMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<org.dyh.learnhub.entity.WikiPage>lambdaQuery()
                        .eq(org.dyh.learnhub.entity.WikiPage::getTopicKey, key));
        boolean create = page == null;
        if (create) {
            page = new org.dyh.learnhub.entity.WikiPage();
            page.setTopicKey(key);
        }
        page.setTopicType("index");
        page.setTopicId(0L);
        page.setTitle("知识索引");
        page.setContentMd(md.toString());
        page.setSourceHash("index-" + entities.size());
        page.setItemCount(entities.size());
        page.setModel(null);
        page.setQuality("ok");
        page.setQualityNote(null);
        page.setTargetId("compile");
        page.setGeneratedAt(java.time.LocalDateTime.now());
        if (create) {
            wikiMapper.insert(page);
        } else {
            wikiMapper.updateById(page);
        }
    }

    // ------------------------------------------------------------------
    // 影响分析（③ 局部重编译的可行性探针：先量模型选页准不准，再决定要不要自动更新）
    // ------------------------------------------------------------------

    /**
     * 给定一段新素材，问模型"应该更新哪些已有页面"。
     * <p>这是"局部重编译"的核心判断步骤 —— 也是 8B 本地模型最容易翻车的一步
     * （要通读页面清单、判断相关性）。所以先做成**可测量的探针**：把模型的选页结果和候选清单一起返回，
     * 人一眼就能看出它选得准不准，再决定要不要把这一步接进自动流程。
     */
    public Map<String, Object> impact(String newText, int limit, String profileId) {
        // 影响分析是"判断类"任务（要通读页面清单再选页）。
        // 这里必须**自己**解析模型目标：曾经依赖 start() 遗留的 modelBaseUrl/modelName 字段，
        // 后端一重启这些字段就是 null，调用立刻抛异常 → 被下面 catch 成空 targets，
        // 表现为"重编译成功、0 页受影响"这种最难查的静默失败。
        setTarget(profileId);
        List<Map<String, Object>> pages = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (org.dyh.learnhub.entity.WikiPage p : wikiMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<org.dyh.learnhub.entity.WikiPage>lambdaQuery()
                        .select(org.dyh.learnhub.entity.WikiPage::getTopicKey,
                                org.dyh.learnhub.entity.WikiPage::getTopicType,
                                org.dyh.learnhub.entity.WikiPage::getTitle))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("topicKey", p.getTopicKey());
            m.put("topicType", p.getTopicType());
            m.put("title", p.getTitle());
            pages.add(m);
            sb.append("- ").append(p.getTopicKey()).append("  ").append(p.getTitle()).append('\n');
        }
        String system = """
                你是知识库维护者。下面是现有页面清单（topicKey + 标题）与一段**新素材**。
                判断：这段新素材应该更新哪些已有页面？（只列确实相关的，最多 %d 个；不要新建页面）
                只输出严格 JSON：{"targets":[{"topicKey":"…","reason":"为什么相关(≤30字)"}]}
                若都不相关，返回 {"targets":[]}。不要输出 JSON 之外的内容。
                """.formatted(limit);
        String user = "现有页面：\n" + sb + "\n新素材：\n" + clip(newText, 4000);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pages", pages);
        out.put("newText", clip(newText, 200));
        try {
            String reply = chat(system, user, 2000, "disabled");
            // 把模型原始输出带回去：解析失败时这是唯一能看出"它到底说了什么"的证据
            out.put("raw", clip(reply, 400));
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(stripFence(reply));
            com.fasterxml.jackson.databind.JsonNode arr = root.path("targets");
            // 模型经常会**直接返回数组**，而不是约定的 {"targets":[...]}。
            // 曾经只认后者：MissingNode 不是数组，for 遍历它一个元素都不产生、也不抛异常，
            // 于是变成"影响分析成功、0 页受影响"的静默失败。这里改成宽容解析。
            if (!arr.isArray()) {
                arr = root.isArray() ? root : objectMapper.createArrayNode();
            }
            if (arr.isEmpty()) {
                // 空数组 + 有原始输出 = 模型确实认为不相关（合法）；
                // 空数组 + 没有原始输出 = 调用异常，前面的 catch 已经处理。
                out.put("targets", List.of());
                return out;
            }
            List<Map<String, Object>> targets = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode t : arr) {
                if (!t.isObject()) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("topicKey", firstText(t, "topicKey", "topic_key", "key", "page", "topic"));
                m.put("reason", firstText(t, "reason", "why", "note", "说明"));
                targets.add(m);
            }
            out.put("targets", targets);
            return out;
        } catch (Exception e) {
            log.warn("影响分析失败（模型 {}）：{}", modelName, e.toString());
            out.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
            out.put("targets", List.of());
            return out;
        }
    }

    /** 依次尝试多个字段名，返回第一个非空的文本值（模型输出的键名并不总是完全一致） */
    private static String firstText(com.fasterxml.jackson.databind.JsonNode node, String... names) {
        for (String n : names) {
            com.fasterxml.jackson.databind.JsonNode v = node.path(n);
            if (v.isTextual() && !v.asText().isBlank()) {
                return v.asText().trim();
            }
        }
        return "";
    }

    /**
     * 清理"子条目"实体页：若某实体页的名字 = 另一个实体页的名字 + 分隔符 + 小写开头，
     * 它就是不该单独成页的子条目（git commit / git config 之于 git）。
     * <p>只做这一条确定性清理，**不**按"本次是否抽到"批量删页 —— 后者会随抽取波动丢掉好页。
     *
     * @return 实际删除的页数
     */
    private int pruneSubEntryPages() {
        List<org.dyh.learnhub.entity.WikiPage> pages = wikiMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<org.dyh.learnhub.entity.WikiPage>lambdaQuery()
                        .select(org.dyh.learnhub.entity.WikiPage::getTopicKey,
                                org.dyh.learnhub.entity.WikiPage::getTitle)
                        .eq(org.dyh.learnhub.entity.WikiPage::getTopicType, "entity"));
        int deleted = 0;
        for (org.dyh.learnhub.entity.WikiPage p : pages) {
            String n = p.getTitle() == null ? "" : p.getTitle().trim().toLowerCase();
            if (n.isEmpty()) {
                continue;
            }
            for (org.dyh.learnhub.entity.WikiPage q : pages) {
                if (q == p) {
                    continue;
                }
                String o = q.getTitle() == null ? "" : q.getTitle().trim().toLowerCase();
                if (o.isEmpty() || n.length() <= o.length() || !n.startsWith(o)) {
                    continue;
                }
                int k = o.length();
                while (k < n.length() && !Character.isLetterOrDigit(n.charAt(k))) {
                    k++;
                }
                if (k >= n.length() || Character.isLowerCase(n.charAt(k))) {
                    wikiMapper.delete(com.baomidou.mybatisplus.core.toolkit.Wrappers
                            .<org.dyh.learnhub.entity.WikiPage>lambdaQuery()
                            .eq(org.dyh.learnhub.entity.WikiPage::getTopicKey, p.getTopicKey()));
                    deleted++;
                    break;
                }
            }
        }
        return deleted;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 实体页的稳定主键：SHA-256 前 10 位（中文名也能得到稳定 key，避免前端/后端 slug 规则不一致） */
    public static String entityKey(String name) {
        return "entity-" + KgService.sha256(name == null ? "" : name.trim()).substring(0, 10);
    }

    private String chat(String system, String user, int maxTokens) {
        return chat(system, user, maxTokens, null);
    }

    /**
     * @param thinkingOverride null = 跟随全局设置；"disabled"/"enabled" = 仅本次强制。
     *                         <p>结构化 JSON 步骤（影响分析）应显式 disabled：思考 token 与正文共用 max_tokens，
     *                         实测 600 预算下思考会把预算吃光，finish_reason=length、content 为空。
     */
    private String chat(String system, String user, int maxTokens, String thinkingOverride) {
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
            com.fasterxml.jackson.databind.JsonNode node = client.chat(msgs, null, modelBaseUrl, modelApiKey, modelName,
                    maxTokens, 0.3,
                    thinkingOverride != null ? thinkingOverride : (modelSeparate ? "disabled" : client.thinkingOf(modelProfileId)),
                    modelSeparate ? null : client.reasoningEffortOf(modelProfileId),
                    java.time.Duration.ofMinutes(3));
            String content = node.path("content").asText("");
            if (content.isBlank()) {
                // 空内容必须**显式失败**。曾经它一路变成空 targets / 空实体列表，
                // 上层看起来就是"模型认为不相关"，是最难查的静默失败。
                int reasoning = node.path("reasoning_content").asText("").length();
                log.warn("模型返回空内容：model={} max_tokens={} 思考内容 {} 字", modelName, maxTokens, reasoning);
                throw new IllegalStateException("模型返回空内容（思考内容 " + reasoning + " 字，max_tokens=" + maxTokens
                        + "），通常是思考 token 占满了预算，请调大预算或关闭思考");
            }
            return content;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("AI 调用失败：" + e.getMessage());
        }
    }

    /**
     * 把 Schema（{@code skills/wiki-schema/SKILL.md}）拼进系统提示词。
     * <p>规则从"代码里的常量"变成"可编辑文件"之后，改 wiki 的性格不用改代码、也不用重启。
     */
    private String replaceSchema(String extra) {
        String schema = null;
        try {
            schema = skillService.prompt("wiki-schema");
        } catch (Exception e) {
            log.debug("未找到 wiki-schema 技能，用内置规则：{}", e.getMessage());
        }
        StringBuilder sb = new StringBuilder();
        if (schema != null && !schema.isBlank()) {
            sb.append(schema.trim()).append("\n\n");
        }
        sb.append(extra);
        return sb.toString();
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

    /** 取实体名在文本中首次出现处的前后文（找不到返回 null） */
    private static String snippetAround(String text, List<String> needles, int radius) {
        if (text == null) {
            return null;
        }
        String lower = text.toLowerCase();
        for (String n : needles) {
            if (n == null || n.isBlank()) {
                continue;
            }
            int at = lower.indexOf(n.toLowerCase());
            if (at >= 0) {
                int start = Math.max(0, at - radius / 2);
                int end = Math.min(text.length(), at + n.length() + radius);
                return text.substring(start, end).replaceAll("\\s+", " ").trim();
            }
        }
        return null;
    }

    private static String marker(String type) {
        return switch (type) {
            case "file" -> "资料";
            case "quick_ref" -> "速查卡";
            default -> "笔记";
        };
    }

    private static String kindLabel(String kind) {
        return switch (kind == null ? "" : kind) {
            case "framework" -> "框架";
            case "tool" -> "工具";
            case "api" -> "API";
            case "protocol" -> "协议";
            case "command" -> "命令";
            case "concept" -> "概念";
            default -> "其他";
        };
    }

    private static Long num(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
