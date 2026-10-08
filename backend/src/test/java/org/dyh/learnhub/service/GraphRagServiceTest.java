package org.dyh.learnhub.service;

import org.dyh.learnhub.ai.DeepSeekClient;
import org.dyh.learnhub.ai.ModelRouting;
import org.dyh.learnhub.entity.KgNode;
import org.dyh.learnhub.mapper.KgCommunityMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 路由、证据选择、过期摘要和预算行为；所有外部依赖均为 mock。 */
class GraphRagServiceTest {
    private KgGraphService graph;
    private KgCommunityMapper mapper;
    private KgCommunitySummaryService summaries;
    private ModelRouting routing;
    private DeepSeekClient client;
    private GraphRagService service;

    @BeforeEach
    void setup() {
        graph = mock(KgGraphService.class);
        mapper = mock(KgCommunityMapper.class);
        summaries = mock(KgCommunitySummaryService.class);
        routing = mock(ModelRouting.class);
        client = mock(DeepSeekClient.class);
        service = new GraphRagService(graph, mapper, summaries, routing, client);
    }

    @Test
    void zeroHitsDoesNotMakeSpecificQuestionsGlobal() {
        assertFalse(GraphRagService.isGlobal("火星旅游怎么订票", 0));
        assertFalse(GraphRagService.isGlobal("我对这块一无所知", 0));
        assertFalse(GraphRagService.isGlobal(null, 0));
        assertFalse(GraphRagService.isGlobal("   ", 0));
        assertFalse(GraphRagService.isGlobal("", 0));
    }

