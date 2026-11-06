package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.dyh.learnhub.common.PageResult;
import org.dyh.learnhub.controller.CliCommandController.Argument;
import org.dyh.learnhub.controller.CliCommandController.Body;
import org.dyh.learnhub.controller.CliCommandController.Command;
import org.dyh.learnhub.dto.AiChatRequest;
import org.dyh.learnhub.entity.AgentPendingAction;
import org.dyh.learnhub.service.AgentSessionService;
import org.dyh.learnhub.service.NoteService;
import org.dyh.learnhub.service.RetrievalContextService;
import org.dyh.learnhub.service.SettingsService;
import org.dyh.learnhub.vo.AiChatVO;
import org.dyh.learnhub.vo.NoteEditVO;
import org.dyh.learnhub.vo.NoteVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentAppCommandIntegrationTest {
    private static final String RUN = "run_app_command";
    private static final String PIPELINE = "run_app_pipeline";
    private static final String SESSION = "synthetic-session";
    private static final long ACTION_ID = 81L;
    private static final String UPDATE_INPUT = """
            {"path":{"id":12},"body":{"title":"合成标题","content":"synthetic-private-input","categoryId":null}}
            """;
    private static final String PAGE_AND_MOVE = """
            {"steps":[
              {"id":"notes","command":"note.page","input":{"query":{"folderId":0,"page":1,"size":90,"kw":"synthetic-private-query"}}},
              {"id":"move","command":"folder.move-items","input":{"body":{"kind":"note","folderId":42,"ids":{"$ref":"notes#/list","$pluck":"/id"}}}}
            ]}
            """;

    private final ObjectMapper json = new ObjectMapper();
    private final AgentToolEvidence evidence = new AgentToolEvidence(RetrievalContextService.Context.empty(), json);
    @Mock AgentAppCommands commands;
    @Mock AgentSessionService sessions;
    @Mock NoteService notes;
    @Mock DeepSeekClient client;
    @Mock ModelRouting routing;
    @Mock SettingsService settings;
    @Mock RetrievalContextService retrieval;
    @InjectMocks AgentService agent;

    @BeforeEach void setUp() {
        ReflectionTestUtils.setField(agent, "objectMapper", json);
        agent.setAppCommands(commands);
    }

    @Test void appToolsAreDirectAndSuccessfulReadsRunWithoutApproval() throws Exception {
        Set<String> appTools = Set.of("list_app_commands", "describe_app_command", RUN, PIPELINE);
        assertTrue(toolNames("toolDefinitions").containsAll(appTools));
        assertTrue(toolNames("builtinToolDefinitions").stream().noneMatch(appTools::contains));

        JsonNode input = json.readTree("{\"path\":{\"id\":12}}");
        var prepared = prepared("note.get", "GET", "/api/notes/{id}", input, false);
        JsonNode result = json.readTree("{\"ok\":true,\"result\":{\"title\":\"合成笔记\"}}");
        when(commands.run(eq(prepared), anyLong())).thenReturn(result);

        assertEquals(result, run("note.get", input, new AiChatVO(), null));
        assertNotNull(evidence.skipReason());
        verify(commands).run(eq(prepared), anyLong());
        verifyNoInteractions(sessions);
    }

    @Test void commandBackedQueriesAreHiddenFromChatAndPreservedForLegacyWorkflows() {
        Set<String> duplicates = Set.of("query_notes", "list_files", "list_categories", "search_code");
        Set<String> chat = toolNames("toolDefinitions");
        assertTrue(chat.stream().noneMatch(duplicates::contains));
        assertTrue(chat.containsAll(Set.of(RUN, PIPELINE, "get_note", "get_file", "get_code", "search_knowledge", "edit_note")));
        assertTrue(toolNames("builtinToolDefinitions").containsAll(duplicates));

        when(notes.page(null, null, "合成关键词", 1, 6)).thenReturn(PageResult.of(1, List.of(note("合成正文"))));
        JsonNode result = runTool("query_notes", "{\"keyword\":\"合成关键词\"}", new AiChatVO(), null);
        assertTrue(result.path("ok").asBoolean());
        assertEquals(12L, result.at("/notes/0/note_id").asLong());

        agent.setAppCommands(null);
        assertTrue(toolNames("toolDefinitions").containsAll(duplicates));
    }

    @Test void appMutationStagesSafePreviewAndRunsOnlyOnceAfterApproval() throws Exception {
        JsonNode input = json.readTree(UPDATE_INPUT);
        var prepared = prepared("note.update", "PUT", "/api/notes/{id}", input, true);
        JsonNode preview = json.readTree("{\"command\":\"note.update\",\"path\":{\"id\":12},\"body\":{\"title\":\"合成标题\"}}");
        when(prepared.preview()).thenReturn(preview);
        when(prepared.summary()).thenReturn("应用命令 note.update");
        when(notes.detail(12L)).thenReturn(note("当前正文"));
        mockStage(RUN);
        AiChatVO reply = new AiChatVO();

        JsonNode result = run("note.update", input, reply, null);

        assertTrue(result.path("staged").asBoolean());
        assertEquals(ACTION_ID, result.path("action_id").asLong());
        verify(commands, never()).run(any(), anyLong());
        assertNull(evidence.skipReason());
        assertEquals(1, reply.getPendingActions().size());
        Map<String, Object> actionBrief = reply.getPendingActions().getFirst();
        assertEquals(12L, actionBrief.get("noteId"));
        assertEquals(preview, actionBrief.get("preview"));
        assertFalse(json.writeValueAsString(actionBrief).contains("synthetic-private-input"));

        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(sessions).stageAction(eq(SESSION), eq(RUN), stored.capture(), eq("应用命令 note.update"));
        AgentPendingAction action = storedAction(RUN, stored.getValue(), "应用命令 note.update");
        when(sessions.claimAction(ACTION_ID)).thenReturn(action)
                .thenThrow(new IllegalStateException("已处理"));
        when(commands.run(eq(prepared), anyLong())).thenReturn(json.readTree("{\"ok\":true,\"result\":{}}"));

        Map<String, Object> approval = agent.executePendingAction(ACTION_ID);
        assertEquals(true, approval.get("ok"));
        assertEquals(12L, ((JsonNode) approval.get("noteEdit")).path("noteId").asLong());
        assertTrue(((JsonNode) approval.get("noteEdit")).path("metadataChanged").asBoolean());
        assertThrows(IllegalStateException.class, () -> agent.executePendingAction(ACTION_ID));
        verify(commands).run(eq(prepared), anyLong());
        verify(sessions).resolveAction(ACTION_ID, "approved");
    }

    @Test void modelPipelineFreezesTheReturnedPageAndApprovalNeverQueriesAgain() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        AtomicReference<JsonNode> sentBody = new AtomicReference<>();
        var firstPage = json.createObjectNode().put("total", 103);
        var list = firstPage.putArray("list");
        var expectedIds = json.createArrayNode();
        for (int id = 101; id < 190; id++) {
            list.addObject().put("id", id).put("title", "synthetic-private-read");
            expectedIds.add(id);
        }
        long largeId = 9_007_199_254_740_993L;
        list.addObject().put("id", largeId);
        expectedIds.add(largeId);
        var displayIds = json.createArrayNode();
        expectedIds.forEach(id -> displayIds.add(id.asText()));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/notes", exchange -> {
            reads.incrementAndGet();
            respond(exchange, firstPage);
        });
        server.createContext("/api/folders/move-items", exchange -> {
            writes.incrementAndGet();
            sentBody.set(json.readTree(exchange.getRequestBody()));
            respond(exchange, json.createObjectNode().put("moved", 90));
        });
        server.start();
        try {
            var page = new Command("note.page", "GET", "/api/notes", List.of(
                    new Argument("folderId", "query", false, "integer", null),
                    new Argument("page", "query", false, "integer", null),
                    new Argument("size", "query", false, "integer", null),
                    new Argument("kw", "query", false, "string", null)), null, "json");
            var move = new Command("folder.move-items", "POST", "/api/folders/move-items", List.of(),
                    new Body(true, Map.of("type", "object")), "json");
            agent.setAppCommands(new AgentAppCommands(json, () -> List.of(page, move), () -> server.getAddress().getPort()));
            mockStage(RUN);

            AiChatVO reply = modelPipelineReply("把这一页合成笔记移动到测试文件夹", PAGE_AND_MOVE);

            assertEquals(1, reads.get());
            assertEquals(0, writes.get());
            Map<String, Object> action = reply.getPendingActions().getFirst();
            assertEquals(RUN, action.get("toolName"));
            assertEquals("移动 90 篇笔记至文件夹 #42", action.get("summary"));
            JsonNode preview = (JsonNode) action.get("preview");
            assertFalse(preview.path("truncated").asBoolean());
            assertFalse(preview.has("input"));
            assertEquals(displayIds, preview.at("/selection/ids"));
            assertEquals("42", preview.at("/selection/folderId").textValue());
            assertEquals(90, preview.at("/pipeline/0/resultCount").asInt());
            assertEquals(103, preview.at("/pipeline/0/total").asInt());
            assertTrue(preview.at("/pipeline/0/partial").asBoolean());
            assertEquals("pending", preview.at("/pipeline/1/status").asText());
            String progress = json.writeValueAsString(reply.getProgress());
            assertTrue(progress.contains("执行应用命令管道"));
            assertFalse(progress.contains("synthetic-private"));

            ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
            verify(sessions).stageAction(eq(SESSION), eq(RUN), stored.capture(), eq((String) action.get("summary")));
            assertFalse(stored.getValue().contains("$ref"));
            assertFalse(stored.getValue().contains("synthetic-private"));
            when(sessions.claimAction(ACTION_ID)).thenReturn(storedAction(RUN, stored.getValue(), (String) action.get("summary")))
                    .thenThrow(new IllegalStateException("已处理"));
            // The original query would now return a different note. Approval uses the stored input.
            list.removeAll().addObject().put("id", 999);

            assertEquals(true, agent.executePendingAction(ACTION_ID).get("ok"));
            assertEquals(1, reads.get());
            assertEquals(1, writes.get());
            assertEquals(json.readTree(stored.getValue()).at("/input/body"), sentBody.get());
            assertEquals(expectedIds, sentBody.get().path("ids"));
            assertThrows(IllegalStateException.class, () -> agent.executePendingAction(ACTION_ID));
            verify(sessions).resolveAction(ACTION_ID, "approved");
        } finally {
            server.stop(0);
        }
    }

    @Test void modelPipelinePaginatesAndLoopsBeforeStagingOneBatch() throws Exception {
        String arguments = """
                {"steps":[
                  {"id":"notes","command":"note.page","paginate":true,"input":{"query":{"kw":"synthetic-query","size":2}}},
                  {"id":"details","command":"note.detail","forEach":{"$ref":"notes#/list"},"input":{"path":{"id":{"$item":"/id"}}}},
                  {"id":"move","command":"folder.move-items","input":{"body":{"kind":"note","folderId":42,"ids":{"$ref":"details#","$pluck":"/id"}}}}
                ]}
                """;
        var reads = new ConcurrentLinkedQueue<String>();
        AtomicInteger writes = new AtomicInteger();
        AtomicReference<JsonNode> sentBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/notes", exchange -> {
            var uri = exchange.getRequestURI();
            reads.add(uri.toString());
            if ("/api/notes".equals(uri.getPath())) {
                var result = json.createObjectNode().put("total", 3);
                var list = result.putArray("list");
                if (List.of(uri.getQuery().split("&")).contains("page=1")) {
                    list.addObject().put("id", 101);
                    list.addObject().put("id", 102);
                } else {
                    list.addObject().put("id", 103);
                }
                respond(exchange, result);
            } else {
                long id = Long.parseLong(uri.getPath().substring("/api/notes/".length()));
                respond(exchange, json.createObjectNode().put("id", id).put("content", "synthetic-private-detail"));
            }
        });
        server.createContext("/api/folders/move-items", exchange -> {
            writes.incrementAndGet();
            sentBody.set(json.readTree(exchange.getRequestBody()));
            respond(exchange, json.createObjectNode().put("moved", 3));
        });
        server.start();
        try {
            var page = new Command("note.page", "GET", "/api/notes", List.of(
                    new Argument("page", "query", false, "integer", null),
                    new Argument("size", "query", false, "integer", null),
                    new Argument("kw", "query", false, "string", null)), null, "json");
            var detail = new Command("note.detail", "GET", "/api/notes/{id}", List.of(
                    new Argument("id", "path", true, "integer", null)), null, "json");
            var move = new Command("folder.move-items", "POST", "/api/folders/move-items", List.of(),
                    new Body(true, Map.of("type", "object")), "json");
            agent.setAppCommands(new AgentAppCommands(json, () -> List.of(page, detail, move), () -> server.getAddress().getPort()));
            mockStage(RUN);

            AiChatVO reply = modelPipelineReply("查全部合成笔记，逐篇读取后移动到测试文件夹", arguments);

            List<String> stagedReads = List.copyOf(reads);
            assertEquals(5, stagedReads.size());
            for (int pageNumber = 1; pageNumber <= 2; pageNumber++) {
                String request = stagedReads.get(pageNumber - 1);
                assertTrue(request.startsWith("/api/notes?"));
                assertEquals(Set.of("kw=synthetic-query", "size=2", "page=" + pageNumber),
                        Set.of(request.substring(request.indexOf('?') + 1).split("&")));
            }
            assertEquals(List.of("/api/notes/101", "/api/notes/102", "/api/notes/103"), stagedReads.subList(2, 5));
            assertEquals(0, writes.get());
            assertEquals(1, reply.getPendingActions().size());
            Map<String, Object> action = reply.getPendingActions().getFirst();
            assertEquals("移动 3 篇笔记至文件夹 #42", action.get("summary"));
            JsonNode preview = (JsonNode) action.get("preview");
            assertEquals(json.readTree("[\"101\",\"102\",\"103\"]"), preview.at("/selection/ids"));
            assertEquals(2, preview.at("/pipeline/0/pageCount").asInt());
            assertEquals(3, preview.at("/pipeline/0/resultCount").asInt());
            assertFalse(preview.at("/pipeline/0/partial").asBoolean());
            assertEquals(3, preview.at("/pipeline/1/iterationCount").asInt());
            assertEquals("pending", preview.at("/pipeline/2/status").asText());

            ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
            verify(sessions).stageAction(eq(SESSION), eq(RUN), stored.capture(), eq((String) action.get("summary")));
            assertFalse(stored.getValue().contains("synthetic-private-detail"));
            when(sessions.claimAction(ACTION_ID)).thenReturn(storedAction(RUN, stored.getValue(), (String) action.get("summary")));
            assertEquals(true, agent.executePendingAction(ACTION_ID).get("ok"));
            assertEquals(stagedReads, List.copyOf(reads));
            assertEquals(1, writes.get());
            assertEquals(json.readTree("{\"kind\":\"note\",\"ids\":[101,102,103],\"folderId\":42}"), sentBody.get());
            verify(sessions).resolveAction(ACTION_ID, "approved");
        } finally {
            server.stop(0);
        }
    }

    @Test void readOnlyPipelineMarksUnverifiedEvidenceAndOmitsReadBodiesFromProgress() throws Exception {
        JsonNode input = json.readTree("{\"path\":{\"id\":12}}");
        var prepared = prepared("note.get", "GET", "/api/notes/{id}", input, false);
        when(commands.describe("note.get")).thenReturn(json.readTree("{\"ok\":true,\"available\":true,\"requiresApproval\":false}"));
        when(commands.run(eq(prepared), anyLong())).thenReturn(json.readTree("{\"ok\":true,\"result\":{\"content\":\"synthetic-private-read\"}}"));
        String arguments = "{\"steps\":[{\"id\":\"note\",\"command\":\"note.get\",\"input\":" + input + "}]}";

        JsonNode result = runTool(PIPELINE, arguments, new AiChatVO(), null);

        assertTrue(result.path("ok").asBoolean());
        assertTrue(result.toString().contains("synthetic-private-read"));
        assertNotNull(evidence.skipReason());
        assertFalse(json.writeValueAsString(AgentProgressDetails.result(PIPELINE, result)).contains("synthetic-private-read"));
        verifyNoInteractions(sessions);
    }

    @Test void unknownAndDirtyNoteCommandsDoNotStageActions() throws Exception {
        JsonNode input = json.readTree(UPDATE_INPUT);
        when(commands.prepare("unknown.action", input)).thenThrow(new IllegalArgumentException("未知命令"));
        prepared("note.update", "PUT", "/api/notes/{id}", input, true);
        var context = new AiChatRequest();
        context.setNoteId(12L);
        context.setNoteDirty(true);
        AiChatVO reply = new AiChatVO();

        assertEquals("未知命令", run("unknown.action", input, reply, context).path("error").asText());
        JsonNode dirty = run("note.update", input, reply, context);
        assertFalse(dirty.path("ok").asBoolean());
        assertTrue(dirty.path("error").asText().contains("自动保存"));
        assertTrue(reply.getPendingActions().isEmpty());
        verify(commands, never()).run(any(), anyLong());
        verifyNoInteractions(sessions);
    }

    @Test void incompleteReplacementCannotClearExistingNoteMetadata() throws Exception {
        JsonNode input = json.readTree("{\"path\":{\"id\":12},\"body\":{\"title\":\"合成标题\",\"content\":\"正文\"}}");
        prepared("note.update", "PUT", "/api/notes/{id}", input, true);

        JsonNode rejected = run("note.update", input, new AiChatVO(), null);
        assertFalse(rejected.path("ok").asBoolean());
        assertTrue(rejected.path("error").asText().contains("categoryId"));
        verifyNoInteractions(notes, sessions);
    }

    @Test void approvalRefusesToOverwriteAnAutosavedDraft() throws Exception {
        JsonNode input = json.readTree(UPDATE_INPUT);
        var prepared = prepared("note.update", "PUT", "/api/notes/{id}", input, true);
        when(prepared.summary()).thenReturn("应用命令 note.update");
        when(notes.detail(12L)).thenReturn(note("当前正文"), note("自动保存的新草稿"));
        mockStage(RUN);

        assertTrue(run("note.update", input, new AiChatVO(), null).path("staged").asBoolean());
        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(sessions).stageAction(eq(SESSION), eq(RUN), stored.capture(), anyString());
        when(sessions.claimAction(ACTION_ID)).thenReturn(storedAction(RUN, stored.getValue(), "应用命令 note.update"));

        Map<String, Object> result = agent.executePendingAction(ACTION_ID);
        assertEquals(false, result.get("ok"));
        assertEquals("笔记版本已变化，请重新准备操作", result.get("error"));
        verify(commands, never()).run(any(), anyLong());
        verify(sessions).resolveAction(ACTION_ID, "failed");
    }

    @Test void unvalidatedAppInputIsOmittedFromBothProgressViews() throws Exception {
        String arguments = "{\"command\":\"note.update\",\"input\":" + UPDATE_INPUT + "}";
        var calls = json.createArrayNode();
        calls.addObject().putObject("function").put("name", RUN).put("arguments", arguments);

        for (Map<String, Object> progress : List.of(AgentProgressDetails.tools(calls), AgentProgressDetails.tool(RUN, arguments))) {
            String serialized = json.writeValueAsString(progress);
            assertTrue(serialized.contains(RUN));
            assertFalse(serialized.contains("arguments"));
            assertFalse(serialized.contains("synthetic-private-input"));
        }
    }

    @Test void deletingOwnSessionCannotStageItsOwnApproval() throws Exception {
        JsonNode input = json.readTree("{\"path\":{\"id\":\"" + SESSION + "\"}}");
        prepared("ai.delete-session", "DELETE", "/api/ai/sessions/{id}", input, true);
        AiChatVO reply = new AiChatVO();

        assertFalse(run("ai.delete-session", input, reply, null).path("ok").asBoolean());
        assertTrue(reply.getPendingActions().isEmpty());
        verify(commands, never()).run(any(), anyLong());
        verifyNoInteractions(sessions);
    }

    @Test void targetedAppNoteEditsReuseTheVersionCheckedPreview() throws Exception {
        JsonNode input = json.readTree("""
                {"path":{"id":12},"body":{"expected_hash":"old-version","operations":[{"action":"delete","text":"旧"}]}}
                """);
        prepared("note.edit-content", "POST", "/api/notes/{id}/edit", input, true);
        when(notes.previewEdit(eq(12L), any())).thenReturn(new NoteEditVO(12L, "合成笔记", "old-version",
                "new-version", true, List.of(new NoteEditVO.Change("delete", "删除文字", 1, "旧知识", "知识"))));
        mockStage("edit_note");
        AiChatVO reply = new AiChatVO();

        assertTrue(run("note.edit-content", input, reply, null).path("staged").asBoolean());
        Map<String, Object> actionBrief = reply.getPendingActions().getFirst();
        assertEquals("edit_note", actionBrief.get("toolName"));
        assertEquals(12L, actionBrief.get("noteId"));
        JsonNode preview = (JsonNode) actionBrief.get("preview");
        assertEquals("旧知识", preview.path("changes").get(0).path("before").asText());
        verify(notes).previewEdit(eq(12L), argThat(request -> "old-version".equals(request.expectedHash())));
        verify(notes, never()).editContent(anyLong(), any());
        verify(commands, never()).run(any(), anyLong());
    }

    private Set<String> toolNames(String method) {
        List<?> definitions = ReflectionTestUtils.invokeMethod(agent, method);
        return definitions.stream().map(definition -> json.<JsonNode>valueToTree(definition)
                        .path("function").path("name").asText()).collect(Collectors.toSet());
    }

    private AgentAppCommands.Prepared prepared(String id, String method, String path, JsonNode input, boolean write) {
        var prepared = mock(AgentAppCommands.Prepared.class);
        when(commands.prepare(id, input)).thenReturn(prepared);
        when(prepared.requiresApproval()).thenReturn(write);
        if (write) {
            when(prepared.command()).thenReturn(new Command(id, method, path, List.of(), null, "json"));
            when(prepared.input()).thenReturn(input);
        }
        return prepared;
    }

    private void mockStage(String toolName) {
        when(sessions.stageAction(eq(SESSION), eq(toolName), anyString(), anyString())).thenAnswer(call ->
                storedAction(toolName, call.getArgument(2), call.getArgument(3)));
    }

    private AiChatVO modelPipelineReply(String requestText, String arguments) throws Exception {
        when(client.isConfigured()).thenReturn(true);
        when(sessions.ensure(isNull(), isNull(), eq(requestText))).thenReturn(SESSION);
        when(settings.effective(SettingsService.KEY_CHAT_PROMPT)).thenReturn("合成系统约定");
        when(retrieval.build(eq(requestText), anyInt(), eq("fused"), eq(false), eq(false)))
                .thenReturn(RetrievalContextService.Context.empty());
        when(routing.forTask(ModelRouting.TASK_CHAT)).thenReturn(new ModelRouting.ModelTarget(
                "synthetic", "synthetic", "http://unused.invalid", "dummy", "mock", true));
        var toolMessage = json.createObjectNode().put("role", "assistant");
        toolMessage.putArray("tool_calls").addObject().put("id", "synthetic-pipe").put("type", "function")
                .putObject("function").put("name", PIPELINE).put("arguments", arguments);
        var finalMessage = json.createObjectNode().put("role", "assistant").put("content", "已准备移动笔记，请确认。");
        when(client.chatFull(anyList(), nullable(List.class), anyString(), anyString(), anyString(), anyInt(),
                anyDouble(), nullable(String.class), nullable(String.class), any(Duration.class)))
                .thenReturn(new DeepSeekClient.ChatResult(toolMessage, "tool_calls", 3, 3, 0),
                        new DeepSeekClient.ChatResult(finalMessage, "stop", 3, 3, 0));
        AiChatRequest request = new AiChatRequest();
        request.setMessage(requestText);
        return agent.chat(request);
    }

    private void respond(HttpExchange exchange, JsonNode response) throws IOException {
        byte[] body = response.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private NoteVO note(String content) {
        var note = new NoteVO();
        note.setId(12L);
        note.setTitle("合成笔记");
        note.setContent(content);
        return note;
    }

    private AgentPendingAction storedAction(String toolName, String args, String summary) {
        AgentPendingAction action = new AgentPendingAction();
        action.setId(ACTION_ID);
        action.setSessionId(SESSION);
        action.setStatus("pending");
        action.setToolName(toolName);
        action.setArgsJson(args);
        action.setSummary(summary);
        return action;
    }

    private JsonNode run(String command, JsonNode input, AiChatVO reply, AiChatRequest context) {
        var args = json.createObjectNode().put("command", command);
        args.set("input", input);
        return runTool(RUN, args.toString(), reply, context);
    }

    private JsonNode runTool(String toolName, String arguments, AiChatVO reply, AiChatRequest context) {
        return ReflectionTestUtils.invokeMethod(agent, "runChatTool", toolName, arguments, new ArrayList<>(),
                SESSION, reply, context, new AgentService.WikiToolBudget(), evidence,
                new AgentService.EvolutionBudget(), System.currentTimeMillis() + 10_000);
    }
}
