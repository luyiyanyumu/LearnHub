package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.dyh.learnhub.dto.AiChatRequest;
import org.dyh.learnhub.dto.NoteEditRequest;
import org.dyh.learnhub.entity.AgentPendingAction;
import org.dyh.learnhub.entity.FileInfo;
import org.dyh.learnhub.service.AgentSessionService;
import org.dyh.learnhub.service.CodeLibraryService;
import org.dyh.learnhub.service.FileStorageService;
import org.dyh.learnhub.service.GroundingService;
import org.dyh.learnhub.service.NoteContentEditor;
import org.dyh.learnhub.service.NoteService;
import org.dyh.learnhub.service.RetrievalContextService;
import org.dyh.learnhub.service.SettingsService;
import org.dyh.learnhub.service.WordDocService;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Chat integration uses synthetic notes and model responses; no network or database. */
@ExtendWith(MockitoExtension.class)
class AgentEvolutionTest {
    private static final String QUESTION = "按现有能力补齐合成笔记工作流。";
    private static final String SESSION = "synthetic-evolution-session";
    private static final String ADAPTIVE = "adaptive_synthetic_note_workflow";
    private static final String BODY = "## 合成知识\n\n旧知识：观察反馈用于后续规划。";
    private static final String ANSWER = "已读到原文，已准备修改预览，请确认后写入。";

    private final ObjectMapper json = new ObjectMapper();
    @Mock DeepSeekClient client;
    @Mock ModelRouting routing;
    @Mock SettingsService settings;
    @Mock AgentSessionService sessions;
    @Mock RetrievalContextService contexts;
    @Mock GroundingService grounding;
    @Mock NoteService notes;
    @Mock CodeLibraryService codeLibrary;
    @Mock FileStorageService fileStorage;
    @Mock WordDocService wordDocs;
    @Mock ToolEvolutionService evolution;
    @InjectMocks AgentService agent;

    @BeforeEach void useMapper() {
        ReflectionTestUtils.setField(agent, "objectMapper", json);
    }

    @Test void nestedNoteEditStagesConcretePreviewAndCapturesTheActualReadEvidence() throws Exception {
        configure();
        sourceNote();
        enableAdaptive();
        mockPendingActions();
        String hash = NoteContentEditor.hash(BODY);
        var preview = new NoteEditVO(12L, "合成原文", hash, "synthetic-after-hash", true,
                List.of(new NoteEditVO.Change("delete", "删除文字", 1, "旧知识", "知识")));
        when(notes.previewEdit(eq(12L), any())).thenReturn(preview);
        when(evolution.execute(eq(ADAPTIVE), any(JsonNode.class), anyList(), anySet(), any(), any()))
                .thenAnswer(invocation -> {
                    var caller = nestedCaller(invocation.getArgument(4));
                    JsonNode read = caller.apply("get_note", json.valueToTree(Map.of("note_id", 12)));
                    assertEquals(BODY, read.path("content").asText());
                    return caller.apply("edit_note", editArgs(read.path("content_hash").asText()));
                });
        whenChat().thenReturn(toolRound(call("adaptive-edit", ADAPTIVE, Map.of("note_id", 12))), answer());

        AiChatVO result = chat(false);

        assertEquals(1, result.getPendingActions().size());
        assertEquals("edit_note", result.getPendingActions().getFirst().get("toolName"));
        JsonNode visiblePreview = (JsonNode) result.getPendingActions().getFirst().get("preview");
        assertEquals("旧知识", visiblePreview.path("changes").get(0).path("before").asText());
        ArgumentCaptor<String> staged = ArgumentCaptor.forClass(String.class);
        verify(sessions).stageAction(eq(SESSION), eq("edit_note"), staged.capture(), anyString());
        JsonNode payload = json.readTree(staged.getValue());
        assertEquals(hash, payload.path("expected_hash").asText());
        assertEquals("旧", payload.path("operations").get(0).path("text").asText());
        assertTrue(payload.has("_edit_preview"));
        verify(notes).previewEdit(eq(12L), argThat(request -> hash.equals(request.expectedHash())
                && request.operations().size() == 1 && "delete".equals(request.operations().getFirst().action())));
        verify(notes, never()).editContent(anyLong(), any(NoteEditRequest.class));
        verify(notes, never()).save(any());
        assertTrue(result.getRetrieved().stream().anyMatch(ref -> "note".equals(ref.get("type"))
                && Long.valueOf(12).equals(ref.get("id")) && "合成原文".equals(ref.get("title"))));
    }

