package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.dyh.learnhub.dto.AiChatRequest;
import org.dyh.learnhub.entity.FileInfo;
import org.dyh.learnhub.service.AgentSessionService;
import org.dyh.learnhub.service.FileStorageService;
import org.dyh.learnhub.service.GroundingService;
import org.dyh.learnhub.service.KgGraphService;
import org.dyh.learnhub.service.KnowledgeRetrievalService;
import org.dyh.learnhub.service.NoteService;
import org.dyh.learnhub.service.QuickRefService;
import org.dyh.learnhub.service.RetrievalContextService;
import org.dyh.learnhub.service.RetrievalHit;
import org.dyh.learnhub.service.SettingsService;
import org.dyh.learnhub.service.WebService;
import org.dyh.learnhub.vo.AiChatVO;
import org.dyh.learnhub.vo.NoteVO;
import org.dyh.learnhub.vo.QuickRefVO;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentToolRetrievalTest {
    private static final String QUESTION = "请核对本轮读取的正文依据";
    private static final String ANSWER = "已根据读取的正文回答。";
    private static final String INITIAL = "自动检索保留的原文材料";
    @Spy ObjectMapper json = new ObjectMapper();
    @Mock KnowledgeRetrievalService retrieval;
    @Mock RetrievalContextService contexts;
    @Mock SettingsService settings;
    @Mock AgentSessionService sessions;
    @Mock DeepSeekClient client;
    @Mock ModelRouting routing;
    @Mock GroundingService grounding;
    @Mock NoteService notes;
    @Mock QuickRefService quickRefs;
    @Mock FileStorageService files;
    @Mock KgGraphService graph;
    @Mock WebService web;
    @InjectMocks AgentService agent;

    @BeforeEach
    void mapper() {
        ReflectionTestUtils.setField(agent, "objectMapper", json);
    }

    @Test
    void noteReadWithoutAutomaticHitsBecomesGroundingEvidenceAndAReference() throws Exception {
        String body = "撤销暂存使用 git restore --staged path。";
        when(notes.detail(7L)).thenReturn(note(7, body));
        configureConversation(RetrievalContextService.Context.empty(), "get_note", Map.of("note_id", 7));
        returnSuccessfulCheck();

        AiChatVO reply = chat();

        assertEquals(ANSWER, reply.getReply());
        assertTrue(checkedEvidence().contains(body));
        assertToolReference(reply, "note", 7);
        assertEquals(1, reply.getRetrieved().size());
        assertEquals(body, consumedToolResult().path("content").asText());
    }

    @Test
    void quickRefReadPreservesExistingEvidenceAndAddsTheActualNewSource() throws Exception {
        QuickRefVO ref = new QuickRefVO();
        ref.setId(8L);
        ref.setTitle("Git 速查卡");
        ref.setContent("git restore --staged . 只撤销暂存。");
        when(quickRefs.detail(8L)).thenReturn(ref);
        var initial = initialContext();
        configureConversation(initial, "get_quick_ref", Map.of("quick_ref_id", 8));
        returnSuccessfulCheck();

        AiChatVO reply = chat();

        String evidence = checkedEvidence();
        assertTrue(evidence.contains(INITIAL));
        assertTrue(evidence.contains(ref.getContent()));
        assertTrue(reply.getRetrieved().contains(initial.retrieved().getFirst()));
        assertToolReference(reply, "quick_ref", 8);
        assertEquals(2, reply.getRetrieved().size());
        consumedToolResult();
    }

    @Test
    void fileQueryUsesOnlyTheExcerptsActuallyReturnedByTheTool() throws Exception {
        String phrase = "公式上下标必须按照原文还原";
        String unseenPrefix = "没有返回给模型的文件开头";
        String unseenSuffix = "没有返回给模型的文件末尾";
        FileInfo file = file(9, unseenPrefix + "前".repeat(700) + phrase + "后".repeat(700) + unseenSuffix);
        when(files.detail(9L)).thenReturn(file);
        configureConversation(initialContext(), "get_file", Map.of("file_id", 9, "query", phrase));
        returnSuccessfulCheck();

        AiChatVO reply = chat();

        JsonNode returned = consumedToolResult();
        assertEquals(1, returned.path("matches").asInt());
        String excerpt = returned.path("excerpts").get(0).path("excerpt").asText();
        String evidence = checkedEvidence();
        assertTrue(evidence.contains(INITIAL));
        assertTrue(evidence.contains(excerpt));
        assertTrue(evidence.contains(phrase));
        assertFalse(evidence.contains(unseenPrefix));
        assertFalse(evidence.contains(unseenSuffix));
        assertToolReference(reply, "file", 9);
    }

    @Test
    void knowledgeToolPreservesPassageAndGraphAttributionInMessagesAndReferences() throws Exception {
        String body = "ReAct 将观察反馈用于后续 Planning。";
        String relation = "ReAct —用于→ Planning";
        RetrievalHit hit = new RetrievalHit("note", 10L, "Agent 笔记", "智能体", body, .05, 2,
                List.of("vector", "graph"), List.of(relation));
        when(retrieval.search("ReAct Planning", 24)).thenReturn(List.of(hit));
        configureConversation(initialContext(), "search_knowledge", Map.of("keyword", "ReAct Planning"));
        returnSuccessfulCheck();

        AiChatVO reply = chat();

        JsonNode returned = consumedToolResult().path("items").get(0);
        assertEquals(hit.key(), returned.path("passageKey").asText());
        assertEquals(2, returned.path("seq").asInt());
        assertEquals(json.valueToTree(hit.channels()), returned.path("channels"));
        assertEquals(json.valueToTree(hit.graphRelations()), returned.path("graphRelations"));
        String evidence = checkedEvidence();
        assertTrue(evidence.contains(INITIAL));
        assertTrue(evidence.contains(body));
        Map<String, Object> ref = assertToolReference(reply, "note", 10);
        assertEquals(hit.key(), ref.get("passageKey"));
        assertEquals(2, ((Number) ref.get("seq")).intValue());
        assertEquals(Set.of("vector", "graph", "tool"), Set.copyOf(channels(ref)));
        assertEquals(hit.graphRelations(), ref.get("graphRelations"));
    }

    @Test
    void oversizedToolEvidenceSkipsTheWholeCheckButKeepsAnswerAndReferences() throws Exception {
        String body = "证".repeat(RetrievalContextService.TOTAL_CHARS + 1) + "末尾的工具证据";
        when(notes.detail(11L)).thenReturn(note(11, body));
        configureConversation(initialContext(), "get_note", Map.of("note_id", 11));

        AiChatVO reply = chat();

        assertEquals(ANSWER, reply.getReply());
        assertSkipped(reply);
        assertTrue(String.valueOf(reply.getGrounding().get("note")).contains("预算"));
        verify(grounding, never()).check(anyString(), anyString(), anyString());
        assertToolReference(reply, "note", 11);
        assertEquals(2, reply.getRetrieved().size());
        assertEquals(body, consumedToolResult().path("content").asText());
    }

    @Test
    void failedReadDoesNotBecomeEvidenceOrAClaimedSource() throws Exception {
        when(notes.detail(404L)).thenThrow(new IllegalArgumentException("deleted"));
        var initial = initialContext();
        configureConversation(initial, "get_note", Map.of("note_id", 404));
        returnSuccessfulCheck();

        AiChatVO reply = chat();

        assertEquals(ANSWER, reply.getReply());
        assertEquals(initial.retrieved(), reply.getRetrieved());
        String evidence = checkedEvidence();
        assertTrue(evidence.contains(INITIAL));
        assertFalse(evidence.contains("笔记不存在"));
        assertFalse(evidence.contains("404"));
        assertFalse(consumedToolResult().path("ok").asBoolean());
    }

    @Test
    void successfulUnverifiedGraphToolSkipsCheckingOnlyTheInitialPassages() throws Exception {
        when(graph.neighbors("ReAct", 1)).thenReturn(Map.of(
                "found", true, "anchorName", "ReAct", "hops", 1, "reachable", 1,
                "triples", List.of(Map.of("from", "ReAct", "relation", "related_to", "label", "相关",
                        "to", "Planning", "direction", "out", "origin", "llm",
                        "evidence", "图工具返回的未核验关系", "sources", List.of("笔记#12")))));
        var initial = initialContext();
        configureConversation(initial, "graph_neighbors", Map.of("entity", "ReAct", "hops", 1));

        AiChatVO reply = chat();

        assertEquals(ANSWER, reply.getReply());
        assertTrue(consumedToolResult().path("ok").asBoolean());
        assertSkipped(reply);
        verify(grounding, never()).check(anyString(), anyString(), anyString());
        assertTrue(reply.getRetrieved().contains(initial.retrieved().getFirst()));
    }

    @Test
    void successfulWebToolSkipsCheckingOnlyTheInitialPassages() throws Exception {
        String url = "https://example.invalid/reference";
        when(settings.webEnabled()).thenReturn(true);
        when(web.fetch(url)).thenReturn(new WebService.FetchResult(url, 200, "text/html",
                "模型实际看到的外部资料正文", false));
        var initial = initialContext();
        configureConversation(initial, "web_fetch", Map.of("url", url));

        AiChatVO reply = chat();

        assertEquals(ANSWER, reply.getReply());
        assertTrue(consumedToolResult().path("ok").asBoolean());
        assertSkipped(reply);
        verify(grounding, never()).check(anyString(), anyString(), anyString());
        assertTrue(reply.getRetrieved().contains(initial.retrieved().getFirst()));
    }

    private void configureConversation(RetrievalContextService.Context context, String tool, Map<String, ?> args)
            throws Exception {
        when(client.isConfigured()).thenReturn(true);
        when(sessions.ensure(null, null, QUESTION)).thenReturn("session");
        when(settings.effective(SettingsService.KEY_CHAT_PROMPT)).thenReturn("系统提示");
        when(contexts.build(QUESTION, 8, "fused", false, false)).thenReturn(context);
        when(grounding.enabled()).thenReturn(true);
        when(routing.forTask(ModelRouting.TASK_CHAT)).thenReturn(
                new ModelRouting.ModelTarget("test", "test", "http://unused", "dummy", "mock", true));
        ObjectNode toolMessage = json.createObjectNode();
        ObjectNode call = toolMessage.putArray("tool_calls").addObject();
        call.put("id", "read-call");
        call.put("type", "function");
        call.putObject("function").put("name", tool).put("arguments", json.writeValueAsString(args));
        var first = new DeepSeekClient.ChatResult(toolMessage, "tool_calls", 1, 1, 0);
        var last = new DeepSeekClient.ChatResult(json.createObjectNode().put("content", ANSWER), "stop", 1, 1, 0);
        when(client.chatFull(anyList(), anyList(), anyString(), anyString(), anyString(), anyInt(),
                anyDouble(), nullable(String.class), nullable(String.class), any(Duration.class)))
                .thenReturn(first, last);
    }

    private void returnSuccessfulCheck() {
        when(grounding.check(eq(QUESTION), anyString(), eq(ANSWER))).thenReturn(
                new GroundingService.Result(true, true, List.of(), "有依据", 100));
    }

    private AiChatVO chat() {
        AiChatRequest request = new AiChatRequest();
        request.setMessage(QUESTION);
        return agent.chat(request);
    }

    private String checkedEvidence() {
        ArgumentCaptor<String> evidence = ArgumentCaptor.forClass(String.class);
        verify(grounding).check(eq(QUESTION), evidence.capture(), eq(ANSWER));
        return evidence.getValue();
    }

    private JsonNode consumedToolResult() throws Exception {
        ArgumentCaptor<List<?>> messages = ArgumentCaptor.forClass(List.class);
        verify(client, times(2)).chatFull(messages.capture(), anyList(), anyString(), anyString(), anyString(),
                anyInt(), anyDouble(), nullable(String.class), nullable(String.class), any(Duration.class));
        for (Object message : messages.getAllValues().getLast()) {
            JsonNode node = json.valueToTree(message);
            if ("tool".equals(node.path("role").asText()) && "read-call".equals(node.path("tool_call_id").asText())) {
                return json.readTree(node.path("content").asText());
            }
        }
        fail("the second model round must receive the real dispatched tool result");
        return null;
    }

    private static void assertSkipped(AiChatVO reply) {
        assertNotNull(reply.getGrounding());
        assertEquals(false, reply.getGrounding().get("checked"));
        assertFalse(String.valueOf(reply.getGrounding().get("note")).isBlank());
    }

    private static Map<String, Object> assertToolReference(AiChatVO reply, String type, long id) {
        Map<String, Object> ref = reply.getRetrieved().stream()
                .filter(item -> type.equals(item.get("type")) && item.get("id") instanceof Number n
                        && n.longValue() == id)
                .findFirst().orElseThrow(() -> new AssertionError("missing actual source " + type + ":" + id));
        assertTrue(channels(ref).contains("tool"));
        return ref;
    }

    @SuppressWarnings("unchecked")
    private static List<String> channels(Map<String, Object> ref) {
        return (List<String>) ref.get("channels");
    }

    private static RetrievalContextService.Context initialContext() {
        return new RetrievalContextService.Context(List.of(INITIAL),
                List.of(new RetrievalHit("note", 1L, "初始来源", "", INITIAL, .5, 0,
                        List.of("keyword"), List.of())),
                List.of(Map.of("type", "note", "id", 1L, "title", "初始来源",
                        "seq", 0, "channels", List.of("keyword"))));
    }

    private static NoteVO note(long id, String content) {
        NoteVO note = new NoteVO();
        note.setId(id);
        note.setTitle("工具读取笔记");
        note.setContent(content);
        return note;
    }

    private static FileInfo file(long id, String content) {
        FileInfo file = new FileInfo();
        file.setId(id);
        file.setOriginName("工具读取资料.pdf");
        file.setTextStatus("ok");
        file.setTextContent(content);
        file.setTextChars(content.length());
        return file;
    }
}
