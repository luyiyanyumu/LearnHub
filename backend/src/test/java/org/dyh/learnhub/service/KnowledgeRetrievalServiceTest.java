package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    @Test void detailedQuestionUsesGroundingForMissingEvidenceEvenWhenFirstRoundHasHits() throws Exception {
        String query = "如何注册 Callback，并说明代码示例";
        when(sources.allNotes()).thenReturn(List.of(
                source(1, "概念", "Callback 是回调机制。"),
                source(2, "实践", "用 attachHandler 安装处理函数。")));
        when(settings.queryRewriteEnabled()).thenReturn(true);
        when(routing.forTask(ModelRouting.TASK_REWRITE)).thenReturn(
                new ModelRouting.ModelTarget("rewrite", "rewrite", "http://rewrite", "", "rewrite-model", false));
        when(routing.forTask(ModelRouting.TASK_GROUNDING)).thenReturn(
                new ModelRouting.ModelTarget("judge", "judge", "http://grounding", "", "grounding-model", false));
        when(client.chat(anyList(), isNull(), anyString(), anyString(), anyString(), anyInt(), anyDouble(),
                anyString(), isNull(), any())).thenReturn(new ObjectMapper().readTree(
                "{\"content\":\"{\\\"missing\\\":[\\\"注册示例\\\"],\\\"queries\\\":[\\\"attachHandler\\\"]}\"}"));

        List<RetrievalHit> hits = service.search(query, 5, "fused", false);

        assertTrue(hits.stream().anyMatch(hit -> hit.sourceId() == 2L && hit.channels().contains("supplemental")));
        verify(client, times(1)).chat(anyList(), isNull(), eq("http://grounding"), anyString(), eq("grounding-model"), anyInt(), anyDouble(),
                anyString(), isNull(), any());
        verify(routing, times(1)).forTask(ModelRouting.TASK_GROUNDING);
        verify(routing, never()).forTask(ModelRouting.TASK_REWRITE);
        verify(vectors).search("attachHandler", 24);
        verify(reranker, times(1)).rerank(eq(query), anyList());
    }

    @Test void sufficientEvidenceDoesNotLaunchSupplementalSearchAndPlanningFailureKeepsOriginalHits() throws Exception {
        String query = "如何使用 Callback 注册回调代码";
        when(sources.allNotes()).thenReturn(List.of(source(1, "实践", "Callback 提供 register(fn) 注册方法。")));
        when(settings.queryRewriteEnabled()).thenReturn(true);
        when(routing.forTask(ModelRouting.TASK_GROUNDING)).thenReturn(
                new ModelRouting.ModelTarget("test", "test", "http://local", "", "test-model", false));
        when(client.chat(anyList(), isNull(), anyString(), anyString(), anyString(), anyInt(), anyDouble(),
                anyString(), isNull(), any())).thenReturn(new ObjectMapper().readTree(
                "{\"content\":\"{\\\"missing\\\":[],\\\"queries\\\":[]}\"}"));
        assertEquals(1, service.search(query, 5, "fused", false).size());
        verify(vectors, times(1)).search(anyString(), eq(24));
        when(client.chat(anyList(), isNull(), anyString(), anyString(), anyString(), anyInt(), anyDouble(),
                anyString(), isNull(), any())).thenThrow(new IllegalStateException("offline"));
        assertEquals(1, service.search(query, 5, "fused", false).size());
    }

    @Test void emptyEvidenceUsesSimpleRewriteWithOneCallAndAtMostTwoQueries() throws Exception {
        String query = "如何处理 TopicA 和 TopicB 的关系";
        when(settings.queryRewriteEnabled()).thenReturn(true);
        when(routing.forTask(ModelRouting.TASK_REWRITE)).thenReturn(
                new ModelRouting.ModelTarget("test", "test", "http://local", "", "test-model", false));
        when(client.chat(anyList(), isNull(), anyString(), anyString(), anyString(), anyInt(), anyDouble(),
                anyString(), isNull(), any())).thenReturn(new ObjectMapper().createObjectNode().put("content", "Alpha\nBeta\nGamma"));
        service.search(query, 5, "fused", false);
        verify(client, times(1)).chat(anyList(), isNull(), anyString(), anyString(), anyString(), anyInt(), anyDouble(),
                anyString(), isNull(), any());
        verify(vectors).search("Alpha", 24);
        verify(vectors).search("Beta", 24);
        verify(vectors, never()).search("Gamma", 24);
        verify(routing, times(1)).forTask(ModelRouting.TASK_REWRITE);
        verify(routing, never()).forTask(ModelRouting.TASK_GROUNDING);
    }

    @Test void supplementalOnlyEvidenceGetsARerankSlotEvenWhenInitialRoutesFillThePool() throws Exception {
        String query = "TopicA 如何执行代码示例";
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        List<VectorIndexService.Hit> vectorHits = new java.util.ArrayList<>();
        for (long id = 1; id <= 20; id++) {
            String body = "TopicA 常见定义，第" + id + "种说明。";
            rows.add(source(id, "TopicA", body));
            vectorHits.add(new VectorIndexService.Hit("note", id, "TopicA", "", body, .9, 0));
        }
        rows.add(source(99, "运行实现", "HiddenHandler 提供处理函数调用示例。"));
        when(sources.allNotes()).thenReturn(rows);
        when(vectors.search(query, 24)).thenReturn(vectorHits);
        when(settings.queryRewriteEnabled()).thenReturn(true);
        when(routing.forTask(ModelRouting.TASK_GROUNDING)).thenReturn(
                new ModelRouting.ModelTarget("test", "test", "http://local", "", "test-model", false));
        when(client.chat(anyList(), isNull(), anyString(), anyString(), anyString(), anyInt(), anyDouble(),
                anyString(), isNull(), any())).thenReturn(new ObjectMapper().readTree(
                "{\"content\":\"{\\\"missing\\\":[\\\"运行示例\\\"],\\\"queries\\\":[\\\"HiddenHandler\\\"]}\"}"));
        when(reranker.rerank(eq(query), anyList())).thenReturn(List.of("note:99:0"));

        List<RetrievalHit> hits = service.search(query, 5, "fused", false);

        verify(reranker).rerank(eq(query), argThat(items -> items.size() <= 20
                && items.stream().anyMatch(item -> item.key().equals("note:99:0"))));
        assertEquals("note:99:0", hits.getFirst().key(), "the model, rather than a forced score, chooses relevance");
    }

    @Test void finalRerankCanKeepThreeNecessarySectionsFromOneSource() {
        String body = "## Topic 定义\nTopic 定义。\n## Topic 用法\nTopic 用法。\n## Topic 示例\nTopic 示例。";
        when(sources.allNotes()).thenReturn(List.of(source(1, "Topic", body),
                source(2, "Topic 其他", "Topic 其他内容。")));
        when(reranker.rerank(eq("Topic"), anyList())).thenReturn(List.of("note:1:0", "note:1:1", "note:1:2", "note:2:0"));

        List<RetrievalHit> hits = service.search("Topic", 3, "fused", false);

        assertEquals(List.of("note:1:0", "note:1:1", "note:1:2"), hits.stream().map(RetrievalHit::key).toList());
    }

    @Test void reservedSupplementalCandidatePreservesFusedGraphProvenance() {
        List<RetrievalHit> initial = new java.util.ArrayList<>();
        for (long id = 1; id <= 20; id++) initial.add(hit(id, 0, "来源正文" + id, "keyword", ""));
        RetrievalHit merged = new RetrievalHit("note", 20L, "关系说明", "", "完整支持关联的正文", .2, 0,
                List.of("keyword", "vector", "graph", "supplemental"), List.of("ConceptA —用于→ ConceptB"));
        initial.set(19, merged);
        RetrievalHit supplemental = new RetrievalHit("note", 20L, "关系说明", "", merged.text(), .1, 0,
                List.of("keyword", "supplemental"), List.of());

        List<RetrievalHit> pool = KnowledgeRetrievalService.rerankPool(initial, List.of(supplemental), 16);

        RetrievalHit reserved = pool.stream().filter(hit -> hit.key().equals(merged.key())).findFirst().orElseThrow();
        assertEquals(16, pool.size());
        assertEquals(merged.channels(), reserved.channels());
        assertEquals(merged.graphRelations(), reserved.graphRelations(),
                "a reserved slot must not turn a verified graph passage into truncatable plain text");
        assertEquals(merged.text(), reserved.text());
    }

    @Test void reservedSameSeqFromAnotherVersionCannotInheritGraphProvenance() {
        List<RetrievalHit> initial = new java.util.ArrayList<>();
        for (long id = 1; id <= 20; id++) initial.add(hit(id, 0, "来源正文" + id, "keyword", ""));
        initial.set(19, hit(20, 0, "有图谱支持的当前版本", "graph", "ConceptA —用于→ ConceptB"));
        RetrievalHit conflicting = hit(20, 0, "同seq的另一个版本", "supplemental", "");

        List<RetrievalHit> pool = KnowledgeRetrievalService.rerankPool(initial, List.of(conflicting), 16);

        assertFalse(pool.stream().anyMatch(hit -> hit.key().equals(conflicting.key())),
                "a same-key version conflict must not replace or borrow the fused evidence");
    }

    @Test void rerankCandidatesCarryCurrentHeadingWithoutAlteringSourceText() {
        when(sources.allNotes()).thenReturn(List.of(source(1, "API 手册", "## Topic 注册\n调用 register(fn)。")));
        RetrievalHit found = service.search("Topic", 5, "fused", false).getFirst();
        verify(reranker).rerank(eq("Topic"), argThat(items -> items.getFirst().title().equals("API 手册")
                && items.getFirst().snippet().equals("小节：Topic 注册\n调用 register(fn)。")));
        assertEquals("调用 register(fn)。", found.text());
    }

    @Test void sectionNeighborsAreFreshAndDoNotCrossHeadingOrCopyGraphRelations() {
        String body = "## Topic\n" + "定义说明。".repeat(110) + "\n\n"
                + "补充说明。".repeat(110) + "\n\n"
                + "register(fn) 示例。".repeat(70) + "\n## Different\n不同主题。";
        when(sources.allNotes()).thenReturn(List.of(source(1, "手册", body)));
        List<TextChunker.Chunk> chunks = TextChunker.splitWithHeadings(body);
        assertTrue(chunks.size() >= 4);
        RetrievalHit first = hit(1, 0, chunks.getFirst().text(), "keyword", "");
        var neighbors = service.relatedSection(first, 2);
        assertEquals("Topic", neighbors.heading());
        assertEquals(List.of(1), neighbors.passages().stream().map(RetrievalHit::seq).toList());
        assertEquals(List.of("section-neighbor"), neighbors.passages().getFirst().channels());
        assertTrue(service.relatedSection(hit(1, 0, "旧正文", "vector", ""), 2).passages().isEmpty());
        assertTrue(service.relatedSection(hit(1, 0, chunks.getFirst().text(), "graph", "verified relation"), 2).passages().isEmpty());
        int lastTopic = java.util.stream.IntStream.range(0, chunks.size())
                .filter(i -> chunks.get(i).heading().equals("Topic")).max().orElseThrow();
        var edge = service.relatedSection(hit(1, lastTopic, chunks.get(lastTopic).text(), "keyword", ""), 2);
        assertTrue(edge.passages().stream().allMatch(h -> h.seq() < lastTopic));
    }

    @Test void batchNeighborsReadSourcesOnceAndExcludeAlreadySelectedSeeds() {
        String body = "## Topic\n" + "定义说明。".repeat(110) + "\n\n" + "补充说明。".repeat(110);
        when(sources.allNotes()).thenReturn(List.of(source(1, "手册", body)));
        List<TextChunker.Chunk> chunks = TextChunker.splitWithHeadings(body);
        List<RetrievalHit> seeds = List.of(hit(1, 0, chunks.get(0).text(), "keyword", ""),
                hit(1, 1, chunks.get(1).text(), "keyword", ""));
        var neighbors = service.relatedSections(seeds, 2);
        assertTrue(neighbors.values().stream().allMatch(group -> group.passages().isEmpty()));
        verify(sources, times(1)).allNotes();
        verify(sources, times(1)).allFiles();
        verify(sources, times(1)).allRefs();
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