    @Test void nestedNoteEditKeepsCurrentDraftProtectionAndDoesNotCreateADeadAction() throws Exception {
        configure();
        sourceNote();
        enableAdaptive();
        when(evolution.execute(eq(ADAPTIVE), any(JsonNode.class), anyList(), anySet(), any(), any()))
                .thenAnswer(invocation -> {
                    var caller = nestedCaller(invocation.getArgument(4));
                    JsonNode read = caller.apply("get_note", json.valueToTree(Map.of("note_id", 12)));
                    return caller.apply("edit_note", editArgs(read.path("content_hash").asText()));
                });
        whenChat().thenReturn(toolRound(call("adaptive-dirty", ADAPTIVE, Map.of("note_id", 12))), answer());

        AiChatVO result = chat(true);

        assertTrue(result.getPendingActions().isEmpty());
        verify(sessions, never()).stageAction(anyString(), anyString(), anyString(), anyString());
        verify(notes, never()).previewEdit(anyLong(), any());
        verify(notes, never()).editContent(anyLong(), any());
        assertTrue(toolResults().stream().anyMatch(output -> !output.path("ok").asBoolean()
                && output.path("error").asText().contains("自动保存尚未完成")));
    }

    @Test void nestedCreateNoteOnlyStagesTheConcreteBuiltinArguments() throws Exception {
        configure();
        sourceNote();
        enableAdaptive();
        mockPendingActions();
        when(evolution.execute(eq(ADAPTIVE), any(JsonNode.class), anyList(), anySet(), any(), any()))
                .thenAnswer(invocation -> {
                    var caller = nestedCaller(invocation.getArgument(4));
                    JsonNode read = caller.apply("get_note", json.valueToTree(Map.of("note_id", 12)));
                    return caller.apply("create_note", json.valueToTree(Map.of(
                            "title", "合成整理稿", "content", read.path("content").asText(),
                            "category_name", "合成分类")));
                });
        whenChat().thenReturn(toolRound(call("adaptive-create", ADAPTIVE, Map.of("note_id", 12))), answer());

        AiChatVO result = chat(false);

        assertEquals(1, result.getPendingActions().size());
        assertEquals("create_note", result.getPendingActions().getFirst().get("toolName"));
        ArgumentCaptor<String> staged = ArgumentCaptor.forClass(String.class);
        verify(sessions).stageAction(eq(SESSION), eq("create_note"), staged.capture(), anyString());
        JsonNode payload = json.readTree(staged.getValue());
        assertEquals("合成整理稿", payload.path("title").asText());
        assertEquals(BODY, payload.path("content").asText());
        assertEquals("合成分类", payload.path("category_name").asText());
        verify(notes, never()).save(any());
        verify(notes, never()).update(anyLong(), any());
        assertTrue(toolResults().stream().anyMatch(output -> output.path("staged").asBoolean()));
    }

