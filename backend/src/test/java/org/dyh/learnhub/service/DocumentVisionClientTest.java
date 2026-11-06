package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.ai.DeepSeekClient;
import org.dyh.learnhub.ai.ModelRouting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentVisionClientTest {
    final DeepSeekClient models = mock(DeepSeekClient.class);
    final ModelRouting routing = mock(ModelRouting.class);
    final ObjectMapper json = new ObjectMapper();
    DocumentVisionClient vision;
    byte[] png;
    @BeforeEach void setup() throws Exception {
        when(routing.forTask(ModelRouting.TASK_FORMULA)).thenReturn(new ModelRouting.ModelTarget(
                "synthetic-vision", "Synthetic visual profile", "http://127.0.0.1:1", "synthetic-secret", "synthetic-vision", true));
        vision = new DocumentVisionClient(models, routing);
        var out = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(80, 60, BufferedImage.TYPE_INT_RGB), "png", out); png = out.toByteArray();
    }
    String response(boolean uncertain) throws Exception {
        return json.writeValueAsString(Map.of("kind", "formula", "latex", "x_{1}=2", "ocrText", "", "description", "",
                "formulas", List.of(), "uncertain", uncertain, "notes", List.of()));
    }
    void answer(String content, String finish) throws Exception {
        when(models.chatFull(anyList(), isNull(), anyString(), any(), anyString(), anyInt(), anyDouble(), anyString(), isNull(), any()))
                .thenReturn(new DeepSeekClient.ChatResult(json.createObjectNode().put("content", content), finish, 0, 0, 0));
    }
    @Test void usesExistingProfileAndImageContentWithoutExposingCredentials() throws Exception {
        answer(response(false), "stop");
        var result = vision.analyze(png, "formula", Duration.ofSeconds(2));
        assertEquals("recognized", result.status()); assertEquals("x_{1}=2", result.latex());
        var messages = ArgumentCaptor.forClass(List.class);
        verify(models).chatFull(messages.capture(), isNull(), anyString(), any(), anyString(), anyInt(), anyDouble(), anyString(), isNull(), any());
        assertTrue(messages.getValue().toString().contains("image_url"));
        assertTrue(messages.getValue().toString().contains("不可信资料"));
        String caps = vision.capabilities().toString();
        assertFalse(caps.contains("synthetic-secret")); assertFalse(caps.contains("127.0.0.1"));
        assertTrue((Boolean) vision.capabilities().get("enabled")); assertTrue(vision.identity().matches("[a-f0-9]{64}"));
    }
    @Test void rejectsTrailingObjectsInvalidLatexAndMissingUncertainty() throws Exception {
        answer(response(false) + " {}", "stop"); assertEquals("failed", vision.analyze(png, "formula", Duration.ofSeconds(1)).status());
        answer(response(false).replace("x_{1}=2", "\\\\input{secret}"), "stop");
        assertEquals("failed", vision.analyze(png, "formula", Duration.ofSeconds(1)).status());
        answer("{\"kind\":\"formula\",\"latex\":\"x=2\"}", "stop");
        assertEquals("failed", vision.analyze(png, "formula", Duration.ofSeconds(1)).status());
    }
    @Test void uncertaintyTruncationAndRefusalNeverBecomeRecognized() throws Exception {
        answer(response(true), "stop"); assertEquals("partial", vision.analyze(png, "formula", Duration.ofSeconds(1)).status());
        answer(response(false), "length"); assertEquals("failed", vision.analyze(png, "formula", Duration.ofSeconds(1)).status());
        when(models.chatFull(anyList(), isNull(), anyString(), any(), anyString(), anyInt(), anyDouble(), anyString(), isNull(), any()))
                .thenReturn(new DeepSeekClient.ChatResult(json.createObjectNode().put("refusal", "synthetic refusal"), "stop", 0, 0, 0));
        assertEquals("failed", vision.analyze(png, "formula", Duration.ofSeconds(1)).status());
    }
    @Test void knownTextOnlyTargetIsUnavailableAndDoesNotCallProvider() {
        when(routing.forTask(ModelRouting.TASK_FORMULA)).thenReturn(new ModelRouting.ModelTarget("text", "Text profile", "http://127.0.0.1:1", "", "qwen3:8b", true));
        assertFalse((Boolean) vision.capabilities().get("enabled"));
        assertEquals("unavailable", vision.analyze(png, "image", Duration.ofSeconds(1)).status()); verifyNoInteractions(models);
    }
    @Test void providerErrorIsSanitizedAndMalformedImageNeverSent() throws Exception {
        when(models.chatFull(anyList(), isNull(), anyString(), any(), anyString(), anyInt(), anyDouble(), anyString(), isNull(), any()))
                .thenThrow(new RuntimeException("synthetic-secret data:image/png;base64,payload"));
        String failed = vision.analyze(png, "image", Duration.ofSeconds(1)).toString();
        assertFalse(failed.contains("synthetic-secret")); assertFalse(failed.contains("payload"));
        clearInvocations(models); assertEquals("failed", vision.analyze(new byte[]{1, 2}, "image", Duration.ofSeconds(1)).status()); verifyNoInteractions(models);
    }
}
