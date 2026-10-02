package org.dyh.learnhub.service;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WikiQuality} 的回归测试（纯函数，不需要 Spring 容器）。
 *
 * <p>重点锁住评估报告 P0-A 的修复：来源类型在代码里有**两套命名** ——
 * {@code KbChunk}/{@code EntityCompileService} 用 {@code quick_ref}，
 * {@code KgService}/{@code WikiService} 用 {@code ref}。修复前
 * {@code normalizeKind} 一律归一成 {@code ref}，拿 {@code ref-3} 去比
 * {@code idsOf()} 产出的 {@code quick_ref-3}，永远不匹配 —— 真实引用被判"不存在"、
 * 方括号被剥掉降级成纯文本（实测 @GetMapping、dsh、git 等页）。
 *
 * <p>这些断言一断，就说明有人把"两种写法都认"的逻辑改回去了。
 */
class WikiQualityTest {

    /** 一段能过长度与分节检查的正文，引用标记由调用方拼进来 */
    private static String body(String citation) {
        StringBuilder b = new StringBuilder("## 概览\n\n");
        for (int i = 0; i < 20; i++) {
            b.append("这是用来凑够长度校验的正文内容，重复若干次。");
        }
        b.append("\n\n## 细节\n\n关键事实一处 ").append(citation).append("，另一处 [笔记#7]。\n");
        return b.toString();
    }

    /** 实体编译路径：合法 id 是 quick_ref-N（EntityCompileService 的写法） */
    @Test
    void quickRefIdFromEntityCompileIsAccepted() {
        WikiQuality.Result r = WikiQuality.check(body("[速查卡#3]"), Set.of("quick_ref-3", "note-7"));
        assertEquals(java.util.List.of(), r.hardIssues(), r.summary());
        assertTrue(r.content().contains("[速查卡#3]"), "合法引用必须保留方括号：" + r.content());
    }

    /** 主题页路径：合法 id 是 ref-N（WikiService 的写法）—— 修 P0-A 时不能把它改坏 */
    @Test
    void refIdFromTopicPageIsAccepted() {
        WikiQuality.Result r = WikiQuality.check(body("[速查卡#3]"), Set.of("ref-3", "note-7"));
        assertEquals(java.util.List.of(), r.hardIssues(), r.summary());
        assertTrue(r.content().contains("[速查卡#3]"), "合法引用必须保留方括号：" + r.content());
    }

    /** 不存在的编号仍要能被识别（评估报告的验收要求："无效 ID 仍能识别"） */
    @Test
    void unknownRefIsStillRejected() {
        WikiQuality.Result r = WikiQuality.check(body("[速查卡#99]"), Set.of("quick_ref-3", "note-7"));
        assertFalse(r.ok(), "引用不存在的素材必须判定为不通过");
        assertTrue(r.summary().contains("速查卡#99"), "问题描述要带上具体编号：" + r.summary());
        // 降级为纯文本，避免渲染成点不开的死链
        assertFalse(r.content().contains("[速查卡#99]"), "无效引用不应保留方括号：" + r.content());
    }

    /** 写坏的标记（模型会在中文词里混标点）仍要能修好并正常校验 */
    @Test
    void malformedMarkerIsRepaired() {
        WikiQuality.Result r = WikiQuality.check(body("[速查、卡#3]"), Set.of("quick_ref-3", "note-7"));
        assertTrue(r.content().contains("[速查卡#3]"), "写坏的标记应被修成规范形式：" + r.content());
    }
}