    @Test void successfullyEvolvedToolIsVisibleAndCallableInTheNextModelRound() throws Exception {
        configure();
        AtomicBoolean registered = new AtomicBoolean();
        JsonNode definition = json.valueToTree(Map.of("name", ADAPTIVE, "description", "合成工作流",
                "parameters", Map.of("type", "object", "properties", Map.of()),
                "steps", List.of(Map.of("tool", "get_note", "args", Map.of("note_id", 12)))));
        JsonNode exposed = json.valueToTree(Map.of("type", "function", "function", Map.of(
                "name", ADAPTIVE, "description", "合成工作流", "parameters", Map.of("type", "object"))));
        when(evolution.evolve(eq(definition), anyList(), anySet(), eq(SESSION))).thenAnswer(invocation -> {
            registered.set(true);
            return Map.of("ok", true, "name", ADAPTIVE, "version", 1);
        });
        when(evolution.definitions(anyList(), anySet())).thenAnswer(invocation ->
                registered.get() ? List.of(exposed) : List.of());
        when(evolution.isAdaptive(ADAPTIVE)).thenAnswer(invocation -> registered.get());
        when(evolution.execute(eq(ADAPTIVE), any(JsonNode.class), anyList(), anySet(), any(), any()))
                .thenReturn(json.valueToTree(Map.of("ok", true, "message", "合成工具执行完成")));
        List<JsonNode> schemaSnapshots = new ArrayList<>();
        List<DeepSeekClient.ChatResult> replies = List.of(
                toolRound(call("evolve", "evolve_tool", Map.of("definition", definition))),
                toolRound(call("new-adaptive", ADAPTIVE, Map.of())), answer());
        whenChat().thenAnswer(invocation -> {
            schemaSnapshots.add(json.valueToTree(invocation.getArgument(1)).deepCopy());
            return replies.get(schemaSnapshots.size() - 1);
        });

        AiChatVO result = chat(false);

        assertEquals(ANSWER, result.getReply());
        assertEquals(3, schemaSnapshots.size());
        assertFalse(hasFunction(schemaSnapshots.getFirst(), ADAPTIVE));
        assertTrue(hasFunction(schemaSnapshots.get(1), ADAPTIVE));
        verify(evolution).evolve(eq(definition), anyList(), argThat((Set<String> writes) ->
                writes.containsAll(Set.of("edit_note", "create_note", "update_note"))), eq(SESSION));
        verify(evolution).execute(eq(ADAPTIVE), any(JsonNode.class), anyList(), anySet(), any(), any());
    }

    @Test void codeDryRunReturnsDiagnosticsWithoutRegisteringOrChangingNotes() throws Exception {
        configure();
        JsonNode definition = json.valueToTree(Map.of("name", "adaptive_synthetic_parser", "steps", List.of(
                Map.of("id", "parse", "op", "code", "language", "python", "source", "def run(input): return input", "input", Map.of()))));
        when(evolution.test(eq(definition), anyList(), anySet())).thenReturn(Map.of("ok", false,
                "tests", List.of(Map.of("name", "synthetic boundary", "passed", false, "error", "expected empty list"))));
        whenChat().thenReturn(toolRound(call("dry-run", "test_adaptive_tool", Map.of("definition", definition))), answer("测试未通过，准备修正边界处理。"));

        AiChatVO result = chat(false);

        verify(evolution).test(eq(definition), anyList(), anySet());
        verify(evolution, never()).evolve(any(), anyList(), anySet(), anyString());
        verify(sessions, never()).stageAction(anyString(), anyString(), anyString(), anyString());
        assertTrue(result.getEvents().stream().anyMatch(event -> event.contains("测试未通过")));
        assertTrue(toolResults().stream().anyMatch(node -> node.path("tests").isArray()
                && node.path("tests").get(0).path("error").asText().contains("empty list")));
    }

    @Test void genuineCapabilityRefusalHasOnlyOneRecoveryAttempt() {
        var budget = new AgentService.EvolutionBudget();
        long deadline = System.currentTimeMillis() + 60_000;

        assertFalse(budget.recover("已经完成知识整理。", deadline));
        assertTrue(budget.recover("当前工具不支持这个操作，无法完成。", deadline));
        assertFalse(budget.recover("缺少批量整理的工具。", deadline));
        assertTrue(budget.recovered);
    }

