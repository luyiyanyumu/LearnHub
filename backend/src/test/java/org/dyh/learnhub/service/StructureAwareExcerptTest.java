package org.dyh.learnhub.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StructureAwareExcerptTest {
    @Test void retainsCompleteFormulaWhenItFitsInsideAnExcerpt() {
        String formula = "$$\nL=\\sum_i (y_i-f(x_i))^2\n$$";
        String text = "介绍文字。".repeat(50) + "\n\n损失函数：\n" + formula + "\n\n说明文字。".repeat(50);
        String excerpt = StructureAwareExcerpt.excerpt("损失函数", text, 120);
        assertTrue(excerpt.contains(formula));
        assertTrue(excerpt.length() <= 120);
    }

    @Test void omitsAnOversizedAtomicBlockInsteadOfBreakingCode() {
        String code = "```java\n" + "// 合成代码\n".repeat(100) + "```";
        assertEquals("", StructureAwareExcerpt.excerpt("合成代码", code, 100));
        String excerpt = StructureAwareExcerpt.excerpt("缓存", code + "\n\n缓存的解释。", 100);
        assertTrue(excerpt.contains("缓存的解释"));
        assertFalse(excerpt.contains("```"));
    }

    @Test void neverEmitsPartOfAMarkdownTable() {
        String table = "| 名称 | 作用 |\n|---|---|\n" + "| 合成索引 | 提高查询效率 |\n".repeat(20);
        String excerpt = StructureAwareExcerpt.excerpt("查询", table, 80);
        assertTrue(excerpt.isEmpty());
    }
}
