package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.ai.DeepSeekClient;
import org.dyh.learnhub.ai.ModelRouting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GroundingResponseValidationTest {
    private final DeepSeekClient client = mock(DeepSeekClient.class);
    private final ModelRouting routing = mock(ModelRouting.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final GroundingService service = new GroundingService(client, routing, mapper, settings);

    @BeforeEach
    void enabledVerifier() {
        when(settings.effective(GroundingService.SETTING_GROUNDING)).thenReturn("1");
        when(routing.forTask(ModelRouting.TASK_GROUNDING)).thenReturn(
                new ModelRouting.ModelTarget("test", "test", "http://test.invalid", "", "test", true));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "[]", "null", "true", "\"not a verdict\"",
            "{\"unsupported\":[]}",
            "{\"grounded\":null,\"unsupported\":[]}",
            "{\"grounded\":\"true\",\"unsupported\":[]}",
            "{\"grounded\":1,\"unsupported\":[]}",
            "{\"grounded\":true}",
            "{\"grounded\":true,\"unsupported\":null}",
            "{\"grounded\":true,\"unsupported\":false}",
            "{\"grounded\":true,\"unsupported\":{}}",
            "{\"grounded\":true,\"unsupported\":\"claim\"}",
            "{\"grounded\":false,\"unsupported\":[null]}",
            "{\"grounded\":false,\"unsupported\":[1]}",
            "{\"grounded\":false,\"unsupported\":[{}]}",
            "{\"grounded\":false,\"unsupported\":[\"valid claim\",false]}",
            "{\"grounded\":false,\"unsupported\":[\"  \"]}",
            "{\"grounded\":true,\"unsupported\":[],\"note\":null}",
            "{\"grounded\":true,\"unsupported\":[],\"note\":false}",
            "{\"grounded\":true,\"unsupported\":[],\"note\":1}",
            "{\"grounded\":true,\"unsupported\":[],\"note\":{}}",
            "{\"grounded\":true,\"unsupported\":[],\"note\":[]}",
            "{\"grounded\":true,\"unsupported\":[]",
            "{\"grounded\":true,\"unsupported\":[]} {}"
    })
    void invalidVerdictDoesNotProduceACheckedOrSupportedLabel(String response) throws Exception {
        respond(response);

        GroundingService.Result result = service.check("问题", "原文证据", "回答");

        assertFalse(result.checked(), "invalid JSON contracts must not masquerade as a successful check");
        assertEquals(0, result.evidenceChars());
        assertTrue(result.unsupported().isEmpty());
        assertFalse(result.note().isBlank());
        verifyModelCall();
    }

    @Test
    void validSupportedVerdictRemainsChecked() throws Exception {
        respond("{\"grounded\":true,\"unsupported\":[],\"note\":\"有依据\"}");

        GroundingService.Result result = service.check("问题", "原文证据", "回答");

        assertTrue(result.checked());
        assertTrue(result.grounded());
        assertEquals(List.of(), result.unsupported());
        assertEquals("有依据", result.note());
        assertEquals(4, result.evidenceChars());
    }

    @Test
    void validUnsupportedVerdictPreservesClaims() throws Exception {
        respond("{\"grounded\":false,\"unsupported\":[\" 缺少来源的断言 \"],\"note\":\"待核实\"}");

        GroundingService.Result result = service.check("问题", "原文证据", "回答");

        assertTrue(result.checked());
        assertFalse(result.grounded());
        assertEquals(List.of("缺少来源的断言"), result.unsupported());
        assertEquals("待核实", result.note());
    }

    @Test
    void explicitFalseWithoutClaimsStillDoesNotBecomeSupported() throws Exception {
        respond("{\"grounded\":false,\"unsupported\":[]}");

        GroundingService.Result result = service.check("问题", "原文证据", "回答");

        assertTrue(result.checked());
        assertFalse(result.grounded());
    }

    @Test
    void claimsOverrideAConflictingSupportedFlag() throws Exception {
        respond("{\"grounded\":true,\"unsupported\":[\"无依据的断言\"]}");

        GroundingService.Result result = service.check("问题", "原文证据", "回答");

        assertTrue(result.checked());
        assertFalse(result.grounded());
        assertEquals(List.of("无依据的断言"), result.unsupported());
    }

    @Test
    void validFencedVerdictRemainsCompatible() throws Exception {
        String fence = String.valueOf((char) 96).repeat(3);
        respond(fence + "json\n{\"grounded\":true,\"unsupported\":[]}\n" + fence);

        GroundingService.Result result = service.check("问题", "原文证据", "回答");

        assertTrue(result.checked());
        assertTrue(result.grounded());
    }

    private void respond(String content) throws Exception {
        when(client.chat(anyList(), isNull(), anyString(), anyString(), anyString(), anyInt(), anyDouble(),
                anyString(), isNull(), any(Duration.class)))
                .thenReturn(mapper.createObjectNode().put("content", content));
    }

    private void verifyModelCall() throws Exception {
        verify(client).chat(anyList(), isNull(), anyString(), anyString(), anyString(), anyInt(), anyDouble(),
                anyString(), isNull(), any(Duration.class));
    }
}
