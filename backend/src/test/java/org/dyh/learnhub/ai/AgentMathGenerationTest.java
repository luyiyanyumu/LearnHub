package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.dto.AiChatRequest;
import org.dyh.learnhub.service.AgentSessionService;
import org.dyh.learnhub.service.GroundingService;
import org.dyh.learnhub.service.RetrievalContextService;
import org.dyh.learnhub.service.SettingsService;
import org.dyh.learnhub.service.SkillService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Uses synthetic content and mocked model calls; no network, credentials or database. */
@ExtendWith(MockitoExtension.class)
class AgentMathGenerationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ModelRouting.ModelTarget TARGET = new ModelRouting.ModelTarget(
            "math-test", "synthetic", "http://unused.invalid", "dummy", "mock", true);
    private static final String BODY = """
            ## 合成相似度示例

            $$
            s(q,d)=\\langle E_q(q),E_d(d)\\rangle
            $$

            这里的代码演示真实实现；字符串中的公式符号也是代码的一部分。

            ```python
            def dot(q, d):
                label = r"$x_i$ and \\frac{a}{b}"
                return sum(x * y for x, y in zip(q, d))
            ```
            """.strip();
    private static final String ADDITION = "新增推导：$p(y\\mid x)=\\frac{e^{s_y}}{\\sum_j e^{s_j}}$。";

    @Mock DeepSeekClient client;
    @Mock ModelRouting routing;
    @Mock SettingsService settings;
    @Mock SkillService skills;
    @Mock AgentSessionService sessions;
    @Mock RetrievalContextService contexts;
    @Mock GroundingService grounding;
    @InjectMocks AgentService agent;

    @BeforeEach void mapper() {
        ReflectionTestUtils.setField(agent, "objectMapper", JSON);
    }

    @Test void customizedChatPromptCannotOmitTheIndependentMathPolicy() throws Exception {
        configured();
        String customizedPrompt = "只按自定义学习文风组织回答。";
        String question = "把相似度整理成笔记；解释数学定义并保留具体编程实现。";
        when(settings.effective(SettingsService.KEY_CHAT_PROMPT)).thenReturn(customizedPrompt);
        when(sessions.ensure(null, null, question)).thenReturn("synthetic-math-session");
        when(contexts.build(question, RetrievalContextService.MAX_PASSAGES, "fused", false, false))
                .thenReturn(RetrievalContextService.Context.empty());
        answer(BODY);
        AiChatRequest request = new AiChatRequest();
        request.setMessage(question);
        request.setNoteTitle("合成笔记");
        request.setNoteContext(BODY);

        assertEquals(BODY, agent.chat(request).getReply());

        List<JsonNode> messages = modelMessages();
        assertEquals(customizedPrompt, messages.getFirst().path("content").asText());
        List<JsonNode> mathMessages = messages.stream()
                .filter(message -> "system".equals(message.path("role").asText()))
                .filter(message -> NoteMathPrompt.GENERATE.equals(message.path("content").asText())).toList();
        assertEquals(1, mathMessages.size(), "公式策略必须独立于可修改的用户系统提示");
        assertMathPolicy(mathMessages.getFirst().path("content").asText());
        assertTrue(roleText(messages, "user").contains(BODY), "现有公式和代码应原样进入模型上下文");
        assertEquals(question, messages.getLast().path("content").asText());
    }

    @Test void wholeNoteGenerationAlsoReceivesMathPolicyAndPreservesRealCodeOutsideChat() throws Exception {
        answer(BODY);
        ReflectionTestUtils.setField(agent, "longNoteClock", (LongSupplier) () -> 0L);
        String outline = "## 相似度\n## 概率\n用公式说明向量内积；真实 Python 实现：\n" + BODY;

        DeepSeekClient.ChatResult result = ReflectionTestUtils.invokeMethod(agent, "writeWholeNote",
                "合成长文", outline, TARGET, 60_000L);

        assertNotNull(result);
        assertEquals(BODY, result.message().path("content").asText());
        List<JsonNode> messages = modelMessages();
        assertMathPolicy(roleText(messages, "system"));
        String user = roleText(messages, "user");
        assertTrue(user.contains(outline), "完整大纲及其中的公式、真实代码必须进入同一个请求");
        assertTrue(user.contains(BODY));
        verifyNoInteractions(settings, sessions, contexts, routing);
    }

    @Test void wholeNoteMergeLimitsMathChangesToTheRequestedAddition() throws Exception {
        configured();
        when(skills.prompt(SkillService.SKILL_NOTE_MERGE)).thenReturn("合成融入技能");
        String merged = BODY + "\n\n" + ADDITION;
        answer(merged);

        assertEquals(merged, agent.mergeIntoNote("合成笔记", BODY, "补充概率公式", ADDITION));

        List<JsonNode> messages = modelMessages();
        String system = roleText(messages, "system");
        assertTrue(system.contains(NoteMathPrompt.MERGE));
        assertMathPolicy(system);
        assertTrue(system.contains("与本次融入无关的原文"));
        assertTrue(system.contains("逐字保留"));
        String user = roleText(messages, "user");
        assertTrue(user.contains("【原笔记】\n标题：合成笔记\n" + BODY));
        assertTrue(user.contains("【要融入的新内容】\n" + ADDITION));
    }

    @ParameterizedTest
    @ValueSource(strings = {"rewrite", "insert"})
    void sectionMergeCarriesMathPolicyAndPreservesUntouchedCodeInItsInput(String mode) throws Exception {
        configured();
        when(skills.prompt(SkillService.SKILL_NOTE_MERGE_SECTION)).thenReturn("合成分节技能");
        String merged = "insert".equals(mode) ? ADDITION : BODY + "\n\n" + ADDITION;
        answer(merged);

        assertEquals(merged, agent.mergeIntoSection("合成笔记", "## 相似度\n## 概率", "相似度",
                BODY, "补充概率公式", ADDITION, mode));

        List<JsonNode> messages = modelMessages();
        String system = roleText(messages, "system");
        assertTrue(system.contains(NoteMathPrompt.MERGE));
        assertMathPolicy(system);
        assertTrue(system.contains("不扩展修改范围"));
        String user = roleText(messages, "user");
        assertTrue(user.contains("本次模式：" + mode));
        assertTrue(user.contains("【目标小节】## 相似度\n" + BODY));
        assertTrue(user.contains("【要融入的新内容】\n" + ADDITION));
    }

    @Test void formatConvertsOnlyUnambiguousMathAndPassesRealCodeUnmodified() throws Exception {
        configured();
        when(skills.prompt(SkillService.SKILL_BEAUTIFY)).thenReturn("合成格式技能");
        answer(BODY);

        assertEquals(BODY, agent.polish(BODY, "format"));

        List<JsonNode> messages = modelMessages();
        String system = roleText(messages, "system");
        assertTrue(system.contains(NoteMathPrompt.FORMAT));
        assertMathPolicy(system);
        assertTrue(system.contains("无法确定的表达保留原文"));
        assertTrue(system.contains("已有正确 LaTeX 和真实代码逐字保留"));
        assertEquals(BODY, roleText(messages, "user"));
    }

    @Test void prosePolishCannotRewriteExistingFormulasOrRealCode() throws Exception {
        configured();
        when(skills.prompt(SkillService.SKILL_POLISH)).thenReturn("合成润色技能");
        answer(BODY);

        assertEquals(BODY, agent.polish(BODY, "polish"));

        List<JsonNode> messages = modelMessages();
        String system = roleText(messages, "system");
        assertTrue(system.contains(NoteMathPrompt.POLISH));
        assertFalse(system.contains(NoteMathPrompt.GENERATE), "纯文字润色不应触发数学内容转换");
        assertTrue(system.contains("已有 LaTeX 公式、公式分隔符、真实代码"));
        assertTrue(system.contains("不补造或新增公式"));
        assertEquals(BODY, roleText(messages, "user"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"format", "polish"})
    void laterChunkTruncationDoesNotReturnTheEarlierSuccessfulChunks(String mode) throws Exception {
        configured();
        String skill = "format".equals(mode) ? SkillService.SKILL_BEAUTIFY : SkillService.SKILL_POLISH;
        when(skills.prompt(skill)).thenReturn("合成分块技能");
        String first = "第一段合成说明。".repeat(300), second = "第二段合成说明。".repeat(300);
        String original = first + "\n\n" + second;
        when(client.chatFull(anyList(), nullable(List.class), anyString(), anyString(), anyString(),
                anyInt(), anyDouble(), nullable(String.class), nullable(String.class), any(Duration.class)))
                .thenReturn(new DeepSeekClient.ChatResult(JSON.createObjectNode().put("content", first), "stop", 12, 15, 0),
                        new DeepSeekClient.ChatResult(JSON.createObjectNode().put("content", second), "length", 12, 15, 0));
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> agent.polish(original, mode));
        assertTrue(failure.getMessage().contains("原文保留"));
        verify(client, times(2)).chatFull(anyList(), nullable(List.class), anyString(), anyString(), anyString(),
                anyInt(), anyDouble(), nullable(String.class), nullable(String.class), any(Duration.class));
        verifyNoInteractions(sessions);
    }

    @Test void openMathBlockInAReportedCompleteFormatResponseIsNotApplied() throws Exception {
        configured();
        when(skills.prompt(SkillService.SKILL_BEAUTIFY)).thenReturn("合成格式技能");
        answer("合成说明。\n\n$$\nx^2 + y^2");
        assertTrue(assertThrows(IllegalStateException.class, () -> agent.polish("合成数学说明。", "format"))
                .getMessage().contains("未完整闭合"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"whole", "section"})
    void truncatedMergeNeverReturnsItsPartialReplacement(String scope) throws Exception {
        configured();
        String skill = "whole".equals(scope) ? SkillService.SKILL_NOTE_MERGE : SkillService.SKILL_NOTE_MERGE_SECTION;
        when(skills.prompt(skill)).thenReturn("合成融入技能");
        when(client.chatFull(anyList(), nullable(List.class), anyString(), anyString(), anyString(),
                anyInt(), anyDouble(), nullable(String.class), nullable(String.class), any(Duration.class)))
                .thenReturn(new DeepSeekClient.ChatResult(JSON.createObjectNode().put("content", BODY), "length", 12, 15, 0));
        assertThrows(IllegalStateException.class, () -> {
            if ("whole".equals(scope)) agent.mergeIntoNote("合成标题", BODY, "合成问题", ADDITION);
            else agent.mergeIntoSection("合成标题", "## 合成", "合成", BODY, "合成问题", ADDITION, "rewrite");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"create_note", "update_note", "write_long_note", "write_note_sections", "append_to_note", "edit_note"})
    void everyNoteWritingToolCarriesFormulaPriorityAndRealCodeException(String name) {
        List<?> definitions = ReflectionTestUtils.invokeMethod(agent, "toolDefinitions");
        JsonNode tool = JSON.valueToTree(definitions);
        JsonNode function = null;
        for (JsonNode item : tool) {
            if (name.equals(item.path("function").path("name").asText())) {
                function = item.path("function");
                break;
            }
        }
        assertNotNull(function, "缺失生成入口：" + name);
        assertMathPolicy(function.path("description").asText());
        if ("edit_note".equals(name)) {
            assertTrue(function.path("description").asText().contains("原文其他部分保持不变"));
        }
        verifyNoInteractions(client);
    }

    private void configured() {
        when(client.isConfigured()).thenReturn(true);
        when(routing.forTask(ModelRouting.TASK_CHAT)).thenReturn(TARGET);
    }

    private void answer(String markdown) throws Exception {
        when(client.chatFull(anyList(), nullable(List.class), anyString(), anyString(), anyString(),
                anyInt(), anyDouble(), nullable(String.class), nullable(String.class), any(Duration.class)))
                .thenReturn(new DeepSeekClient.ChatResult(JSON.createObjectNode().put("content", markdown),
                        "stop", 12, 15, 0));
    }

    private List<JsonNode> modelMessages() throws Exception {
        ArgumentCaptor<List<?>> captured = ArgumentCaptor.forClass(List.class);
        verify(client).chatFull(captured.capture(), nullable(List.class), eq(TARGET.baseUrl()),
                eq(TARGET.apiKey()), eq(TARGET.model()), anyInt(), anyDouble(), nullable(String.class),
                nullable(String.class), any(Duration.class));
        List<JsonNode> messages = new ArrayList<>();
        for (Object message : captured.getValue()) messages.add(JSON.valueToTree(message));
        return messages;
    }

    private static String roleText(List<JsonNode> messages, String role) {
        return String.join("\n", messages.stream()
                .filter(message -> role.equals(message.path("role").asText()))
                .map(message -> message.path("content").asText()).toList());
    }

    private static void assertMathPolicy(String text) {
        assertTrue(text.contains("优先用 LaTeX 公式"));
        assertTrue(text.contains("独立公式的起止 $$ 各自独占一行"));
        assertTrue(text.contains("不要放在行内反引号"));
        assertTrue(text.contains("$s(q,d)=\\langle E_q(q),E_d(d)\\rangle$"),
                "模型输入中的 LaTeX 应为单层反斜杠转义，而不是 JSON 二次转义");
        assertTrue(text.contains("只有真实程序实现、命令、SQL、配置、日志"));
        assertTrue(text.contains("不补造缺乏依据的公式"));
    }
}
