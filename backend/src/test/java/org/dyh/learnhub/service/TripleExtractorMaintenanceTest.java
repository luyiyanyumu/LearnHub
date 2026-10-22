package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.ai.DeepSeekClient;
import org.dyh.learnhub.ai.ModelRouting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TripleExtractorMaintenanceTest {
    private final DeepSeekClient client = mock(DeepSeekClient.class);
    private final ModelRouting routing = mock(ModelRouting.class);
    private final ObjectMapper json = new ObjectMapper();
    private final TripleExtractor extractor = new TripleExtractor(client, routing, json);

    @BeforeEach void setup() {
        when(routing.forTask(ModelRouting.TASK_TRIPLE)).thenReturn(new ModelRouting.ModelTarget(
                "synthetic", "合成档案", "https://model.example/v1", "synthetic-not-a-real-key", "synthetic-model", false));
    }
    private DeepSeekClient.ChatResult reply(String body) {
        return new DeepSeekClient.ChatResult(json.createObjectNode().put("content", body), "stop", 10, 10, 0);
    }
    private void response(DeepSeekClient.ChatResult result) throws Exception {
        when(client.chatFull(anyList(), isNull(), anyString(), anyString(), anyString(), anyInt(), anyDouble(),
                eq("disabled"), isNull(), any(Duration.class))).thenReturn(result);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not json", "{}", "{\"triples\":null}", "{\"triples\":[{}]}", "{\"triples\":[1]}", "{\"triples\":[]} trailing-junk"})
    void invalidOrEmptyRepliesFailTheWholeSource(String malformed) throws Exception {
        response(reply(malformed));
        assertThrows(IllegalStateException.class,
                () -> extractor.extractSourceStrict("笔记#1", "合成标题", "合成的非空原文正文。", List.of()));
    }

    @Test void lengthStopFailsEvenWhenThePartialJsonHappensToBeValid() throws Exception {
        response(new DeepSeekClient.ChatResult(json.createObjectNode().put("content", "{\"triples\":[]}"),
                "length", 10, 2500, 0));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> extractor.extractSourceStrict("资料#2", "合成资料", "非空的合成资料正文。", List.of()));
        assertTrue(error.getMessage().contains("输出被截断"));
    }

    @Test void absentResponseIsNotReportedAsTokenTruncation() throws Exception {
        response(null);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> extractor.extractSourceStrict("资料#2", "合成资料", "非空的合成资料正文。", List.of()));
        assertTrue(error.getMessage().contains("未取得模型结果"));
        assertFalse(error.getMessage().contains("截断"));
    }

    @Test void strictTransportFailureIncludesSafeRootReasonAndPreservesTheOriginalCause() throws Exception {
        SSLHandshakeException handshake = new SSLHandshakeException("Remote host terminated the handshake "
                + "token=synthetic-private-token https://user:synthetic-password@example.invalid/v1");
        IOException transport = new IOException("synthetic-private transport detail", handshake);
        when(client.chatFull(anyList(), isNull(), anyString(), anyString(), anyString(), anyInt(), anyDouble(),
                eq("disabled"), isNull(), any(Duration.class))).thenThrow(transport);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> extractor.extractSourceStrict("笔记#1", "合成标题", "合成原文。", List.of()));
        assertSame(transport, error.getCause()); assertSame(handshake, error.getCause().getCause());
        assertTrue(error.getMessage().contains("握手"));
        assertFalse(error.getMessage().contains("synthetic-private"));
        assertFalse(error.getMessage().contains("synthetic-password"));
        assertFalse(error.getMessage().contains("https://"));
        verify(client, times(1)).chatFull(anyList(), isNull(), anyString(), anyString(), anyString(), anyInt(), anyDouble(),
                eq("disabled"), isNull(), any(Duration.class));
    }

    @Test void legacyExtractionLogsTheSafeReasonWithoutProviderCredentialsOrResponseBodies() throws Exception {
        when(client.chat(anyList(), isNull(), anyString(), anyString(), anyString(), anyInt(), anyDouble(),
                eq("disabled"), isNull(), any(Duration.class))).thenThrow(new SSLHandshakeException(
                "Remote host terminated the handshake token=synthetic-private-token https://example.invalid/private"));
        Logger logger = (Logger) LoggerFactory.getLogger(TripleExtractor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start(); logger.addAppender(appender);
        try {
            assertTrue(extractor.extract("合成原文。", List.of()).triples().isEmpty());
            String visible = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(visible.contains("握手"));
            assertFalse(visible.contains("synthetic-private"));
            assertFalse(visible.contains("https://"));
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test void genuineEmptyTriplesAreAValidSuccessfulExtraction() throws Exception {
        response(reply("{\"triples\":[]}"));
        assertTrue(extractor.extractSourceStrict("资料#2", "合成资料", "一段未包含概念关系的合成正文。", List.of()).triples().isEmpty());
    }

    @Test void eachBatchGetsTheCanonicalSourceAndTheEntireBodyIsRead() throws Exception {
        List<String> users = new ArrayList<>();
        when(client.chatFull(anyList(), isNull(), anyString(), anyString(), anyString(), anyInt(), anyDouble(),
                eq("disabled"), isNull(), any(Duration.class))).thenAnswer(call -> {
            List<?> messages = call.getArgument(0);
            users.add((String) ((Map<?, ?>) messages.get(1)).get("content"));
            return reply("{\"triples\":[]}");
        });
        String body = "合成正文用于检验完整来源分批读取。\n".repeat(650) + "合成末尾必须被读取。";
        TripleExtractor.Result result = extractor.extractSourceStrict("速查卡#41", "合成标题", body, List.of());
        assertTrue(result.batches() > 2);
        StringBuilder seen = new StringBuilder();
        for (String user : users) {
            assertTrue(user.contains("[速查卡#41] 《合成标题》\n"));
            seen.append(user.substring(user.indexOf("》\n") + 2));
        }
        assertEquals(body, seen.toString());
    }

    @Test void modelCitationCannotSpoofAnotherSourceAndOnlyExactEvidenceIsAccepted() throws Exception {
        response(reply("""
                {"triples":[
                  {"head":"Alpha","relation":"part_of","tail":"Beta","evidence":"Alpha 是 Beta 的组成部分。","cite":"笔记#999"},
                  {"head":"Gamma","relation":"part_of","tail":"Beta","evidence":"Gamma 与 Beta 关系密切，但这个后半句不在原文。","cite":"资料#999"}
                ]}
                """));
        TripleExtractor.Result result = extractor.extractSourceStrict("笔记#1", "合成概念", "Alpha 是 Beta 的组成部分。\nGamma 与 Beta 关系密切。", List.of());
        assertEquals(1, result.triples().size());
        assertEquals("笔记#1", result.triples().getFirst().cite());
        assertFalse(TripleExtractor.evidenceSupported("Gamma 与 Beta 关系密切。", "Gamma 与 Beta 关系密切，但后半句不存在。"));
    }

    @Test void splitPreservesDisplayMathQuotedFencesAndUnicodeWithoutLosingCharacters() {
        String formula = "$$\n\\begin{aligned}\nx &= y + z \\\\\nq &= r\n\\end{aligned}\n$$\n";
        String fence = "> ```java\n> String s = \"$$\";\n> ```\n";
        String source = "前文合成内容。\n".repeat(8) + formula + "中间的合成正文。\n".repeat(8) + fence + "😀".repeat(40);
        List<String> parts = TripleExtractor.splitSource(source, 90);
        assertEquals(source, String.join("", parts));
        assertTrue(parts.stream().anyMatch(part -> part.contains(formula)));
        assertTrue(parts.stream().anyMatch(part -> part.contains(fence)));
        for (String part : parts) {
            assertTrue(part.length() <= 90);
            assertFalse(Character.isHighSurrogate(part.charAt(part.length() - 1)));
        }
    }

    @Test void formulaAndCodeOverFourThousandCharactersStayWholeInDedicatedBatches() throws Exception {
        response(reply("{\"triples\":[]}"));
        String formula = "$$\n" + "x = y + z \\\\\n".repeat(500) + "$$\n";
        String code = "```java\n" + "int synthetic = 1;\n".repeat(500) + "```\n";
        String source = "合成开头。\n" + formula + "合成中间。\n" + code + "合成末尾。";
        List<String> parts = TripleExtractor.splitSource(source, 4000);
        assertEquals(source, String.join("", parts));
        assertTrue(parts.contains(formula)); assertTrue(parts.contains(code));
        assertDoesNotThrow(() -> extractor.extractSourceStrict("资料#1", "合成长公式", source, List.of()));
    }

    @Test void atomicFormulaBeyondHardCeilingFailsClearlyWithoutAnyModelRequest() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> TripleExtractor.splitSource("$$\n" + "x".repeat(32_001) + "\n$$\n", 4000));
        assertTrue(error.getMessage().contains("32000"));
        verifyNoInteractions(client);
    }
}