    @Test void versionRetestsShareTheDryRunBudgetAndDeadlineButDisablingAlwaysRemainsAvailable() throws Exception {
        configure();
        when(evolution.test(any(JsonNode.class), anyList(), anySet())).thenReturn(Map.of("ok", true));
        when(evolution.manage(eq(ADAPTIVE), anyString(), eq(1), anyList(), anySet())).thenReturn(Map.of("ok", true));
        whenChat().thenReturn(toolRound(
                call("test-before-enable", "test_adaptive_tool", Map.of("definition", Map.of())),
                call("enable", "manage_adaptive_tool", Map.of("name", ADAPTIVE, "action", "enable", "expected_version", 1)),
                call("rollback-over-budget", "manage_adaptive_tool", Map.of("name", ADAPTIVE, "action", "rollback", "expected_version", 1)),
                call("disable-after-budget", "manage_adaptive_tool", Map.of("name", ADAPTIVE, "action", "disable", "expected_version", 1))),
                answer("已完成可用操作，并保留工具停用入口。"));

        chat(false);

        verify(evolution).test(any(JsonNode.class), anyList(), anySet());
        verify(evolution).manage(eq(ADAPTIVE), eq("enable"), eq(1), anyList(), anySet());
        verify(evolution, never()).manage(eq(ADAPTIVE), eq("rollback"), anyInt(), anyList(), anySet());
        verify(evolution).manage(eq(ADAPTIVE), eq("disable"), eq(1), anyList(), anySet());
        assertTrue(toolResults().stream().anyMatch(result -> "evolution_budget_exhausted".equals(result.path("code").asText())));

        // Exercise a short deadline directly without sleeping or changing the production clock.
        var budget = new AgentService.EvolutionBudget();
        for (String action : List.of("enable", "disable")) {
            JsonNode result = ReflectionTestUtils.invokeMethod(agent, "runChatTool", "manage_adaptive_tool",
                    json.writeValueAsString(Map.of("name", ADAPTIVE, "action", action, "expected_version", 1)),
                    new ArrayList<String>(), SESSION, new AiChatVO(), new AiChatRequest(), new AgentService.WikiToolBudget(),
                    new AgentToolEvidence(RetrievalContextService.Context.empty(), json), budget, System.currentTimeMillis() + 1_000);
            assertNotNull(result);
            if (action.equals("enable")) assertEquals("evolution_budget_exhausted", result.path("code").asText());
            else assertTrue(result.path("ok").asBoolean());
        }
        verify(evolution, times(1)).manage(eq(ADAPTIVE), eq("enable"), eq(1), anyList(), anySet());
        verify(evolution, times(2)).manage(eq(ADAPTIVE), eq("disable"), eq(1), anyList(), anySet());
    }

    @Test void missingDataDraftPermissionsNetworkAndBudgetDoNotBecomeEvolutionTriggers() {
        long deadline = System.currentTimeMillis() + 60_000;
        List<String> blocked = List.of(
                "当前工具无法完成，因为资料不足。",
                "缺少工具，且缺少信息，需要先补充信息。",
                "当前工具无法完成：没有找到目标笔记。",
                "当前工具无法完成：当前笔记未保存。",
                "当前工具无法完成：缺少权限或授权。",
                "当前工具无法完成：网络故障导致超时。",
                "当前工具无法完成：本轮预算已经耗尽。",
                "Cannot complete the tool operation: permission denied.",
                "Cannot complete the tool operation: network timeout.");
        for (String reply : blocked) {
            var budget = new AgentService.EvolutionBudget();
            assertFalse(budget.recover(reply, deadline), reply);
            assertFalse(budget.recovered, "非能力缺口不能消耗真实缺口恢复机会：" + reply);
            assertTrue(budget.recover("当前工具不支持这个操作。", deadline), reply);
        }
    }

