package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.dyh.learnhub.dto.AiChatRequest;
import org.dyh.learnhub.service.AgentSessionService;
import org.dyh.learnhub.service.GroundingService;
import org.dyh.learnhub.service.KnowledgeRetrievalService;
import org.dyh.learnhub.service.NoteService;
import org.dyh.learnhub.service.RetrievalContextService;
import org.dyh.learnhub.service.SettingsService;
import org.dyh.learnhub.service.WikiRetrievalService;
import org.dyh.learnhub.vo.AiChatVO;
import org.dyh.learnhub.vo.NoteVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentWikiToolTest {
    private static final String QUESTION = "ReAct 和 Planning 有何关系？";
    @Spy ObjectMapper json = new ObjectMapper();
    @Mock WikiRetrievalService wiki;
    @Mock KnowledgeRetrievalService retrieval;
    @Mock RetrievalContextService contexts;
    @Mock SettingsService settings;
    @Mock AgentSessionService sessions;
    @Mock DeepSeekClient client;
    @Mock ModelRouting routing;
    @Mock GroundingService grounding;
    @Mock NoteService notes;
    @InjectMocks AgentService agent;

    @BeforeEach void mapper() {
        ReflectionTestUtils.setField(agent, "objectMapper", json);
    }

    @Test void searchValidatesQueryAndCapsTheResultCountWithoutInventingVerification() throws Exception {
        when(wiki.searchView("ReAct", 6)).thenReturn(Map.of("ok", true, "items", List.of(
                Map.of("topicKey", "entity:react", "sectionKey", "relations", "text", "生成解释"))));

        JsonNode result = dispatch("search_wiki", Map.of("query", " ReAct ", "limit", 999));

        assertTrue(result.path("ok").asBoolean());
        assertEquals("entity:react", result.path("items").get(0).path("topicKey").asText());
        assertTrue(result.path("requires_source_check").asBoolean());
        assertTrue(result.path("notice").asText().contains("不得执行"));
        assertFalse(result.has("verified"));
        assertFalse(dispatch("search_wiki", Map.of("query", " ")).path("ok").asBoolean());
        assertFalse(dispatch("search_wiki", Map.of("query", 123)).path("ok").asBoolean());
        assertFalse(dispatch("search_wiki", Map.of("query", "ReAct", "limit", "6")).path("ok").asBoolean());
        verify(wiki, times(1)).searchView(anyString(), anyInt());
    }

    @Test void readPassesTheExactPageAndSectionAndPreservesServiceFailures() throws Exception {
        when(wiki.readPage("entity:react", "relations", 4000)).thenReturn(Map.of(
                "ok", true, "sections", List.of(Map.of("text", "生成解释")),
                "links", List.of("Planning"), "sourceRefs", List.of(Map.of("type", "note", "id", 7))));
        when(wiki.readPage("missing", "", 2500)).thenReturn(Map.of("ok", false, "error", "页面已过期或不存在"));

        JsonNode result = dispatch("read_wiki", Map.of("topic_key", " entity:react ",
                "section_key", " relations ", "max_chars", Long.MAX_VALUE));

        assertEquals("Planning", result.path("links").get(0).asText());
        assertTrue(result.path("requires_source_check").asBoolean());
        assertFalse(result.has("verified"));
        JsonNode failed = dispatch("read_wiki", Map.of("topic_key", "missing"));
        assertFalse(failed.path("ok").asBoolean());
        assertEquals("页面已过期或不存在", failed.path("error").asText());
        assertFalse(dispatch("read_wiki", Map.of("topic_key", " ")).path("ok").asBoolean());
        assertFalse(dispatch("read_wiki", Map.of("topic_key", "a", "section_key", 1)).path("ok").asBoolean());
        assertFalse(dispatch("read_wiki", Map.of("topic_key", "a", "max_chars", "1000")).path("ok").asBoolean());
        verify(wiki, times(2)).readPage(anyString(), anyString(), anyInt());
    }

    @Test void toolDefinitionsExposeReadOnlyWikiToolsWithRequiredLocators() {
        List<?> definitions = ReflectionTestUtils.invokeMethod(agent, "toolDefinitions");
        JsonNode defs = json.valueToTree(definitions);
        JsonNode search = definition(defs, "search_wiki");
        JsonNode read = definition(defs, "read_wiki");

        assertTrue(search.path("parameters").path("required").toString().contains("query"));
        assertTrue(read.path("parameters").path("required").toString().contains("topic_key"));
        assertTrue(read.path("description").asText().contains("get_note"));
        assertEquals(false, ReflectionTestUtils.invokeMethod(agent, "requiresApproval", "search_wiki"));
        assertEquals(false, ReflectionTestUtils.invokeMethod(agent, "requiresApproval", "read_wiki"));
    }

    @Test void agentCanFollowWikiLinksAndReadTheActualSourceWithoutMakingWikiAReference() throws Exception {
        configure();
        when(wiki.searchView("ReAct", 4)).thenReturn(Map.of("ok", true, "items", List.of(
                Map.of("pageId", 3, "pageTitle", "ReAct", "topicKey", "entity:react", "sectionKey", "relations"))));
        when(wiki.readPage("entity:react", "relations", 2500)).thenReturn(Map.of("ok", true,
                "sections", List.of(Map.of("pageId", 3, "pageTitle", "ReAct", "topicKey", "entity:react",
                        "sectionKey", "relations", "links", List.of("Planning"), "text", "未核验的 Wiki 解释"))));
        when(wiki.searchView("Planning", 4)).thenReturn(Map.of("ok", true, "items", List.of()));
        sourceNote();
        var searchRound = toolRound(call("s1", "search_wiki", Map.of("query", "ReAct")));
        var followRound = toolRound(call("r1", "read_wiki", Map.of("topic_key", "entity:react", "section_key", "relations")),
                call("s2", "search_wiki", Map.of("query", "Planning")));
        var sourceRound = toolRound(call("n1", "get_note", Map.of("note_id", 7)));
        var finalRound = answer();
        whenChat().thenReturn(searchRound, followRound, sourceRound, finalRound);

        AiChatVO reply = chat();

        assertEquals("按原文回答。", reply.getReply());
        assertEquals(2, reply.getRetrieved().size());
        assertTrue(reply.getRetrieved().stream().anyMatch(ref -> "note".equals(ref.get("type")) && Long.valueOf(7).equals(ref.get("id"))));
        assertTrue(reply.getRetrieved().stream().anyMatch(ref -> "wiki".equals(ref.get("type"))
                && Boolean.TRUE.equals(ref.get("generatedGuide")) && Boolean.FALSE.equals(ref.get("factVerified"))));
        assertEquals(false, reply.getGrounding().get("checked"));
        verify(grounding, never()).check(anyString(), anyString(), anyString());
        assertTrue(allToolResults().stream().anyMatch(result -> result.path("content").asText().contains("原文正文")));
    }

    @Test void budgetRejectsFurtherWikiCallsButLeavesSourceReadsAvailableAndResetsForTheNextRequest() throws Exception {
        configure();
        sourceNote();
        when(wiki.searchView(anyString(), anyInt())).thenReturn(Map.of("ok", true, "items", List.of()));
        when(wiki.readPage(anyString(), anyString(), anyInt())).thenReturn(Map.of("ok", true, "sections", List.of()));
        List<JsonNode> calls = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            calls.add(call("s" + i, "search_wiki", Map.of("query", "概念" + i)));
            calls.add(call("r" + i, "read_wiki", Map.of("topic_key", "entity:" + i)));
        }
        var wikiRound = toolRound(calls.toArray(JsonNode[]::new));
        var sourceRound = toolRound(call("n", "get_note", Map.of("note_id", 7)));
        var finalRound = answer();
        whenChat().thenReturn(wikiRound, sourceRound, finalRound, wikiRound, sourceRound, finalRound);

        AiChatVO first = chat();
        AiChatVO next = chat();

        verify(wiki, times(6)).searchView(anyString(), anyInt());
        verify(wiki, times(6)).readPage(anyString(), anyString(), anyInt());
        verify(notes, times(2)).detail(7L);
        assertEquals(1, first.getRetrieved().size());
        assertEquals(1, next.getRetrieved().size());
        assertEquals(4, allToolResults().stream()
                .filter(result -> "wiki_budget_exhausted".equals(result.path("code").asText())).count());
    }

    @Test void wikiServiceFailureReturnsAnErrorAndDoesNotClaimReadEvidence() throws Exception {
        configure();
        when(wiki.searchView("ReAct", 4)).thenThrow(new IllegalStateException("索引暂不可用"));
        when(grounding.check(eq(QUESTION), eq(""), eq("按原文回答。"))).thenReturn(
                GroundingService.Result.skipped("没有原文"));
        var searchRound = toolRound(call("s", "search_wiki", Map.of("query", "ReAct")));
        var finalRound = answer();
        whenChat().thenReturn(searchRound, finalRound);

        AiChatVO reply = chat();

        assertTrue(reply.getRetrieved().isEmpty());
        JsonNode result = allToolResults().getFirst();
        assertFalse(result.path("ok").asBoolean());
        assertTrue(result.path("error").asText().contains("索引暂不可用"));
        verify(grounding).check(QUESTION, "", "按原文回答。");
    }

    private JsonNode dispatch(String tool, Map<String, ?> args) throws Exception {
        String result = ReflectionTestUtils.invokeMethod(agent, "dispatch", tool,
                json.writeValueAsString(args), new ArrayList<String>());
        return json.readTree(result);
    }

    private static JsonNode definition(JsonNode definitions, String name) {
        for (JsonNode definition : definitions) {
            JsonNode function = definition.path("function");
            if (name.equals(function.path("name").asText())) return function;
        }
        throw new AssertionError("missing tool " + name);
    }

    private void configure() {
        when(client.isConfigured()).thenReturn(true);
        when(sessions.ensure(null, null, QUESTION)).thenReturn("wiki-session");
        when(settings.effective(SettingsService.KEY_CHAT_PROMPT)).thenReturn("系统提示");
        // Wiki tools can be explicitly used even if automatic Wiki injection is disabled.
        when(contexts.build(QUESTION, 8, "fused", false, false)).thenReturn(RetrievalContextService.Context.empty());
        when(grounding.enabled()).thenReturn(true);
        when(routing.forTask(ModelRouting.TASK_CHAT)).thenReturn(
                new ModelRouting.ModelTarget("test", "test", "http://unused", "dummy", "mock", true));
    }

    private void sourceNote() {
        NoteVO note = new NoteVO();
        note.setId(7L);
        note.setTitle("原文笔记");
        note.setContent("原文正文：观察反馈用于后续规划。");
        when(notes.detail(7L)).thenReturn(note);
    }

    private org.mockito.stubbing.OngoingStubbing<DeepSeekClient.ChatResult> whenChat() throws Exception {
        return when(client.chatFull(anyList(), anyList(), anyString(), anyString(), anyString(), anyInt(),
                anyDouble(), nullable(String.class), nullable(String.class), any(Duration.class)));
    }

    private JsonNode call(String id, String tool, Map<String, ?> args) throws Exception {
        ObjectNode call = json.createObjectNode().put("id", id).put("type", "function");
        call.putObject("function").put("name", tool).put("arguments", json.writeValueAsString(args));
        return call;
    }

    private DeepSeekClient.ChatResult toolRound(JsonNode... calls) {
        ObjectNode message = json.createObjectNode();
        message.putArray("tool_calls").addAll(List.of(calls));
        return new DeepSeekClient.ChatResult(message, "tool_calls", 1, 1, 0);
    }

    private DeepSeekClient.ChatResult answer() {
        return new DeepSeekClient.ChatResult(json.createObjectNode().put("content", "按原文回答。"), "stop", 1, 1, 0);
    }

    private AiChatVO chat() {
        AiChatRequest request = new AiChatRequest();
        request.setMessage(QUESTION);
        return agent.chat(request);
    }

    private List<JsonNode> allToolResults() throws Exception {
        ArgumentCaptor<List<?>> messages = ArgumentCaptor.forClass(List.class);
        verify(client, atLeastOnce()).chatFull(messages.capture(), anyList(), anyString(), anyString(), anyString(),
                anyInt(), anyDouble(), nullable(String.class), nullable(String.class), any(Duration.class));
        // Every client invocation sees the same mutable request list; only inspect each request once.
        List<List<?>> unique = new ArrayList<>();
        List<JsonNode> results = new ArrayList<>();
        for (List<?> request : messages.getAllValues()) {
            if (unique.stream().anyMatch(prior -> prior == request)) continue;
            unique.add(request);
            for (Object message : request) {
                JsonNode node = json.valueToTree(message);
                if ("tool".equals(node.path("role").asText())) results.add(json.readTree(node.path("content").asText()));
            }
        }
        return results;
    }
}
