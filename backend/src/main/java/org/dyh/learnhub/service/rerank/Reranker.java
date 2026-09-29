package org.dyh.learnhub.service.rerank;

import java.util.List;
import java.util.Map;

/**
 * 检索精排（rerank）后端的抽象：召回之后、注入之前，把候选按"对回答这个问题的用处"重排。
 *
 * <h3>为什么要有这个抽象（2026-09-29）</h3>
 * 原来只有一种实现：把候选（标题 + 120 字摘要）丢给对话模型，让它输出一个 order 数组
 * —— 即 **LLM listwise 重排**。它能用（实测 MRR 0.815 → 0.926），但有三处结构缺陷：
 * <ol>
 *   <li><b>慢</b>：一次只排 20 条，60 条候选要分 3 批；本地 qwen3:8b 上每批是秒级到十几秒；</li>
 *   <li><b>有位置偏差</b>：listwise 排序受候选出现顺序影响，长提示还会摊薄注意力；</li>
 *   <li><b>不受控</b>：窗口外的候选被切掉，模型漏写编号要补，输出还得是合法 JSON。</li>
 * </ol>
 * Cross-Encoder（`bge-reranker-*` 这类）是**逐对打分**：score(query, doc) 独立算，
 * 排序完全由分数决定，不受批次与顺序影响，560M 模型在 CPU 上给几十条候选打分是几百毫秒。
 * 所以这里抽出接口，两种实现并存，用设置切换、用**同一批评测用例**比数字。
 *
 * <h3>约定</h3>
 * <ul>
 *   <li>{@link #rerank} 失败**直接抛异常**：回退策略由门面 {@code RerankService} 统一决定
 *       （换后端再试 → 都不行就用原顺序），实现类不要各自吞掉。</li>
 *   <li>返回的 key 列表必须**覆盖全部候选**：漏掉的按原顺序补在后面，绝不丢候选。</li>
 * </ul>
 */
public interface Reranker {

    /** 后端名：{@code llm}（列表重排）/ {@code cross}（Cross-Encoder） */
    String name();

    /**
     * 喂给重排器的候选文本长度（字符，默认 120）。
     *
     * <p>这个旋钮值得单独存在，因为两个后端对它的反应**方向相反**：
     * Cross-Encoder 逐对读全文，给到整块正文（几百字）通常更准；
     * LLM 列表重排一次要看 20 条，文本越长提示词越挤、排序反而变糊。
     * 所以 A/B 时它是自变量之一，不要写死在实现里。
     */
    String KEY_SNIPPET = "kb.rerank_snippet";

    /** 现在能不能用（LLM 看开关；cross 看服务是否探活成功） */
    boolean available();

    /**
     * 重排。
     *
     * @param question 用户问题原文
     * @param items    候选（顺序 = 召回顺序）
     * @return 重排后的 key 顺序（覆盖全部候选）
     * @throws RuntimeException 失败（由门面回退）
     */
    List<String> rerank(String question, List<Item> items);

    /** 状态（进 /api/kb/rerank，界面要看"当前用哪个后端"） */
    Map<String, Object> status();

    /** 一条待重排候选：key 是稳定标识（{@code note:5} / {@code file:2}），title/snippet 是给模型看的部分 */
    record Item(String key, String title, String snippet) {
    }
}
