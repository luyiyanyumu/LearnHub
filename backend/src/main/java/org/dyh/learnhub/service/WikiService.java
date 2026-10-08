package org.dyh.learnhub.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.dyh.learnhub.ai.AgentService;
import org.dyh.learnhub.ai.DeepSeekClient;
import org.dyh.learnhub.ai.ModelRouting;
import org.dyh.learnhub.common.KnowledgeChangedEvent;
import org.dyh.learnhub.entity.AppSetting;
import org.dyh.learnhub.entity.Category;
import org.dyh.learnhub.entity.Tag;
import org.dyh.learnhub.entity.WikiPage;
import org.dyh.learnhub.mapper.AppSettingMapper;
import org.dyh.learnhub.mapper.KgMapper;
import org.dyh.learnhub.mapper.WikiPageMapper;
import org.dyh.learnhub.vo.NoteVO;
import org.dyh.learnhub.vo.QuickRefVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * LLM Wiki：把某个主题（分类 / 标签）下的笔记与速查卡，交给模型整理成一页结构化长文。
 *
 * <h3>为什么按主题而不是"一篇总 wiki"</h3>
 * 生成一次要几十秒、几万 token；总 wiki 会让每次改一篇小笔记都得重生成整篇。
 * 按主题切分后，改动只影响所在主题那一页，素材也只有几十条 —— 质量和成本都可控。
 *
 * <h3>三个关键机制</h3>
 * <ol>
 *   <li><b>缓存 + 指纹</b>：正文落 {@code wiki_page}，同时存生成时素材的指纹。
 *       指纹与当前素材不一致 = 这一页过期，界面显示「待更新」并可一键重新生成。</li>
 *   <li><b>自动增量更新</b>：笔记/速查卡一改，{@link KnowledgeChangedEvent} 就会到达这里，
 *       只有**已经生成过**的主题才会被排进后台队列（不给没生成过的主题偷偷烧 token），
 *       同一主题 20 秒内只排一次（连续编辑不会触发连串调用）。</li>
 *   <li><b>可关闭</b>：自动更新会花钱，所以设置里有开关（{@code wiki.auto_refresh}，默认开）。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class WikiService {

    private static final Logger log = LoggerFactory.getLogger(WikiService.class);
    /** Query freshness inspects rendered prose, not citation examples inside code or metadata. */
    private static final Parser RETRIEVAL_MARKDOWN = Parser.builder().build();

    /** 自动增量更新的开关（存 app_setting，与 AI 设置同表但不出现在设置面板里：它属于 wiki 页的工具栏） */
    public static final String SETTING_AUTO_REFRESH = "wiki.auto_refresh";

    /** 上次选择的生成目标（main / local）：自动增量更新沿用，避免"手动用本地、后台偷烧云端 token" */
    public static final String KEY_WIKI_TARGET = "wiki.target";

    /** 送进模型的素材条数上限 */
    private static final int MAX_ITEMS = 40;

    /** 送进模型的素材总字数上限（真正生效：超了就只取最近的若干条） */
    private static final int MAX_MATERIAL_CHARS = 12000;

    /**
     * 单次生成的整体超时。
     * <p>本地 8B 出一页约 20~35 秒，大主题更久；这里给 4 分钟，与前端 5 分钟的等待上限对齐，
     * 留出余量给质量校验与一次重生成。
     */
    private static final java.time.Duration WIKI_TIMEOUT = java.time.Duration.ofMinutes(4);

    /** 单条素材摘要截断长度 */
    private static final int SNIPPET_CHARS = 400;

    /**
     * 指纹扫描上限。指纹要覆盖"整个主题"，否则没被送进模型的那些条目的改动探测不到；
     * 但这个查询刻意用列表 VO（不含 LONGTEXT 正文），几百条也很轻。
     */
    private static final int MAX_FETCH = 500;

    /** 同一主题两次自动更新之间的最小间隔：连续编辑不该触发连串生成 */
    private static final long DEBOUNCE_MS = 20_000;

    /** 长笔记的跨段采样上限（摘要之外再加这么多） */
    private static final int NOTE_SAMPLE_CHARS = 1600;

    /** 长文档采样上限：先按这个宽度取样，再 clip 到 FILE_SNIPPET_CHARS 进素材 */
    private static final int FILE_SAMPLE_CHARS = 2400;

    /** 资料素材：单条截断更长（文档正文比笔记摘要更有内容），并限制条数 */
    private static final int FILE_SNIPPET_CHARS = 2000;
    private static final int MAX_FILES_FOR_WIKI = 200;
    /** 影响分析看几条最近变更的素材 */
    private static final int RECENT_SOURCES = 5;

    /**
     * 检索注入：最多几页、块的门槛分。
     * <p>注入的**内容形态**在 2026-09-29 改过：从"整页摘要/最相关一段"（{@code RAG_EXCERPT} 字）
     * 改成"目录 + 缺口"（{@link #indexEntry}，约 100~200 字）—— 详见该方法的注释。
     * <p>页数从 1 放回 2 是同一批实测的副产品：近重复主题（`MQTT` / `MQTT协议`）会让
     * 问题**正好命中那个没有「待补充」的页**，缺口信息就丢了；两条目录条目一共 250~350 字，
     * 仍比原来一页 600 字的摘要便宜。主题去重（合并近重复页）是更根本的修法，见 rag-design §2.5。
     */
    private static final int RAG_MAX_PAGES = 2;
    /** 「待补充」里最多列几条缺口、每条截多长 */
    private static final int RAG_GAP_ITEMS = 4;
    private static final int RAG_GAP_ITEM_CHARS = 40;
    /** 目录里最多列几个小节名 */
    private static final int RAG_SECTIONS = 6;
    private static final int RAG_MIN_SCORE = 3;

    private final NoteService noteService;
    private final QuickRefService quickRefService;
    private final CategoryService categoryService;
    private final TagService tagService;
    /** 资料库：资料也进主题 wiki 的素材（标记为 [资料#N]） */
    private final FileStorageService fileStorageService;
    private final WikiPageMapper mapper;
    private final AppSettingMapper settingMapper;
    private final KgMapper kgMapper;
    /** 模型分工表：主题页默认走本地，见 ModelRouting */
    private final ModelRouting routing;
    /** Schema 层：wiki 的行为规则来自 skills/wiki-schema/SKILL.md（可编辑，不用改代码） */
    private final SkillService skillService;
    /** 局部重编译要用"最近变更的素材"做影响分析 */
    private final org.dyh.learnhub.mapper.KbChunkMapper kbChunkMapper;
    private final EntityCompileService entityCompileService;
    private final WikiDependencyService dependencyService;
    private final DeepSeekClient client;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /** 自动更新走单线程：同时生成多页既烧钱又容易撞 API 限流 */
    private final java.util.concurrent.ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "wiki-refresh");
                t.setDaemon(true);
                return t;
            });

    /**
     * 待刷新的主题（脏标记）。
     * <p>
     * 这里刻意不用"看到事件就直接跑、跑不了就丢"的写法：实测踩到过 —— 新增笔记触发的重生成还没跑完，
     * 紧接着删除又来了，第二次事件被防抖/in-flight 判掉、**且没有任何补偿**，
     * 于是这一页永远停在「待更新」，用户只能手动点一次才会好。
     * 现在改成：事件只置脏标记 + 安排一次延迟合并；真正跑之前再比一次指纹，被推迟的变更因此不会丢。
     */
    private final Set<String> dirty = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicBoolean workerQueued =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
        jobRunner.shutdownNow();
    }

    // ------------------------------------------------------------------
    // 1. 主题清单
    // ------------------------------------------------------------------

    /** 主题（分类 / 标签）及其 wiki 状态：有没有生成过、是否过期、几条素材 */
    public List<Map<String, Object>> topics() {
        List<Map<String, Object>> out = new ArrayList<>();
        List<WikiPage> persisted = mapper.selectList(Wrappers.<WikiPage>lambdaQuery());
        Map<Long, WikiDependencyService.Freshness> dependencies = dependencyService.freshness(persisted);

        List<Map<String, Object>> flatCats = new ArrayList<>();
        flattenCats(categoryService.tree(), "", flatCats);
        for (Map<String, Object> c : flatCats) {
            Long id = (Long) c.get("id");
            Material m = material(new Topic("cat-" + id, "category", id, (String) c.get("label")));
            out.add(topicInfo("cat-" + id, "category", id, (String) c.get("label"), m, dependencies));
        }

        for (Tag t : tagService.list()) {
            Material m = material(new Topic("tag-" + t.getId(), "tag", t.getId(), t.getName()));
            if (m.items().isEmpty()) {
                continue; // 没挂任何笔记的标签不值得生成
            }
            out.add(topicInfo("tag-" + t.getId(), "tag", t.getId(), t.getName(), m, dependencies));
        }

        // 实体页与索引页（编译产物）：作为伪主题一起列出，让它们在同一个界面里可点可读
        List<WikiPage> compiled = persisted.stream()
                .filter(p -> List.of("entity", "index", "lint").contains(p.getTopicType())).toList();
        // 索引页的输入就是**实体页清单**，可以用同一套指纹算法重算来判断它是否过期。
        // 实体页的输入是"它用到的来源"，同样在读取时重算（评估报告 P1-2）：
        // 以前这里一律 stale=false —— 编译页永远不提示"待更新"。
        List<WikiPage> entityPages = new ArrayList<>();
        for (WikiPage p : compiled) {
            if ("entity".equals(p.getTopicType())) {
                entityPages.add(p);
            }
        }
        String currentIndexFp = EntityCompileService.indexFingerprint(entityPages);
        // 三类来源的最后修改时间：**一次批量查全**（36 个实体页只在内存里比对，不逐页查库）
        boolean legacyEntities = entityPages.stream().anyMatch(p -> dependencyState(p, dependencies).legacy());
        Map<String, LocalDateTime> sourceTimes = legacyEntities ? sourceTimes() : Map.of();
        for (WikiPage p : compiled) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("topicKey", p.getTopicKey());
            o.put("topicType", p.getTopicType());
            o.put("topicId", 0L);
            o.put("title", p.getTitle());
            o.put("itemCount", p.getItemCount() == null ? 0 : p.getItemCount());
            o.put("sentItems", p.getItemCount() == null ? 0 : p.getItemCount());
            o.put("generated", StringUtils.hasText(p.getContentMd()));
            // 索引页按"实体页清单"指纹比对（增/删/改名才过期；重生成正文不影响索引内容，不算过期）；
            // 实体页按"来源指纹 + 依赖映射"重算（来源被删 / 被改才过期，无关页不受影响）；
            // 自检页（lint）的输入是"全库扫描结果"，没有稳定的来源清单，保持 false。
            var state = dependencyState(p, dependencies);
            o.put("stale", "entity".equals(p.getTopicType()) && !state.legacy()
                    ? !state.current() : compiledStale(p.getTopicType(), p, currentIndexFp, sourceTimes));
            o.put("chars", p.getContentMd() == null ? 0 : p.getContentMd().length());
            o.put("quality", p.getQuality() == null ? "" : p.getQuality());
            o.put("generatedAt", p.getGeneratedAt() == null ? null
                    : String.valueOf(p.getGeneratedAt()).replace('T', ' '));
            o.put("model", p.getModel());
            out.add(o);
        }

        // 排序：先"已生成但过期"（最需要关注），再"已生成且最新"，最后"还没生成"的按条目数排
        out.sort(Comparator
                .comparingInt((Map<String, Object> o) -> {
                    boolean generated = (Boolean) o.get("generated");
                    boolean stale = (Boolean) o.get("stale");
                    if (generated && stale) {
                        return 0;
                    }
                    return generated ? 1 : 2;
                })
                .thenComparing(o -> -((Integer) o.get("itemCount"))));
        return out;
    }

    /**
     * 编译产物页（实体页 / 索引页 / 自检页）是否过期 —— 清单页用**预算好的**输入判断，不再查库。
     *
     * @param currentIndexFp 当前实体页清单的指纹（{@link EntityCompileService#indexFingerprint}）
     * @param sourceTimes    三类来源的最后修改时间（{@link #sourceTimes()}）
     */
    private boolean compiledStale(String type, WikiPage page, String currentIndexFp,
                                  Map<String, LocalDateTime> sourceTimes) {
        if (page == null) {
            return false;
        }
        if ("index".equals(type)) {
            // 索引的内容只由"实体页清单"决定，所以指纹对不上才是真的过期
            return StringUtils.hasText(page.getSourceHash()) && !page.getSourceHash().equals(currentIndexFp);
        }
        if ("entity".equals(type)) {
            // 实体页：按落库的来源清单重算（来源被删 / 被改 → 过期；无关页不受影响）
            return EntityCompileService.staleBySources(
                    EntityCompileService.sourceIdsOf(page.getSourceHash(), retrievalCitationText(page.getContentMd())),
                    sourceTimes, page.getGeneratedAt());
        }
        // 自检报告（lint）：输入是"全库扫描结果"，没有稳定的来源清单可重算，保持不过期
        return false;
    }

    /** 单页读取（{@link #page}）用的版本：按需查一次库，不为了没用到的类型白查 */
    private boolean compiledStale(String type, WikiPage page) {
        if (page == null) {
            return false;
        }
        if ("entity".equals(type)) {
            var state = dependencyState(page);
            if (!state.legacy()) return !state.current();
        }
        String fp = "index".equals(type) ? currentIndexFingerprint() : null;
        Map<String, LocalDateTime> times = "entity".equals(type) ? sourceTimes() : Map.of();
        return compiledStale(type, page, fp, times);
    }

    private static WikiDependencyService.Freshness dependencyState(WikiPage page,
            Map<Long, WikiDependencyService.Freshness> states) {
        var unknown = new WikiDependencyService.Freshness(WikiDependencyService.Status.UNKNOWN,
                List.of("dependency_check_failed"), false);
        return page == null || page.getId() == null || states == null ? unknown : states.getOrDefault(page.getId(), unknown);
    }

    private WikiDependencyService.Freshness dependencyState(WikiPage page) {
        return dependencyState(page, page == null ? Map.of() : dependencyService.freshness(List.of(page)));
    }

    /** 当前实体页清单的指纹（索引页"是否过期"的比对基准） */
    private String currentIndexFingerprint() {
        // 只取 topicKey + 标题：与 indexFingerprint 真正用到的那两个字段一致
        return EntityCompileService.indexFingerprint(mapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                .select(WikiPage::getTopicKey, WikiPage::getTitle)
                .eq(WikiPage::getTopicType, "entity")));
    }

    /**
     * 三类来源的最后修改时间，键形如 {@code note-3} / {@code quick_ref-8} / {@code file-5}。
     * <p>**一次批量查全**：实体页有几十个，逐页查库会把一个列表接口变成几十次往返。
     */
    private Map<String, LocalDateTime> sourceTimes() {
        Map<String, LocalDateTime> out = new LinkedHashMap<>();
        for (Map<String, Object> r : kbChunkMapper.allSourceTimes()) {
            String type = r.get("t") == null ? "" : String.valueOf(r.get("t"));
            Object id = r.get("id");
            LocalDateTime ts = asTime(r.get("ts"));
            if (type.isEmpty() || id == null || ts == null) {
                continue;
            }
            out.put(type + "-" + (id instanceof Number n ? n.longValue() : String.valueOf(id)), ts);
        }
        return out;
    }

    /** Query-only freshness gate. Missing provenance or an incomplete scan is not a fresh page. */
    public Map<String, Boolean> retrievalFreshness(List<WikiPage> pages) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        if (pages == null || pages.isEmpty()) return out;
        for (WikiPage p : pages) if (p != null && StringUtils.hasText(p.getTopicKey())) out.put(p.getTopicKey(), false);
        Map<Long, WikiDependencyService.Freshness> dependencies;
        Map<String, LocalDateTime> times;
        List<Map<String, Object>> files;
        try {
            dependencies = dependencyService.freshness(pages);
            boolean legacy = pages.stream().anyMatch(p -> p != null && dependencyState(p, dependencies).legacy());
            times = legacy ? sourceTimes() : Map.of();
            boolean categories = pages.stream().anyMatch(p -> p != null && "category".equals(p.getTopicType()));
            files = categories ? mapper.retrievalFileScope(MAX_FILES_FOR_WIKI + 1) : List.of();
            if (files == null) return out;
        } catch (Exception e) {
            log.warn("Wiki 检索无法确认来源新鲜度，跳过页面：{}", e.toString());
            return out;
        }
        Map<String, RetrievalMaterial> materials = new LinkedHashMap<>();
        for (WikiPage p : pages) {
            if (p == null || !StringUtils.hasText(p.getTopicKey()) || !StringUtils.hasText(p.getContentMd())
                    || p.getGeneratedAt() == null) continue;
            try {
                var state = dependencyState(p, dependencies);
                // A tracked page must never fall back to timestamp freshness after a failed check.
                if (!state.legacy() && !state.current()) continue;
                String citationText = retrievalCitationText(p.getContentMd());
                Set<String> refs = new LinkedHashSet<>(EntityCompileService.sourceIdsOf(p.getSourceHash(), citationText));
                // The compact source_hash is only 64 characters. Always include citations that did not fit.
                refs.addAll(EntityCompileService.citationsOf(citationText));
                if ("category".equals(p.getTopicType()) || "tag".equals(p.getTopicType())) {
                    String expectedKey = ("tag".equals(p.getTopicType()) ? "tag-" : "cat-") + p.getTopicId();
                    if (p.getTopicId() == null || p.getTopicId() <= 0 || !expectedKey.equals(p.getTopicKey())
                            || !StringUtils.hasText(p.getSourceHash())) continue;
                    RetrievalMaterial current = materials.computeIfAbsent(p.getTopicKey(), ignored -> retrievalMaterial(p, files));
                    if (current == null || !current.hash().equals(p.getSourceHash())) continue;
                    refs.addAll(current.sourceIds());
                } else if (!"entity".equals(p.getTopicType()) || !p.getTopicKey().startsWith("entity-")) {
                    continue;
                }
                if (!state.legacy()) {
                    out.put(p.getTopicKey(), true);
                    continue;
                }
                if (refs.isEmpty()) continue;
                out.put(p.getTopicKey(), !EntityCompileService.staleBySources(new ArrayList<>(refs), times, p.getGeneratedAt()));
            } catch (Exception e) {
                log.warn("Wiki 页面新鲜度未知，跳过 {}：{}", p.getTopicKey(), e.toString());
            }
        }
        return out;
    }

    /** Preserve prose boundaries so removing inline code cannot join two fragments into a fake citation. */
    private static String retrievalCitationText(String markdown) {
        StringBuilder text = new StringBuilder();
        RETRIEVAL_MARKDOWN.parse(markdown == null ? "" : markdown).accept(new AbstractVisitor() {
            @Override public void visit(Text node) { text.append(node.getLiteral()); }
            @Override public void visit(SoftLineBreak node) { text.append('\n'); }
            @Override public void visit(HardLineBreak node) { text.append('\n'); }
            @Override public void visit(Paragraph node) { super.visit(node); text.append('\n'); }
            @Override public void visit(Heading node) { super.visit(node); text.append('\n'); }
            // A citation label such as [笔记#1](/notes/1) remains prose; its URL is not a dependency.
            @Override public void visit(Link node) { text.append('['); super.visit(node); text.append(']'); }
            @Override public void visit(Code node) { text.append('\n'); }
            @Override public void visit(FencedCodeBlock node) { text.append('\n'); }
            @Override public void visit(IndentedCodeBlock node) { text.append('\n'); }
            @Override public void visit(HtmlBlock node) { text.append('\n'); }
            @Override public void visit(HtmlInline node) { text.append('\n'); }
        });
        return text.toString();
    }

    private record RetrievalMaterial(String hash, List<String> sourceIds) {}

    /** Reproduce the generation fingerprint without reading samples or calling a model. */
    private RetrievalMaterial retrievalMaterial(WikiPage page, List<Map<String, Object>> files) {
        boolean tag = "tag".equals(page.getTopicType());
        var notes = noteService.page(tag ? null : page.getTopicId(), tag ? page.getTopicId() : null, null, 1, MAX_FETCH);
        if (notes == null || notes.getList() == null || notes.getTotal() > MAX_FETCH
                || notes.getTotal() != notes.getList().size()
                || (!tag && files.size() > MAX_FILES_FOR_WIKI)) return null;
        List<Item> items = new ArrayList<>();
        for (NoteVO n : notes.getList()) {
            if (n.getId() == null || n.getUpdatedAt() == null) return null;
            items.add(new Item("note", n.getId(), "", "", "", n.getUpdatedAt(), 0));
        }
        if (!tag) {
            for (QuickRefVO r : quickRefService.list(page.getTopicId(), null)) {
                if (r.getId() == null || r.getUpdatedAt() == null) return null;
                items.add(new Item("ref", r.getId(), "", "", "", r.getUpdatedAt(), 0));
            }
            for (Map<String, Object> f : files) {
                if (!page.getTopicId().equals(asLong(f.get("categoryId")))) continue;
                Long id = asLong(f.get("id"));
                if (id == null) return null;
                items.add(new Item("file", id, "", "", "", null, 0));
            }
        }
        if (items.isEmpty()) return null;
        items.sort(Comparator.comparing(Item::type).thenComparing(Item::id));
        StringBuilder fp = new StringBuilder();
        List<String> refs = new ArrayList<>();
        for (Item i : items) {
            fp.append(i.type()).append(i.id()).append('@').append(i.updatedAt()).append(';');
            refs.add(("ref".equals(i.type()) ? "quick_ref" : i.type()) + "-" + i.id());
        }
        return new RetrievalMaterial(KgService.sha256(fp.toString()), List.copyOf(refs));
    }

    /** 结果集里的时间列 → LocalDateTime（驱动一般直接给 LocalDateTime，这里兼容 Timestamp / 字符串） */
    private static LocalDateTime asTime(Object v) {
        if (v instanceof LocalDateTime t) {
            return t;
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(s.replace(' ', 'T'));
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> topicInfo(String key, String type, Long id, String title, Material m,
            Map<Long, WikiDependencyService.Freshness> dependencies) {
        WikiPage page = byKey(key);
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("topicKey", key);
        o.put("topicType", type);
        o.put("topicId", id);
        o.put("title", title);
        o.put("itemCount", m.total());
        o.put("sentItems", m.items().size());
        o.put("generated", page != null && StringUtils.hasText(page.getContentMd()));
        o.put("stale", page != null && StringUtils.hasText(page.getContentMd())
                && (!m.hash().equals(page.getSourceHash()) || trackedStale(dependencyState(page, dependencies))));
        if (page != null) {
            o.put("chars", page.getContentMd() == null ? 0 : page.getContentMd().length());
            o.put("generatedAt", page.getGeneratedAt() == null ? null
                    : String.valueOf(page.getGeneratedAt()).replace('T', ' '));
            o.put("model", page.getModel());
        }
        return o;
    }

    private static boolean trackedStale(WikiDependencyService.Freshness state) {
        return !state.legacy() && !state.current();
    }

    public Map<String, Object> dependencyView(String topicKey) {
        WikiPage page = byKey(topicKey == null ? "" : topicKey.trim());
        if (page == null) throw new IllegalArgumentException("知识页不存在：" + topicKey);
        return dependencyService.evidenceView(page.getId());
    }

    // ------------------------------------------------------------------
    // 2. 读取 / 生成
    // ------------------------------------------------------------------

    /** 读一页（含过期标记）；没生成过时 contentMd 为空，前端据此显示「生成」按钮 */
    public Map<String, Object> page(String topicKey) {
        Topic topic = resolve(topicKey);
        WikiPage page = byKey(topicKey);

        // 编译产物（实体页 / 索引页 / 自检报告）没有"素材覆盖率"这套口径，单独返回
        if ("entity".equals(topic.type()) || "index".equals(topic.type()) || "lint".equals(topic.type())) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("topicKey", topicKey);
            o.put("topicType", topic.type());
            o.put("title", topic.title());
            o.put("itemCount", page == null || page.getItemCount() == null ? 0 : page.getItemCount());
            o.put("sentItems", 0);
            o.put("generated", page != null && StringUtils.hasText(page.getContentMd()));
            // 与清单接口同一套判定：实体页按来源清单重算、索引页按实体页清单指纹比对
            o.put("stale", compiledStale(topic.type(), page));
            o.put("contentMd", page == null || page.getContentMd() == null ? "" : page.getContentMd());
            o.put("generatedAt", page == null || page.getGeneratedAt() == null ? null
                    : String.valueOf(page.getGeneratedAt()).replace('T', ' '));
            o.put("model", page == null ? "" : page.getModel());
            o.put("quality", page == null || page.getQuality() == null ? "" : page.getQuality());
            o.put("qualityNote", page == null || page.getQualityNote() == null ? "" : page.getQualityNote());
            o.put("targetId", page == null || page.getTargetId() == null ? "" : page.getTargetId());
            o.put("entityIndex", entityIndex());
            return o;
        }

        Material m = material(topic);
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("topicKey", topicKey);
        o.put("topicType", topic.type());
        o.put("topicId", topic.id());
        o.put("title", topic.title());
        o.put("itemCount", m.total());
        // sentItems 与 itemCount 的区别：前者是"这次会送进模型的条数"（有上限），
        // 后者是"该主题全部素材数"。界面按 itemCount 显示，被截断时由自己算差值。
        o.put("sentItems", m.items().size());
        o.put("items", m.items().stream().map(it -> Map.of(
                "type", it.type(), "id", it.id(), "title", it.title())).toList());
        if (page == null) {
            o.put("generated", false);
            o.put("stale", false);
            o.put("contentMd", "");
            return o;
        }
        o.put("generated", StringUtils.hasText(page.getContentMd()));
        o.put("stale", !m.hash().equals(page.getSourceHash()) || trackedStale(dependencyState(page)));
        o.put("contentMd", page.getContentMd() == null ? "" : page.getContentMd());
        o.put("generatedAt", page.getGeneratedAt() == null ? null
                : String.valueOf(page.getGeneratedAt()).replace('T', ' '));
        o.put("model", page.getModel());
        o.put("generatedItemCount", page.getItemCount());
        // 质量校验结论：界面据此显示"已校验 / N 处提示"
        o.put("quality", page.getQuality() == null ? "" : page.getQuality());
        o.put("qualityNote", page.getQualityNote() == null ? "" : page.getQualityNote());
        o.put("targetId", page.getTargetId() == null ? "" : page.getTargetId());
        // 素材覆盖率：让"这一页到底覆盖了源文档多少"看得见。
        // 起因是实测发现 11.8 万字的资料只进了 1.68%、5.9 万字的笔记只进了 0.4%，而界面上完全看不出来 ——
        // 用户以为"wiki = 把知识全做进去了"，实际每条只进了片段。
        List<Map<String, Object>> coverage = new ArrayList<>();
        int usedTotal = 0;
        int sourceTotal = 0;
        int unknown = 0;
        for (Item it : m.items()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("type", it.type());
            c.put("id", it.id());
            c.put("title", it.title());
            c.put("usedChars", it.usedChars());
            Integer src = it.totalChars();
            c.put("sourceChars", src);
            if (src == null || src <= 0) {
                unknown++;
            } else {
                c.put("percent", Math.min(100, (int) Math.round(it.usedChars() * 100.0 / src)));
                sourceTotal += src;
            }
            usedTotal += it.usedChars();
            coverage.add(c);
        }
        o.put("materialChars", usedTotal);
        o.put("sourceChars", sourceTotal);
        o.put("coveragePercent", sourceTotal <= 0 ? 100 : Math.min(100, (int) Math.round(usedTotal * 100.0 / sourceTotal)));
        o.put("coverageUnknown", unknown);
        o.put("coverage", coverage);
        o.put("entityIndex", entityIndex());
        return o;
    }

    /**
     * 删除一页 wiki 编译产物。
     * <p>界面上必须讲清楚的一件事：删掉的只是**编译结果**，原始笔记 / 速查卡 / 资料一行都不动。
     * 所以被删的主题页在下次「生成」、实体页在下次「编译知识页」、自检页在下次「自检」时会重新出现。
     * 这个功能真正的用途是清掉**不该存在的页**（例如早期版本把索引页写成 {@code cat-0} 留下的垃圾行），
     * 以及不想要的实体页 —— 而不是"我不想要这条知识了"。
     *
     * @return 被删页面的 key / 标题 / 类型与实际删除行数
     */
    public Map<String, Object> deletePage(String topicKey) {
        String key = topicKey == null ? "" : topicKey.trim();
        if (key.isEmpty()) {
            throw new IllegalArgumentException("缺少主题标识");
        }
        WikiPage page = byKey(key);
        if (page == null) {
            throw new IllegalArgumentException("知识页不存在：" + key);
        }
        int n = mapper.delete(Wrappers.<WikiPage>lambdaQuery().eq(WikiPage::getTopicKey, key));
        // 别让它继续躺在"待重编译"集合里，否则删除后很快又被自动重建回来
        dirty.remove(key);
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("topicKey", key);
        o.put("title", page.getTitle());
        o.put("topicType", page.getTopicType());
        o.put("deleted", n);
        return o;
    }

    // ------------------------------------------------------------------
    // 生成任务：可选模型 + 进度可见 + 质量校验
    // ------------------------------------------------------------------

    /** 可选的生成目标（主模型 / 本地或自建模型） */
    public record Target(String id, String label, String baseUrl, String apiKey, String model, boolean separate) {
    }

    /** 生成任务：界面靠它显示进度（stage / percent / 已生成字数） */
    public static final class Job {
        public final String id;
        public final String topicKey;
        public final String targetId;
        public final long startedAt = System.currentTimeMillis();
        public volatile String stage = "排队";
        public volatile int percent;
        public volatile int chars;
        /** 局部重编译用：受影响页数 / 已完成 / 当前明细 */
        public volatile int total;
        public volatile int done;
        public volatile String detail = "";
        public volatile long finishedAt;
        public volatile String status = "running";
        public volatile String error;
        public volatile String model;
        public volatile String quality;

        Job(String id, String topicKey, String targetId) {
            this.id = id;
            this.topicKey = topicKey;
            this.targetId = targetId;
        }
    }

    /** 单线程跑生成：本地小模型扛不住并发，串行反而更快也更稳 */
    private final java.util.concurrent.ExecutorService jobRunner =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "wiki-generate");
                t.setDaemon(true);
                return t;
            });
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    /**
     * 可选目标列表 = **全部模型档案**。
     * <p>2026-09 改造：原来只有"主模型 + 一个本地目标"两个位置；现在档案可以有很多个，
     * 所以 wiki 页上的"生成模型"下拉直接列全部档案（与设置里的模型分工用同一份数据）。
     */
    public List<Target> targets() {
        List<Target> list = new ArrayList<>();
        for (ModelRouting.ModelTarget t : routing.targets()) {
            list.add(new Target(t.id(), t.label() + "（" + t.model() + "）",
                    t.baseUrl(), t.apiKey(), t.model(), t.separate()));
        }
        return list;
    }

    public Target target(String id) {
        List<Target> list = targets();
        if (id == null || id.isBlank()) {
            // 没指定时用"上次选的那个"；从没选过则优先本地（省 token）
            // 没显式指定时：上次选择的 > ModelRouting 的分工表（默认本地：摘要类任务够用且免费）
            String saved = targetSetting();
            String fallback = saved == null || saved.isBlank()
                    ? routing.targetIdOf(ModelRouting.TASK_WIKI) : saved;
            id = fallback;
        }
        String want = id;
        return list.stream().filter(t -> t.id().equals(want)).findFirst().orElse(list.get(0));
    }

    /** 实体名 → 页面 key 映射：前端把 [[名字]] 解析成可点链接用（避免前后端 slug 规则不一致） */
    public Map<String, String> entityIndex() {
        Map<String, String> m = new LinkedHashMap<>();
        for (WikiPage p : mapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                .select(WikiPage::getTopicKey, WikiPage::getTitle)
                .eq(WikiPage::getTopicType, "entity"))) {
            if (p.getTitle() != null) {
                m.put(p.getTitle(), p.getTopicKey());
            }
        }
        return m;
    }

    /** Schema 层文本：技能文件优先，缺失时回退内置常量 */
    private String schemaText() {
        try {
            String s = skillService.prompt("wiki-schema");
            if (s != null && !s.isBlank()) {
                return s.trim();
            }
        } catch (Exception e) {
            log.debug("未找到 wiki-schema 技能，回退内置规则：{}", e.getMessage());
        }
        return WIKI_SYSTEM;
    }

    /**
     * ③ 局部重编译：只更新"真正受影响"的页面，而不是整库重来。
     * <p>流水线：取最近变更的素材 → {@code TASK_IMPACT} 影响分析（模型判断该动哪些页）→
     * 逐页重生成受影响的**主题页** → 触发**知识页（实体/索引）整批重编译**。
     * <p>为什么实体页整批重编而不是单页重生成：实体是**跨页**的，单页重生成拿不到"它在哪些素材里出现过"
     * 的证据（证据是在抽取阶段收集的），硬造只会让页面变成对着名字瞎编。
     */
    public Map<String, Object> startRecompile() {
        Job job = new Job(UUID.randomUUID().toString().substring(0, 8), "recompile", "impact");
        jobs.put(job.id, job);
        jobRunner.submit(() -> recompile(job));
        return jobView(job.id);
    }

    private void recompile(Job job) {
        try {
            job.stage = "收集最近变更";
            job.percent = 5;
            List<Map<String, Object>> recent = kbChunkMapper.recentSources(RECENT_SOURCES);
            StringBuilder material = new StringBuilder();
            for (Map<String, Object> r : recent) {
                String type = String.valueOf(r.get("t"));
                material.append('[').append(marker(type)).append('#').append(r.get("id")).append("] 《")
                        .append(String.valueOf(r.get("title"))).append("》\n")
                        .append(sampleSpread(clip(String.valueOf(r.get("content")), 6000), 1200, 2))
                        .append("\n\n");
            }

            job.stage = "影响分析（判断该更新哪些页）";
            job.percent = 20;
            // 传 null = 按分工表里"影响分析"这一项指向的模型档案
            Map<String, Object> impact = entityCompileService.impact(material.toString(), 6, null);
            // 影响分析失败必须**当场失败**：否则 targets 为空，整个重编译会"成功"地什么都不做，
            // 这是最难排查的一类静默失败（曾经真实发生过）。
            Object impactErr = impact.get("error");
            if (impactErr != null && !String.valueOf(impactErr).isBlank()) {
                throw new IllegalStateException("影响分析失败：" + impactErr);
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> targets = (List<Map<String, Object>>) impact.getOrDefault("targets", List.of());
            job.total = Math.max(1, targets.size());
            job.detail = "受影响页面 " + targets.size() + " 个";

            job.stage = "重建受影响的主题页";
            int idx = 0;
            int topicDone = 0;
            for (Map<String, Object> t : targets) {
                idx++;
                String key = String.valueOf(t.getOrDefault("topicKey", ""));
                if (key.startsWith("cat-") || key.startsWith("tag-")) {
                    try {
                        Topic topic = resolve(key);
                        Material m = generationMaterial(topic);
                        if (!m.items().isEmpty()) {
                            Job sub = new Job(UUID.randomUUID().toString().substring(0, 8), key, "recompile");
                            // 复用与"手动生成"完全相同的流水线：生成 → 质量校验 →（必要时）带反馈重生成 → 落库
                            runGenerate(sub, topic, m, target(null));
                            topicDone++;
                            job.detail = "已重建 " + topicDone + " 页 · 最近：" + topic.title();
                        }
                    } catch (Exception e) {
                        log.warn("重编译主题页失败 {}：{}", key, e.toString());
                    }
                }
                job.done = idx;
                job.percent = 30 + (int) (55.0 * idx / Math.max(1, targets.size()));
            }

            job.stage = "重编译知识页（实体/索引）";
            job.percent = 92;
            Map<String, Object> ent = entityCompileService.start(null);
            // 受影响的页面可能全是实体页 —— 它们由"知识页整批重编"覆盖，所以主题页 0 页是正常结果，
            // 但也确实可能是"模型认为没有页面相关"。两种要说清楚，不能都写成"主题页 0 页已更新"。
            String head = targets.isEmpty()
                    ? "影响分析认为没有页面需要更新"
                    : "受影响 " + targets.size() + " 页，已重建主题页 " + topicDone + " 页";
            job.detail = head + "；知识页任务 " + ent.get("jobId");
            job.percent = 100;
            job.status = "done";
            job.finishedAt = System.currentTimeMillis();
            log.info("局部重编译完成：受影响 {} 页，重生主题页 {} 页，知识页任务 {}", targets.size(), topicDone, ent.get("jobId"));
        } catch (Exception e) {
            log.warn("局部重编译失败：{}", e.toString());
            job.status = "failed";
            job.error = e.getMessage() == null ? e.toString() : e.getMessage();
            job.stage = "失败";
            job.finishedAt = System.currentTimeMillis();
        }
    }

    private static String marker(String type) {
        return switch (type == null ? "" : type) {
            case "file" -> "资料";
            case "quick_ref" -> "速查卡";
            default -> "笔记";
        };
    }

    /** 读上次选择的生成目标（没有就返回空，由 target() 决定默认） */
    private String targetSetting() {
        AppSetting s = settingMapper.selectById(KEY_WIKI_TARGET);
        return s == null || s.getSettingValue() == null ? "" : s.getSettingValue();
    }

    /** 记住选择：自动增量更新也用它，避免"手动用本地、后台偷偷烧云端 token" */
    public void selectTarget(String id) {
        settingMapper.deleteById(KEY_WIKI_TARGET);
        AppSetting s = new AppSetting();
        s.setSettingKey(KEY_WIKI_TARGET);
        s.setSettingValue(id);
        settingMapper.insert(s);
    }

    /** 模型选项（给界面下拉） */
    public Map<String, Object> modelOptions() {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("options", targets().stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id());
            m.put("label", t.label());
            m.put("model", t.model());
            m.put("separate", t.separate());
            return m;
        }).toList());
        o.put("selected", target(null).id());
        return o;
    }

    /**
     * 启动一次生成（异步）并立刻返回任务，界面轮询 {@link #jobView} 看进度。
     * <p>
     * 为什么从"同步等待"改成"异步 + 轮询"：本地小模型出一页要 20~35 秒（大主题更久），
     * 同步期间界面除了转圈什么都做不了；异步后能显示**真实阶段**（调用模型/校验/写入）
     * 与**真实进度**（流式累计的生成字数），失败也能看到具体原因。
     */
    public Map<String, Object> startGenerate(String topicKey, String targetId) {
        Topic topic = resolve(topicKey);
        if ("entity".equals(topic.type())) return entityCompileService.startRegenerate(topic.key(), targetId);
        if ("index".equals(topic.type()) || "lint".equals(topic.type())) {
            throw new IllegalArgumentException("索引和自检页请使用对应的编译或自检入口");
        }
        Material m = generationMaterial(topic);
        if (m.items().isEmpty()) {
            throw new IllegalStateException("该主题下还没有笔记或速查卡，先写点东西再来生成。");
        }
        Target t = target(targetId);
        if (t.apiKey() == null || t.apiKey().isBlank()) {
            // 主模型必须配 Key；本地目标允许空（Ollama 不需要）
            if (!t.separate()) {
                throw new IllegalStateException("AI 未配置：请先在「设置 → 外观与 AI」里填 API Key。");
            }
        }
        if (targetId != null && !targetId.isBlank()) {
            selectTarget(t.id());
        }
        Job job = new Job(UUID.randomUUID().toString().substring(0, 8), topicKey, t.id());
        job.model = t.model();
        jobs.put(job.id, job);
        jobRunner.submit(() -> runGenerate(job, topic, m, t));
        return jobView(job.id);
    }

    public Map<String, Object> jobView(String jobId) {
        Job j = jobs.get(jobId);
        if (j == null) {
            // 重编译会派生一个"知识页"任务，它登记在实体编译器自己的任务表里。
            // 统一在这里回落，前端就只需要轮询这一个端点，也不会再出现 500。
            Map<String, Object> sub = entityCompileService.viewOrNull(jobId);
            if (sub != null) {
                return sub;
            }
        }
        if (j == null) {
            throw new IllegalArgumentException("任务不存在或已过期: " + jobId);
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("jobId", j.id);
        o.put("topicKey", j.topicKey);
        o.put("targetId", j.targetId);
        o.put("model", j.model);
        o.put("stage", j.stage);
        o.put("percent", j.percent);
        o.put("chars", j.chars);
        o.put("total", j.total);
        o.put("done", j.done);
        o.put("detail", j.detail);
        o.put("status", j.status);
        o.put("quality", j.quality);
        o.put("error", j.error);
        o.put("elapsedMs", (j.finishedAt == 0 ? System.currentTimeMillis() : j.finishedAt) - j.startedAt);
        return o;
    }

    /** 真正干活的流水线：调用（流式，带进度）→ 质量校验 → 必要时带反馈重生成 → 落库 */
    private void runGenerate(Job job, Topic topic, Material m, Target t) {
        try {
            job.stage = "调用模型";
            job.percent = 8;
            int expected = Math.max(600, Math.min(3000, totalMaterialChars(m) / 8));
            String body = callModel(topic, m, t, null, chars -> {
                job.chars = chars;
                job.percent = Math.min(90, 8 + (int) (80.0 * chars / expected));
            });

            job.stage = "校验内容";
            job.percent = 92;
            Set<String> validIds = new LinkedHashSet<>();
            for (Item it : m.items()) {
                validIds.add(it.type() + "-" + it.id());
            }
            WikiQuality.Result q = WikiQuality.check(body, validIds);
            if (!q.ok()) {
                // 一次带反馈的重生成：把具体问题告诉模型，比"请重写"有效得多
                log.info("wiki 质量校验未通过（{}），带反馈重生成一次", q.summary());
                job.stage = "校验未通过，重生成";
                job.percent = 94;
                job.chars = 0;
                String retry = callModel(topic, m, t, WikiQuality.retryInstruction(q.hardIssues()), chars -> {
                    job.chars = chars;
                    job.percent = Math.min(98, 94 + (int) (3.0 * chars / expected));
                });
                WikiQuality.Result q2 = WikiQuality.check(retry, validIds);
                // 两次都不合格时留"问题更少"的那份
                if (q2.hardIssues().size() <= q.hardIssues().size()) {
                    q = q2;
                }
            }

            job.stage = "写入";
            job.percent = 99;
            savePage(topic, m, t, q);
            job.quality = q.ok() ? "ok" : "warn";
            job.percent = 100;
            job.status = "done";
            job.finishedAt = System.currentTimeMillis();
        } catch (Exception e) {
            log.warn("wiki 生成失败：{} - {}", topic.title(), e.toString());
            job.status = "failed";
            job.error = e.getMessage() == null ? e.toString() : e.getMessage();
            job.stage = "失败";
            job.finishedAt = System.currentTimeMillis();
        }
    }

    private void savePage(Topic topic, Material m, Target t, WikiQuality.Result q) {
        // 编译产物页（实体/索引/自检）必须用它**自己的 topicKey**。
        // 这里曾经只有 "tag -> tag-<id>，其余 -> cat-<id>" 两个分支，
        // 于是对 entity 主题（id 恒为 0）会拼出 "cat-0" —— 单页重编实体页时，
        // 结果被写进一个历史垃圾键，页面上看不到任何变化，还凭空多出一个同名入口。
        // 实测证据：cat-0 的 topic_type=entity、title=@GetMapping、
        // generated_at 恰好等于一次 entity-51a81e9bc1 的重编时刻。
        String key = switch (topic.type() == null ? "" : topic.type()) {
            case "tag" -> "tag-" + topic.id();
            case "entity", "index", "lint" -> topic.key();
            default -> "cat-" + topic.id();
        };
        WikiPage page = byKey(key);
        boolean create = page == null;
        if (create) {
            page = new WikiPage();
            page.setTopicKey(key);
        }
        page.setTopicType(topic.type());
        page.setTopicId(topic.id());
        page.setTitle(topic.title());
        page.setContentMd(q.content());
        page.setSourceHash(m.hash());
        page.setItemCount(m.total());
        page.setModel(t.model());
        page.setQuality(q.ok() ? "ok" : "warn");
        page.setQualityNote(q.summary().isEmpty() ? null : clip(q.summary(), 250));
        page.setTargetId(t.id());
        page.setGeneratedAt(LocalDateTime.now());
        dependencyService.saveGenerated(page, m.snapshot());
        log.info("wiki 已生成：{}（模型 {}，素材 {}/{} 条，正文 {} 字，质量 {}）",
                key, t.model(), m.items().size(), m.total(), q.content().length(), page.getQuality());
    }

    private static int totalMaterialChars(Material m) {
        int n = 0;
        for (Item it : m.items()) {
            n += it.snippet() == null ? 0 : it.snippet().length();
        }
        return n;
    }

    /** 自动增量更新走同一条流水线（不跟踪任务，只记日志） */
    private void generateQuietly(String topicKey) {
        try {
            Topic topic = resolve(topicKey);
            if ("entity".equals(topic.type())) {
                entityCompileService.startRegenerate(topic.key(), null);
                return;
            }
            Material m = generationMaterial(topic);
            if (m.items().isEmpty()) {
                return;
            }
            Target t = target(null);
            if ((t.apiKey() == null || t.apiKey().isBlank()) && !t.separate()) {
                return;
            }
            Job job = new Job(UUID.randomUUID().toString().substring(0, 8), topicKey, t.id());
            job.model = t.model();
            jobs.put(job.id, job);
            jobRunner.submit(() -> runGenerate(job, topic, m, t));
        } catch (Exception e) {
            log.warn("wiki 自动更新排程失败：{} - {}", topicKey, e.toString());
        }
    }

    private static final String WIKI_SYSTEM = """
            你是个人 IT 知识库的编辑，负责把某个主题下零散的笔记与速查卡整理成一页结构化 wiki。

            写作要求：
            1. 只依据给定素材，**不得引入素材之外的任何技术事实**；素材没写到的宁可留白，不要凭常识补全；
            2. 结构：开头一段 2~3 句概览 → 用 ## 分节（按知识点重新聚类，不要照抄原文顺序）→ 每节先结论后依据；
            3. 每条具体事实后面标注来源，写成 [笔记#3] / [速查卡#5] / [资料#8]（前端会渲染成可点击的跳转）；
            4. 重复内容合并；素材之间如有冲突，把两种说法都写出来并各自标注来源；
            5. 末尾可选加一节「## 待补充」，只列素材里明显缺失、但该主题本该有的要点（没有就省略整节）；
            6. 篇幅与素材匹配，不要为凑长度复述；
            7. 只输出 wiki 正文（Markdown），不要前言、结语，也不要"以下是整理结果"之类的话。
            """;

    /**
     * 调模型生成正文（**流式**）。
     * <p>
     * 流式的两个理由：① 界面要能显示真实进度（累计生成字数），而不是干等；② 长输出不易撞整体超时。
     * 独立目标（本地/自建）会关掉 DeepSeek 专有的 thinking / reasoning_effort 参数 ——
     * 别的 OpenAI 兼容服务不认识这些字段。
     *
     * @param extraInstruction 重生成时追加的纠正指令（可为 null）
     * @param onChars          累计生成字数的回调（可为 null）
     */
    private String callModel(Topic topic, Material m, Target t, String extraInstruction,
                             java.util.function.IntConsumer onChars) {
        StringBuilder sb = new StringBuilder();
        sb.append("主题：").append(topic.title())
          .append("\n素材（共 ").append(m.total()).append(" 条");
        if (m.items().size() < m.total()) {
            // 必须告诉模型"素材被截断了"：否则它会把"最近 40 条里没有"误当成"知识库里没有"，
            // 在「待补充」里写一堆其实已经记过的东西
            sb.append("，以下为最近 ").append(m.items().size())
              .append(" 条，其余因篇幅未提供，请勿臆测其内容");
        }
        sb.append("）：\n");
        if (m.prompt() != null) {
            sb.append(m.prompt());
        } else for (Item it : m.items()) {
            String marker = switch (it.type()) {
                case "note" -> "[笔记#";
                case "file" -> "[资料#";
                default -> "[速查卡#";
            };
            sb.append('\n').append(marker).append(it.id()).append("] 《")
              .append(it.title()).append('》');
            if (StringUtils.hasText(it.category())) {
                sb.append("｜分类 ").append(it.category());
            }
            sb.append('\n').append(it.snippet() == null ? "（无摘要）" : it.snippet()).append('\n');
            // 如实告知"这条只给了片段"：否则模型会把"素材里没写"当成"文档里没有"
            Integer src = it.totalChars();
            if (src != null && src > it.usedChars()) {
                sb.append("（该素材源文档共约 ").append(src).append(" 字，此处仅提供 ")
                  .append(it.usedChars()).append(" 字采样，未提供的部分不要臆测）\n");
            }
        }
        // Schema 层：优先用 skills/wiki-schema/SKILL.md（文件/面板可编辑），缺失时回退内置常量 ——
        // 对应那份原理里的 Schema 层：改 wiki 的性格不用改代码、不用重启。
        String base = schemaText();
        String system = extraInstruction == null ? base : base + extraInstruction;
        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", sb.toString()));
        try {
            long started = System.currentTimeMillis();
            String out = client.chatStream(messages, t.baseUrl(), t.apiKey(), t.model(),
                    client.maxTokensOf(t.id()), 0.3,
                    t.separate() ? "disabled" : client.thinkingOf(t.id()),
                    t.separate() ? null : client.reasoningEffortOf(t.id()),
                    WIKI_TIMEOUT, onChars);
            out = out == null ? "" : out.trim();
            log.info("wiki 调用完成：{}（模型 {}，{} 字，耗时 {} ms）",
                    topic.title(), t.model(), out.length(), System.currentTimeMillis() - started);
            if (out.isEmpty()) {
                throw new IllegalStateException("模型没有返回内容");
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("AI 调用失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 3. 智能体检索注入（把 wiki 也当作召回内容）
    // ------------------------------------------------------------------

    /**
     * 用与本项目其余检索**同一套词面打分**（复用 {@link AgentService#retrievalTerms} 与
     * {@link AgentService#score}），在 wiki 正文里找相关的页，截一段注入对话上下文。
     * <p>
     * 之所以复用而不是各写一份：两处排序口径不一致时，会出现"笔记召回了、wiki 没召回"
     * 这类难以解释的现象。评分不足（{@link #RAG_MIN_SCORE}）就**不注入** ——
     * 塞一段不相关的内容比不塞更糟，模型会硬往上靠。
     *
     * @return 注入文本；没有相关页时返回 null（调用方据此决定要不要拼进消息）
     */
    public String retrievalBlock(String message) {
        return retrievalBlock(message, Integer.MAX_VALUE);
    }

    /**
     * 同上，但带**上下文预算**：超过 maxChars 就截断并标记。
     *
     * <p>为什么需要预算：检索现在有四路（词面 / 语义 / wiki / 概念图谱），
     * 每路都有自己的上限，加起来没有封顶 —— 四路全命中时能塞进上万字，把回答空间挤掉。
     * 调用方按"剩余预算"依次注入，谁排前面谁先占。
     */
    public String retrievalBlock(String message, int maxChars) {
        if (maxChars <= 0) {
            return null;
        }
        List<String> terms = AgentService.retrievalTerms(message);
        if (terms.isEmpty()) {
            return null;
        }
        List<WikiPage> pages;
        try {
            pages = mapper.selectList(Wrappers.<WikiPage>lambdaQuery().isNotNull(WikiPage::getContentMd));
        } catch (Exception e) {
            log.warn("读取 wiki 失败（跳过注入）：{}", e.getMessage());
            return null;
        }
        record Scored(WikiPage page, int score) {
        }
        List<Scored> hits = new ArrayList<>();
        int totalPages = 0;
        for (WikiPage p : pages) {
            if (!StringUtils.hasText(p.getContentMd())) {
                continue;
            }
            totalPages++;
            int s = AgentService.score(terms, p.getTitle(), p.getContentMd());
            if (s >= RAG_MIN_SCORE) {
                hits.add(new Scored(p, s));
            }
        }
        if (hits.isEmpty()) {
            return null;
        }
        // 排序：**标题"具体度"优先**（明确主题词 > 通用词）→ 分数 → 短的在前。
        // 为什么不能用布尔命中：问句切词后的通用 2-gram（"知识"）能同时命中《知识索引》
        // 《知识自检》的标题，与明确主题词（MQTT）落进同一档，通用页于是靠总分把主题页
        // 挤出注入 —— 实测 RAG_MAX_PAGES=2 时 MQTT 页完全不注入。
        // 为什么仍需"标题优先"：长页靠词频就能压过对症的短页，实测问"关于 MQTT，我的知识库里
        // 还缺哪些关键点？"，注入的是 AI Agent / Docker 两个长页（各 7 分），MQTT 页根本没进去，
        // 模型于是声称"索引里没有 MQTT 页"——其实有（517 字、还带着待补充清单）。
        // 排序失当会直接制造幻觉，这已经不是排序好不好看的问题。
        hits.sort(Comparator
                .comparingInt((Scored h) -> -titleScore(terms, h.page().getTitle()))
                .thenComparing(Comparator.comparingInt(Scored::score).reversed())
                .thenComparingInt(h -> h.page().getContentMd().length()));

        List<String> entries = new ArrayList<>();
        int used = 0;
        for (Scored h : hits) {
            if (entries.size() >= RAG_MAX_PAGES) {
                break;
            }
            String entry = indexEntry(h.page());
            // 预算不够就停：宁可少注入一页，也不要挤掉后面的证据（表头约 200 字，先扣掉）
            if (used + entry.length() + 200 > maxChars) {
                break;
            }
            entries.add(entry);
            used += entry.length();
        }
        if (entries.isEmpty()) {
            return null;   // 一页都放不下 = 预算已耗尽，不注入空块
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【知识库 wiki 索引】同一主题的记录已被整理成页面 —— 这里**只给目录与缺口**，")
          .append("正文请用 get_note / get_file 取原文，不要把这当成素材内容。")
          .append("下面「待补充」只说明**该页生成时素材没写到、或没被采样覆盖**，不等于全库没有：")
          .append("遇到这些点先用 get_note / get_file / 知识索引页核对原文，核对后确实找不到才说「全库未找到」，")
          .append("原文里有而该页没展开则说「该页未展开」。");
        // **必须给分母**：只列命中的前几页，不等于知识库只有这几页。少了这句，模型会把
        // 局部目录当全集（实测就这么编出过"没有 MQTT 页"）。
        sb.append("本次命中的 ").append(entries.size()).append(" 页（知识库共 ").append(totalPages)
          .append(" 页）；**没列出的主题不代表知识库里没有**，完整清单见知识库页。\n");
        entries.forEach(sb::append);
        return sb.toString();
    }

    /**
     * 标题命中的"具体度"。
     *
     * <p>布尔命中不够用：问句里的通用 2-gram（"知识"）能命中《知识索引》《知识自检》的标题，
     * 与明确主题词（MQTT / Git / JVM）被算成同一档，通用页于是靠总分把主题页挤出注入
     * （实测 RAG_MAX_PAGES=2 时 MQTT 页完全不注入）。ASCII 术语是问题里的明确主题，权重最高；
     * 中文 gram 给 1（2 字）/10（3 字及以上）分。阈值是启发式的，可按实测调整。
     */
    private static int titleScore(List<String> terms, String title) {
        String t = title == null ? "" : title.toLowerCase();
        int s = 0;
        for (String term : terms) {
            String w = term.toLowerCase();
            if (w.length() < 2 || !t.contains(w)) {
                continue;
            }
            s += w.charAt(0) < 128 ? 100 : (w.length() >= 3 ? 10 : 1);
        }
        return s;
    }

    /** 问题里的检索词有没有出现在页标题上（保留布尔口径，供探针等处使用） */
    private static boolean titleHit(List<String> terms, String title) {
        return titleScore(terms, title) > 0;
    }

    /**
     * 把一页 wiki 压成"目录 + 缺口"条目（约 100~200 字）。
     */

    /**
     * 注入探针：返回"这个问题会注入什么 wiki 块"（不调模型）。
     *
     * <p>存在的理由与图谱的 {@code /api/kg/concept/recognize} 一样：注入是"看不见的输入"，
     * 没有探针就只能靠跑一次对话反推 —— 而判断"目录 + 缺口"这类改动值不值，
     * 第一步是能直接看到块内容。
     *
     * @return {question, block, chars, hits:[{title, score, quality, itemCount, gaps}]}
     */
    public Map<String, Object> probe(String question, int maxChars) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("question", question);
        String block = retrievalBlock(question, maxChars);
        out.put("block", block);
        out.put("chars", block == null ? 0 : block.length());
        List<String> terms = AgentService.retrievalTerms(question);
        List<Map<String, Object>> hits = new ArrayList<>();
        int totalPages = 0;
        for (WikiPage p : mapper.selectList(Wrappers.<WikiPage>lambdaQuery().isNotNull(WikiPage::getContentMd))) {
            if (!StringUtils.hasText(p.getContentMd())) {
                continue;
            }
            totalPages++;
            int s = AgentService.score(terms, p.getTitle(), p.getContentMd());
            if (s >= RAG_MIN_SCORE) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("title", p.getTitle());
                m.put("score", s);
                m.put("titleHit", titleHit(terms, p.getTitle()));
                m.put("quality", p.getQuality());
                m.put("itemCount", p.getItemCount());
                m.put("gaps", gapItems(p.getContentMd()));
                hits.add(m);
            }
        }
        // 与真实注入**同一套排序**，否则探针显示的"会注入什么"和行为不一致
        hits.sort(Comparator
                .comparingInt((Map<String, Object> m) -> -titleScore(terms, String.valueOf(m.get("title"))))
                .thenComparing(m -> -(int) m.get("score")));
        out.put("totalPages", totalPages);
        out.put("hits", hits);
        return out;
    }

    /** 小节标题（## / ### / ####） */
    private static final java.util.regex.Pattern SECTION_HEAD =
            java.util.regex.Pattern.compile("(?m)^#{2,4}\\s+(.+?)\\s*$");

    /**
     * 把一页 wiki 压成"目录 + 缺口"条目（约 100~200 字）。
     *
     * <h3>为什么不注入正文摘要</h3>
     * 2026-09-29 的 A/B 实测：注入整页摘要（→ 后来改的"最相关一段"）对答案关键短语命中**没有影响**
     * （关 46/48 vs 开 45/48，逐题 10 题无差异）。机制上说得通 —— 页均只聚合 **1.9 条来源**，
     * 内容与同一轮召回的原文 chunk 高度重复，等于"同一份信息的第三种写法"。
     *
     * <h3>为什么"目录 + 缺口"才可能有增量</h3>
     * 这两样东西**chunk 里没有、模型自己也不可能知道**：
     * <ul>
     *   <li><b>目录</b>：这个主题由哪几条记录构成、分了哪几个小节（"我库里有什么"）；</li>
     *   <li><b>缺口</b>：{@code ## 待补充} 里列的、素材**明确没记**的点（"我库里没什么"）。
     *       有了它，模型才能把"你记的"与"我知道的"分开说 —— 这是 wiki 相对三路文本检索唯一的增量。</li>
     * </ul>
     */
    private String indexEntry(WikiPage p) {
        String body = p.getContentMd() == null ? "" : p.getContentMd();
        StringBuilder sb = new StringBuilder();
        sb.append("\n· ").append(p.getTitle())
          .append("（来源 ").append(p.getItemCount() == null ? 1 : p.getItemCount()).append(" 条");
        if (StringUtils.hasText(p.getQuality())) {
            sb.append("；质量 ").append(p.getQuality());
        }
        sb.append("）\n");
        List<String> sections = new ArrayList<>();
        java.util.regex.Matcher m = SECTION_HEAD.matcher(body);
        while (m.find()) {
            String t = m.group(1).trim();
            if (!"待补充".equals(t)) {
                sections.add(t);
            }
        }
        if (!sections.isEmpty()) {
            int max = Math.min(sections.size(), RAG_SECTIONS);
            sb.append("  小节：").append(String.join(" / ", sections.subList(0, max)))
              .append(sections.size() > max ? " …" : "").append('\n');
        }
        List<String> gaps = gapItems(body);
        if (!gaps.isEmpty()) {
            List<String> open = new ArrayList<>();
            List<String> sampled = new ArrayList<>();
            for (String g : gaps) {
                (isSamplingGap(g) ? sampled : open).add(g);
            }
            if (!open.isEmpty()) {
                sb.append("  待补充（该页未展开，原文里可能有）：").append(String.join("；", open)).append('\n');
            }
            if (!sampled.isEmpty()) {
                sb.append("  采样未覆盖（原文里可能有）：").append(String.join("；", sampled)).append('\n');
            }
        }
        return sb.toString();
    }

    /** 缺口文本是否自述"采样/截断"造成的（模型在待补充里会写"本次仅提供…字采样"） */
    private static boolean isSamplingGap(String g) {
        return g.contains("采样") || g.contains("未提供") || g.contains("截断") || g.contains("篇幅");
    }

    /** 取 {@code ## 待补充} 一节里的条目（`-` 开头的行），最多 {@link #RAG_GAP_ITEMS} 条 */
    private static List<String> gapItems(String body) {
        java.util.regex.Matcher m = SECTION_HEAD.matcher(body);
        while (m.find()) {
            String title = m.group(1).trim();
            if (!title.startsWith("待补充")) {
                continue;
            }
            // 从标题行**之后**开始，到下一个标题为止（之前用固定 +5 位移，把标题最后一个字
            // 当成了第一条缺口：实测输出过「充；QoS 的具体等级…」）
            String tail = body.substring(m.end());
            java.util.regex.Matcher next = SECTION_HEAD.matcher(tail);
            if (next.find()) {
                tail = tail.substring(0, next.start());
            }
            List<String> out = new ArrayList<>();
            for (String line : tail.split("\\R")) {
                String t = line.trim().replaceFirst("^[-*+]\\s*", "").trim();
                if (t.isEmpty()) {
                    continue;
                }
                if (t.length() > RAG_GAP_ITEM_CHARS) {
                    t = t.substring(0, RAG_GAP_ITEM_CHARS) + "…";
                }
                out.add(t);
                if (out.size() >= RAG_GAP_ITEMS) {
                    break;
                }
            }
            return out;
        }
        return List.of();
    }

    // ------------------------------------------------------------------
    // 4. 自动增量更新
    // ------------------------------------------------------------------

    /** 开关（默认开）。写在 app_setting 里，键为 {@link #SETTING_AUTO_REFRESH} */
    public boolean autoRefreshEnabled() {
        AppSetting s = settingMapper.selectById(SETTING_AUTO_REFRESH);
        return s == null || !"0".equals(s.getSettingValue());
    }

    public void setAutoRefresh(boolean on) {
        AppSetting s = settingMapper.selectById(SETTING_AUTO_REFRESH);
        if (s == null) {
            s = new AppSetting();
            s.setSettingKey(SETTING_AUTO_REFRESH);
            s.setSettingValue(on ? "1" : "0");
            settingMapper.insert(s);
        } else {
            s.setSettingValue(on ? "1" : "0");
            settingMapper.updateById(s);
        }
    }

    /**
     * 内容变了 → 把受影响主题排进后台队列。
     * <p>
     * <b>必须等事务提交</b>（{@code AFTER_COMMIT}）：写入是 {@code @Transactional} 的，
     * 事件在事务内发布、监听器也同步执行，而后台线程走的是**另一条连接** ——
     * 提交前它读不到这条新笔记。实测踩到过：新增笔记后重生成用的是旧快照（4 条），
     * 存进去的指纹与提交后的实际素材永远对不上，页面就永久停在「待更新」。
     * <p>
     * {@code fallbackExecution = true}：万一将来有非事务路径发这个事件，也照常处理，不要静默漏掉。
     * <p>
     * 只处理**已经生成过**的主题：没生成过的主题自动生成属于"用户没要求就花钱"，
     * 那条路径必须由用户自己在界面上点。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onKnowledgeChanged(KnowledgeChangedEvent event) {
        if (!autoRefreshEnabled()) {
            return;
        }
        for (Long cid : event.categoryIds()) {
            if (cid != null) {
                scheduleRefresh("cat-" + cid);
            }
        }
    }

    private void scheduleRefresh(String topicKey) {
        WikiPage existing = byKey(topicKey);
        if (existing == null || !StringUtils.hasText(existing.getContentMd())) {
            return; // 没生成过的主题不自动生成：那是"用户没要求就花钱"
        }
        dirty.add(topicKey);
        // 延迟合并：连续编辑只跑一趟。这里只是"安排"，真正跑的时候还会再比一次指纹，
        // 所以即便这一趟被后来的事件挤掉，脏标记仍在、下一趟还会处理它。
        if (workerQueued.compareAndSet(false, true)) {
            scheduler.schedule(this::drainDirty, DEBOUNCE_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    /** 后台跑一遍所有脏主题：逐个比指纹，变了才重生成（串行执行，不会并发撞限流） */
    private void drainDirty() {
        workerQueued.set(false);
        for (String topicKey : new ArrayList<>(dirty)) {
            if (!dirty.remove(topicKey)) {
                continue;
            }
            try {
                WikiPage existing = byKey(topicKey);
                if (existing == null || !StringUtils.hasText(existing.getContentMd())) {
                    continue;
                }
                Material now = material(resolve(topicKey));
                if (now.hash().equals(existing.getSourceHash()) && !trackedStale(dependencyState(existing))) {
                    log.debug("wiki 素材未变化，跳过重生成：{}", topicKey);
                    continue;
                }
                generateQuietly(topicKey);
                log.info("wiki 自动更新完成：{}", topicKey);
            } catch (Exception e) {
                log.warn("wiki 自动更新失败（{}）：{}", topicKey, e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------
    // 5. 素材与主题
    // ------------------------------------------------------------------

    private record Topic(String key, String type, Long id, String title) {
    }

    private record Item(String type, Long id, String title, String category, String snippet,
                        LocalDateTime updatedAt, Integer totalChars) {
        /** 素材里这一条实际用了多少字（算覆盖率用） */
        int usedChars() {
            return snippet == null ? 0 : snippet.length();
        }
    }

    private record Material(List<Item> items, int total, String hash, String prompt,
                            WikiDependencyService.Snapshot snapshot, List<Item> candidates) {
        Material(List<Item> items, int total, String hash) {
            this(items, total, hash, null, null, items);
        }
    }

    /** Capture before calling the model; the saved dependencies must describe exactly this material. */
    private Material generationMaterial(Topic topic) {
        Material preview = material(topic);
        List<WikiDependencyService.SourceRef> refs = preview.candidates().stream()
                .map(i -> new WikiDependencyService.SourceRef("ref".equals(i.type()) ? "quick_ref" : i.type(), i.id()))
                .toList();
        var captured = dependencyService.captureSnapshot(refs);
        var sources = captured.sources().stream().map(s -> new WikiMaterialSampler.Source(
                s.ref().type(), s.ref().id(), s.title(), s.fullContent())).toList();
        var sampled = WikiMaterialSampler.sample("", List.of(), sources, MAX_MATERIAL_CHARS);
        if (sampled.chunks().isEmpty()) throw new IllegalStateException("该主题暂无可读取的原文片段，未覆盖已有知识页。");
        var snapshot = WikiDependencyService.selectChunks(captured, sampled.chunks().stream()
                .map(c -> new WikiDependencyService.ChunkRef(c.sourceType(), c.sourceId(), c.seq())).toList());
        Map<String, Item> metadata = new LinkedHashMap<>();
        for (Item it : preview.candidates()) metadata.put(("ref".equals(it.type()) ? "quick_ref" : it.type()) + ":" + it.id(), it);
        List<Item> actual = new ArrayList<>();
        for (var source : snapshot.sources()) {
            Item original = metadata.get(source.ref().key());
            String text = source.chunks().stream().map(WikiDependencyService.SourceChunk::text)
                    .collect(java.util.stream.Collectors.joining("\n"));
            actual.add(new Item("quick_ref".equals(source.ref().type()) ? "ref" : source.ref().type(),
                    source.ref().id(), source.title(), original == null ? "" : original.category(), text,
                    original == null ? null : original.updatedAt(), source.fullContent().length()));
        }
        return new Material(List.copyOf(actual), preview.total(), preview.hash(), sampled.prompt(), snapshot, preview.candidates());
    }

    private Topic resolve(String topicKey) {
        String key = topicKey == null ? "" : topicKey.trim();
        if (key.startsWith("cat-")) {
            Long id = parseId(key);
            Category c = categoryService.getById(id);
            return new Topic(key, "category", id, c == null ? "分类#" + id : c.getName());
        }
        if (key.startsWith("tag-")) {
            Long id = parseId(key);
            String name = tagService.list().stream().filter(t -> t.getId().equals(id))
                    .map(Tag::getName).findFirst().orElse("标签#" + id);
            return new Topic(key, "tag", id, name);
        }
        // 编译产物页（索引 / 实体 / 自检报告）：内容都在 wiki_page 里，和分类页不同，没有"素材"。
        // lint 页曾经漏在这里 —— 它会被 topics() 列出来，但点开就报"未知主题：lint"。
        if (key.startsWith("entity-") || "index".equals(key) || "lint".equals(key)) {
            WikiPage page = byKey(key);
            if (page == null) {
                throw new IllegalArgumentException("知识页不存在：" + key);
            }
            return new Topic(key, page.getTopicType(), 0L, page.getTitle());
        }
        throw new IllegalArgumentException("未知主题：" + topicKey + "（应为 cat-<id> / tag-<id> / entity-<hash> / index / lint）");
    }

    private Long parseId(String key) {
        try {
            return Long.parseLong(key.substring(key.indexOf('-') + 1));
        } catch (Exception e) {
            throw new IllegalArgumentException("主题标识不合法：" + key);
        }
    }

    /**
     * 收集某主题的素材。
     * <p>
     * 这里刻意做两件事的**分离**：
     * <ol>
     *   <li><b>指纹覆盖全量</b>（只取 id + 更新时间，很轻）：否则"没被送进模型的那几十条"改了，
     *       这一页不会被判定为过期，用户看不到「待更新」。</li>
     *   <li><b>送模型的素材有上限</b>（条数与总字数双限）：prompt 不能随知识库无限膨胀。
     *       按更新时间倒序取，也就是"优先给最近的"。</li>
     * </ol>
     */
    private Material material(Topic topic) {
        List<Item> all = new ArrayList<>();
        List<NoteVO> notes;
        List<QuickRefVO> refs;
        List<Map<String, Object>> files = List.of();
        if ("tag".equals(topic.type())) {
            notes = noteService.page(null, topic.id(), null, 1, MAX_FETCH).getList();
            refs = List.of(); // 速查卡没有标签，标签主题只含笔记
        } else {
            notes = noteService.page(topic.id(), null, null, 1, MAX_FETCH).getList();
            refs = quickRefService.list(topic.id(), null);
            // 资料按分类归属（资料没有标签，所以标签主题不含资料）
            files = fileStorageService.retrievalScan(MAX_FILES_FOR_WIKI).stream()
                    .filter(f -> topic.id().equals(asLong(f.get("categoryId"))))
                    .toList();
        }
        // 笔记正文长度 + 跨段采样：只为算覆盖率与给长笔记更多上下文，取不到就退化为摘要
        Map<Long, Integer> noteChars = new LinkedHashMap<>();
        Map<Long, String> noteSample = new LinkedHashMap<>();
        try {
            for (Map<String, Object> r : kgMapper.noteCharLengths()) {
                noteChars.put(((Number) r.get("id")).longValue(), ((Number) r.get("chars")).intValue());
            }
            for (Map<String, Object> r : kgMapper.noteSamples()) {
                noteSample.put(((Number) r.get("id")).longValue(), String.valueOf(r.get("sample")));
            }
        } catch (Exception e) {
            log.warn("读取笔记长度/采样失败（覆盖率将退化为摘要口径）：{}", e.toString());
        }
        for (NoteVO n : notes) {
            String summary = clip(n.getSummary(), SNIPPET_CHARS);
            // 长笔记：摘要之外再带上头/中/尾三段采样，否则 5.9 万字的笔记等于只进了 255 字
            String sample = noteSample.get(n.getId());
            String body = sample == null ? summary
                    : (summary == null ? "" : summary + "\n") + clip(sample, NOTE_SAMPLE_CHARS);
            Integer total = noteChars.get(n.getId());
            all.add(new Item("note", n.getId(), n.getTitle(), n.getCategoryName(),
                    clip(body, SNIPPET_CHARS + NOTE_SAMPLE_CHARS), n.getUpdatedAt(), total));
        }
        for (QuickRefVO r : refs) {
            String content = r.getContent() == null ? "" : r.getContent();
            all.add(new Item("ref", r.getId(), r.getTitle(), r.getCategoryName(),
                    clip(content, SNIPPET_CHARS), r.getUpdatedAt(), content.length()));
        }
        for (Map<String, Object> f : files) {
            // 资料没有 updated_at，用 created_at 参与排序与指纹
            String name = String.valueOf(f.getOrDefault("originName", ""));
            String summary = String.valueOf(f.getOrDefault("summary", ""));
            String text = String.valueOf(f.getOrDefault("text", ""));
            // 长文档**跨段采样**而不是只取开头：只取开头时 11.8 万字的书只有第 1 章能进素材，
            // 均匀取几段至少能覆盖全书脉络（仍是采样，报告里会如实标出覆盖率）。
            String sampled = text.length() > FILE_SAMPLE_CHARS ? sampleSpread(text, FILE_SAMPLE_CHARS, 4) : text;
            String body = summary.isBlank() ? sampled : summary + "\n" + sampled;
            int total = f.get("textChars") instanceof Number n ? n.intValue() : body.length();
            all.add(new Item("file", asLong(f.get("id")), name,
                    String.valueOf(f.getOrDefault("categoryName", "")),
                    clip(body, FILE_SNIPPET_CHARS), null, total));
        }
        all.sort(Comparator.comparing(Item::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())));

        // 指纹：全量、只取 id + 更新时间。只改标题不改时间戳的极端情况探测不到，
        // 但正常保存都会刷 updated_at，够用且不必把全文读出来算哈希。
        StringBuilder fp = new StringBuilder();
        all.stream()
           .sorted(Comparator.comparing(Item::type).thenComparing(Item::id))
           .forEach(i -> fp.append(i.type()).append(i.id()).append('@').append(i.updatedAt()).append(';'));

        // 送模型的子集：条数 + 字数双限
        List<Item> picked = new ArrayList<>();
        int chars = 0;
        for (Item it : all) {
            int cost = (it.title() == null ? 0 : it.title().length())
                    + (it.snippet() == null ? 0 : it.snippet().length()) + 24;
            if (picked.size() >= MAX_ITEMS || (!picked.isEmpty() && chars + cost > MAX_MATERIAL_CHARS)) {
                break;
            }
            picked.add(it);
            chars += cost;
        }
        return new Material(picked, all.size(), KgService.sha256(fp.toString()), null, null,
                List.copyOf(all.subList(0, Math.min(all.size(), MAX_ITEMS))));
    }

    private WikiPage byKey(String topicKey) {
        return mapper.selectOne(Wrappers.<WikiPage>lambdaQuery().eq(WikiPage::getTopicKey, topicKey));
    }

    /**
     * 长文本**跨段采样**：在全文里均匀取 slices 段，每段前加上位置标注。
     * <p>只取开头时，11.8 万字的书只有第 1 章能进素材（实测覆盖率 1.68%）；
     * 均匀取样后同一预算能覆盖全书脉络 —— 仍是采样，但不再是"只看开头"。
     */
    static String sampleSpread(String text, int budget, int slices) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (text.length() <= budget || slices <= 1) {
            return text;
        }
        int per = Math.max(1, budget / slices);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < slices; i++) {
            int start = (int) ((long) (text.length() - per) * i / (slices - 1));
            start = Math.max(0, Math.min(text.length() - per, start));
            sb.append("【第 ").append(i + 1).append('/').append(slices).append(" 段 · 约第 ")
              .append(start).append(" 字处】\n")
              .append(text, start, Math.min(text.length(), start + per)).append('\n');
        }
        return sb.toString();
    }

    /** 资料行里的 categoryId 可能来自 Map（Number）也可能是 null，统一成一个可比较的 Long */
    private static Long asLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        if (o == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void flattenCats(List<Category> nodes, String prefix, List<Map<String, Object>> out) {        for (Category c : nodes == null ? List.<Category>of() : nodes) {
            String label = prefix.isEmpty() ? c.getName() : prefix + " / " + c.getName();
            out.add(Map.of("id", c.getId(), "label", label));
            flattenCats(c.getChildren(), label, out);
        }
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.replaceAll("[\\s\\u00a0]+", " ").trim();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }
}
