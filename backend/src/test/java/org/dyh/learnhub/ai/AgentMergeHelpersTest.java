package org.dyh.learnhub.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 「融入笔记」里那段纯逻辑的护栏：剥掉模型给整篇套上的代码围栏。
 *
 * <p>为什么要单独测：模型经常把整篇 Markdown 包进 ``` 里（提示词已明确禁止），
 * 不剥的话笔记正文会凭空多出一层代码块 —— 而且**没有任何报错**，只在渲染时看出来。
 * 反过来也不能剥过头：正文里本来就有代码块（多块内容）时必须原样保留。
 */
class AgentMergeHelpersTest {

    @Test
    @DisplayName("整篇被 ``` 包住 → 剥掉围栏")
    void stripsWholeDocumentFence() {
        assertEquals("# 标题\n\n正文", AgentService.stripFence("```\n# 标题\n\n正文\n```"));
        assertEquals("# 标题", AgentService.stripFence("```markdown\n# 标题\n```"));
    }

    @Test
    @DisplayName("正文里本来就有代码块 → 不许剥（否则会把内容截断）")
    void keepsInnerFences() {
        String md = "# 标题\n\n```java\nint a = 1;\n```\n\n结尾";
        // 外层空白统一 trim（与调用方一致），**内容一字不动** —— 这里断的是"没有被截断"
        assertEquals(md.trim(), AgentService.stripFence(md));
        // 包了一层、里面还有块：也不剥（保守优先，宁可多一层也不截断内容）
        String wrapped = "```\n# 标题\n\n```java\nint a = 1;\n```\n";
        assertEquals(wrapped.trim(), AgentService.stripFence(wrapped));
    }

    @Test
    @DisplayName("普通正文原样返回")
    void keepsPlainMarkdown() {
        assertEquals("# 标题\n\n- 列表", AgentService.stripFence("# 标题\n\n- 列表"));
        assertEquals("", AgentService.stripFence("   "));
    }
}
