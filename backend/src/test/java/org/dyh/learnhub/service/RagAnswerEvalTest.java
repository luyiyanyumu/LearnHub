package org.dyh.learnhub.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 答案级评测的纯函数回归测试（不依赖 Spring 容器）。
 *
 * <p>锁住四件容易悄悄写错、而错了会让指标说假话的事：
 * <ol>
 *   <li><b>没标注 ≠ 0 分</b>：{@code expect_words} 为空时必须返回 -1（不参与均值），
 *       否则"没标注"会被当成"答错"，正确性均值凭空变低；</li>
 *   <li><b>引用完整性是 ALL 口径</b>：多来源题只命中一个必须算不完整 ——
 *       报告明确要求"不能仅命中任一文件即成功"；</li>
 *   <li><b>缺口题不进召回分母</b>：否则每加一条缺口题就白扣一分 recall；</li>
 *   <li><b>拒答要能被认出来</b>：模型老实说"材料里没有"时，不能被判成硬编答案。</li>
 * </ol>
 */
class RagAnswerEvalTest {

    // ---------------- ① 正确性：关键词覆盖率 ----------------

    @Test
    void coverageIsMinusOneWhenNothingLabeled() {
        assertEquals(-1.0, RagAnswerEvalService.keywordCoverage(null, "随便什么答案"), 1e-9,
                "没标注时必须返回 -1（不参与均值），不能算 0 分");
        assertEquals(-1.0, RagAnswerEvalService.keywordCoverage("   ", "答案"), 1e-9);
        assertEquals(-1.0, RagAnswerEvalService.keywordCoverage(";;;", "答案"), 1e-9);
    }

    @Test
    void coverageCountsHitRatio() {
        assertEquals(1.0, RagAnswerEvalService.keywordCoverage("可变;线程安全", "StringBuilder 可变，但不是线程安全"), 1e-9);
        assertEquals(0.5, RagAnswerEvalService.keywordCoverage("可变;线程安全", "StringBuilder 是可变的"), 1e-9);
        assertEquals(0.0, RagAnswerEvalService.keywordCoverage("可变;线程安全", "完全无关的内容"), 1e-9);
    }

    /** 英文关键词不该因为大小写差异漏判（expect_words 里写 equals，答案里可能是 Equals） */
    @Test
    void coverageIsCaseInsensitive() {
        assertEquals(1.0, RagAnswerEvalService.keywordCoverage("equals", "用 Equals 比较内容"), 1e-9);
    }

    /** 标注时三种分隔符混用都要认（人工标注很难统一） */
    @Test
    void wordSeparatorsAreFlexible() {
        assertEquals(List.of("a", "b", "c", "d"), RagAnswerEvalService.splitWords("a;b|c，d"));
        assertEquals(List.of("a", "b"), RagAnswerEvalService.splitWords(" a ; b "));
    }

    // ---------------- ② 引用完整性：ALL 而非 ANY ----------------

    @Test
    void coverageRequiresEveryNecessarySource() {
        Set<String> both = refs("note:2", "file:2");
        assertTrue(RagAnswerEvalService.coversAll(both, List.of("note:2", "file:2", "note:9")),
                "两个必要来源都召回才算完整");
        assertFalse(RagAnswerEvalService.coversAll(both, List.of("note:2", "note:9")),
                "只命中一个 → 不完整（ANY 口径会误判为成功）");
        assertFalse(RagAnswerEvalService.coversAll(both, List.of("file:2")), "只命中另一个 → 同样不完整");
    }

    @Test
    void coverageOnSingleSourceDegradesToHit() {
        assertTrue(RagAnswerEvalService.coversAll(refs("quick_ref:1"), List.of("quick_ref:1")));
        assertFalse(RagAnswerEvalService.coversAll(refs("quick_ref:1"), List.of("quick_ref:4")));
        assertFalse(RagAnswerEvalService.coversAll(refs("quick_ref:1"), List.of()), "什么都没召回 → 不完整");
        assertFalse(RagAnswerEvalService.coversAll(Set.of(), List.of("quick_ref:1")), "没有期望来源 → 不适用，判 false");
    }

    // ---------------- ③ 缺口题判定 ----------------

    @Test
    void noneRefsAreGapCases() {
        assertTrue(RagEvalService.isGapCase(RagEvalService.parseRefs("none")));
        assertTrue(RagEvalService.isGapCase(RagEvalService.parseRefs("NONE")));
        assertTrue(RagEvalService.isGapCase(RagEvalService.parseRefs("无")));
        assertTrue(RagEvalService.isGapCase(RagEvalService.parseRefs("-")));
        assertTrue(RagEvalService.isGapCase(RagEvalService.parseRefs("")), "空 → 缺口题");
        assertTrue(RagEvalService.isGapCase(RagEvalService.parseRefs(null)), "null → 缺口题");
    }