    @Test
    void corpusOverviewRoutesGlobalButSpecificSummaryStaysLocal() {
        assertTrue(GraphRagService.isGlobal("我这段时间在学的方向大致有哪些", 3));
        assertTrue(GraphRagService.isGlobal("帮我总结一下这块的脉络", 2));
        assertTrue(GraphRagService.isGlobal("我的笔记整体覆盖了哪些方面", 5));
        assertFalse(GraphRagService.isGlobal("总结 ReAct 和 CoT 的区别", 2));
        assertFalse(GraphRagService.isGlobal("Spring Boot 的自动配置怎么做的", 1));
        assertFalse(GraphRagService.isGlobal("向量方向怎么计算", 1));
        assertFalse(GraphRagService.isGlobal("总结我的笔记中 ReAct 与 CoT 的区别", 2));
        assertFalse(GraphRagService.isGlobal("我的笔记里向量方向怎么计算", 1));
        assertTrue(GraphRagService.isGlobal("我的知识库整体涵盖哪些主题与实现方向", 3));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "全部知识的总体主题",
            "全部知识库的总体主题",
            "全部笔记的总体主题",
            "全部资料的总体主题",
            "所有笔记的总体主题",
            "我的全部知识总体覆盖哪些主题",
            "全部知识的总体方向有哪些"
    })
    void explicitWholeCorpusTopicsUseGlobalSearchWithoutResidualScopeNoise(String question) {
        assertTrue(GraphRagService.isGlobal(question, 0));
        assertEquals(List.of(), GraphRagService.topicTerms(question));
        when(summaries.summaries()).thenReturn(List.of(
                summary(1, 10, "Java 内存", List.of("java")),
                summary(2, 3, "智能体工具", List.of("agent"))));

        Map<String, Object> result = service.search(question, 6, false, "auto");

        assertEquals("global", result.get("routed"));
        assertEquals("global", result.get("mode"));
        assertEquals(2, rows(result, "summaries").size());
        assertEquals(0, result.get("calls"));
        verify(graph).recognize(question, 6);
        verifyNoInteractions(mapper, client, routing);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "全部字符如何复制",
            "总体概率如何计算",
            "ReAct 的总体结构",
            "全部文件",
            "全部向量的方向",
            "全部字符的总体主题",
            "全部知识产权案例的主题",
            "全部知识工程的主题",
            "Java 的总体主题",
            "全部知识里 ReAct 与 CoT 的总体区别",
            "全部笔记中 ReAct 和 Planning 的总体关系",
            "全部资料里 JVM 与 JDK 的总体区别",
            "全部知识中的 Planning 总体如何实现"
    })
    void generalTotalWordsAndSpecificQuestionsStayLocal(String question) {
        assertFalse(GraphRagService.isGlobal(question, 0));

        Map<String, Object> result = service.search(question, 6, false, "auto");

        assertEquals("local", result.get("routed"));
        assertEquals("document_search", result.get("fallback"));
        assertEquals(0, result.get("calls"));
        verify(graph).recognize(question, 6);
        verifyNoInteractions(summaries, mapper, client, routing);
    }

    @Test
    void wholeCorpusScopeNoiseDoesNotEraseAnExplicitTopic() {
        String question = "全部笔记的 ReAct 总体主题";
        assertEquals(List.of("react"), GraphRagService.topicTerms(question));
        when(summaries.summaries()).thenReturn(List.of(
                summary(1, 10, "Java 内存", List.of("java")),
                summary(2, 3, "ReAct 的智能体工具", List.of("react"))));

        Map<String, Object> result = service.search(question, 6, false, "auto");

        assertEquals("global", result.get("routed"));
        assertEquals(1, rows(result, "summaries").size());
        assertEquals(2, rows(result, "summaries").getFirst().get("communityId"));
        verifyNoInteractions(mapper, client, routing);
    }

    @Test
    void unknownQuestionFallsBackWithoutReadingSummariesOrCallingModels() {
        Map<String, Object> result = service.search("火星旅游怎么订票", 6, true, "auto");
        assertEquals("local", result.get("mode"));
        assertEquals("document_search", result.get("fallback"));
        assertEquals(0, result.get("conceptHits"));
        assertEquals("", result.get("block"));
        verify(graph, times(1)).recognize("火星旅游怎么订票", 6);
        verifyNoInteractions(summaries, mapper, client, routing);
    }

    @Test
    void blankQuestionDoesNotReadGraphOrSummaries() {
        Map<String, Object> result = service.search("   ", 6, false, "global");
        assertEquals("请输入检索问题", result.get("emptyReason"));
        assertEquals("", result.get("block"));
        verifyNoInteractions(graph, mapper, summaries, client, routing);
    }

    @Test
    void limitsAndModesAreBoundedBeforeRecognition() {
        service.search("ReAct", Integer.MAX_VALUE, false, "local");
        verify(graph).recognize("ReAct", 12);
        assertThrows(IllegalArgumentException.class, () -> service.search("ReAct", 6, false, "unknown"));
    }

    @Test
    void localKeepsCanonicalDirectionEvidenceAndSources() {
        when(graph.recognize("JVM 与栈的关系", 6)).thenReturn(List.of(node("jvm", "JVM")));
        when(mapper.relationsAround(List.of("jvm"))).thenReturn(List.of(
                relation(1, "stack", "part_of", "jvm", "栈属于 JVM", "llm", "笔记#5|资料#2", "", .8)));
        Map<String, Object> result = service.search("JVM 与栈的关系", 6, false, "auto");
        Map<String, Object> rel = rows(result, "relations").getFirst();
        assertEquals("stack", rel.get("headId"));
        assertEquals("jvm", rel.get("tailId"));
        assertEquals(List.of("笔记#5", "资料#2"), rel.get("sources"));
        assertTrue(result.get("block").toString().contains("stack —属于→ jvm"));
        assertTrue(result.get("block").toString().contains("原文证据：栈属于 JVM"));
        assertEquals(List.of("笔记#5", "资料#2"), result.get("sourceRefs"));
        verifyNoInteractions(summaries, routing, client);
    }

    @Test
    void unverifiedFactsAndUnsupportedDerivedEdgesAreExcluded() {
        when(graph.recognize("A 的关系", 6)).thenReturn(List.of(node("a", "A")));
        when(mapper.relationsAround(List.of("a"))).thenReturn(List.of(
                relation(1, "a", "related_to", "b", "", "llm", "笔记#1", "", 1),
                relation(2, "a", "is_a", "c", "", "derived", "", "", .7),
                relation(3, "a", "is_a", "d", "", "derived", "", "1,2", .6)));
        Map<String, Object> result = service.local("A 的关系", 6);
        assertEquals(2, result.get("excludedUnverified"));
        assertEquals(1, rows(result, "relations").size());
        assertEquals(true, rows(result, "relations").getFirst().get("derived"));
        assertTrue(result.get("block").toString().contains("规则推导，需核实"));
        assertTrue(result.get("block").toString().contains("推导依据：1,2"));
    }

    @Test
    void symmetricDuplicatePrefersDirectEvidenceAndPreservesDifferentRelations() {
        when(graph.recognize("A 与 B 的区别", 6)).thenReturn(List.of(node("a", "A"), node("b", "B")));
        when(mapper.relationsAround(List.of("a", "b"))).thenReturn(List.of(
                relation(1, "b", "contrast_with", "a", "", "derived", "", "4,5", 1),
                relation(2, "a", "contrast_with", "b", "A与B容易混淆", "llm", "笔记#1", "", .5),
                relation(3, "a", "prerequisite", "b", "A需要先懂B", "llm", "笔记#1", "", .8)));
        List<Map<String, Object>> picked = rows(service.local("A 与 B 的区别", 6), "relations");
        assertEquals(2, picked.size());
        assertTrue(picked.stream().noneMatch(r -> Boolean.TRUE.equals(r.get("derived"))));
        assertEquals(2, picked.stream().map(r -> r.get("relation")).distinct().count());
    }

    @Test
    void connectingBothSeedsRanksAheadOfNoisyHeavyNeighbours() {
        when(graph.recognize("A B", 1)).thenReturn(List.of(node("a", "A"), node("b", "B")));
        List<Map<String, Object>> noisy = new ArrayList<>();
        for (int i = 0; i < 9; i++) noisy.add(relation(i, "a", "related_to", "noise" + i,
                "旁支" + i, "llm", "资料#1", "", 1));
        noisy.add(relation(99, "a", "prerequisite", "b", "A 依赖 B", "llm", "笔记#1", "", .3));
        when(mapper.relationsAround(List.of("a", "b"))).thenReturn(noisy);
        Map<String, Object> result = service.local("A B", 1);
        assertEquals(99, rows(result, "relations").getFirst().get("id"));
        assertEquals(3, rows(result, "relations").size());
        assertEquals(true, result.get("truncated"));
    }

    @Test
    void localBudgetKeepsWholeEvidenceEntriesRatherThanHalfClaims() {
        when(graph.recognize("A 的关系", 6)).thenReturn(List.of(node("a", "A")));
        when(mapper.relationsAround(List.of("a"))).thenReturn(List.of(
                relation(1, "a", "related_to", "b", "证".repeat(500), "llm", "笔记#1", "", 1)));
        Map<String, Object> result = service.search("A 的关系", 6, false, "local", 80);
        assertTrue(result.get("block").toString().length() <= 80);
        assertEquals(List.of(), result.get("relations"));
        assertFalse(result.get("block").toString().contains("a —相关→ b"));
        assertEquals(true, result.get("truncated"));
    }

    @Test
    void topicOverviewUsesMatchingCommunityRatherThanLargest() {
        when(graph.recognize("梳理 ReAct 的整体脉络", 6)).thenReturn(List.of(node("react", "ReAct")));
        when(summaries.summaries()).thenReturn(List.of(
                summary(1, 100, "Java 的类型系统", List.of("java")),
                summary(2, 3, "ReAct 的推理与工具反馈", List.of("react"))));
        Map<String, Object> result = service.search("梳理 ReAct 的整体脉络", 6, false, "auto");
        assertEquals("global", result.get("mode"));
        assertEquals(2, rows(result, "summaries").getFirst().get("communityId"));
        assertEquals(1, rows(result, "summaries").size());
        verify(graph, times(1)).recognize("梳理 ReAct 的整体脉络", 6);
        verifyNoInteractions(mapper, client, routing);
    }

    @Test
    void unknownTopicOverviewDoesNotUseUnrelatedSummaries() {
        when(summaries.summaries()).thenReturn(List.of(summary(1, 100, "Java 类型系统", List.of("java"))));
        Map<String, Object> result = service.search("梳理火星旅游的主题", 6, true, "auto");
        assertEquals("local", result.get("mode"));
        result = service.search("火星旅游概览", 6, true, "auto");
        assertEquals("global", result.get("mode"));
        assertEquals(List.of(), result.get("summaries"));
        assertEquals("document_search", result.get("fallback"));
        verifyNoInteractions(client, routing);
    }

    @Test
    void wholeCorpusOverviewKeepsBroadCoverageWithoutBoilerplateRelevanceNoise() {
        when(summaries.summaries()).thenReturn(List.of(
                summary(1, 10, "Java 内存", List.of("java")),
                summary(2, 3, "智能体工具", List.of("agent"))));
        Map<String, Object> result = service.search("我这段时间在学的方向大致有哪些", 6, false, "auto");
        assertEquals(2, rows(result, "summaries").size());
        assertEquals(List.of(), GraphRagService.topicTerms("我这段时间在学的方向大致有哪些"));
    }

    @Test
    void staleOrInvalidSummariesNeverReachContextOrSynthesis() {
        Map<String, Object> stale = summary(1, 100, "Java 类型系统", List.of("java"));
        stale.put("fresh", false);
        Map<String, Object> invalid = summary(2, 50, "工具", List.of("tool"));
        invalid.put("valid", false);
        when(summaries.summaries()).thenReturn(List.of(stale, invalid));
        Map<String, Object> result = service.search("我的知识库整体概览", 6, true, "auto");
        assertEquals("", result.get("block"));
        assertEquals(0, result.get("calls"));
        assertEquals(List.of(), result.get("summaries"));
        verifyNoInteractions(client, routing);
    }

    @Test
    void summaryBudgetSkipsEntireEntriesAndKeepsSourceRefs() {
        Map<String, Object> big = summary(1, 100, "文".repeat(300), List.of("java"));
        Map<String, Object> small = summary(2, 3, "工具", List.of("tool"));
        small.put("sources", List.of(Map.of("ref", "笔记#3", "id", 3, "type", "note")));
        when(summaries.summaries()).thenReturn(List.of(big, small));
        Map<String, Object> result = service.search("我的知识库整体概览", 6, false, "global", 100);
        assertEquals(1, rows(result, "summaries").size());
        assertEquals(2, rows(result, "summaries").getFirst().get("communityId"));
        assertTrue(result.get("block").toString().length() <= 100);
        assertEquals(List.of("笔记#3"), result.get("sourceRefs"));
        assertEquals(true, result.get("truncated"));
    }

    @Test
    void contextUsesExistingLocalRelationGateAndPathsWithoutDuplicateRecognition() {
        when(graph.retrievalBlock("JVM 和 JDK 的关系", 900)).thenReturn(
                "【概念关联】\n1. JVM -属于→ JDK\n  证据：JVM 是运行时　来源：笔记#5、资料#2\n路径：JVM → JRE → JDK\n");
        Map<String, Object> result = service.contextResult("JVM 和 JDK 的关系", 900);
        assertEquals("local", result.get("mode"));
        assertTrue(result.get("block").toString().contains("路径：JVM → JRE → JDK"));
        assertEquals(List.of("笔记#5", "资料#2"), result.get("sourceRefs"));
        verify(graph).retrievalBlock("JVM 和 JDK 的关系", 900);
        verify(graph, never()).recognize(anyString(), anyInt());
        verifyNoInteractions(mapper, summaries, client, routing);
    }

    @Test
    void contextGlobalHasOnePathAndHonoursZeroBudget() {
        when(summaries.summaries()).thenReturn(List.of(summary(1, 3, "智能体工具", List.of("agent"))));
        Map<String, Object> result = service.contextResult("我的知识库整体概览", 300);
        assertEquals("global", result.get("mode"));
        assertFalse(result.get("block").toString().isBlank());
        verify(graph, never()).retrievalBlock(anyString(), anyInt());
        verify(graph, times(1)).recognize("我的知识库整体概览", 6);
        assertEquals("", service.contextBlock("我的知识库整体概览", 0));
        assertEquals("", service.globalBlockIfNeeded("没有命中的具体问题", 200));
        verify(graph, times(1)).recognize("我的知识库整体概览", 6);
        verifyNoInteractions(client, routing);
    }

    @Test
    void contextClampsOldGraphSuffixThatExceedsBudget() {
        when(graph.retrievalBlock("A B关系", 20)).thenReturn("完整条目\n" + "超出".repeat(20));
        Map<String, Object> result = service.contextResult("A B关系", 20);
        assertEquals("完整条目\n", result.get("block"));
        assertEquals(true, result.get("truncated"));
        when(graph.retrievalBlock("A B关系", 20)).thenReturn("字".repeat(20) + "\n额外文字");
        assertTrue(service.contextBlock("A B关系", 20).length() <= 20);
    }

    @Test
    void englishStopWordsDoNotStripCharactersFromTechnologyNames() {
        assertEquals(List.of("spring", "training"), GraphRagService.topicTerms("overview of Spring and training"));
    }

    private static KgNode node(String id, String name) {
        KgNode n = new KgNode(); n.setId(id); n.setName(name); return n;
    }

    private static Map<String, Object> relation(int id, String head, String kind, String tail, String evidence,
                                                String origin, String sources, String derivedFrom, double weight) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id); out.put("headId", head); out.put("headName", head); out.put("relation", kind);
        out.put("tailId", tail); out.put("tailName", tail); out.put("evidence", evidence); out.put("origin", origin);
        out.put("sources", sources); out.put("derivedFrom", derivedFrom); out.put("weight", weight);
        return out;
    }

    private static Map<String, Object> summary(int id, int size, String text, List<String> ids) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("communityId", id); out.put("size", size); out.put("summary", text); out.put("nodeIds", ids);
        out.put("fresh", true); out.put("valid", true); out.put("memberHash", "hash-" + id);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> result, String key) {
        return (List<Map<String, Object>>) result.get(key);
    }
}
