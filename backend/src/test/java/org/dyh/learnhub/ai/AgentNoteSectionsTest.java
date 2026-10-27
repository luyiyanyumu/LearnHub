package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.dyh.learnhub.dto.AiChatRequest;
import org.dyh.learnhub.dto.NoteEditRequest;
import org.dyh.learnhub.entity.AgentPendingAction;
import org.dyh.learnhub.service.AgentSessionService;
import org.dyh.learnhub.service.NoteContentEditor;
import org.dyh.learnhub.service.NoteService;
import org.dyh.learnhub.vo.AiChatVO;
import org.dyh.learnhub.vo.NoteEditVO;
import org.dyh.learnhub.vo.NoteVO;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentNoteSectionsTest {
    private static final String ORIGINAL = "## 合成甲\n\n保留甲。\n\n## 合成乙\n\n保留乙。\n\n## 合成丙\n\n保留丙。\n\n## 合成丁\n\n保留丁。";
    private static final String HASH = NoteContentEditor.hash(ORIGINAL);
    private static final ModelRouting.ModelTarget TARGET = new ModelRouting.ModelTarget("synthetic", "synthetic", "http://unused.invalid", "dummy", "mock", true);
    private final ObjectMapper json = new ObjectMapper();
    private String originalContent = ORIGINAL;
    private String previewContent;
    @Mock DeepSeekClient client;
    @Mock ModelRouting routing;
    @Mock NoteService notes;
    @Mock AgentSessionService sessions;
    @InjectMocks AgentService agent;

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(agent, "objectMapper", json);
        useNoteContent(ORIGINAL);
        lenient().when(routing.forTask(ModelRouting.TASK_CHAT)).thenReturn(TARGET);
    }

    @Test void parallelTasksProduceOneVersionedApprovalAndNoPartialWrites() throws Exception {
        AtomicInteger running = new AtomicInteger(), peak = new AtomicInteger();
        CountDownLatch firstThree = new CountDownLatch(3);
        model().thenAnswer(inv -> {
            int active = running.incrementAndGet(); peak.accumulateAndGet(active, Math::max);
            firstThree.countDown();
            assertTrue(firstThree.await(2, TimeUnit.SECONDS));
            try {
                JsonNode messages = json.valueToTree(inv.getArgument(0));
                assertTrue(messages.get(0).path("content").asText().contains("无工具权限"));
                assertNull(inv.getArgument(1), "子智能体不能获得写入工具");
                return response("\n\n合成补充正文。\n\n$$\nx^2 + y^2 = z^2\n$$\n", "stop");
            } finally { running.decrementAndGet(); }
        });
        stageUsingRealEditor();
        AiChatVO vo = new AiChatVO();
        JsonNode result = run(tasks(4), vo);
        assertTrue(result.path("staged").asBoolean());
        assertEquals(3, peak.get());
        assertEquals(1, vo.getPendingActions().size());
        assertEquals(12L, vo.getPendingActions().getFirst().get("noteId"));
        verify(sessions, times(1)).stageAction(eq("synthetic-session"), eq("edit_note"), argThat(raw -> {
            try {
                JsonNode edit = json.readTree(raw);
                return HASH.equals(edit.path("expected_hash").asText()) && edit.path("operations").size() == 4;
            } catch (Exception failure) { return false; }
        }), anyString());
        verify(notes, never()).editContent(anyLong(), any());
    }

    @Test void anyTruncatedChildRejectsTheEntireBatch() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        model().thenAnswer(inv -> response("合成片段正文。", calls.getAndIncrement() == 0 ? "length" : "stop"));
        JsonNode result = run(tasks(2), new AiChatVO());
        assertFalse(result.path("ok").asBoolean());
        assertEquals("note_sections_incomplete", result.path("code").asText());
        verify(sessions, never()).stageAction(any(), any(), any(), any());
        verify(notes, never()).previewEdit(anyLong(), any());
        verify(notes, never()).editContent(anyLong(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"## 合成甲", "合成甲"})
    void multipleChildrenAtTheSameAnchorPreserveTheRequestedOrder(String secondAnchor) throws Exception {
        ObjectNode args = tasks(2);
        ((ObjectNode) args.path("tasks").get(1)).put("text", secondAnchor);
        model().thenAnswer(inv -> {
            String user = json.valueToTree(inv.getArgument(0)).get(1).path("content").asText();
            return response(user.contains("负责分段：合成甲") ? "第一项正文。" : "第二项正文。", "stop");
        });
        stageUsingRealEditor();
        assertTrue(run(args, new AiChatVO()).path("staged").asBoolean());
        verify(sessions).stageAction(anyString(), eq("edit_note"), argThat(raw -> {
            try {
                JsonNode operations = json.readTree(raw).path("operations");
                String body = operations.get(0).path("value").asText();
                return operations.size() == 1 && body.indexOf("第一项正文。") >= 0
                        && body.indexOf("第一项正文。") < body.indexOf("第二项正文。");
            } catch (Exception failure) { return false; }
        }), anyString());
        assertIndependentHeadings();
        assertEquals(List.of("第一项正文。", "第二项正文。", "保留甲。", "保留乙。", "保留丙。", "保留丁。"),
                topLevelText(Paragraph.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"## 合成甲", "合成甲", "## 合成甲\n\n保留甲。\n\n"})
    void generatedBodyWithoutBoundaryNewlinesDoesNotMergeWithAnAfterAnchor(String anchor) throws Exception {
        ObjectNode args = tasks(1);
        ((ObjectNode) args.path("tasks").get(0)).put("text", anchor);
        model().thenReturn(response("新增正文。", "stop"));
        stageUsingRealEditor();

        assertTrue(run(args, new AiChatVO()).path("staged").asBoolean());

        assertIndependentHeadings();
        List<String> paragraphs = anchor.contains("保留甲。")
                ? List.of("保留甲。", "新增正文。", "保留乙。", "保留丙。", "保留丁。")
                : List.of("新增正文。", "保留甲。", "保留乙。", "保留丙。", "保留丁。");
        assertEquals(paragraphs, topLevelText(Paragraph.class));
    }

    @Test void generatedDuplicateHeadingCannotRetargetALaterOriginalHeading() throws Exception {
        model().thenAnswer(inv -> {
            String user = json.valueToTree(inv.getArgument(0)).get(1).path("content").asText();
            return response(user.contains("负责分段：合成甲")
                    ? "## 合成乙\n\n甲中的示例正文。" : "乙专属补充。", "stop");
        });
        stageUsingRealEditor();

        assertTrue(run(tasks(2), new AiChatVO()).path("staged").asBoolean());

        assertEquals(List.of("H2:合成甲", "H2:合成乙", "P:甲中的示例正文。", "P:保留甲。",
                        "H2:合成乙", "P:乙专属补充。", "P:保留乙。", "H2:合成丙", "P:保留丙。", "H2:合成丁", "P:保留丁。"),
                topLevelDescriptions());
        verify(sessions, times(1)).stageAction(anyString(), eq("edit_note"), anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"## 合成乙", "合成乙"})
    void generatedBodyBeforeAHeadingDoesNotConsumeTheOriginalHeading(String anchor) throws Exception {
        ObjectNode args = tasks(1);
        ((ObjectNode) args.path("tasks").get(0)).put("action", "insert_before").put("text", anchor);
        model().thenReturn(response("新增正文。", "stop"));
        stageUsingRealEditor();

        assertTrue(run(args, new AiChatVO()).path("staged").asBoolean());

        assertIndependentHeadings();
        assertEquals(List.of("保留甲。", "新增正文。", "保留乙。", "保留丙。", "保留丁。"),
                topLevelText(Paragraph.class));
    }

    @ParameterizedTest
    @CsvSource({"insert_after, 合成甲", "insert_before, 合成甲",
            "insert_after, ## 合成甲 ##", "insert_before, ## 合成甲 ##"})
    void closedAtxHeadingKeepsItsClosingMarkersAndOwnBlock(String action, String anchor) throws Exception {
        useNoteContent(ORIGINAL.replace("## 合成甲", "## 合成甲 ##"));
        ObjectNode args = tasks(1);
        ((ObjectNode) args.path("tasks").get(0)).put("action", action).put("text", anchor);
        model().thenReturn(response("新增正文。", "stop"));
        stageUsingRealEditor();

        assertTrue(run(args, new AiChatVO()).path("staged").asBoolean());

        assertIndependentHeadings();
        assertTrue(previewContent.contains("## 合成甲 ##"), "原始 ATX 闭合标记必须保留");
        assertEquals(action.equals("insert_after")
                        ? List.of("H2:合成甲", "P:新增正文。", "P:保留甲。", "H2:合成乙", "P:保留乙。", "H2:合成丙", "P:保留丙。", "H2:合成丁", "P:保留丁。")
                        : List.of("P:新增正文。", "H2:合成甲", "P:保留甲。", "H2:合成乙", "P:保留乙。", "H2:合成丙", "P:保留丙。", "H2:合成丁", "P:保留丁。"),
                topLevelDescriptions());
    }

    @ParameterizedTest
    @ValueSource(strings = {"insert_after", "insert_before"})
    void bareSetextHeadingAnchorInsertsOutsideBothHeadingLines(String action) throws Exception {
        useNoteContent(ORIGINAL.replace("## 合成甲", "合成甲\n-----"));
        ObjectNode args = tasks(1);
        ((ObjectNode) args.path("tasks").get(0)).put("action", action).put("text", "合成甲");
        model().thenReturn(response("新增正文。", "stop"));
        stageUsingRealEditor();

        assertTrue(run(args, new AiChatVO()).path("staged").asBoolean());

        assertIndependentHeadings();
        assertTrue(previewContent.contains("合成甲\n-----"), "Setext 标题及下划线必须保持相邻");
        assertEquals(action.equals("insert_after")
                        ? List.of("H2:合成甲", "P:新增正文。", "P:保留甲。", "H2:合成乙", "P:保留乙。", "H2:合成丙", "P:保留丙。", "H2:合成丁", "P:保留丁。")
                        : List.of("P:新增正文。", "H2:合成甲", "P:保留甲。", "H2:合成乙", "P:保留乙。", "H2:合成丙", "P:保留丙。", "H2:合成丁", "P:保留丁。"),
                topLevelDescriptions());
    }

    @ParameterizedTest
    @ValueSource(strings = {"insert_after", "insert_before"})
    void crlfHeadingAndGeneratedParagraphsRemainIndependent(String action) throws Exception {
        useNoteContent(ORIGINAL.replace("\n", "\r\n"));
        String body = "新增正文。\r\n\r\n末段正文。";
        ObjectNode args = tasks(1);
        ((ObjectNode) args.path("tasks").get(0)).put("action", action).put("text", "合成乙");
        model().thenReturn(response(body, "stop"));
        stageUsingRealEditor();

        assertTrue(run(args, new AiChatVO()).path("staged").asBoolean());

        assertIndependentHeadings();
        assertTrue(previewContent.contains(body), "模型正文内部 CRLF 不应重写");
        assertTrue(previewContent.contains("## 合成甲\r\n\r\n保留甲。"), "非目标原文换行必须保留");
        assertEquals(action.equals("insert_after")
                        ? List.of("H2:合成甲", "P:保留甲。", "H2:合成乙", "P:新增正文。", "P:末段正文。", "P:保留乙。", "H2:合成丙", "P:保留丙。", "H2:合成丁", "P:保留丁。")
                        : List.of("H2:合成甲", "P:保留甲。", "P:新增正文。", "P:末段正文。", "H2:合成乙", "P:保留乙。", "H2:合成丙", "P:保留丙。", "H2:合成丁", "P:保留丁。"),
                topLevelDescriptions());
    }

    @ParameterizedTest
    @ValueSource(strings = {"insert_after", "insert_before"})
    void duplicateHeadingOccurrenceKeepsTheSecondHeadingAsTheTarget(String action) throws Exception {
        useNoteContent(ORIGINAL.replace("合成乙", "合成甲"));
        ObjectNode args = tasks(1);
        ((ObjectNode) args.path("tasks").get(0)).put("action", action).put("text", "合成甲").put("occurrence", 2);
        model().thenReturn(response("新增正文。", "stop"));
        stageUsingRealEditor();

        assertTrue(run(args, new AiChatVO()).path("staged").asBoolean());

        assertEquals(List.of("合成甲", "合成甲", "合成丙", "合成丁"), topLevelText(Heading.class));
        assertEquals(action.equals("insert_after")
                        ? List.of("H2:合成甲", "P:保留甲。", "H2:合成甲", "P:新增正文。", "P:保留乙。", "H2:合成丙", "P:保留丙。", "H2:合成丁", "P:保留丁。")
                        : List.of("H2:合成甲", "P:保留甲。", "P:新增正文。", "H2:合成甲", "P:保留乙。", "H2:合成丙", "P:保留丙。", "H2:合成丁", "P:保留丁。"),
                topLevelDescriptions());
    }

    @Test void replacementBodyPreservesItsExactWhitespaceAndInternalMarkdown() throws Exception {
        String body = "核心答案。\n\n$$\nx^2 + y^2 = z^2\n$$\n\n    int preserved = 1;\n\n结尾正文。";
        ObjectNode args = tasks(1);
        ((ObjectNode) args.path("tasks").get(0)).put("action", "replace").put("text", "保留乙。");
        model().thenReturn(response(body, "stop"));
        stageUsingRealEditor();

        assertTrue(run(args, new AiChatVO()).path("staged").asBoolean());

        assertEquals(ORIGINAL.replace("保留乙。", body), previewContent);
        verify(sessions).stageAction(anyString(), eq("edit_note"), argThat(raw -> {
            try {
                return body.equals(json.readTree(raw).path("operations").get(0).path("value").asText());
            } catch (Exception failure) { return false; }
        }), anyString());
    }

    @Test void unclosedFormulaRejectsAllOtherwiseCompleteChildren() throws Exception {
        model().thenReturn(response("合成片段正文。\n\n$$\nx + y", "stop"));
        assertFalse(run(tasks(2), new AiChatVO()).path("ok").asBoolean());
        verify(sessions, never()).stageAction(any(), any(), any(), any());
        assertTrue(AgentService.completeMarkdownFragment("公式：\n\\[\nx+y\n\\]\n\n```java\nString x = \"$$\";\n```"));
        assertFalse(AgentService.completeMarkdownFragment("正文\n```python\nprint(1)"));
    }

    @Test void staleInitialVersionStopsBeforeModelGeneration() throws Exception {
        ObjectNode args = tasks(2); args.put("expected_hash", "stale");
        assertThrows(IllegalArgumentException.class, () -> run(args, new AiChatVO()));
        verifyNoInteractions(client, sessions);
    }

    @Test void versionChangedDuringGenerationCannotCreateApproval() throws Exception {
        model().thenReturn(response("合成完整片段。", "stop"));
        when(notes.previewEdit(eq(12L), any())).thenThrow(new IllegalArgumentException("笔记正文已变化"));
        assertThrows(IllegalArgumentException.class, () -> run(tasks(2), new AiChatVO()));
        verify(sessions, never()).stageAction(any(), any(), any(), any());
        verify(notes, never()).editContent(anyLong(), any());
    }

    @Test void ambiguousAnchorIsRejectedBeforeSpawningChildren() throws Exception {
        ObjectNode args = tasks(2);
        ((ObjectNode) args.path("tasks").get(0)).put("text", "合成");
        assertThrows(IllegalArgumentException.class, () -> run(args, new AiChatVO()));
        verifyNoInteractions(client, sessions);
    }

    @Test void multiSectionMergeLocationKeepsAllTargetsAndLegacyFirstTarget() {
        java.util.Map<String, Object> parsed = ReflectionTestUtils.invokeMethod(agent, "parseLocateResult", """
                {"targets":[{"action":"merge","index":2,"heading":"合成甲","reason":"补充定义","answer":"合成定义"},
                {"action":"merge","index":4,"heading":"合成乙","reason":"补充示例","answer":"合成示例"}]}
                """);
        assertEquals(2, parsed.get("index"));
        assertEquals(2, ((List<?>) parsed.get("targets")).size());
        assertTrue(parsed.toString().contains("合成示例"));
        java.util.Map<String, Object> legacy = ReflectionTestUtils.invokeMethod(agent, "parseLocateResult",
                "{\"action\":\"merge\",\"index\":3,\"heading\":\"合成旧目标\"}");
        assertEquals(1, ((List<?>) legacy.get("targets")).size());
    }

    @Test void duplicateOrUnnumberedMergeTargetsAreRejected() {
        assertThrows(IllegalStateException.class, () -> ReflectionTestUtils.invokeMethod(agent, "parseLocateResult",
                "{\"targets\":[{\"action\":\"merge\",\"index\":1},{\"action\":\"merge\",\"index\":1}]}"));
        assertThrows(IllegalStateException.class, () -> ReflectionTestUtils.invokeMethod(agent, "parseLocateResult",
                "{\"targets\":[{\"action\":\"merge\",\"index\":0}]}"));
    }

    private void stageUsingRealEditor() {
        when(notes.previewEdit(eq(12L), any())).thenAnswer(inv -> {
            NoteEditRequest request = inv.getArgument(1);
            var patched = new NoteContentEditor().apply(originalContent, request);
            previewContent = patched.content();
            assertTrue(patched.content().contains("保留甲。"));
            assertTrue(patched.content().contains("保留丁。"));
            return new NoteEditVO(12L, "合成笔记", NoteContentEditor.hash(originalContent), NoteContentEditor.hash(patched.content()), true, patched.changes());
        });
        when(sessions.stageAction(anyString(), eq("edit_note"), anyString(), anyString())).thenAnswer(inv -> {
            AgentPendingAction action = new AgentPendingAction(); action.setId(9L); action.setStatus("pending");
            action.setToolName("edit_note"); action.setArgsJson(inv.getArgument(2)); action.setSummary(inv.getArgument(3));
            return action;
        });
    }

    private void useNoteContent(String content) {
        originalContent = content;
        NoteVO note = new NoteVO(); note.setId(12L); note.setTitle("合成笔记"); note.setContent(content);
        lenient().when(notes.detail(12L)).thenReturn(note);
    }

    private void assertIndependentHeadings() {
        assertEquals(List.of("合成甲", "合成乙", "合成丙", "合成丁"), topLevelText(Heading.class));
        for (Node block = Parser.builder().build().parse(previewContent).getFirstChild(); block != null; block = block.getNext()) {
            if (block instanceof Heading heading) assertEquals(2, heading.getLevel());
        }
    }

    private List<String> topLevelText(Class<? extends Node> type) {
        List<String> texts = new ArrayList<>();
        Node document = Parser.builder().build().parse(previewContent);
        for (Node block = document.getFirstChild(); block != null; block = block.getNext()) {
            if (type.isInstance(block)) texts.add(nodeText(block));
        }
        return texts;
    }

    private List<String> topLevelDescriptions() {
        List<String> descriptions = new ArrayList<>();
        Node document = Parser.builder().build().parse(previewContent);
        for (Node block = document.getFirstChild(); block != null; block = block.getNext()) {
            String prefix = block instanceof Heading heading ? "H" + heading.getLevel() + ":"
                    : block instanceof Paragraph ? "P:" : block.getClass().getSimpleName() + ":";
            descriptions.add(prefix + nodeText(block));
        }
        return descriptions;
    }

    private String nodeText(Node node) {
        if (node instanceof Text text) return text.getLiteral();
        StringBuilder value = new StringBuilder();
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) value.append(nodeText(child));
        return value.toString();
    }

    private org.mockito.stubbing.OngoingStubbing<DeepSeekClient.ChatResult> model() throws Exception {
        return when(client.chatFull(anyList(), isNull(), eq(TARGET.baseUrl()), eq(TARGET.apiKey()), eq(TARGET.model()),
                anyInt(), anyDouble(), nullable(String.class), nullable(String.class), any(Duration.class)));
    }

    private JsonNode run(JsonNode args, AiChatVO vo) throws Exception {
        return agent.writeNoteSections(args, vo.getEvents(), "synthetic-session", vo, new AiChatRequest(),
                System.currentTimeMillis() + 270_000, null, null);
    }

    private ObjectNode tasks(int count) {
        ObjectNode args = json.createObjectNode().put("note_id", 12).put("expected_hash", NoteContentEditor.hash(originalContent));
        var tasks = args.putArray("tasks");
        List<String> heads = List.of("合成甲", "合成乙", "合成丙", "合成丁");
        for (int i = 0; i < count; i++) tasks.addObject().put("heading", heads.get(i)).put("action", "insert_after")
                .put("text", "## " + heads.get(i)).put("instructions", "为该位置添加一个完整公式示例。");
        return args;
    }

    private DeepSeekClient.ChatResult response(String content, String reason) {
        return new DeepSeekClient.ChatResult(json.createObjectNode().put("role", "assistant").put("content", content), reason, 30, 30, 0);
    }
}