    @Test void recoveryRequiresEnoughRemainingTimeAndARealReply() {
        var budget = new AgentService.EvolutionBudget();

        assertFalse(budget.recover("当前工具不支持这个操作。", System.currentTimeMillis() - 1));
        assertFalse(budget.recover("当前工具不支持这个操作。", System.currentTimeMillis() + 1));
        assertFalse(budget.recover(null, System.currentTimeMillis() + 60_000));
        assertFalse(budget.recovered);
        assertTrue(budget.recover("当前工具不支持这个操作。", System.currentTimeMillis() + 60_000));
    }

    @Test void chatRecoversARefusalByInspectingThenReusingExistingSourceReadCapability() throws Exception {
        configure();
        sourceNote();
        String goal = "检查读取合成笔记的能力";
        String refused = "当前工具不支持这个操作，无法完成。";
        String completed = "已读取合成原文：观察反馈用于后续规划。";
        when(evolution.inspect(eq(goal), anyList())).thenReturn(Map.of("ok", true,
                "tools", List.of(Map.of("name", "get_note", "description", "读取原文")),
                "source_refs", List.of(Map.of("path", "synthetic/NoteService.java", "sha256", "synthetic-hash"))));
        List<DeepSeekClient.ChatResult> replies = List.of(
                answer(refused), toolRound(call("inspect", "inspect_capabilities", Map.of("goal", goal))),
                toolRound(call("read-after-recovery", "get_note", Map.of("note_id", 12))), answer(completed));
        List<JsonNode> messageSnapshots = new ArrayList<>();
        whenChat().thenAnswer(invocation -> {
            messageSnapshots.add(json.valueToTree(invocation.getArgument(0)).deepCopy());
            return replies.get(messageSnapshots.size() - 1);
        });

        AiChatVO result = chat(false);

        assertEquals(completed, result.getReply());
        assertEquals(4, messageSnapshots.size());
        assertTrue(messageSnapshots.get(1).toString().contains("能力缺口恢复"));
        long recoveryMessages = 0;
        for (JsonNode message : messageSnapshots.getLast())
            if ("user".equals(message.path("role").asText())
                    && message.path("content").asText().contains("【能力缺口恢复】")) recoveryMessages++;
        assertEquals(1, recoveryMessages);
        verify(evolution).inspect(eq(goal), anyList());
        verify(evolution, never()).evolve(any(JsonNode.class), anyList(), anySet(), anyString());
        verify(notes).detail(12L);
        assertTrue(result.getRetrieved().stream().anyMatch(ref -> "note".equals(ref.get("type"))
                && Long.valueOf(12).equals(ref.get("id"))));
        assertTrue(result.getEvents().stream().anyMatch(event -> event.contains("检测到能力缺口")));
        assertTrue(result.getPendingActions().isEmpty());
    }

    @Test void directWordGenerationIsStagedAndOnlyStoresBytesAfterApproval() throws Exception {
        configure();
        mockPendingActions();
        Map<String, Object> documentArgs = Map.of("title", "合成文档", "markdown", BODY, "summary", "合成说明");
        whenChat().thenReturn(toolRound(call("direct-word", "create_word_document", documentArgs)), answer());

        AiChatVO result = chat(false);

        assertEquals(1, result.getPendingActions().size());
        assertEquals("create_word_document", result.getPendingActions().getFirst().get("toolName"));
        verifyNoInteractions(wordDocs, fileStorage);
        ArgumentCaptor<String> staged = ArgumentCaptor.forClass(String.class);
        verify(sessions).stageAction(eq(SESSION), eq("create_word_document"), staged.capture(), anyString());
        assertEquals(json.valueToTree(documentArgs), json.readTree(staged.getValue()));
        assertTrue(toolResults().getFirst().path("staged").asBoolean());

        var action = new AgentPendingAction();
        action.setId(301L);
        action.setSessionId(SESSION);
        action.setToolName("create_word_document");
        action.setArgsJson(staged.getValue());
        action.setStatus("pending");
        action.setSummary("生成合成文档");
        when(sessions.claimAction(301L)).thenReturn(action);
        byte[] generated = new byte[] {80, 75, 3, 4};
        when(wordDocs.render("合成文档", BODY)).thenReturn(new WordDocService.Doc("合成文档.docx", generated));
        var stored = new FileInfo();
        stored.setId(42L);
        stored.setOriginName("合成文档.docx");
        stored.setSize((long) generated.length);
        when(fileStorage.uploadBytes(eq("合成文档.docx"), any(byte[].class), isNull())).thenReturn(stored);

        Map<String, Object> approved = agent.executePendingAction(301L);

        assertEquals(true, approved.get("ok"));
        assertEquals("/api/files/42/download", json.readTree((String) approved.get("result")).path("download_url").asText());
        verify(wordDocs).render("合成文档", BODY);
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(fileStorage).uploadBytes(eq("合成文档.docx"), bytes.capture(), isNull());
        assertArrayEquals(generated, bytes.getValue());
        verify(fileStorage).updateSummary(42L, "合成说明");
        verify(sessions).resolveAction(301L, "approved");
    }