    @Test
    void realRefsAreNotGapCases() {
        assertFalse(RagEvalService.isGapCase(RagEvalService.parseRefs("note:2")));
        assertFalse(RagEvalService.isGapCase(RagEvalService.parseRefs("note:2|file:2")),
                "多来源题不是缺口题");
        assertFalse(RagEvalService.isGapCase(RagEvalService.parseRefs("none|note:2")),
                "混写时只要有真实来源，就不是缺口题");
    }

    // ---------------- ④ 无答案处理：拒答识别 ----------------

    @Test
    void refusalIsDetected() {
        assertTrue(RagAnswerEvalService.isRefusal("材料里没有相关内容，无法回答这个问题。"));
        assertTrue(RagAnswerEvalService.isRefusal("知识库中没有找到关于 Kubernetes Operator 的记录。"));
        assertTrue(RagAnswerEvalService.isRefusal("现有材料不足以回答该问题。"));
    }

    /** 开头就说"材料里没有" = 拒答（缺口题的正确答案正是这样） */
    @Test
    void refusalAtTheStartIsDetected() {
        assertTrue(RagAnswerEvalService.isRefusal(
                "材料里没有相关内容。依据：自动检索到的 8 条记录分别涉及 AI Agent 规划、Transformer…"));
        assertTrue(RagAnswerEvalService.isRefusal(
                "**结论：材料里没有足够内容回答这个问题。**\n\n具体来说："));
    }

    /**
     * 回归测试：**正确回答末尾附带的"范围声明"不是拒答**。
     *
     * <p>这条是从真实运行里抓出来的缺陷：第一版没有位置判断（只要出现缺失类说法就算拒答），
     * 结果 3 条**完全正确**的回答全被判成"误拒"，noAnswerScore 从真实的 1.0 掉到 0.25。
     * 模型习惯在正确回答末尾补一句"材料未覆盖底层细节"——那是范围声明，不是拒答。
     */
    @Test
    void trailingScopeCaveatIsNotRefusal() {
        // 真实运行里 id=126 的回答原文（正确回答 + 末尾范围声明），逐字取自评测 detail
        String good = "**结论：** 三者核心区别在**可变性**和**线程安全**，选择原则是——单线程拼接用 StringBuilder，"
                + "多线程共享用 StringBuffer，不需要修改就用 String。\n\n"
                + "**依据（你之前记的速查卡#1）：**\n\n"
                + "| 类型 | 可变 | 线程安全 | 性能 |\n"
                + "|------|------|---------|------|\n"
                + "| String | 不可变 | 安全（本身不可变） | 拼接慢 |\n"
                + "| StringBuilder | 可变 | 不安全 | 最快 |\n"
                + "| StringBuffer | 可变 | 安全（synchronized） | 较慢 |\n\n"
                + "**怎么选：**\n"
                + "- 内容不需要改动 → **String**（不可变，天然安全）\n"
                + "- 需要频繁拼接/修改，且只在单线程里用 → **StringBuilder**（最快）\n"
                + "- 需要频繁修改，且会被多线程共享 → **StringBuffer**（靠 synchronized 保证安全，但较慢）\n\n"
                + "**材料未覆盖的部分：** 关于三者的底层实现细节（如扩容机制、具体源码）以及性能差异的量化数据，材料里没有相关内容。";
        assertTrue(good.length() > 300, "回归样本要足够长，才符合真实回答的形态");
        assertFalse(RagAnswerEvalService.isRefusal(good),
                "结论在前、只在末尾声明范围 —— 这是正常回答，不是拒答");
    }

    /** 强标志（明确说"答不了"）出现在任何位置都算拒答 */
    @Test
    void strongRefusalMarkerCountsAnywhere() {
        assertTrue(RagAnswerEvalService.isRefusal("先看材料：仅提到 MQTT。但这个问题我无法回答。"));
    }

    @Test
    void normalAnswerIsNotRefusal() {
        assertFalse(RagAnswerEvalService.isRefusal("MQTT 是基于发布/订阅的轻量消息协议，适合 IoT 弱网场景。"));
        assertFalse(RagAnswerEvalService.isRefusal(""), "空答案不算拒答（是失败，另行归因）");
        assertFalse(RagAnswerEvalService.isRefusal(null));
    }

    // ---------------- ⑤ 输入拼装 ----------------

    @Test
    void promptPutsMaterialBeforeQuestion() {
        String p = RagAnswerEvalService.promptOf("MQTT 是什么？", "【自动检索到的相关记录】1. [笔记#3] MQTT");
        assertTrue(p.contains("【材料】"));
        assertTrue(p.contains("【问题】"));
        assertTrue(p.indexOf("MQTT 是什么？") > p.indexOf("【材料】"), "问题必须在材料之后");
    }

    @Test
    void promptStatesWhenThereIsNoMaterial() {
        String p = RagAnswerEvalService.promptOf("Rust 所有权？", "");
        assertTrue(p.contains("没有检索到任何材料"), "没有材料时必须明说，否则模型会以为材料被省略了");
    }

    private static Set<String> refs(String... items) {
        return new LinkedHashSet<>(List.of(items));
    }
}
