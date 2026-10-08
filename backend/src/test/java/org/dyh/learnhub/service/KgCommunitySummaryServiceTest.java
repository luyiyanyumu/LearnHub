package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.ai.DeepSeekClient;
import org.dyh.learnhub.ai.ModelRouting;
import org.dyh.learnhub.mapper.KgCommunityMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KgCommunitySummaryServiceTest {
    private final KgCommunityMapper mapper = mock(KgCommunityMapper.class);
    private final KgCommunityService communities = mock(KgCommunityService.class);
    private final ModelRouting routing = mock(ModelRouting.class);
    private final DeepSeekClient client = mock(DeepSeekClient.class);
    private final KgCommunitySummaryService service = new KgCommunitySummaryService(mapper, communities, routing, client);
    private final ModelRouting.ModelTarget target = new ModelRouting.ModelTarget("writer", "摘要档案", "http://localhost:1", "test-only", "writer-model", true);
    private final List<Map<String, Object>> stored = new ArrayList<>();
    private final List<Map<String, Object>> nodes = new ArrayList<>();
    private final List<Map<String, Object>> relations = new ArrayList<>();

    @BeforeEach void setup() throws Exception {
        for (String id : List.of("a", "b", "c")) nodes.add(new LinkedHashMap<>(Map.of("id", id, "name", id.toUpperCase(), "brief", "说明" + id)));
        relations.add(new LinkedHashMap<>(Map.of("id", 1L, "head", "a", "tail", "b", "relation", "uses",
                "evidence", "A调用B完成任务", "sources", "笔记#5", "origin", "llm", "weight", 1.0)));
        when(routing.forTask(ModelRouting.TASK_COMMUNITY)).thenReturn(target);
        when(mapper.allNodeInfos()).thenAnswer(i -> nodes);
        when(mapper.allRelations()).thenAnswer(i -> relations);
        when(mapper.noteSources(anyList())).thenReturn(List.of(Map.of("id", 5L, "title", "A与B",
                "content", "A调用B完成任务。D使用E完成任务。", "contentHash", "正文-v1")));
        when(mapper.summaries()).thenAnswer(i -> new ArrayList<>(stored));
        when(communities.grouped()).thenReturn(List.of(group(0, "a", "b", "c")));
        when(mapper.upsertSummary(anyInt(), anyString(), anyString(), anyString(), anyInt())).thenAnswer(i -> {
            int id = i.getArgument(0);
            stored.removeIf(row -> ((Number) row.get("communityId")).intValue() == id);
            stored.add(new LinkedHashMap<>(Map.of("communityId", id, "memberHash", i.<String>getArgument(1),
                    "model", i.<String>getArgument(2), "summary", i.<String>getArgument(3), "size", i.<Integer>getArgument(4))));
            return 1;
        });
        response("A与B围绕技术学习形成一组关联。[笔记#5]", "stop");
    }

    private static Map<String, Object> group(int id, String... members) {
        return Map.of("communityId", id, "size", members.length, "nodeIds", List.of(members));
    }

    private void response(String text, String finish) throws Exception {
        when(client.chatFull(anyList(), isNull(), anyString(), anyString(), anyString(), anyInt(), anyDouble(),
                anyString(), isNull(), any(Duration.class))).thenReturn(new DeepSeekClient.ChatResult(
                new ObjectMapper().createObjectNode().put("content", text), finish, 0, 0, 0));
    }

    @Test void unchangedReadsAndRepeatedGenerationUseCacheWithoutModelOrWrites() {
        assertEquals(1, service.summarize(1).get("calls"));
        clearInvocations(client, mapper);
        var fresh = service.summaries();
        assertEquals(1, fresh.size());
        assertEquals(true, fresh.getFirst().get("fresh"));
        assertEquals(List.of("a", "b", "c"), fresh.getFirst().get("nodeIds"));
        assertEquals(List.of("A", "B", "C"), fresh.getFirst().get("nodeNames"));
        assertEquals(1, service.status().get("fresh"));
        assertEquals(1, service.summarize(1).get("cached"));
        verifyNoInteractions(client);
        verify(mapper, never()).upsertSummary(anyInt(), anyString(), anyString(), anyString(), anyInt());
        verify(mapper, never()).clear();
        verify(mapper, never()).savePartitionFingerprint(anyString());
    }

    @Test void conceptBriefChangeInvalidatesSameMemberCache() {
        service.summarize(1);
        nodes.getFirst().put("brief", "改正后的概念说明");
        assertTrue(service.summaries().isEmpty());
        assertEquals(1, service.status().get("stale"));
    }

    @Test void relationEvidenceChangeInvalidatesSameMemberCache() {
        service.summarize(1);
        relations.getFirst().put("evidence", "A不调用B完成任务");
        assertTrue(service.summaries().isEmpty());
    }

    @Test void sourceBodyChangeInvalidatesSameMemberCacheEvenWhenGraphIsUnchanged() {
        service.summarize(1);
        when(mapper.noteSources(anyList())).thenReturn(List.of(Map.of("id", 5L, "title", "A与B",
                "content", "A调用B完成任务。新增内容。", "contentHash", "正文-v2")));
        assertTrue(service.summaries().isEmpty());
    }

    @Test void deletedSourceCannotRemainCurrentEvidence() {
        service.summarize(1);
        when(mapper.noteSources(anyList())).thenReturn(List.of());
        assertTrue(service.summaries().isEmpty());
        assertEquals(1, service.status().get("unsupported"));
    }

    @Test void modelEndpointChangeInvalidatesCacheButSecretAndLabelDoNot() {
        service.summarize(1);
        when(routing.forTask(ModelRouting.TASK_COMMUNITY)).thenReturn(new ModelRouting.ModelTarget(
                target.id(), "重命名", target.baseUrl(), "different-secret", target.model(), true));
        assertEquals(1, service.summaries().size());
        when(routing.forTask(ModelRouting.TASK_COMMUNITY)).thenReturn(new ModelRouting.ModelTarget(
                target.id(), target.label(), "http://localhost:2", target.apiKey(), target.model(), true));
        assertTrue(service.summaries().isEmpty());
    }

    @Test void renumberedCommunityReusesSummaryAtCorrectIdWithoutAnotherModelCall() {
        service.summarize(1);
        clearInvocations(client);
        when(communities.grouped()).thenReturn(List.of(group(7, "c", "a", "b")));
        assertTrue(service.summaries().isEmpty(), "旧社区编号的摘要不能直接参与回答");
        assertEquals(1, service.summarize(1).get("cached"));
        assertEquals(7, service.summaries().getFirst().get("communityId"));
        verifyNoInteractions(client);
    }

    @Test void derivedRelationsAndUncitedEdgesDoNotBecomeSummaryFacts() throws Exception {
        relations.add(new LinkedHashMap<>(Map.of("head", "b", "tail", "c", "relation", "derived-rule",
                "evidence", "ONLY-DERIVED", "sources", "笔记#5", "origin", "derived", "weight", .5)));
        service.summarize(1);
        ArgumentCaptor<List<?>> prompt = ArgumentCaptor.forClass(List.class);
        verify(client).chatFull(prompt.capture(), isNull(), anyString(), anyString(), anyString(), anyInt(), anyDouble(),
                anyString(), isNull(), any(Duration.class));
        String material = String.valueOf(((Map<?, ?>) prompt.getValue().get(1)).get("content"));
        assertTrue(material.contains("A调用B完成任务"));
        assertTrue(material.contains("[笔记#5]"));
        assertFalse(material.contains("ONLY-DERIVED"));
        assertFalse(material.contains("derived-rule"));
        assertEquals(1, ((List<?>) service.summaries().getFirst().get("relations")).size());
    }

    @Test void failedModelCallsConsumeBudgetAndIncompleteOutputIsNotStored() throws Exception {
        for (String id : List.of("d", "e", "f")) nodes.add(Map.of("id", id, "name", id, "brief", "说明"));
        relations.add(Map.of("head", "d", "tail", "e", "relation", "uses", "evidence", "D使用E完成任务", "sources", "笔记#5", "origin", "llm"));
        when(communities.grouped()).thenReturn(List.of(group(0, "a", "b", "c"), group(1, "d", "e", "f")));
        response("截断的摘要", "length");
        var result = service.summarize(1);
        assertEquals(1, result.get("calls"));
        assertEquals(1, result.get("failed"));
        assertEquals(0, result.get("written"));
        assertTrue(stored.isEmpty());
        verify(client, times(1)).chatFull(anyList(), isNull(), anyString(), anyString(), anyString(), anyInt(),
                anyDouble(), anyString(), isNull(), any(Duration.class));
    }

    @Test void materialEditedWhileModelRunsDoesNotCommitOutdatedSummary() throws Exception {
        when(client.chatFull(anyList(), isNull(), anyString(), anyString(), anyString(), anyInt(), anyDouble(),
                anyString(), isNull(), any(Duration.class))).thenAnswer(i -> {
            nodes.getFirst().put("brief", "模型执行期间修改");
            return new DeepSeekClient.ChatResult(new ObjectMapper().createObjectNode().put("content", "旧摘要[笔记#5]"), "stop", 0, 0, 0);
        });
        assertEquals(0, service.summarize(1).get("written"));
        assertTrue(stored.isEmpty());
    }

    @Test void oldMemberOnlyHashCannotMasqueradeAsFreshSummary() {
        stored.add(Map.of("communityId", 0, "memberHash", "legacy-member-hash", "summary", "旧摘要", "size", 3));
        assertTrue(service.summaries().isEmpty());
        verifyNoInteractions(client);
    }

    @Test void sourceNoLongerContainsQuoteCannotRegenerateOldClaims() {
        service.summarize(1);
        clearInvocations(client, mapper);
        when(mapper.noteSources(anyList())).thenReturn(List.of(Map.of("id", 5L, "title", "A与B",
                "content", "A调用B失败，没有完成任务。", "contentHash", "正文-v2")));
        assertTrue(service.summaries().isEmpty());
        assertEquals(1, service.summarize(1).get("skippedUnsupported"));
        verifyNoInteractions(client);
        verify(mapper, never()).upsertSummary(anyInt(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test void sourceBodiesStayPrivateAndMarkdownFormattingDoesNotInvalidateQuoteMatch() {
        when(mapper.noteSources(anyList())).thenReturn(List.of(Map.of("id", 5L, "title", "A与B",
                "content", "**A** 调用 B 完成任务。正文里还有PRIVATE-SENTENCE。", "contentHash", "正文-v1")));
        assertEquals(1, service.summarize(1).get("written"));
        String result = service.summaries().toString();
        assertFalse(result.contains("PRIVATE-SENTENCE"));
        assertFalse(result.contains("quoteText="));
        assertFalse(result.contains("content="));
    }
}
