package org.dyh.learnhub.service;

import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.annotations.Select;
import org.dyh.learnhub.entity.KgNode;
import org.dyh.learnhub.mapper.GraphEvidenceSourceMapper;
import org.dyh.learnhub.mapper.KbChunkMapper;
import org.dyh.learnhub.mapper.KgCommunityMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GraphEvidenceServiceTest {
    private KgGraphService graph;
    private KgCommunityMapper relations;
    private GraphEvidenceSourceMapper sources;
    private GraphEvidenceService service;

    @BeforeEach
    void setup() {
        graph = mock(KgGraphService.class);
        relations = mock(KgCommunityMapper.class);
        sources = mock(GraphEvidenceSourceMapper.class);
        service = new GraphEvidenceService(graph, relations, sources);
        when(graph.recognize(anyString(), anyInt())).thenReturn(List.of(node("a"), node("b")));
    }

    @Test
    void emptyOrDisabledQueryDoesNoWork() {
        assertTrue(service.search(null, 6).isEmpty());
        assertTrue(service.search("  ", 6).isEmpty());
        assertTrue(service.search("A", 0).isEmpty());
        assertTrue(service.search("A", -1).isEmpty());
        verifyNoInteractions(graph, relations, sources);
    }

    @Test
    void noRecognizedConceptDoesNotReadRelationsOrSources() {
        when(graph.recognize(anyString(), anyInt())).thenReturn(List.of());
        assertTrue(service.search("没见过的问题", 6).isEmpty());
        verify(graph).recognize("没见过的问题", 12);
        verifyNoInteractions(relations, sources);
    }

    @Test
    void findsEvidenceAfterLongIntroductionAndPreservesActualChunkSequence() {
        String content = "前言只是一些其它主题。".repeat(400) + "\n\n## 实现\nA 依赖 B 才能启动。";
        rows(relation("a", "prerequisite", "b", "A 依赖 B 才能启动。", "笔记#7"));
        docs("note", source(7, "当前标题", content));

        List<RetrievalHit> hits = service.search("A 怎么启动", 6);

        assertEquals(1, hits.size());
        RetrievalHit hit = hits.getFirst();
        assertEquals("note", hit.sourceType());
        assertEquals(7L, hit.sourceId());
        assertEquals("当前标题", hit.title());
        assertEquals("测试分类", hit.category());
        assertTrue(hit.seq() > 0);
        assertEquals(TextChunker.splitWithHeadings(content).get(hit.seq()).text(), hit.text());
        assertEquals(List.of("graph"), hit.channels());
        assertEquals(List.of("a —前置知识→ b"), hit.graphRelations());
        assertFalse(hit.text().contains("前言"));
    }

    @Test
    void retainsDifferentEvidenceChunksFromSameSourceWithoutItsUnrelatedChunk() {
        String content = "# 启动\nA 依赖 B。\n# 其它\n无关资料。\n# 结构\nA 属于 C。";
        rows(relation("a", "prerequisite", "b", "A 依赖 B。", "笔记#1"),
                relation("a", "part_of", "c", "A 属于 C。", "笔记#1"));
        docs("note", source(1, "同源", content));

        List<RetrievalHit> hits = service.search("A B", 6);

        assertEquals(List.of(0, 2), hits.stream().map(RetrievalHit::seq).toList());
        assertTrue(hits.stream().noneMatch(hit -> hit.text().contains("无关")));
        verify(sources, times(1)).currentSources("note", List.of(1L));
    }

    @Test
    void deletedDocumentImmediatelyInvalidatesPreviouslyValidGraphEvidence() {
        rows(relation("a", "prerequisite", "b", "A 依赖 B。", "笔记#1"));
        docs("note", source(1, "标题", "A 依赖 B。"));
        assertEquals(1, service.search("A B", 6).size());

        when(sources.currentSources("note", List.of(1L))).thenReturn(List.of());

        assertTrue(service.search("A B", 6).isEmpty());
    }

    @Test
    void editedDocumentMustStillContainTheEntireEvidenceIncludingItsEnding() {
        String evidence = "A 依赖 B 才能启动，并且必须开启事务。";
        rows(relation("a", "prerequisite", "b", evidence, "笔记#1"));
        docs("note", source(1, "标题", evidence));
        assertEquals(1, service.search("A B", 6).size());

        docs("note", source(1, "标题", "A 依赖 B 才能启动，并且不需要开启事务。"));

        assertTrue(service.search("A B", 6).isEmpty());
    }

    @Test
    void sourceReferenceOrTitleAloneIsInsufficientEvidence() {
        rows(relation("a", "prerequisite", "b", "A 依赖 B。", "笔记#1"));
        docs("note", source(1, "A 依赖 B。", "正文只讲 C 的调用。"));
        assertTrue(service.search("A B", 6).isEmpty());
    }

    @Test
    void ignoresDerivedUnsupportedAndEvidenceFreeRelationsBeforeReadingDocuments() {
        Map<String, Object> derived = relation("a", "is_a", "b", "A 是 B。", "笔记#1");
        derived.put("origin", "derived");
        derived.put("derivedFrom", "2,3");
        rows(derived,
                relation("a", "related_to", "b", "  ", "笔记#1"),
                relation("a", "unknown", "b", "A 与 B。", "笔记#1"),
                relation("a", "is_a", "a", "A 是 A。", "笔记#1"),
                relation("x", "is_a", "y", "X 是 Y。", "笔记#1"),
                relation("a", "is_a", "b", "A 是 B。", "wiki#1|代码#2|笔记#0|笔记#9999999999999999999999"));

        assertTrue(service.search("A B", 6).isEmpty());
        verifyNoInteractions(sources);
    }

    @Test
    void allowsMarkdownAndWhitespaceNormalizationWithoutInventingText() {
        String content = "# 说明\n**A**\t依赖\n [B](https://example.test) 才能启动。";
        rows(relation("a", "prerequisite", "b", "A 依赖 B 才能启动。", "笔记#1"));
        docs("note", source(1, "标题", content));

        RetrievalHit hit = service.search("A B", 6).getFirst();

        assertEquals(0, hit.seq());
        assertEquals(TextChunker.splitWithHeadings(content).getFirst().text(), hit.text());
        assertTrue(content.contains(hit.text()));
    }

    @Test
    void normalizationKeepsEnglishWordBoundariesAndMeaningfulSymbols() {
        rows(relation("a", "related_to", "b", "The rate is high", "笔记#1"),
                relation("a", "used_for", "b", "*ptr is valid", "笔记#2"),
                relation("a", "part_of", "b", "foo_bar is valid", "笔记#3"));
        docs("note", source(1, "一", "The rateishigh"), source(2, "二", "ptr is valid"),
                source(3, "三", "foobar is valid"));

        assertTrue(service.search("A B", 6).isEmpty());
    }

    @Test
    void changedGenericTypesAreNotMistakenForHtmlDecoration() {
        rows(relation("a", "related_to", "b", "List<String> stores values", "笔记#1"),
                relation("a", "used_for", "b", "`List<String>` stores values", "笔记#2"));
        docs("note", source(1, "纯文本泛型", "List<Integer> stores values"),
                source(2, "行内代码泛型", "`List<Integer>` stores values"));

        assertTrue(service.search("A B", 6).isEmpty());
    }

    @Test
    void inlineCodeKeepsItsOperatorsAndLiteralMarkdown() {
        rows(relation("a", "related_to", "b", "`value = a ** 2 + b ** 3`", "笔记#1"),
                relation("a", "used_for", "b", "A depends on B", "笔记#2"));
        docs("note", source(1, "运算符已修改", "`value = a 2 + b 3`"),
                source(2, "代码中的星号", "`**A** depends on B`"));

        assertTrue(service.search("A B", 6).isEmpty());
    }

    @Test
    void fencedCodeKeepsLiteralLinksTagsAndHeadingMarkers() {
        rows(relation("a", "related_to", "b", "```\n[A](https://old.test)\n```", "笔记#1"),
                relation("a", "used_for", "b", "```html\n<strong>A</strong>\n```", "笔记#2"),
                relation("a", "part_of", "b", "```\n# A\n```", "笔记#3"));
        docs("note", source(1, "代码链接", "```\n[A](https://new.test)\n```"),
                source(2, "代码标签", "```html\nA\n```"), source(3, "代码注释", "```\nA\n```"));

        assertTrue(service.search("A B", 6).isEmpty());
    }

    @Test
    void realHtmlFormattingAndInlineCodeDelimitersCanStillNormalize() {
        rows(relation("a", "prerequisite", "b", "List<String> 依赖 B。", "笔记#1"));
        docs("note", source(1, "真正格式", "<font color='red'>`List<String>`</font> 依赖 **B**。"));

        assertEquals(1, service.search("A B", 6).size());
    }

    @Test
    void chunkStartingInsideLongFenceMustKeepTheFullDocumentsCodeMeaning() {
        String evidence = "A depends on B.";
        String content = evidence + "\n\n```text\n" + "filler; ".repeat(160)
                + "**A** depends on B.\n" + "ending; ".repeat(160) + "\n```";
        rows(relation("a", "prerequisite", "b", evidence, "笔记#1"));
        docs("note", source(1, "长代码段", content));

        List<RetrievalHit> hits = service.search("A B", 6);

        assertEquals(1, hits.size());
        assertEquals(0, hits.getFirst().seq());
        assertFalse(hits.getFirst().text().contains("**A**"));
        assertTrue(hits.getFirst().text().contains(evidence));
    }

    @Test
    void actualEvidenceInsideLaterCodeChunkStillUsesItsMatchingSequence() {
        String evidence = "List<String> values = new ArrayList<>();";
        String content = "```java\n" + "filler; ".repeat(160) + evidence + "\n"
                + "ending; ".repeat(160) + "\n```";
        rows(relation("a", "used_for", "b", "`" + evidence + "`", "笔记#1"));
        docs("note", source(1, "后续代码块", content));

        List<RetrievalHit> hits = service.search("A B", 6);

        assertFalse(hits.isEmpty());
        for (RetrievalHit hit : hits) {
            assertTrue(hit.seq() > 0);
            assertEquals(TextChunker.splitWithHeadings(content).get(hit.seq()).text(), hit.text());
            assertTrue(hit.text().contains(evidence));
        }
    }

    @Test
    void identicalRawPassagesWithDifferentMarkdownContextsUseVerifiedWindow() {
        String content = "# 正文\n**A** depends on B.\n# 代码\n```text\n"
                + "filler; ".repeat(160) + "**A** depends on B.\n" + "ending; ".repeat(160) + "\n```";
        rows(relation("a", "prerequisite", "b", "A depends on B.", "笔记#1"));
        docs("note", source(1, "语义位置含糊", content));

        List<RetrievalHit> hits = service.search("A B", 6);

        assertEquals(1, hits.size());
        assertEquals(-1, hits.getFirst().seq());
        assertTrue(hits.getFirst().text().contains("# 正文\n**A** depends on B."));
        assertTrue(content.contains(hits.getFirst().text()));
    }

    @Test
    void evidenceThatSpansChunkBoundariesUsesAnExactCurrentSourceWindow() {
        String evidence = "证".repeat(TextChunker.MAX + 100);
        String content = "引".repeat(400) + evidence + "尾".repeat(400);
        rows(relation("a", "prerequisite", "b", evidence, "资料#3"));
        docs("file", source(3, "资料", content));

        RetrievalHit hit = service.search("A B", 6).getFirst();

        assertEquals(-1, hit.seq());
        assertTrue(hit.text().contains(evidence));
        assertTrue(content.contains(hit.text()));
        assertTrue(hit.text().length() < content.length());
    }

    @Test
    void eachSourceMustIndependentlyContainTheEvidence() {
        rows(relation("a", "prerequisite", "b", "A 依赖 B。", "笔记#1|速查卡#2|资料#3"));
        docs("note", source(1, "笔记", "A 只是相关。"));
        docs("quick_ref", source(2, "速查", "A 依赖 B。"));
        docs("file", source(3, "资料", "已修改。"));

        List<RetrievalHit> hits = service.search("A B", 6);

        assertEquals(1, hits.size());
        assertEquals("quick_ref", hits.getFirst().sourceType());
        assertEquals(2L, hits.getFirst().sourceId());
    }

    @Test
    void duplicateReferencesAndSymmetricRelationsDoNotDuplicateHitsOrLabels() {
        Map<String, Object> first = relation("a", "contrast_with", "b", "A 与 B 不同。", "笔记#1|笔记#1");
        Map<String, Object> second = relation("b", "contrast_with", "a", "A 与 B 不同。", "note#1");
        rows(first, first, second, relation("a", "related_to", "b", "A 与 B 不同。", "笔记#1"));
        docs("note", source(1, "标题", "A 与 B 不同。"));

        List<RetrievalHit> hits = service.search("A B", 6);

        assertEquals(1, hits.size());
        assertEquals(List.of("a —易混→ b", "a —相关→ b"), hits.getFirst().graphRelations());
        verify(sources).currentSources("note", List.of(1L));
    }

    @Test
    void bridgeOutranksHeavierOneSidedNeighbourAfterEvidenceVerification() {
        Map<String, Object> noisy = relation("a", "related_to", "x", "旁支证据。", "笔记#1");
        noisy.put("weight", 1);
        Map<String, Object> bridge = relation("a", "prerequisite", "b", "A 依赖 B。", "笔记#2");
        bridge.put("weight", .1);
        rows(noisy, bridge);
        docs("note", source(1, "旁支", "旁支证据。"), source(2, "桥接", "A 依赖 B。"));

        List<RetrievalHit> hits = service.search("A B", 1);

        assertEquals(1, hits.size());
        assertEquals(2L, hits.getFirst().sourceId());
    }

    @Test
    void capsRelationAndSourceCandidatesEvenIfMapperReturnsTooMany() {
        List<Map<String, Object>> all = new ArrayList<>();
        for (int i = 1; i <= 100; i++) all.add(relation("a", "related_to", "n" + i, "证据。", "笔记#" + i));
        when(relations.relationsAround(anyList())).thenReturn(all);
        when(sources.currentSources(eq("note"), anyList())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(1);
            assertEquals(24, ids.size());
            assertTrue(ids.stream().allMatch(id -> id <= 60));
            return ids.stream().map(id -> source(id, "标题", "证据。")).toList();
        });

        assertEquals(24, service.search("A B", Integer.MAX_VALUE).size());
        verify(graph).recognize("A B", 12);
        verify(sources, times(1)).currentSources(eq("note"), anyList());
    }

    @Test
    void sourceQueriesReadWholeCurrentTextAndUseTheIndexFileComposition() throws Exception {
        String script = String.join("", GraphEvidenceSourceMapper.class.getMethod("currentSources", String.class,
                List.class).getAnnotation(Select.class).value());
        XMLLanguageDriver driver = new XMLLanguageDriver();
        for (String type : List.of("note", "quick_ref", "file")) {
            BoundSql sql = driver.createSqlSource(new Configuration(), script, Map.class)
                    .getBoundSql(Map.of("type", type, "ids", List.of(1L, 2L)));
            assertTrue(sql.getSql().contains("FROM " + (type.equals("file") ? "file_info" : type)));
            assertFalse(sql.getSql().contains("LEFT("));
            assertFalse(sql.getSql().contains("vec"));
            assertEquals(2, sql.getParameterMappings().size());
            if (type.equals("file")) {
                String allFilesSql = String.join("", KbChunkMapper.class.getMethod("allFiles")
                        .getAnnotation(Select.class).value());
                String indexedContent = allFilesSql.substring(allFilesSql.indexOf("CONCAT("),
                        allFilesSql.indexOf(" AS content"));
                assertTrue(sql.getSql().contains(indexedContent));
                assertTrue(sql.getSql().contains("f.text_status = 'ok'"));
            }
        }
    }

    @SafeVarargs
    private void rows(Map<String, Object>... rows) {
        when(relations.relationsAround(anyList())).thenReturn(List.of(rows));
    }

    @SafeVarargs
    private void docs(String type, Map<String, Object>... rows) {
        when(sources.currentSources(eq(type), anyList())).thenReturn(List.of(rows));
    }

    private static KgNode node(String id) {
        KgNode node = new KgNode(); node.setId(id); node.setName(id); return node;
    }

    private static Map<String, Object> source(long id, String title, String content) {
        return Map.of("id", id, "title", title, "content", content, "category", "测试分类");
    }

    private static Map<String, Object> relation(String head, String kind, String tail, String evidence, String refs) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("headId", head); row.put("headName", head); row.put("tailId", tail); row.put("tailName", tail);
        row.put("relation", kind); row.put("evidence", evidence); row.put("sources", refs);
        row.put("origin", "llm"); row.put("weight", .7);
        return row;
    }
}
