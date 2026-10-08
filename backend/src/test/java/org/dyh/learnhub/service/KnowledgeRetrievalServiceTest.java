package org.dyh.learnhub.service;

import org.dyh.learnhub.ai.DeepSeekClient;
import org.dyh.learnhub.ai.ModelRouting;
import org.dyh.learnhub.mapper.KbChunkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeRetrievalServiceTest {
    private KbChunkMapper sources;
    private VectorIndexService vectors;
    private GraphEvidenceService graph;
    private RerankService reranker;
    private SettingsService settings;
    private ModelRouting routing;
    private DeepSeekClient client;
    private WikiRetrievalService wiki;
    private KnowledgeRetrievalService service;

    @BeforeEach
    void setup() {
        sources = mock(KbChunkMapper.class);
        vectors = mock(VectorIndexService.class);
        graph = mock(GraphEvidenceService.class);
        reranker = mock(RerankService.class);
        settings = mock(SettingsService.class);
        routing = mock(ModelRouting.class);
        client = mock(DeepSeekClient.class);
        wiki = mock(WikiRetrievalService.class);
        service = new KnowledgeRetrievalService(sources, vectors, graph, reranker, settings, routing, client, wiki);
        when(settings.vectorEnabled()).thenReturn(true);
        when(settings.kgInjectEnabled()).thenReturn(true);
    }

    @Test
    void fusedSearchAddsGraphOnlyPassagesAndMergesChannelsForTheSamePassage() {
        when(sources.allNotes()).thenReturn(List.of(source(1, "ReAct", "ReAct 以循环执行工具。"),
                source(2, "记忆", "受控记忆保存推理过程中观察到的信息。")));
        when(vectors.search("ReAct", 24)).thenReturn(List.of(
                new VectorIndexService.Hit("note", 1L, "old title", "", "ReAct 以循环执行工具。", .8, 0)));
        when(graph.search("ReAct", 24)).thenReturn(List.of(
                hit(1, 0, "ReAct 以循环执行工具。", "graph", "ReAct → Agent 循环"),
                hit(2, 0, "受控记忆保存推理过程中观察到的信息。", "graph", "ReAct → 记忆")));
        List<RetrievalHit> hits = service.search("ReAct", 10);
        assertEquals(2, hits.size());
        assertEquals(Set.of("keyword", "vector", "graph"), Set.copyOf(hits.getFirst().channels()));
        assertEquals("ReAct", hits.getFirst().title(), "current title replaces stale redundant vector metadata");
        assertEquals(List.of("graph"), hits.get(1).channels());
        assertEquals(List.of("ReAct → 记忆"), hits.get(1).graphRelations());
        verify(reranker, times(1)).rerank(eq("ReAct"), anyList());
        verifyNoInteractions(client, routing);
    }

    @Test
    void keywordBaselineWorksWithoutAnIndexAndDoesNotUseGraphRewriteOrReranking() {
        when(sources.allNotes()).thenReturn(List.of(source(1, "笔记", "ReAct 是一种循环。")));
        when(settings.queryRewriteEnabled()).thenReturn(true);
        List<RetrievalHit> hits = service.search("ReAct", 5, "keyword", true);
        assertEquals(1, hits.size());
        assertEquals(List.of("keyword"), hits.getFirst().channels());
        assertEquals(0, hits.getFirst().seq());
        verifyNoInteractions(vectors, graph, reranker, routing, client);
    }

    @Test
    void vectorFailureKeepsCurrentKeywordEvidence() {
        when(sources.allFiles()).thenReturn(List.of(source(8, "容器教程", "Docker 使用容器隔离程序。")));
        when(vectors.search(anyString(), anyInt())).thenThrow(new IllegalStateException("offline"));
        List<RetrievalHit> hits = service.search("Docker", 5, "fused", false);
        assertEquals("file", hits.getFirst().sourceType());
        assertEquals(List.of("keyword"), hits.getFirst().channels());
        verifyNoInteractions(graph);
    }

    @Test
    void changedOrDeletedVectorPassagesAreNotFusedWithCurrentChunks() {
        when(sources.allNotes()).thenReturn(List.of(source(1, "ReAct", "ReAct 当前正确的内容。")));
        when(vectors.search("ReAct", 24)).thenReturn(List.of(
                new VectorIndexService.Hit("note", 1L, "ReAct", "", "已删除的错误内容。", .99, 0),
                new VectorIndexService.Hit("note", 2L, "ReAct", "", "已删除整篇来源。", .98, 0)));
        List<RetrievalHit> fused = service.search("ReAct", 5, "fused", false);
        assertEquals(1, fused.size());
        assertEquals(List.of("keyword"), fused.getFirst().channels());
        assertFalse(fused.getFirst().text().contains("已删除"));
        assertTrue(service.search("ReAct", 5, "vector", true).isEmpty());
        verifyNoInteractions(graph);
    }

    @Test
    void successfulSemanticMatchDoesNotTriggerARewriteOnKeywordMiss() {
        when(sources.allNotes()).thenReturn(List.of(source(3, "版本控制", "git reset 撤销暂存。")));
        when(settings.queryRewriteEnabled()).thenReturn(true);
        when(vectors.search("退回改动", 24)).thenReturn(List.of(
                new VectorIndexService.Hit("note", 3L, "版本控制", "", "git reset 撤销暂存。", .82, 0)));
        assertEquals(List.of("vector"), service.search("退回改动", 5, "fused", false).getFirst().channels());
        verifyNoInteractions(client, routing);
    }

    @Test
    void rrfVotesOncePerRouteAndKeepsDifferentPassagesFromTheSameDocument() {
        RetrievalHit shared = hit(1, 0, "共同段落", "keyword", "");
        RetrievalHit second = hit(1, 1, "另一段", "keyword", "");
        RetrievalHit other = hit(2, 0, "单路高分", "vector", "");
        List<RetrievalHit> fused = KnowledgeRetrievalService.fuse(List.of(
                List.of(shared, shared, second),
                List.of(other, hit(1, 0, "共同段落", "vector", "")),
                List.of(hit(1, 0, "共同段落", "graph", "关系一"), hit(1, 0, "共同段落", "graph", "关系二"))));
        assertEquals(3, fused.size());
        assertEquals(shared.key(), fused.getFirst().key());
        assertEquals(1.0 / 61 + 1.0 / 62 + 1.0 / 61, fused.getFirst().score(), 1e-12);
        assertEquals(List.of("关系一", "关系二"), fused.getFirst().graphRelations());
        assertTrue(fused.stream().anyMatch(h -> h.seq() == 1));
    }

    @Test
    void partialInvalidRerankerOrderPreservesUnmentionedEvidenceExactlyOnce() {
        List<RetrievalHit> hits = List.of(hit(1, 0, "A", "keyword", ""), hit(1, 1, "B", "vector", ""), hit(2, 0, "C", "graph", ""));
        List<RetrievalHit> reordered = KnowledgeRetrievalService.reordered(hits,
                List.of(hits.get(1).key(), hits.get(1).key(), "not-a-candidate"));
        assertEquals(List.of(hits.get(1), hits.get(0), hits.get(2)), reordered);
    }

    @Test
    void crossChunkWindowsHaveDifferentStablePassageKeys() {
        RetrievalHit a = hit(1, -1, "跨块证据甲", "graph", "");
        RetrievalHit b = hit(1, -1, "跨块证据乙", "graph", "");
        assertNotEquals(a.key(), b.key());
        assertEquals(a.key(), hit(1, -1, "跨块证据甲", "graph", "").key());
        assertEquals(2, KnowledgeRetrievalService.fuse(List.of(List.of(a, b))).size());
    }

    @Test
    void differentTextAtTheSameSeqCannotInheritAnotherVersionsGraphRelation() {
        List<RetrievalHit> hits = KnowledgeRetrievalService.fuse(List.of(
                List.of(hit(1, 0, "当前段落", "keyword", "")),
                List.of(hit(1, 0, "不同版本的段落", "graph", "不能转移的关系"))));
        assertEquals(List.of("keyword"), hits.getFirst().channels());
        assertTrue(hits.getFirst().graphRelations().isEmpty());
    }

    @Test
    void aLongSourceCannotFillTheEntireFusionPoolBeforeSourceDiversityIsApplied() {
        List<RetrievalHit> many = java.util.stream.IntStream.range(0, 24)
                .mapToObj(i -> hit(1, i, "长文第" + i + "段", "vector", "")).toList();
        RetrievalHit shortSource = hit(2, 0, "另一个来源的有用证据", "keyword", "");
        List<RetrievalHit> hits = KnowledgeRetrievalService.fuse(List.of(List.of(shortSource), many, many));
        assertEquals(20, hits.size(), "the fused pool must fit one listwise batch for a global order");
        assertTrue(hits.stream().anyMatch(h -> h.sourceId() == 2L));
    }

    @Test
    void sectionHeadingCanFindItsBodyEvenWhenTheTermIsMissingFromTheBody() {
        when(sources.allNotes()).thenReturn(List.of(source(1, "学习笔记", "## ReAct\n交替观察和行动。")));
        RetrievalHit hit = service.search("ReAct", 5, "keyword", false).getFirst();
        assertEquals("交替观察和行动。", hit.text());
        assertEquals(0, hit.seq());
    }

    @Test
    void verifiedGraphRelationsAreVisibleToTheShortExcerptReranker() {
        String body = "说明段落。".repeat(80) + "ReAct 与 Planning 配合执行任务。";
        when(graph.search("ReAct", 24)).thenReturn(List.of(
                hit(1, 0, body, "graph", "ReAct —用于→ Planning")));
        RetrievalHit result = service.search("ReAct", 5).getFirst();
        verify(reranker).rerank(eq("ReAct"), argThat(items -> items.size() == 1
                && items.getFirst().snippet().startsWith("原文支持的关联：ReAct —用于→ Planning")
                && items.getFirst().snippet().endsWith(body)));
        assertEquals(body, result.text(), "rerank metadata does not alter the source excerpt");
    }

    @Test
    void relationQuestionDoesNotRecallUnrelatedSourcesThroughQuestionBoilerplate() {
        when(sources.allNotes()).thenReturn(List.of(
                source(1, "智能体", "ReAct 交替进行推理和行动。Planning 制定步骤。"),
                source(2, "Java", "强引用与弱引用的关系以及对应关系。"),
                source(3, "数据库", "关系数据库使用表表示数据。")));
        List<RetrievalHit> hits = service.search("ReAct 和 Planning 有什么关系", 10, "keyword", false);
        assertEquals(List.of(1L), hits.stream().map(RetrievalHit::sourceId).toList());
        assertEquals(3L, service.search("关系数据库", 10, "keyword", false).getFirst().sourceId(),
                "relation remains searchable as part of a real subject");
    }

    @Test
    void emptyQueriesDoNotReadSourcesOrCallModelsAndModesAreValidated() {
        assertTrue(service.search("  ", 10).isEmpty());
        assertTrue(service.search(null, 10).isEmpty());
        assertTrue(service.search("ReAct", 0).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> service.search("ReAct", 5, "unknown", true));
        verifyNoInteractions(sources, graph, vectors, reranker, client, routing);
    }

    @Test void wikiLocatesAnOriginalPassageMissingFromKeywordAndSharesOneRerank() {
        String q = "ReAct";
        var section = section("循环机制", "交替执行推理、行动与观察。[笔记#2]");
        when(sources.allNotes()).thenReturn(List.of(source(2, "原文", "通过反馈循环调用工具。")));
        when(wiki.search(q, 4)).thenReturn(List.of(section));
        when(wiki.sourcePassages(q, List.of(section), 24)).thenReturn(List.of(
                hit(2, 0, "通过反馈循环调用工具。", "wiki", "")));
        when(reranker.rerank(eq(q), anyList())).thenReturn(List.of(section.key(), "note:2:0"));

        var result = service.searchWithWiki(q, 5, "fused", false, true);

        assertEquals(List.of("note:2"), result.passages().stream().map(RetrievalHit::sourceRef).toList());
        assertEquals(List.of("wiki"), result.passages().getFirst().channels());
        assertEquals(List.of(section), result.wikiSections());
        verify(reranker, times(1)).rerank(eq(q), argThat(items -> items.size() == 2
                && items.stream().anyMatch(item -> item.key().equals(section.key())
                && item.snippet().contains("事实需回查原文"))));
    }

    @Test void wikiAndKeywordHitsDoNotDuplicateTheOriginalOrItsReference() {
        var section = section("定义", "ReAct 使用反馈。[笔记#1]");
        when(sources.allNotes()).thenReturn(List.of(source(1, "ReAct", "ReAct 使用反馈。")));
        when(wiki.search("ReAct", 4)).thenReturn(List.of(section));
        var original = hit(1, 0, "ReAct 使用反馈。", "wiki", "");
        when(wiki.sourcePassages("ReAct", List.of(section), 24)).thenReturn(List.of(original, original));
        var result = service.searchWithWiki("ReAct", 5, "fused", false, true);
        assertEquals(1, result.passages().size());
        assertEquals(Set.of("keyword", "wiki"), Set.copyOf(result.passages().getFirst().channels()));
        assertEquals(1.0 / 61 + 1.0 / 61, result.passages().getFirst().score(), 1e-12);
    }

    @Test void wikiSourceProjectionCannotInjectDeletedOrConcurrentlyChangedOriginals() {
        var section = section("定义", "ReAct 旧说明。[笔记#1]");
        when(sources.allNotes()).thenReturn(List.of(source(1, "原文", "当前的新正文。")));
        when(wiki.search("ReAct", 4)).thenReturn(List.of(section));
        when(wiki.sourcePassages("ReAct", List.of(section), 24)).thenReturn(List.of(
                hit(1, 0, "已经修改的旧正文。", "wiki", ""), hit(2, 0, "已删除的来源。", "wiki", "")));
        var result = service.searchWithWiki("ReAct", 5, "fused", false, true);
        assertTrue(result.passages().isEmpty());
        assertEquals(List.of(section), result.wikiSections(), "a guide remains explicitly generated, not source evidence");
    }

    @Test void wikiIsOptInAndFailureLeavesTheOtherChannelsAvailable() {
        when(sources.allNotes()).thenReturn(List.of(source(1, "ReAct", "ReAct 当前说明。")));
        assertEquals(1, service.search("ReAct", 5, "fused", false).size());
        verifyNoInteractions(wiki);
        when(wiki.search("ReAct", 4)).thenThrow(new IllegalStateException("unavailable"));
        var result = service.searchWithWiki("ReAct", 5, "fused", false, true);
        assertEquals(1, result.passages().size());
        assertTrue(result.wikiSections().isEmpty());
        verify(wiki, never()).sourcePassages(anyString(), anyList(), anyInt());
    }

    private static WikiRetrievalService.Section section(String heading, String text) {
        return new WikiRetrievalService.Section(10L, "entity-react", "ReAct", "section-1", heading,
                text, List.of("note:1", "note:2"), List.of("Planning"), 10);
    }

    private static Map<String, Object> source(long id, String title, String body) {
        return Map.of("id", id, "title", title, "content", body, "category", "学习");
    }

    private static RetrievalHit hit(long id, int seq, String body, String channel, String relation) {
        return new RetrievalHit("note", id, "标题", "", body, .9, seq,
                List.of(channel), relation.isEmpty() ? List.of() : List.of(relation));
    }
}
