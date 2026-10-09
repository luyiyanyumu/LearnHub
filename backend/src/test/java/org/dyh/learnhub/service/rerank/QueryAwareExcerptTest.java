package org.dyh.learnhub.service.rerank;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QueryAwareExcerptTest {
    @Test
    void findsIdentifiersInTheTailInsteadOfReturningUnrelatedIntroduction() {
        String body = "概览：这里介绍项目中的其他基础概念。".repeat(20)
                + "\n使用 AlphaClient 配合 registerHandler 完成订阅，随后启动应用。";
        String excerpt = QueryAwareExcerpt.excerpt("AlphaClient 如何调用 registerHandler？", body, 120);
        assertTrue(excerpt.contains("AlphaClient"));
        assertTrue(excerpt.contains("registerHandler"));
        assertTrue(body.contains(excerpt));
        assertTrue(excerpt.length() <= 120);
    }

    @Test
    void findsChineseTopicWithoutEnglishIdentifiers() {
        String body = "前面的内容讨论其他主题。".repeat(30)
                + "\n连接池配置：连接池上限会约束同时打开的连接数，应结合数据库的负载调整。";
        assertTrue(QueryAwareExcerpt.excerpt("连接池上限如何配置？", body, 80).contains("连接池上限"));
    }

    @Test
    void prefersSeveralConceptsToRepeatingTheSameTerm() {
        String body = "Alpha Alpha Alpha Alpha Alpha Alpha。" + "无关描述。".repeat(30)
                + "这里 Alpha 与 Beta 配合，Beta 完成最终检查。";
        String excerpt = QueryAwareExcerpt.excerpt("How do Alpha and Beta cooperate?", body, 55);
        assertTrue(excerpt.contains("Alpha 与 Beta"));
    }

    @Test
    void identifierMatchingDoesNotUseSubstringsOfUnrelatedWords() {
        String body = "systemCPUSetting 是另外一个配置名称。" + "过渡说明。".repeat(30)
                + "CPU 的可用核数决定工作线程基数。";
        String excerpt = QueryAwareExcerpt.excerpt("CPU 核数", body, 55);
        assertTrue(excerpt.contains("CPU 的可用核数"));
    }

    @Test
    void preservesCodeWhitespaceAndOriginalEvidence() {
        String body = "一般说明。".repeat(300)
                + "\n```java\nwhile (cursor.next()) {\n    int value = cursor.getValue();\n}\n```\n后续说明。";
        String excerpt = QueryAwareExcerpt.excerpt("cursor.next getValue", body, 100);
        assertTrue(excerpt.contains("\n    int value"));
        assertTrue(excerpt.contains("getValue"));
        assertTrue(body.contains(excerpt));
    }

    @Test
    void equalCoverageKeepsExplanationAndCodeFollowingATailTopicLabel() {
        String body = "前面无关的说明。".repeat(220)
                + "\n注册监听器：application.addListeners(listener);\n";
        String excerpt = QueryAwareExcerpt.excerpt("注册监听器", body, 1200);
        assertTrue(excerpt.contains("注册监听器"));
        assertTrue(excerpt.contains("application.addListeners(listener);"));
        assertTrue(body.contains(excerpt));
        assertTrue(excerpt.length() <= 1200);
    }

    @Test
    void retainsSectionLabelWithoutSpendingTheBodyBudgetOnIt() {
        String body = "小节：二级标题 / 消息监听器的注册\n" + "其他段落的前置解释。".repeat(20)
                + "AlphaClient 调用 registerHandler 完成注册。";
        String excerpt = QueryAwareExcerpt.rerankExcerpt("AlphaClient registerHandler", body, 120);
        assertTrue(excerpt.startsWith("小节：二级标题 / 消息监听器的注册｜"));
        assertTrue(excerpt.contains("registerHandler"));
        assertTrue(excerpt.length() <= 120);
        assertFalse(excerpt.contains("\n"));
    }

    @Test
    void limitsVeryLongSectionNamesAndHonorsSmallerBudgets() {
        String body = "小节：" + "非常长的多层级标题。".repeat(20) + "\n" + "一般解释。".repeat(30)
                + "betaClient 完成任务。";
        String excerpt = QueryAwareExcerpt.rerankExcerpt("betaClient", body, 60);
        assertTrue(excerpt.startsWith("小节："));
        assertTrue(excerpt.contains("betaClient"));
        assertTrue(excerpt.length() <= 60);
    }

    @Test
    void usesPrefixWhenThereAreNoMatchesAndKeepsShortTextUntouched() {
        assertEquals("abcdefgh", QueryAwareExcerpt.excerpt("unrelated", "abcdefghijkl", 8));
        assertEquals("\n  short\n", QueryAwareExcerpt.excerpt("unrelated", "\n  short\n", 120));
        assertEquals("", QueryAwareExcerpt.excerpt("anything", null, 120));
        assertEquals("", QueryAwareExcerpt.excerpt("anything", "text", 0));
    }

    @Test
    void neverSplitsASurrogatePairAtTheBudgetBoundary() {
        String excerpt = QueryAwareExcerpt.excerpt("unknown", "abc😀defghij", 4);
        assertEquals("abc", excerpt);
    }
}
