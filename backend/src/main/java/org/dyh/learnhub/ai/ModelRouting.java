package org.dyh.learnhub.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dyh.learnhub.entity.ModelProfile;
import org.dyh.learnhub.service.ModelProfileService;
import org.dyh.learnhub.service.SettingsService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型分工表：**每个任务各自指向一个模型档案**，集中在这里，一处可查、一处可改。
 *
 * <h3>2026-09 的改造：从"两个位置"到"任意多个档案"</h3>
 * 原来目标只有 {@code main}（云端主模型）与 {@code local}（本地/自建）两个 id，
 * 于是想再接一个 Kimi、或者让"自检"用 A、"wiki"用 B，都只能改代码。
 * 现在目标来自 {@link ModelProfileService} 的**档案列表**（可无限新增），
 * 任务与档案是多对一的关系：`ai.model_for_<task>` 存档案 id。
 *
 * <h3>默认值的依据（都是实测，不是拍脑袋）</h3>
 * <table>
 *   <tr><th>任务</th><th>默认</th><th>依据</th></tr>
 *   <tr><td>chat 对话</td><td>当前激活档案</td><td>要工具调用纪律与回答质量；也是你唯一主动等待的环节</td></tr>
 *   <tr><td>wiki 主题页</td><td><b>本地档案（若有）</b></td><td>批量、长输出、摘要性质；本地 20~35 秒/页、0 元，实测够用</td></tr>
 *   <tr><td>entity 实体页编译</td><td>当前激活档案</td><td>实测本地 8B 三次都翻车（不守跨页规则/标签不稳/结果波动）</td></tr>
 *   <tr><td>impact 影响分析</td><td>当前激活档案</td><td>同上是"跨页判断"，小模型最不稳的一步</td></tr>
 *   <tr><td>lint 语义自检</td><td>当前激活档案</td><td>找矛盾/缺口属判断类，小模型容易"看不出问题"</td></tr>
 *   <tr><td>graph 图谱关联</td><td>当前激活档案</td><td>要严格 JSON 与相关性判断</td></tr>
 *   <tr><td>triple 三元组抽取</td><td>当前激活档案</td><td>关系判错就是往图里写假事实</td></tr>
 *   <tr><td>rerank 检索重排</td><td><b>本地档案（若有）</b></td><td>只排一个列表、输入很短，本地够用且免费（每次提问都要跑）</td></tr>
 *   <tr><td>grounding 答案核对</td><td>当前激活档案</td><td>判断"这句话有没有依据"属于判断类</td></tr>
 *   <tr><td>rewrite 检索词扩展</td><td><b>本地档案（若有）</b></td><td>只在词面 0 命中时兜底，便宜、可以慢</td></tr>
 *   <tr><td>embed 向量</td><td>本地（固定）</td><td>必须与索引里的向量同模型，换模型要重建索引，所以不走路由</td></tr>
 * </table>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelRouting {

    public static final String TASK_CHAT = "chat";
    public static final String TASK_WIKI = "wiki";
    public static final String TASK_ENTITY = "entity";
    public static final String TASK_IMPACT = "impact";
    public static final String TASK_LINT = "lint";
    public static final String TASK_GRAPH = "graph";
    public static final String TASK_REWRITE = "rewrite";
    /** 概念三元组抽取（kg_relation）：严格 JSON + 关系判定，错一条就是一条假事实 */
    public static final String TASK_TRIPLE = "triple";
    /** 检索重排：输入很短、每次提问都要跑，适合便宜/本地 */
    public static final String TASK_RERANK = "rerank";
    /** 阅读器里的翻译：机械任务、可慢，默认与批量摘要类一样优先本地档案 */
    public static final String TASK_TRANSLATE = "translate";
    /** 答案级核对：判断回答有没有超出证据 */
    public static final String TASK_GROUNDING = "grounding";

    /** 任务的默认目标类型：MAIN=当前激活档案，LOCAL=优先本地档案（没有就激活档案） */
    private static final String MAIN = "main";
    private static final String LOCAL = "local";

    /** 任务 → （中文名, 默认目标类型, 说明） */
    private static final Map<String, TaskMeta> META = new LinkedHashMap<>();

    public record TaskMeta(String task, String label, String defaultKind, String why) {
    }

    static {
        META.put(TASK_CHAT, new TaskMeta(TASK_CHAT, "对话问答", MAIN,
                "你要等的那个环节：工具调用纪律与回答质量都靠它"));
        META.put(TASK_WIKI, new TaskMeta(TASK_WIKI, "主题 wiki 生成", LOCAL,
                "批量长输出、摘要性质：本地 20~35 秒/页且 0 元，实测够用"));
        META.put(TASK_ENTITY, new TaskMeta(TASK_ENTITY, "实体/概念页编译", MAIN,
                "跨页判断：实测本地 8B 三次都翻车（不守规则/标签不稳/结果波动），云端 54 秒 8 页质量全 ok"));
        META.put(TASK_IMPACT, new TaskMeta(TASK_IMPACT, "影响分析（该更新哪些页）", MAIN,
                "同为跨页判断，是小模型最不稳的一步"));
        META.put(TASK_LINT, new TaskMeta(TASK_LINT, "语义自检（矛盾/缺口）", MAIN,
                "判断类任务，小模型容易“看不出问题”"));
        META.put(TASK_GRAPH, new TaskMeta(TASK_GRAPH, "知识图谱语义关联", MAIN,
                "要严格 JSON 与相关性判断"));
        META.put(TASK_TRIPLE, new TaskMeta(TASK_TRIPLE, "概念三元组抽取", MAIN,
                "要判定两个概念之间到底是哪种关系（属于/前置/易混），判错就是往图里写假事实"));
        META.put(TASK_RERANK, new TaskMeta(TASK_RERANK, "检索结果重排", MAIN,
                "要把候选按「对回答这个问题有多大用处」排序，得读懂问题与候选的关系"
                        + "（实测 97 条用例：本地 qwen3:8b 的 MRR 0.759/0.743≈不重排，deepseek-flash 0.902）"));
        META.put(TASK_GROUNDING, new TaskMeta(TASK_GROUNDING, "答案核对（有没有依据）", MAIN,
                "判断“这句话有没有被材料支撑”，判不准就会误报或漏报"));
        META.put(TASK_TRANSLATE, new TaskMeta(TASK_TRANSLATE, "阅读器翻译", LOCAL,
                "机械任务：逐段翻译成中文。本地模型够用且免费；嫌质量差可在「模型参数」里把它的档案换成云端"));
        META.put(TASK_REWRITE, new TaskMeta(TASK_REWRITE, "检索词扩展（兜底）", LOCAL,
                "只在词面 0 命中时触发，便宜、可以慢"));
    }

    /**
     * 全部任务 id —— **任务清单的唯一源头**。
     *
     * <p>设置白名单等地方都必须派生自这里，不要再手写平行清单：曾经漏写
     * triple / rerank / grounding，界面上改那三行会被后端 400 拒掉
     *（"不支持的设置项: ai.model_for_triple"），而前端又把异常吞了 →
     * 用户看到的就是"这个修改不了"。加新任务时只改这一处。
     */
    public static List<String> allTasks() {
        return List.copyOf(META.keySet());
    }

    private final SettingsService settingsService;
    private final ModelProfileService profiles;

    /**
     * 一条可选目标 —— 现在直接用模型档案，字段与 {@link ModelProfileService.Target} 对齐。
     * 保留这个 record 是为了让调用方（wiki/entity/lint/…）不用改签名。
     *
     * @param separate 是否本地/自建（旧字段，保留兼容；现在判断依据是基址是否指向本机）
     */
    public record ModelTarget(String id, String label, String baseUrl, String apiKey, String model,
                              boolean separate) {
    }

    /** 是否本地目标（基址指向本机 / provider 是本地推理服务） */
    private static boolean isLocal(String baseUrl, String provider) {
        String u = baseUrl == null ? "" : baseUrl.toLowerCase();
        if (u.contains("localhost") || u.contains("127.0.0.1") || u.contains("host.docker.internal")
                || u.contains("://0.0.0.0")) {
            return true;
        }
        return "ollama".equals(provider) || "lmstudio".equals(provider) || "vllm".equals(provider);
    }

    /** 全部可选目标 = 全部档案 */
    public List<ModelTarget> targets() {
        List<ModelTarget> list = new ArrayList<>();
        for (ModelProfile p : profiles.all()) {
            list.add(new ModelTarget(p.getId(), p.getName(), p.getBaseUrl(), p.getApiKey(), p.getModel(),
                    isLocal(p.getBaseUrl(), p.getProvider())));
        }
        if (list.isEmpty()) {
            // 没有任何档案时给一个"旧配置回退"目标，保证界面与调用都不空
            ModelProfileService.Target t = profiles.resolve(null);
            list.add(new ModelTarget("legacy", t.label(), t.baseUrl(), t.apiKey(), t.model(), false));
        }
        return list;
    }

    /** 该任务当前用哪个档案（返回档案 id） */
    public String targetIdOf(String task) {
        String v = settingsService.effective(SettingsService.modelForTaskKey(task));
        TaskMeta m = META.get(task);
        String kind = m == null ? MAIN : m.defaultKind();
        if (v != null && !v.isBlank()) {
            String id = v.trim();
            // 老值 main/local 兼容：main → 激活档案；local → 本地档案（没有就激活档案）
            if (LOCAL.equals(id)) {
                ModelTarget local = firstLocal();
                return local != null ? local.id() : activeIdOrFirst();
            }
            if (MAIN.equals(id)) {
                return activeIdOrFirst();
            }
            // 指向的档案被删了 → 回退默认，别让任务卡死
            if (!profiles.all().stream().anyMatch(p -> p.getId().equals(id))) {
                log.debug("任务 {} 指向的档案 {} 已不存在，回退默认", task, id);
                return LOCAL.equals(kind) ? localOrActive() : activeIdOrFirst();
            }
            return id;
        }
        return LOCAL.equals(kind) ? localOrActive() : activeIdOrFirst();
    }

    private String activeIdOrFirst() {
        String active = profiles.activeId();
        List<ModelProfile> all = profiles.all();
        if (active != null && all.stream().anyMatch(p -> p.getId().equals(active))) {
            return active;
        }
        return all.isEmpty() ? "legacy" : all.get(0).getId();
    }

    private String localOrActive() {
        ModelTarget local = firstLocal();
        return local != null ? local.id() : activeIdOrFirst();
    }

    /** 第一个本地目标（没有返回 null） */
    public ModelTarget firstLocal() {
        for (ModelTarget t : targets()) {
            if (t.separate()) {
                return t;
            }
        }
        return null;
    }

    /** 解析出可用的模型目标 */
    public ModelTarget forTask(String task) {
        String id = targetIdOf(task);
        for (ModelTarget t : targets()) {
            if (t.id().equals(id)) {
                return t;
            }
        }
        return targets().get(0);
    }

    /** 按档案 id 解析（会话级覆盖用它：会话可以自己指定模型） */
    public ModelTarget forProfile(String profileId) {
        if (profileId != null && !profileId.isBlank()) {
            for (ModelTarget t : targets()) {
                if (t.id().equals(profileId.trim())) {
                    return t;
                }
            }
        }
        return forTask(TASK_CHAT);
    }

    /** 给界面用的分工表（含默认与理由、当前指向哪个档案） */
    public List<Map<String, Object>> table() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TaskMeta m : META.values()) {
            // 「对话问答」**不出现在分工表里**：它的模型是在智能体界面按会话选的
            //（每个会话可固定一个档案，选"默认"则走当前激活档案）。
            // 列在这里会让人以为必须在这里配，而改了它对已有会话也不生效 —— 徒增困惑。
            // 注意 TASK_CHAT 本身仍然在用（chatOnce 的默认目标就是它），只是不展示。
            if (TASK_CHAT.equals(m.task())) {
                continue;
            }
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("task", m.task());
            o.put("label", m.label());
            o.put("why", m.why());
            o.put("defaultKind", m.defaultKind());
            String id = targetIdOf(m.task());
            o.put("target", id);
            ModelTarget t = forTask(m.task());
            o.put("targetLabel", t.label());
            o.put("model", t.model());
            o.put("local", t.separate());
            out.add(o);
        }
        return out;
    }

    /** 是否云端（用于日志/界面提示"这一步会花 token"） */
    public boolean isCloud(String task) {
        return !forTask(task).separate();
    }
}
