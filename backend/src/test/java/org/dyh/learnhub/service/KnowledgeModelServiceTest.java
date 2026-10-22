package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.dyh.learnhub.common.FileTextExtractedEvent;
import org.dyh.learnhub.common.GlobalExceptionHandler;
import org.dyh.learnhub.controller.KnowledgeModelController;
import org.dyh.learnhub.entity.FileInfo;
import org.dyh.learnhub.entity.KnowledgeModelOutbox;
import org.dyh.learnhub.mapper.FileInfoMapper;
import org.dyh.learnhub.mapper.KnowledgeModelOutboxMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class KnowledgeModelServiceTest {
    private final SettingsService settings = mock(SettingsService.class);
    private final FileInfoMapper files = mock(FileInfoMapper.class);
    private final KnowledgeModelOutboxMapper outbox = mock(KnowledgeModelOutboxMapper.class);
    private final ObjectMapper json = new ObjectMapper();
    private final KnowledgeModelService service = new KnowledgeModelService(settings, files, outbox, json);
    private HttpServer server;

    @AfterEach void close() { service.stop(); if (server != null) server.stop(0); }

    private void serve(String path, int status, String response, AtomicReference<String> received) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            if (received != null) received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        when(settings.effective(SettingsService.KEY_KNOWLEDGE_MODEL_BASE_URL))
                .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private KnowledgeModelOutbox row() {
        KnowledgeModelOutbox row = new KnowledgeModelOutbox();
        row.setId(12L); row.setFileId(7L); row.setTitle("合成资料.txt");
        row.setTextContent("合成星港的识别码是 LUM-472。"); row.setRevision(KnowledgeModelService.revision(row.getTextContent()));
        row.setState("pending"); row.setAttempts(0);
        return row;
    }

    @Test void automaticEnrollmentIsDisabledUntilExplicitlyEnabled() {
        service.captureExtractedText(new FileTextExtractedEvent(7L, "合成资料.txt", "正文"));
        verifyNoInteractions(outbox, files);
    }

    @Test void capturesExactRevisionWithoutCallingTrainerAndStableHashesDeduplicate() {
        when(settings.effective(SettingsService.KEY_KNOWLEDGE_MODEL_ENABLED)).thenReturn("1");
        var event = new FileTextExtractedEvent(7L, "合成资料.txt", "合成星港的识别码是 LUM-472。");
        service.captureExtractedText(event);
        service.captureExtractedText(event);
        ArgumentCaptor<KnowledgeModelOutbox> captured = ArgumentCaptor.forClass(KnowledgeModelOutbox.class);
        verify(outbox, times(2)).insertRevision(captured.capture());
        assertThat(captured.getAllValues()).extracting(KnowledgeModelOutbox::getRevision)
                .containsOnly(KnowledgeModelService.revision(event.text()));
        assertThat(captured.getValue().getTextContent()).isEqualTo(event.text());
        verifyNoInteractions(files);
    }

    @Test void outboxFailureNeverPropagatesToUploader() {
        when(settings.effective(SettingsService.KEY_KNOWLEDGE_MODEL_ENABLED)).thenReturn("1");
        doThrow(new IllegalStateException("synthetic database failure")).when(outbox).insertRevision(any());
        assertThatCode(() -> service.captureExtractedText(new FileTextExtractedEvent(7L, "合成资料", "正文")))
                .doesNotThrowAnyException();
    }

    @Test void queuesOnlyExplicitFileAndDoesNotExposeText() {
        FileInfo file = new FileInfo(); file.setId(7L); file.setOriginName("合成资料.txt");
        file.setTextStatus("ok"); file.setTextContent("合成正文");
        when(files.selectById(7L)).thenReturn(file);
        when(outbox.findRevision(eq(7L), anyString())).thenReturn(row());
        Map<String, Object> queued = service.enqueueFile(7L);
        assertThat(queued).containsEntry("fileId", 7L).doesNotContainKeys("text", "textContent");
        verify(files).selectById(7L);
        verify(files, never()).selectList(any());
    }

    @Test void serialSubmissionUsesStableRevisionAndMarksAcknowledgedJob() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        serve("/v1/documents", 200, "{\"job_id\":\"job_synthetic_1\"}", received);
        KnowledgeModelOutbox row = row();
        when(outbox.nextDue()).thenReturn(row, (KnowledgeModelOutbox) null);
        service.drainOutbox();
        assertThat(json.readTree(received.get()).get("source_id").asText()).isEqualTo("file:7");
        assertThat(json.readTree(received.get()).get("revision").asText()).isEqualTo(row.getRevision());
        verify(outbox).submitted(12L, "job_synthetic_1");
        verify(outbox, never()).failed(anyLong(), anyString(), anyInt());
    }

    @Test void failedDeliveryRemainsDurableAndAnotherServiceInstanceCanResumeIt() throws Exception {
        serve("/v1/documents", 503, "{}", null);
        KnowledgeModelOutbox row = row();
        when(outbox.nextDue()).thenReturn(row);
        service.drainOutbox();
        verify(outbox).failed(eq(12L), contains("HTTP 503"), eq(5));
        verify(outbox, never()).submitted(anyLong(), anyString());
        server.stop(0);
        serve("/v1/documents", 200, "{\"id\":\"job_synthetic_2\"}", null);
        when(outbox.nextDue()).thenReturn(row, (KnowledgeModelOutbox) null);
        KnowledgeModelService restarted = new KnowledgeModelService(settings, files, outbox, json);
        try { restarted.drainOutbox(); }
        finally { restarted.stop(); }
        verify(outbox).submitted(12L, "job_synthetic_2");
    }

    @Test void malformedAcknowledgementDoesNotLosePendingRevision() throws Exception {
        serve("/v1/documents", 200, "{\"status\":\"queued\"}", null);
        when(outbox.nextDue()).thenReturn(row());
        service.drainOutbox();
        verify(outbox).failed(eq(12L), contains("有效任务编号"), eq(5));
        verify(outbox, never()).submitted(anyLong(), anyString());
    }

    @Test void statusAndJobsRemainInspectableWhenServiceIsOffline() throws Exception {
        serve("/v1/status", 503, "{}", null);
        when(outbox.counts()).thenReturn(List.of(Map.of("state", "retry", "total", 2L)));
        when(outbox.recent()).thenReturn(List.of(row()));
        assertThat(service.status()).containsEntry("online", false);
        assertThat(((Map<?, ?>) service.status().get("outbox")).get("retry")).isEqualTo(2L);
        assertThat(service.jobs()).containsEntry("online", false);
    }

    @Test void queryPassesOnlyQuestionToModelAndPreservesUnvalidatedResult() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        serve("/v1/query", 200, "{\"answer\":\"LUM-472\",\"validation\":\"unvalidated\"}", received);
        assertThat(service.query("合成星港的识别码？", 64)).containsEntry("validation", "unvalidated");
        assertThat(json.readTree(received.get()).size()).isEqualTo(2);
        assertThat(json.readTree(received.get()).get("max_tokens").asInt()).isEqualTo(64);
        verifyNoInteractions(files, outbox);
    }

    @Test void controllerReportsTransportFailureAsExplicit503Result() throws Exception {
        serve("/v1/query", 500, "{}", null);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new KnowledgeModelController(service)).build();
        mvc.perform(post("/api/knowledge-model/query").contentType("application/json")
                        .content("{\"question\":\"合成问题\",\"max_tokens\":32}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test void acceptsOnlyLiteralLoopbackOrNormalizedLocalhostAndRejectsCredentialsPaths() {
        assertThat(KnowledgeModelService.validateBaseUrl("http://localhost:8766/").toString())
                .isEqualTo("http://127.0.0.1:8766");
        assertThat(KnowledgeModelService.validateBaseUrl("http://[::1]:8766").getHost()).contains("::1");
        for (String unsafe : List.of("http://example.com:8766", "http://127.0.0.1.evil.test", "file:///etc/passwd",
                "http://user@127.0.0.1:8766", "http://127.0.0.1:8766/v1", "http://127.0.0.1:8766?x=1",
                "http://127.0.0.1:0", "http://2130706433:8766", "http://[::ffff:127.0.0.1]:8766"))
            assertThatThrownBy(() -> KnowledgeModelService.validateBaseUrl(unsafe)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void invalidQueryBoundsAreRejectedAs400BeforeForwardingToTrainer() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new KnowledgeModelController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        for (Map<String, Object> invalid : List.of(
                Map.<String, Object>of("question", "合成问题", "max_tokens", 513),
                Map.<String, Object>of("question", "x".repeat(5001), "max_tokens", 32))) {
            mvc.perform(post("/api/knowledge-model/query").contentType("application/json")
                            .content(json.writeValueAsString(invalid)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(400));
        }
        assertThatThrownBy(() -> service.query("合成问题", 513)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.query("x".repeat(5001), 32)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(settings, files, outbox);
    }
}