    @Test void adaptiveWordGenerationUsesConcreteBuiltinApprovalInsteadOfWritingImmediately() throws Exception {
        configure();
        sourceNote();
        enableAdaptive();
        mockPendingActions();
        when(evolution.execute(eq(ADAPTIVE), any(JsonNode.class), anyList(), anySet(), any(), any()))
                .thenAnswer(invocation -> {
                    var caller = nestedCaller(invocation.getArgument(4));
                    JsonNode note = caller.apply("get_note", json.valueToTree(Map.of("note_id", 12)));
                    return caller.apply("create_word_document", json.valueToTree(Map.of(
                            "title", "合成导出稿", "markdown", note.path("content").asText())));
                });
        whenChat().thenReturn(toolRound(call("adaptive-word", ADAPTIVE, Map.of("note_id", 12))), answer());

        AiChatVO result = chat(false);

        assertEquals(1, result.getPendingActions().size());
        assertEquals("create_word_document", result.getPendingActions().getFirst().get("toolName"));
        ArgumentCaptor<String> staged = ArgumentCaptor.forClass(String.class);
        verify(sessions).stageAction(eq(SESSION), eq("create_word_document"), staged.capture(), anyString());
        JsonNode payload = json.readTree(staged.getValue());
        assertEquals("合成导出稿", payload.path("title").asText());
        assertEquals(BODY, payload.path("markdown").asText());
        verifyNoInteractions(wordDocs, fileStorage);
        verify(evolution).execute(eq(ADAPTIVE), any(JsonNode.class), anyList(),
                argThat((Set<String> writes) -> writes.contains("create_word_document")), any(), any());
    }

    @Test void directCodeSearchAndReadPlainTextArePreservedInModelToolResults() throws Exception {
        configure();
        String searchText = "· [id=21] 合成规划器（java） 命中符号 SyntheticPlanner @ 第1行";
        String codeText = "【合成规划器】 语言：java\npublic class SyntheticPlanner { }\n说明：合成示例。";
        when(codeLibrary.searchForAgent("SyntheticPlanner", "java", "symbol", 8)).thenReturn(searchText);
        when(codeLibrary.detailForAgent(21L)).thenReturn(codeText);
        whenChat().thenReturn(toolRound(
                call("direct-search", "search_code", Map.of("keyword", "SyntheticPlanner", "lang", "java", "mode", "symbol")),
                call("direct-read", "get_code", Map.of("snippet_id", 21))), answer("已读取合成代码实现。"));

        AiChatVO result = chat(false);

        assertEquals("已读取合成代码实现。", result.getReply());
        List<JsonNode> outputs = toolResults();
        assertEquals(2, outputs.size());
        assertTrue(outputs.getFirst().path("ok").asBoolean(), outputs.getFirst().toString());
        assertTrue(outputs.getFirst().path("text").asText().contains(searchText));
        assertTrue(outputs.get(1).path("ok").asBoolean(), outputs.get(1).toString());
        assertEquals(codeText, outputs.get(1).path("text").asText());
        assertTrue(result.getPendingActions().isEmpty());
    }

