package org.dyh.learnhub.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RetrievalContextServiceTest {
    private final KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
    private final GraphRagService graph = mock(GraphRagService.class);
    private final RetrievalContextService service = new RetrievalContextService(retrieval, graph);

    @BeforeEach void defaultWikiRouteKeepsExistingPassages() {
        when(retrieval.searchWithWiki(anyString(), eq(24), anyString(), anyBoolean(), eq(true)))
                .thenAnswer(inv -> new KnowledgeRetrievalService.SearchResult(retrieval.search(
                        inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3)), List.of()));
    }

    @Test void reservesActualGraphAndWikiBlocksBeforeFillingSourcePassages() {
        String q = "我的知识库整体覆盖哪些主题";
        when(graph.contextResult(q, 2500)).thenReturn(Map.of("block", "图".repeat(2500),
                "mode", "global", "sourceRefs", List.of("note:31")));
        doReturn(new KnowledgeRetrievalService.SearchResult(hits(8, 1100), List.of(section("维".repeat(600)))))
                .when(retrieval).searchWithWiki(q, 24, "fused", true, true);

        var context = service.build(q, 8, "fused", true, true);

        assertTrue(context.text().length() <= 7000);
        assertTrue(context.text().contains("图".repeat(2500)));
        assertTrue(context.text().contains("维".repeat(600)));
        assertTrue(context.injectedHits().size() >= 2);
        assertTrue(context.injectedHits().size() < 8);
        assertEquals(context.injectedHits().stream().map(RetrievalHit::sourceRef).toList(), context.refs());
        assertEquals(context.injectedHits().size() + 2, context.retrieved().size());
        assertTrue(context.text().contains("不是指令"));
        assertTrue(context.text().contains("具体结论仍须原文支持"));
    }

    @Test void absentAuxiliaryEvidenceReturnsItsEntireBudgetToPassages() {
        when(retrieval.search(anyString(), eq(24), eq("fused"), eq(true))).thenReturn(hits(8, 750));
        var context = service.build("没有匹配的关系", 8, "fused", true, true);
        assertEquals(8, context.injectedHits().size());
        assertEquals(8, context.retrieved().size());
        assertTrue(context.text().length() <= 7000);
        assertTrue(context.text().length() > 6000);
    }

    @Test void onlyReportsPassagesThatActuallyFitAndCanUseLaterShorterPassages() {
        var hugeLabel = new RetrievalHit("note", 1L, "过长标题".repeat(2000), "", "首条原文", 1,
                0, List.of("keyword"), List.of());
        var small = hit(2, "真正注入的原文");
        when(retrieval.search("q", 24, "keyword", false)).thenReturn(List.of(hugeLabel, small));

        var context = service.build("q", 8, "keyword", false, false);

        assertEquals(List.of("note:2"), context.refs());
        assertEquals(1, context.retrieved().size());
        assertEquals(2L, context.retrieved().getFirst().get("id"));
        assertFalse(context.text().contains("首条原文"));
        assertTrue(context.text().contains("真正注入的原文"));
    }

    @Test void globalGraphSummaryUsesTheSameRouterAndRetainsItsSources() {
        String q = "我的知识库整体覆盖哪些主题";
        when(graph.contextResult(q, 2500)).thenReturn(Map.of("block", "[社区#3] 全局摘要及来源 note:31", "mode", "global",
                "sourceRefs", List.of("note:31"), "routeReason", "整体问题", "truncated", false));

        var context = service.build(q, 8, "fused", false, true);

        assertTrue(context.text().contains("全局摘要"));
        assertEquals(List.of(), context.refs(), "Graph summary refs do not pretend that raw passages were injected");
        var metadata = context.retrieved().getFirst();
        assertEquals("graph", metadata.get("type"));
        assertEquals("global", metadata.get("mode"));
        assertEquals("GraphRAG 社区摘要", metadata.get("title"));
        assertEquals(List.of("note:31"), metadata.get("sourceRefs"));
        assertEquals("[社区#3] 全局摘要及来源 note:31".length(), metadata.get("chars"));
        verify(graph).contextResult(q, 2500);
    }

    @Test void labelsAndMetadataPreserveChunkChannelsAndGraphRelations() {
        var hit = new RetrievalHit("file", 8L, "Java 原文", "语言", "证据正文", .2, 12,
                List.of("vector", "graph"), List.of("JVM —part_of→ JDK"));
        when(retrieval.search("q", 24, "fused", true)).thenReturn(List.of(hit));

        var context = service.build("q", 8, "fused", false, true);

        assertTrue(context.text().contains("来源：file:8；片段 seq=12"));
        assertTrue(context.text().contains("[资料#8]"));
        assertTrue(context.text().contains("渠道：vector / graph"));
        assertTrue(context.text().contains("图谱关联路径：JVM —part_of→ JDK"));
        assertEquals(hit.key(), context.retrieved().getFirst().get("passageKey"));
        assertEquals(hit.channels(), context.retrieved().getFirst().get("channels"));
        assertEquals(hit.graphRelations(), context.retrieved().getFirst().get("graphRelations"));
    }

    @Test void maximumPassagesAndLimitApplyToBothEvidenceAndReferences() {
        when(retrieval.search("q", 24, "vector", false)).thenReturn(hits(12, 10));
        assertEquals(8, service.build("q", 100, "vector", false, false).refs().size());
        assertEquals(2, service.build("q", 2, "vector", false, false).refs().size());
        verifyNoInteractions(graph);
    }

    @Test void auxiliaryFailureDoesNotDropAvailablePassagesOrConsumeTheirBudget() {
        String q = "知识库整体涵盖哪些方向";
        when(graph.contextResult(q, 2500)).thenThrow(new IllegalStateException("unavailable"));
        doThrow(new IllegalStateException("unavailable")).when(retrieval).searchWithWiki(q, 24, "fused", true, true);
        when(retrieval.search(q, 24, "fused", true)).thenReturn(hits(8, 750));
        var context = service.build(q, 8, "fused", true, true);
        assertEquals(8, context.refs().size());
        assertTrue(context.text().length() <= 7000);
    }

    @Test void sourceFailureDoesNotHideAvailableGraphEvidence() {
        String q = "知识库整体涵盖哪些方向";
        when(graph.contextResult(q, 2500)).thenReturn(Map.of("mode", "global", "block", "已有社区摘要"));
        when(retrieval.search(q, 24, "fused", true)).thenThrow(new IllegalStateException("unavailable"));
        var context = service.build(q, 8, "fused", false, true);
        assertTrue(context.text().contains("已有社区摘要"));
        assertTrue(context.refs().isEmpty());
    }

    @Test void concurrentModesAndFlagsStayLocalToEachCall() throws Exception {
        CountDownLatch bothEntered = new CountDownLatch(2);
        when(retrieval.search(anyString(), eq(24), anyString(), anyBoolean())).thenAnswer(inv -> {
            bothEntered.countDown();
            assertTrue(bothEntered.await(3, TimeUnit.SECONDS));
            String mode = inv.getArgument(2);
            return List.of(hit("keyword".equals(mode) ? 1 : 2, mode));
        });
        String graphQuestion = "知识库整体概览";
        when(graph.contextResult(graphQuestion, 2500)).thenReturn(Map.of("mode", "global", "block", "社区摘要块"));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var keyword = executor.submit(() -> service.build("plain q", 2, "keyword", false, false));
            var vectorWithGraph = executor.submit(() -> service.build(graphQuestion, 2, "vector", false, true));
            var one = keyword.get(5, TimeUnit.SECONDS);
            var two = vectorWithGraph.get(5, TimeUnit.SECONDS);
            assertEquals(List.of("note:1"), one.refs());
            assertFalse(one.text().contains("社区摘要块"));
            assertEquals(List.of("note:2"), two.refs());
            assertTrue(two.text().contains("社区摘要块"));
        }
        verify(retrieval).search("plain q", 24, "keyword", false);
        verify(retrieval).search(graphQuestion, 24, "vector", true);
        verify(graph, never()).contextResult(eq("plain q"), anyInt());
    }

    @Test void emptyInputsAndNoHitsProduceNoEmptyShellOrClaimedReferences() {
        assertTrue(service.build(" ", 8, "fused", true, true).blocks().isEmpty());
        assertTrue(service.build("q", 0, "fused", true, true).blocks().isEmpty());
        assertTrue(service.build("q", -1, "fused", true, true).blocks().isEmpty());
        verifyNoInteractions(retrieval, graph);
        var context = service.build("q", 8, "keyword", false, false);
        assertEquals("", context.text());
        assertTrue(context.refs().isEmpty());
        assertTrue(context.retrieved().isEmpty());
    }

    @Test void crossChunkGraphWindowRetainsTheSupportingEvidenceBeyond1200Chars() {
        String body = "前文".repeat(800) + "末尾证据：JVM 是 JDK 的组成部分";
        var window = new RetrievalHit("note", 1L, "跨块关系", "", body, 1, -1,
                List.of("graph"), List.of("JVM —part_of→ JDK"));
        when(retrieval.search("q", 24, "fused", true)).thenReturn(List.of(window));
        var context = service.build("q", 8, "fused", false, true);
        assertEquals(body, context.injectedHits().getFirst().text());
        assertTrue(context.text().contains("末尾证据：JVM 是 JDK 的组成部分"));
        assertTrue(context.text().contains("JVM —part_of→ JDK"));
        assertEquals(window.key(), context.retrieved().getFirst().get("passageKey"));
    }

    @Test void graphWindowThatCannotFitIsNotReportedAsConsulted() {
        var window = new RetrievalHit("note", 1L, "过长关系窗口", "", "前文".repeat(4000) + "末尾证据", 1, -1,
                List.of("graph"), List.of("A —part_of→ B"));
        when(retrieval.search("q", 24, "fused", true)).thenReturn(List.of(window));
        var context = service.build("q", 8, "fused", false, true);
        assertTrue(context.blocks().isEmpty());
        assertTrue(context.refs().isEmpty());
        assertTrue(context.retrieved().isEmpty());
    }

    @Test void legacyLocalGraphBlockCannotInjectAQuoteMissingFromCurrentSourcePassages() {
        String q = "JVM 和 JDK 的关系";
        when(graph.contextResult(eq(q), anyInt())).thenReturn(Map.of("mode", "local",
                "block", "已经从原文删除的旧关系证据", "sourceRefs", List.of("note:9")));
        when(retrieval.search(q, 24, "fused", true)).thenReturn(List.of());

        var context = service.build(q, 8, "fused", false, true);

        assertTrue(context.text().isEmpty());
        assertTrue(context.refs().isEmpty());
        assertTrue(context.retrieved().isEmpty());
        verifyNoInteractions(graph);
    }

    @Test void generatedWikiSectionsHaveExactNavigationButNeverBecomeGroundingEvidence() {
        var section = section("生成的概念解释。[笔记#7]");
        doReturn(new KnowledgeRetrievalService.SearchResult(List.of(hit(7, "原文独立依据")), List.of(section)))
                .when(retrieval).searchWithWiki("q", 24, "fused", false, true);
        var context = service.build("q", 8, "fused", true, false);
        assertTrue(context.text().contains("生成的概念解释"));
        assertFalse(context.groundingText().contains("生成的概念解释"));
        assertTrue(context.groundingText().contains("原文独立依据"));
        assertEquals(List.of("note:7"), context.refs());
        var metadata = context.retrieved().stream().filter(m -> "wiki".equals(m.get("type"))).findFirst().orElseThrow();
        assertEquals(11L, metadata.get("id"));
        assertEquals("ReAct", metadata.get("title"));
        assertEquals("entity-react", metadata.get("topicKey"));
        assertEquals("section-1", metadata.get("sectionKey"));
        assertEquals(true, metadata.get("generatedGuide"));
        assertEquals(false, metadata.get("factVerified"));
        assertEquals(List.of("note:7"), metadata.get("sourceRefs"));
    }

    @Test void anOversizedWikiSectionIsOmittedWholeAndReturnsBudgetToOriginals() {
        doReturn(new KnowledgeRetrievalService.SearchResult(hits(8, 750), List.of(section("未完整展示".repeat(300)))))
                .when(retrieval).searchWithWiki("q", 24, "fused", false, true);
        var context = service.build("q", 8, "fused", true, false);
        assertEquals(8, context.injectedHits().size());
        assertFalse(context.text().contains("未完整展示"));
        assertFalse(context.retrieved().stream().anyMatch(m -> "wiki".equals(m.get("type"))));
        assertTrue(context.text().length() <= 7000);
    }

    @Test void aWikiOnlyContextHasNoOriginalRefsAndContainsNoGeneratedGroundingClaims() {
        doReturn(new KnowledgeRetrievalService.SearchResult(List.of(), List.of(section("仅有生成导览"))))
                .when(retrieval).searchWithWiki("q", 24, "fused", false, true);
        var context = service.build("q", 8, "fused", true, false);
        assertTrue(context.refs().isEmpty());
        assertEquals(1, context.retrieved().size());
        assertFalse(context.groundingText().contains("仅有生成导览"));
    }

    @Test void sameSectionDefinitionAndCodeShareOneEntryWithoutDisplacingOtherSeeds() {
        var seed = hit(1, "启动事件由监听器接收。");
        var code = neighbor(1, 1, "```java\napplication.addListeners(new StartupListener());\n```");
        var other = hit(2, "另一主题的独立依据");
        when(retrieval.search("启动事件如何注册", 24, "fused", false)).thenReturn(List.of(seed, other));
        when(retrieval.relatedSections(List.of(seed, other), 2)).thenReturn(Map.of(seed.key(),
                new KnowledgeRetrievalService.SectionNeighbors("启动流程 > 监听器", List.of(code))));

        var context = service.build("启动事件如何注册", 2, "fused", false, false);

        assertEquals(2, context.injectedHits().size(), "A neighbor enriches its parent, not an extra topK slot");
        assertEquals(List.of("note:1", "note:2"), context.refs());
        var window = context.injectedHits().getFirst();
        assertEquals(-1, window.seq());
        assertTrue(window.key().contains(":window:"));
        assertNotEquals(seed.key(), window.key());
        assertTrue(window.text().contains("启动事件由监听器接收"));
        assertTrue(window.text().contains("application.addListeners(new StartupListener())"));
        assertTrue(context.text().contains("同小节窗口：启动流程 > 监听器"));
        assertTrue(context.text().contains("片段 seq=[0, 1]"));
        assertTrue(context.text().contains("另一主题的独立依据"));
        var metadata = context.retrieved().getFirst();
        assertEquals(window.key(), metadata.get("passageKey"));
        assertEquals(List.of(seed.key(), code.key()), metadata.get("originalPassageKeys"));
        assertEquals(List.of(0, 1), metadata.get("expandedSeqs"));
        assertEquals("启动流程 > 监听器", metadata.get("sectionHeading"));
        assertEquals(List.of("keyword", "section-neighbor"), metadata.get("channels"));
        assertTrue(context.text().length() <= RetrievalContextService.TOTAL_CHARS);
    }

    @Test void expansionUsesFreshOriginalSeedsAndNeverIncludesAnotherSourceOrDuplicateSeed() {
        var seed = hit(1, "种子");
        var other = hit(2, "独立证据");
        var foreign = neighbor(99, 1, "其他文档的邻居不得注入");
        when(retrieval.search("q", 24, "fused", false)).thenReturn(List.of(seed, other));
        when(retrieval.relatedSections(List.of(seed, other), 2)).thenReturn(Map.of(seed.key(),
                new KnowledgeRetrievalService.SectionNeighbors("小节", List.of(foreign, other))));

        var context = service.build("q", 2, "fused", false, false);

        assertEquals(List.of("note:1", "note:2"), context.refs());
        assertFalse(context.text().contains("其他文档的邻居不得注入"));
        assertEquals(seed.key(), context.retrieved().getFirst().get("passageKey"));
        assertFalse(context.retrieved().getFirst().containsKey("expandedSeqs"));
    }

    @Test void neighborsHaveARoundRobinBudgetAndRetainExactInjectedExcerpts() {
        String q = "代码注册";
        var seeds = hits(5, 800);
        Map<String, KnowledgeRetrievalService.SectionNeighbors> related = new java.util.LinkedHashMap<>();
        for (var seed : seeds) {
            var next = neighbor(seed.sourceId(), 1, "解释".repeat(550) + " 代码注册=" + seed.sourceId());
            var prev = neighbor(seed.sourceId(), -2, "不合法的seq不得注入");
            related.put(seed.key(), new KnowledgeRetrievalService.SectionNeighbors("注册", List.of(next, prev)));
        }
        when(retrieval.search(q, 24, "fused", false)).thenReturn(seeds);
        when(retrieval.relatedSections(seeds, 2)).thenReturn(related);

        var context = service.build(q, 5, "fused", false, false);

        assertEquals(5, context.injectedHits().size());
        assertEquals(5, context.refs().size());
        assertTrue(context.injectedHits().get(0).text().contains("代码注册=1"));
        assertTrue(context.injectedHits().get(1).text().contains("代码注册=2"),
                "A second source can expand before the first receives another neighbor");
        assertFalse(context.text().contains("不合法的seq不得注入"));
        assertTrue(context.text().length() <= RetrievalContextService.TOTAL_CHARS);
        for (var hit : context.injectedHits()) assertTrue(context.text().contains(hit.text()));
        assertTrue(context.retrieved().stream().anyMatch(m -> m.containsKey("memberExcerpts")));
    }

    @Test void adjacentLookupFailurePreservesSeedEvidenceAndBudget() {
        var seed = hit(1, "已有原文不能因扩展失败而消失");
        when(retrieval.search("q", 24, "keyword", false)).thenReturn(List.of(seed));
        when(retrieval.relatedSections(List.of(seed), 2)).thenThrow(new IllegalStateException("unavailable"));

        var context = service.build("q", 1, "keyword", false, false);

        assertEquals(List.of("note:1"), context.refs());
        assertEquals(seed.key(), context.retrieved().getFirst().get("passageKey"));
        assertTrue(context.text().contains(seed.text()));
    }

    @Test void neighborWindowThatCannotFitLeavesNoClaimOfExpandedEvidence() {
        var seed = hit(1, "已经注入的定义");
        var code = neighbor(1, 1, "未展示的注册代码");
        when(retrieval.search("q", 24, "fused", false)).thenReturn(List.of(seed));
        when(retrieval.relatedSections(List.of(seed), 2)).thenReturn(Map.of(seed.key(),
                new KnowledgeRetrievalService.SectionNeighbors("过长小节标题".repeat(2000), List.of(code))));

        var context = service.build("q", 1, "fused", false, false);

        assertEquals(seed.key(), context.retrieved().getFirst().get("passageKey"));
        assertFalse(context.retrieved().getFirst().containsKey("originalPassageKeys"));
        assertFalse(context.retrieved().getFirst().containsKey("expandedSeqs"));
        assertFalse(context.text().contains(code.text()));
        assertEquals(seed.text(), context.injectedHits().getFirst().text());
        assertTrue(context.text().length() <= RetrievalContextService.TOTAL_CHARS);
    }

    @Test void oversizePassageIncludesLateQueryMatchAndLookupReceivesTheUnclippedSeed() {
        String q = "注册监听器";
        var seed = hit(1, "前面无关的说明。".repeat(220) + "\n注册监听器：application.addListeners(listener);\n");
        when(retrieval.search(q, 24, "keyword", false)).thenReturn(List.of(seed));

        var context = service.build(q, 1, "keyword", false, false);

        assertTrue(context.injectedHits().getFirst().text().contains("application.addListeners(listener)"));
        assertTrue(context.injectedHits().getFirst().text().length() <= 1200);
        verify(retrieval).relatedSections(List.of(seed), 2);
    }

    private static RetrievalHit neighbor(long id, int seq, String text) {
        return new RetrievalHit("note", id, "标题" + id, "", text, 1, seq,
                List.of("keyword", "section-neighbor"), List.of());
    }

    private static WikiRetrievalService.Section section(String text) {
        return new WikiRetrievalService.Section(11L, "entity-react", "ReAct", "section-1", "循环机制",
                text, List.of("note:7"), List.of("Planning"), 9);
    }

    private static List<RetrievalHit> hits(int count, int chars) {
        List<RetrievalHit> out = new ArrayList<>();
        for (int i = 1; i <= count; i++) out.add(hit(i, "文".repeat(chars)));
        return out;
    }

    private static RetrievalHit hit(long id, String text) {
        return new RetrievalHit("note", id, "标题" + id, "", text, 1, 0, List.of("keyword"), List.of());
    }
}
