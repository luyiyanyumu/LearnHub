package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.config.AiProperties;
import org.dyh.learnhub.service.ModelProfileService;
import org.dyh.learnhub.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** All timing, transport, and model responses are synthetic; no network requests are sent. */
class ClientRetryBudgetTest {
    private static final String RESTARTABLE = "{\"error\":{\"message\":"
            + "\"the engine stopped unexpectedly; the next request restarts it\"}}";
    private static final String SUCCESS = "{\"choices\":[{\"message\":{\"role\":\"assistant\","
            + "\"content\":\"synthetic answer\"},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":4}}";

    private HttpClient http;
    private DeepSeekClient client;
    private AtomicLong now;

    @BeforeEach
    void setup() {
        http = mock(HttpClient.class);
        now = new AtomicLong();
        client = new DeepSeekClient(new AiProperties(), mock(SettingsService.class), new ObjectMapper(),
                mock(ModelProfileService.class), mock(ModelRouting.class));
        ReflectionTestUtils.setField(client, "http", http);
        ReflectionTestUtils.setField(client, "nanoTime", (LongSupplier) now::get);
    }

    @Test
    void engineRecoveryOnlyUsesRemainingTimeAndPreservesTheRequest() throws Exception {
        List<HttpRequest> requests = new ArrayList<>();
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenAnswer(invocation -> {
                    requests.add(invocation.getArgument(0));
                    if (requests.size() == 1) {
                        now.addAndGet(Duration.ofMillis(400).toNanos());
                        return response(503, RESTARTABLE);
                    }
                    return response(200, SUCCESS);
                });

        DeepSeekClient.ChatResult result = call(Duration.ofSeconds(1));

        assertEquals("synthetic answer", result.message().path("content").asText());
        assertEquals(12, result.promptTokens());
        assertEquals(2, requests.size());
        HttpRequest first = requests.get(0);
        HttpRequest retry = requests.get(1);
        assertEquals(Duration.ofSeconds(1), first.timeout().orElseThrow());
        assertEquals(Duration.ofMillis(600), retry.timeout().orElseThrow());
        assertEquals(URI.create("http://synthetic.invalid/v1/chat/completions"), retry.uri());
        assertEquals(first.method(), retry.method());
        assertEquals(first.headers(), retry.headers());
        assertSame(first.bodyPublisher().orElseThrow(), retry.bodyPublisher().orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(longs = {1000, 1001})
    void exhaustedBudgetDoesNotSendARecoveryRequest(long elapsedMillis) throws Exception {
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenAnswer(invocation -> {
                    now.set(Duration.ofMillis(elapsedMillis).toNanos());
                    return response(503, RESTARTABLE);
                });

        assertThrows(HttpTimeoutException.class, () -> call(Duration.ofSeconds(1)));

        verify(http, times(1)).send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
    }

    @Test
    void requestPreparationSharesTheOriginalBudget() throws Exception {
        LongSupplier clock = mock(LongSupplier.class);
        when(clock.getAsLong()).thenReturn(0L, Duration.ofSeconds(1).toNanos());
        ReflectionTestUtils.setField(client, "nanoTime", clock);

        assertThrows(HttpTimeoutException.class, () -> call(Duration.ofSeconds(1)));

        verifyNoInteractions(http);
    }

    @Test
    void aSecondEngineCrashDoesNotCreateAnUnboundedRetryLoop() throws Exception {
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenAnswer(invocation -> {
                    now.addAndGet(Duration.ofMillis(200).toNanos());
                    return response(503, RESTARTABLE);
                });

        assertThrows(RuntimeException.class, () -> call(Duration.ofSeconds(1)));

        verify(http, times(2)).send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
    }

    @Test
    void ordinaryServiceErrorsDoNotConsumeASecondAttempt() throws Exception {
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenAnswer(invocation -> response(503, "{\"error\":{\"message\":\"temporarily unavailable\"}}"));

        assertThrows(RuntimeException.class, () -> call(Duration.ofSeconds(1)));

        verify(http, times(1)).send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
    }

    private DeepSeekClient.ChatResult call(Duration timeout) throws Exception {
        return client.chatFull(List.of(Map.of("role", "user", "content", "synthetic question")), List.of(),
                "http://synthetic.invalid/v1", "synthetic-test-key", "synthetic-model",
                64, 0.2, "disabled", null, timeout);
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }
}