    @Test void adaptiveCodeSearchAndReadReceiveTheSameStructuredPlainTextWrapper() throws Exception {
        configure();
        enableAdaptive();
        String searchText = "· [id=21] 合成规划器 命中 SyntheticPlanner";
        String codeText = "【合成规划器】\npublic class SyntheticPlanner { }";
        when(codeLibrary.searchForAgent("SyntheticPlanner", null, "all", 8)).thenReturn(searchText);
        when(codeLibrary.detailForAgent(21L)).thenReturn(codeText);
        when(evolution.execute(eq(ADAPTIVE), any(JsonNode.class), anyList(), anySet(), any(), any()))
                .thenAnswer(invocation -> {
                    var caller = nestedCaller(invocation.getArgument(4));
                    JsonNode search = caller.apply("search_code", json.valueToTree(Map.of("keyword", "SyntheticPlanner")));
                    JsonNode read = caller.apply("get_code", json.valueToTree(Map.of("snippet_id", 21)));
                    return json.valueToTree(Map.of("ok", true, "search", search, "code", read));
                });
        whenChat().thenReturn(toolRound(call("adaptive-code", ADAPTIVE, Map.of())), answer("已读取合成代码实现。"));

        AiChatVO result = chat(false);

        JsonNode output = toolResults().getFirst();
        assertTrue(output.path("search").path("ok").asBoolean(), output.toString());
        assertTrue(output.path("search").path("text").asText().contains(searchText));
        assertTrue(output.path("code").path("ok").asBoolean(), output.toString());
        assertEquals(codeText, output.path("code").path("text").asText());
        verify(codeLibrary).searchForAgent("SyntheticPlanner", null, "all", 8);
        verify(codeLibrary).detailForAgent(21L);
        assertTrue(result.getPendingActions().isEmpty());
    }

    @Test void internalAuditFailureDoesNotTurnAnAlreadyStagedBuiltinActionIntoExecutionFailure() throws Exception {
        configure();
        enableAdaptive();
        mockPendingActions();
        doAnswer(invocation -> {
            String role = invocation.getArgument(1);
            String content = invocation.getArgument(2);
            String tool = invocation.getArgument(3);
            if (AgentSessionService.ROLE_TOOL.equals(role) && "create_note".equals(tool)
                    && content != null && content.startsWith("自适应工具 "))
                throw new IllegalStateException("合成审计写入暂不可用");
            return null;
        }).when(sessions).append(anyString(), anyString(), anyString(), nullable(String.class), nullable(String.class));
        when(evolution.execute(eq(ADAPTIVE), any(JsonNode.class), anyList(), anySet(), any(), any()))
                .thenAnswer(invocation -> nestedCaller(invocation.getArgument(4)).apply("create_note",
                        json.valueToTree(Map.of("title", "合成待确认稿", "content", BODY))));
        whenChat().thenReturn(toolRound(call("adaptive-audit-failure", ADAPTIVE, Map.of())), answer());

        AiChatVO result = chat(false);

        assertEquals(1, result.getPendingActions().size());
        assertEquals("create_note", result.getPendingActions().getFirst().get("toolName"));
        JsonNode output = toolResults().getFirst();
        assertTrue(output.path("ok").asBoolean(), output.toString());
        assertTrue(output.path("staged").asBoolean(), output.toString());
        assertEquals(301L, output.path("action_id").asLong());
        verify(sessions).stageAction(eq(SESSION), eq("create_note"), anyString(), anyString());
        verify(sessions).append(eq(SESSION), eq(AgentSessionService.ROLE_TOOL),
                argThat(content -> content != null && content.startsWith("自适应工具 ")),
                eq("create_note"), isNull());
        verify(sessions).append(eq(SESSION), eq(AgentSessionService.ROLE_TOOL), anyString(),
                eq(ADAPTIVE), eq("adaptive-audit-failure"));
        verify(notes, never()).save(any());
        verify(notes, never()).editContent(anyLong(), any());
        verifyNoInteractions(wordDocs, fileStorage);
    }

