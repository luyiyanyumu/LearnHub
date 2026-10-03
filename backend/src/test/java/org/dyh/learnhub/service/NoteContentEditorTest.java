package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.dto.NoteEditRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NoteContentEditorTest {
    private final NoteContentEditor editor = new NoteContentEditor();
    private final ObjectMapper json = new ObjectMapper();

    private NoteEditRequest request(String source, String operations) throws Exception {
        return new NoteEditRequest(NoteContentEditor.hash(source),
                List.of(json.readValue(operations, NoteEditRequest.Operation[].class)));
    }

    private String edit(String source, String operations) throws Exception {
        return editor.apply(source, request(source, operations)).content();
    }

    @Test void deleteSingleChineseCharacterKeepsAllOtherBytes() throws Exception {
        String source = "# 原文\r\n\r\n这是我的知识笔记。\r\n\r\n```java\r\nint x = 1;\r\n```\r\n";
        assertEquals(source.replace("我的", "我"), edit(source, """
                [{"action":"delete","text":"的","prefix":"这是我","suffix":"知识"}]
                """));
    }

    @Test void ambiguousTargetNeverSilentlyChoosesFirstOccurrence() throws Exception {
        String source = "甲词、乙词、丙词";
        var request = request(source, """
[{"action":"delete","text":"词"}]""");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> editor.apply(source, request))
                .getMessage().contains("3 处"));
        assertEquals("甲词、乙、丙词", edit(source, """
[{"action":"delete","text":"词","occurrence":2}]"""));
    }

    @Test void allIsExplicitAndSequentialOperationsUseUpdatedSource() throws Exception {
        String source = "这里有错字，错字要改。";
        assertEquals("这里有正确文字，正确文字要改。", edit(source, """
                [{"action":"replace","text":"错字","value":"正确文字","all":true}]
                """));
        assertEquals("这里有字，字要改。", edit(source, """
[{"action":"delete","text":"错","all":true}]"""));
        assertEquals("<u>新</u>知识。", edit("旧知识。", """
                [{"action":"replace","text":"旧","value":"新"}, {"action":"format","text":"新","style":"underline"}]
                """));
    }

    @Test void contextAndOccurrenceWorkTogether() throws Exception {
        assertEquals("甲的例子，乙的例子，甲例子", edit("甲的例子，乙的例子，甲的例子", """
                [{"action":"delete","text":"的","prefix":"甲","occurrence":2}]
                """));
    }

    @Test void invalidOccurrenceAndConflictingAllAreRejected() throws Exception {
        for (String op : List.of("""
[{"action":"delete","text":"甲","occurrence":0}]""",
                """
[{"action":"delete","text":"甲","occurrence":2}]""",
                """
[{"action":"delete","text":"甲","occurrence":1,"all":true}]""")) {
            var req = request("甲", op);
            assertThrows(IllegalArgumentException.class, () -> editor.apply("甲", req));
        }
    }

    @Test void missingTextOrReplacementIsRejected() throws Exception {
        for (String op : List.of("""
[{"action":"delete","text":""}]""",
                """
[{"action":"replace","text":"甲"}]""", """
[{"action":"delete","text":"乙"}]""")) {
            var req = request("甲", op);
            assertThrows(IllegalArgumentException.class, () -> editor.apply("甲", req));
        }
    }

    @Test void singleLetterInsideEnglishWordActuallyRendersBold() throws Exception {
        assertEquals("Py<strong>t</strong>hon", edit("Python", """
[{"action":"format","text":"t","style":"bold"}]"""));
        assertEquals("甲<font style=\"color: #e34e58\">乙</font>丙", edit("甲乙丙", """
                [{"action":"format","text":"乙","style":"color","value":"#e34e58"}]
                """));
    }

    @Test void formatsCanBeCombinedWithoutChangingText() throws Exception {
        assertEquals("<strong><font style=\"font-size: 24px\">重</font></strong>点", edit("重点", """
                [{"action":"format","text":"重","style":"bold"},
                 {"action":"format","text":"重","style":"font_size","value":"24"}]
                """));
        assertEquals("<font style=\"background-color: yellow\">重点</font>", edit("重点", """
                [{"action":"format","text":"重点","style":"highlight","value":"yellow"}]
                """));
    }

    @Test void existingRichHtmlTextCanBeFormattedWithoutTouchingAttributes() throws Exception {
        assertEquals("<p style=\"color: red\">前<u>中</u>后</p>", edit("<p style=\"color: red\">前中后</p>", """
                [{"action":"format","text":"中","style":"underline"}]
                """));
    }

    @Test void changingSingleCharacterFontSizeKeepsItsExistingColor() throws Exception {
        String after = edit("<font style=\"color: red\">甲乙</font>、<font style=\"color: blue\">甲乙</font>", """
                [{"action":"format","text":"甲","style":"font_size","value":"24","all":true}]
                """ );
        assertTrue(after.contains("<font style=\"color: red; font-size: 24px\">甲</font>乙"), after);
        assertTrue(after.contains("<font style=\"color: blue; font-size: 24px\">甲</font>乙"), after);
        String combined = edit("重点", """
                [{"action":"format","text":"重","style":"color","value":"red"},
                 {"action":"format","text":"重","style":"font_size","value":"24"}]
                """ );
        assertTrue(combined.contains("<font style=\"color: red; font-size: 24px\">重</font>"), combined);
    }

    @Test void formatProtectsCodeLinkAddressesTagsAndComments() throws Exception {
        for (String source : List.of("```\nPython\n```", "`Python`", "[链接](https://Python.test)",
                "<span title=\"Python\">正文</span>", "<!-- Python -->", "<code>Python</code>")) {
            var req = request(source, """
[{"action":"format","text":"Python","style":"bold"}]""");
            assertThrows(IllegalArgumentException.class, () -> editor.apply(source, req), source);
        }
    }

    @Test void arbitraryCssAndMultilineInlineFormatAreRejected() throws Exception {
        for (String op : List.of("""
[{"action":"format","text":"甲","style":"color","value":"red;display:none"}]""",
                """
[{"action":"format","text":"甲","style":"font_size","value":"999"}]""",
                """
[{"action":"format","text":"甲\\n乙","style":"bold"}]""")) {
            var req = request("甲\n乙", op);
            assertThrows(IllegalArgumentException.class, () -> editor.apply("甲\n乙", req));
        }
    }

    @Test void insertionPreservesWhitespaceAndAllowsEmptyReplacement() throws Exception {
        assertEquals("前 \r\n新甲后", edit("前 \r\n甲后", """
[{"action":"insert_before","text":"甲","value":"新"}]"""));
        assertEquals("甲新后", edit("甲后", """
[{"action":"insert_after","text":"甲","value":"新"}]"""));
        assertEquals("甲", edit("甲乙", """
[{"action":"replace","text":"乙","value":""}]"""));
    }

    @Test void codeFormatKeepsVisibleEntitiesAndEscapedPunctuation() throws Exception {
        assertEquals("甲`&`乙", edit("甲&amp;乙", """
                [{"action":"format","text":"&amp;","style":"code"}]
                """));
        assertEquals("`*`", edit("\\*", """
                [{"action":"format","text":"\\\\*","style":"code"}]
                """));
        var req = request("甲&amp;乙", """
                [{"action":"format","text":"amp","style":"bold"}]
                """ );
        assertThrows(IllegalArgumentException.class, () -> editor.apply("甲&amp;乙", req));
    }

    @Test void tocIdsAvoidSuffixCollisionsAndCountEmptyHeadings() throws Exception {
        String after = edit("# foo\n\n## foo-1\n\n## foo\n\n##\n\n## !!!", """
                [{"action":"insert_toc"}]
                """ );
        assertTrue(after.contains("[foo](#foo-2)"), after);
        assertTrue(after.contains("[!!!](#section-1)"), after);
    }

    @Test void emojiIsKeptWhole() throws Exception {
        assertEquals("中文<em>😀</em>原样", edit("中文😀原样", """
[{"action":"format","text":"😀","style":"italic"}]"""));
        var lowHalf = new NoteEditRequest.Operation("delete", "\ude00", null, null, null, null, null, null, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> editor.apply("😀", new NoteEditRequest(NoteContentEditor.hash("😀"), List.of(lowHalf))));
    }

    @Test void staleVersionIsRejectedAndLargeNoteIsNeverRegenerated() throws Exception {
        var req = request("原文", """
[{"action":"delete","text":"原"}]""");
        assertThrows(IllegalArgumentException.class, () -> editor.apply("新原文", req));
        String longNote = "x ".repeat(30000) + "唯一错字" + " y".repeat(30000);
        assertEquals(longNote.replace("错", ""), edit(longNote, """
[{"action":"delete","text":"错"}]"""));
        assertEquals("保留", edit("错".repeat(100000) + "保留", """
                [{"action":"delete","text":"错","all":true}]
                """));
    }

    @Test void tocHasWorkingDuplicateAnchorsAndSkipsCodeAndHtmlComments() throws Exception {
        String source = "# 总题\n\n## 一、Java 核心基础\n\n### **简介**\n\n### **简介**\n\n```md\n## 假标题\n```\n\n<!--\n## 注释标题\n-->\n\n尾巴";
        String after = edit(source, """
[{"action":"insert_toc"}]""");
        assertTrue(after.contains("[一、Java 核心基础](#一java-核心基础)"), after);
        assertTrue(after.contains("[简介](#简介-1)"), after);
        assertFalse(after.contains("[假标题]"));
        assertFalse(after.contains("[注释标题]"));
        assertTrue(after.endsWith(source));
        assertEquals(after, edit(after, """
[{"action":"insert_toc"}]"""));
    }

    @Test void tocFiltersLevelsStillCountsSkippedHeadingsForSlugs() throws Exception {
        String after = edit("# 同名\n\n## 同名\n\n### 小节", """
[{"action":"insert_toc","min_level":2,"max_level":2}]""");
        assertTrue(after.contains("[同名](#同名-1)"), after);
        assertFalse(after.contains("[小节]"));
    }

    @Test void tocHandlesSetextAndInlineLinksWithMatchingAnchorRules() throws Exception {
        String after = edit("主标题\n======\n\n## [官网](https://example.com) 与 `代码`", """
[{"action":"insert_toc","placement":"end"}]""");
        assertTrue(after.contains("[主标题](#主标题)"), after);
        assertTrue(after.contains("[官网 与 代码](#官网httpsexamplecom-与-代码)"), after);
    }

    @Test void tocAnchorPlacementAndCrLfArePreserved() throws Exception {
        String source = "# 标题\r\n\r\n摘要\r\n\r\n## 章节\r\n";
        String after = edit(source, """
[{"action":"insert_toc","placement":"after","text":"摘要"}]""");
        assertTrue(after.startsWith("# 标题\r\n\r\n摘要"));
        assertTrue(after.endsWith("\r\n\r\n## 章节\r\n"));
        assertFalse(after.replace("\r\n", "").contains("\n"));
    }

    @Test void malformedTocAndNoHeadingsAreRejected() throws Exception {
        for (String source : List.of("没有标题", "<!-- learn-hub-toc:start -->\n\n## 标题")) {
            var req = request(source, """
[{"action":"insert_toc"}]""");
            assertThrows(IllegalArgumentException.class, () -> editor.apply(source, req));
        }
    }

    @Test void tocMarkersInsideExamplesAreNotTreatedAsAnExistingDirectory() throws Exception {
        String source = "# 标题\n\n```md\n<!-- learn-hub-toc:start -->\n## 示例\n<!-- learn-hub-toc:end -->\n```\n";
        String after = edit(source, """
                [{"action":"insert_toc"}]
                """ );
        assertTrue(after.endsWith(source));
        assertFalse(after.contains("[示例]"));
        assertEquals(after, edit(after, """
                [{"action":"insert_toc"}]
                """ ));
    }
}