    private void configure() {
        when(client.isConfigured()).thenReturn(true);
        when(sessions.ensure(isNull(), eq(12L), eq(QUESTION))).thenReturn(SESSION);
        when(settings.effective(SettingsService.KEY_CHAT_PROMPT)).thenReturn("合成测试系统提示");
        when(contexts.build(QUESTION, 8, "fused", false, false)).thenReturn(RetrievalContextService.Context.empty());
        when(routing.forTask(ModelRouting.TASK_CHAT)).thenReturn(new ModelRouting.ModelTarget(
                "synthetic", "synthetic", "http://unused.invalid", "dummy", "mock", true));
        when(evolution.definitions(anyList(), anySet())).thenReturn(List.of());
    }

    private void sourceNote() {
        var note = new NoteVO();
        note.setId(12L);
        note.setTitle("合成原文");
        note.setContent(BODY);
        when(notes.detail(12L)).thenReturn(note);
    }

    private void enableAdaptive() {
        when(evolution.isAdaptive(ADAPTIVE)).thenReturn(true);
    }

    private void mockPendingActions() {
        when(sessions.stageAction(eq(SESSION), anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            var pending = new AgentPendingAction();
            pending.setId(301L);
            pending.setSessionId(SESSION);
            pending.setToolName(invocation.getArgument(1));
            pending.setArgsJson(invocation.getArgument(2));
            pending.setSummary(invocation.getArgument(3));
            pending.setStatus("pending");
            return pending;
        });
    }

    @SuppressWarnings("unchecked")
    private static BiFunction<String, JsonNode, JsonNode> nestedCaller(Object callback) {
        return (BiFunction<String, JsonNode, JsonNode>) callback;
    }

    private JsonNode editArgs(String hash) {
        return json.valueToTree(Map.of("note_id", 12, "expected_hash", hash,
                "operations", List.of(Map.of("action", "delete", "text", "旧"))));
    }

    private org.mockito.stubbing.OngoingStubbing<DeepSeekClient.ChatResult> whenChat() throws Exception {
        return when(client.chatFull(anyList(), nullable(List.class), anyString(), anyString(), anyString(), anyInt(),
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
        return answer(ANSWER);
    }

    private DeepSeekClient.ChatResult answer(String content) {
        return new DeepSeekClient.ChatResult(json.createObjectNode().put("content", content), "stop", 1, 1, 0);
    }

    private AiChatVO chat(boolean dirty) {
        var request = new AiChatRequest();
        request.setMessage(QUESTION);
        request.setNoteId(12L);
        request.setNoteDirty(dirty);
        return agent.chat(request);
    }

    private static boolean hasFunction(JsonNode definitions, String name) {
        for (JsonNode definition : definitions)
            if (name.equals(definition.path("function").path("name").asText())) return true;
        return false;
    }

    private List<JsonNode> toolResults() throws Exception {
        ArgumentCaptor<List<?>> messages = ArgumentCaptor.forClass(List.class);
        verify(client, atLeastOnce()).chatFull(messages.capture(), nullable(List.class), anyString(), anyString(), anyString(),
                anyInt(), anyDouble(), nullable(String.class), nullable(String.class), any(Duration.class));
        List<List<?>> unique = new ArrayList<>();
        List<JsonNode> outputs = new ArrayList<>();
        for (List<?> request : messages.getAllValues()) {
            if (unique.stream().anyMatch(prior -> prior == request)) continue;
            unique.add(request);
            for (Object message : request) {
                JsonNode node = json.valueToTree(message);
                if ("tool".equals(node.path("role").asText())) outputs.add(json.readTree(node.path("content").asText()));
            }
        }
        return outputs;
    }
}
