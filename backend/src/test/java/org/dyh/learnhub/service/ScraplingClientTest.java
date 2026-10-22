package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ScraplingClientTest {
    private static final String ENDPOINT = "https://renderer.example.invalid/render";
    private static final String SOURCE = "https://docs.example.invalid/guide?chapter=synthetic";
    private static final String SECRET = "synthetic-credential-do-not-forward";
    private static final String MARKDOWN = "# 合成页面\n\n实际渲染正文 🧪\n\n"
            + "$$\nf(x) = x^2\n$$\n\n```java\nint answer = 42;\n```\n";
    private final ObjectMapper json = new ObjectMapper();

    @Test void postsOnlyTheBoundedAnonymousFetchContract() throws Exception {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        AtomicLong timeout = new AtomicLong();
        ScraplingClient client = new ScraplingClient(json, "  " + ENDPOINT + "///  ", (request, budget) -> {
            captured.set(request);
            timeout.set(budget);
            return response(payload());
        });

        client.fetch(SOURCE, 12_345);

        assertTrue(client.configured());
        HttpRequest request = captured.get();
        assertEquals(ENDPOINT + "/fetch", request.uri().toString());
        assertEquals("POST", request.method());
        assertEquals(Duration.ofMillis(12_345), request.timeout().orElseThrow());
        assertEquals(12_345, timeout.get());
        assertEquals("application/json", request.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("application/json", request.headers().firstValue("Accept").orElseThrow());
        assertEquals(2, request.headers().map().size());
        assertTrue(request.headers().firstValue("Authorization").isEmpty());
        assertTrue(request.headers().firstValue("Cookie").isEmpty());
        JsonNode body = json.readTree(requestBody(request));
        Set<String> names = new java.util.HashSet<>();
        body.fieldNames().forEachRemaining(names::add);
        assertEquals(Set.of("url", "timeoutMs", "maxChars"), names);
        assertEquals(SOURCE, body.path("url").asText());
        assertEquals(12_345, body.path("timeoutMs").asLong());
        assertEquals(ScraplingClient.MAX_SOURCE_CHARS, body.path("maxChars").asInt());
    }

    @Test void clampsAllThreeTimeoutRepresentationsToSixtySeconds() throws Exception {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        AtomicLong timeout = new AtomicLong();
        ScraplingClient client = new ScraplingClient(json, ENDPOINT, (request, budget) -> {
            captured.set(request);
            timeout.set(budget);
            return response(payload());
        });

        client.fetch(SOURCE, Long.MAX_VALUE);

        assertEquals(60_000, timeout.get());
        assertEquals(Duration.ofSeconds(60), captured.get().timeout().orElseThrow());
        assertEquals(60_000, json.readTree(requestBody(captured.get())).path("timeoutMs").asLong());
    }

    @Test void missingConfigurationNeverCallsTheTransport() {
        AtomicInteger calls = new AtomicInteger();
        for (String endpoint : new String[]{null, "", "  \t\r\n"}) {
            ScraplingClient client = new ScraplingClient(json, endpoint, (request, budget) -> {
                calls.incrementAndGet();
                throw new AssertionError("An unconfigured client must not send a request");
            });
            assertFalse(client.configured());
            assertEquals("动态网页服务未配置", assertThrows(IllegalStateException.class,
                    () -> client.fetch(SOURCE, 1_000)).getMessage());
        }
        assertEquals(0, calls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///synthetic/config", "ftp://renderer.example.invalid", "https:///missing-host",
            "https://user:synthetic-credential-do-not-forward@renderer.example.invalid",
            "https://renderer.example.invalid?token=synthetic-credential-do-not-forward",
            "https://renderer.example.invalid/#synthetic-credential-do-not-forward",
            "not a valid endpoint synthetic-credential-do-not-forward"})
    void invalidServiceEndpointsFailWithoutSendingOrDisclosingConfiguration(String endpoint) {
        ScraplingClient client = new ScraplingClient(json, endpoint, (request, budget) -> {
            throw new AssertionError("An invalid endpoint must not reach the transport");
        });
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> client.fetch(SOURCE, 1_000));
        assertTrue(Set.of("动态网页服务配置无效", "动态网页服务暂时不可用").contains(failure.getMessage()));
        assertSanitized(failure);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void exhaustedTimeBudgetNeverCallsTheTransport(long timeout) {
        ScraplingClient client = new ScraplingClient(json, ENDPOINT, (request, budget) -> {
            throw new AssertionError("An exhausted budget must not reach the transport");
        });
        assertEquals("网页抓取时间预算已用尽", assertThrows(IllegalStateException.class,
                () -> client.fetch(SOURCE, timeout)).getMessage());
    }

    @Test void keepsTheActualRenderedMarkdownAndUpstreamPageMetadata() throws Exception {
        ObjectNode payload = payload().put("url", "https://docs.example.invalid/final")
                .put("status", 206).put("title", "合成标题").put("contentType", "text/html; charset=utf-8");
        ScraplingClient.Result result = client(response(payload)).fetch(SOURCE, 1_000);

        assertEquals("https://docs.example.invalid/final", result.url());
        assertEquals(206, result.status());
        assertEquals("text/html; charset=utf-8", result.contentType());
        assertEquals("合成标题", result.title());
        assertEquals(MARKDOWN, result.markdown());
        assertFalse(result.truncated());
        assertEquals("dynamic", result.fetchMode());
    }

    @Test void retainsUpstreamTruncationEvenWhenMarkdownFitsTheLocalLimit() throws Exception {
        ScraplingClient.Result result = client(response(payload().put("truncated", true))).fetch(SOURCE, 1_000);
        assertTrue(result.truncated());
        assertEquals(MARKDOWN, result.markdown());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \n\t"})
    void successfulResponsesWithoutReadableMarkdownFailClearly(String markdown) throws Exception {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> client(response(payload().put("markdown", markdown))).fetch(SOURCE, 1_000));
        assertEquals("动态网页服务未返回可读正文", failure.getMessage());
    }

    @Test void capsOversizedMarkdownWithoutLeavingHalfOfASurrogatePair() throws Exception {
        String prefix = "x".repeat(ScraplingClient.MAX_SOURCE_CHARS - 1);
        String markdown = prefix + "🧪" + "tail";
        ScraplingClient.Result result = client(response(payload().put("markdown", markdown))).fetch(SOURCE, 1_000);
        assertTrue(result.truncated());
        assertEquals(prefix, result.markdown());
        assertFalse(Character.isHighSurrogate(result.markdown().charAt(result.markdown().length() - 1)));
    }

    @Test void timeoutMessagesContainNoTransportCredentialsOrCause() {
        for (Exception timeout : List.of(new HttpTimeoutException("token=" + SECRET),
                new TimeoutException("service password=" + SECRET))) {
            ScraplingClient client = failing(timeout);
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> client.fetch(SOURCE, 1_000));
            assertEquals("动态网页服务抓取超时", failure.getMessage());
            assertSanitized(failure);
        }
    }

    @Test void transportFailureMessagesCannotBecomeUserFacingErrors() {
        for (Exception unsafe : List.of(new IOException("Bearer " + SECRET),
                new IllegalArgumentException("api_key=" + SECRET),
                new IllegalStateException("password=" + SECRET),
                new IllegalStateException("动态网页 transport failure: api_key=" + SECRET))) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> failing(unsafe).fetch(SOURCE, 1_000));
            assertEquals("动态网页服务暂时不可用", failure.getMessage());
            assertSanitized(failure);
        }
    }

    @Test void interruptedTransportRestoresTheInterruptFlagWithoutLeakingItsMessage() {
        boolean wasInterrupted = Thread.interrupted();
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> failing(new InterruptedException("token=" + SECRET)).fetch(SOURCE, 1_000));
            assertEquals("动态网页抓取已中断", failure.getMessage());
            assertTrue(Thread.currentThread().isInterrupted());
            assertSanitized(failure);
        } finally {
            Thread.interrupted();
            if (wasInterrupted) Thread.currentThread().interrupt();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 303, 307, 308, 401, 429, 504})
    void httpFailuresAndRedirectsAreExplicitAndDoNotParseOrLeakTheirBodies(int status) {
        ScraplingClient client = client(new ScraplingClient.Response(status,
                ("transport credentials: " + SECRET).getBytes(StandardCharsets.UTF_8)));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> client.fetch(SOURCE, 1_000));
        String expected = status == 429 ? "动态网页服务繁忙，请稍后重试"
                : status == 504 ? "动态网页服务抓取超时" : "动态网页服务请求失败（HTTP " + status + "）";
        assertEquals(expected, failure.getMessage());
        assertSanitized(failure);
    }

    @Test void oversizedOrMissingBodiesFailBeforeJsonParsing() {
        for (byte[] body : new byte[][]{null, new byte[ScraplingClient.MAX_RESPONSE_BYTES + 1]}) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> client(new ScraplingClient.Response(200, body)).fetch(SOURCE, 1_000));
            assertEquals("动态网页服务响应超过安全上限", failure.getMessage());
        }
    }

    @Test void unsuccessfulOrMalformedPayloadsFailWithoutForwardingServerDetails() throws Exception {
        List<byte[]> bodies = new ArrayList<>();
        bodies.add(("malformed response " + SECRET).getBytes(StandardCharsets.UTF_8));
        bodies.add(json.writeValueAsBytes(json.createObjectNode().put("ok", false).put("error", SECRET)));
        bodies.add(json.writeValueAsBytes(payload().putNull("markdown")));
        bodies.add(json.writeValueAsBytes(payload().put("status", 600)));
        bodies.add(json.writeValueAsBytes(payload().put("fetchMode", "static")));
        for (byte[] body : bodies) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> client(new ScraplingClient.Response(200, body)).fetch(SOURCE, 1_000));
            assertSanitized(failure);
        }
    }

    @Test void limitedSubscriberAcceptsTheExactByteLimitAndPreservesItsBytes() {
        ScraplingClient.LimitedSubscriber subscriber = new ScraplingClient.LimitedSubscriber();
        TrackingSubscription subscription = new TrackingSubscription();
        subscriber.onSubscribe(subscription);
        byte[] content = new byte[ScraplingClient.MAX_RESPONSE_BYTES];
        content[0] = 12;
        content[content.length - 1] = 34;
        subscriber.onNext(List.of(ByteBuffer.wrap(content, 0, 17).asReadOnlyBuffer(),
                ByteBuffer.wrap(content, 17, content.length - 17).asReadOnlyBuffer()));
        assertFalse(subscriber.getBody().toCompletableFuture().isDone());
        subscriber.onComplete();

        assertArrayEquals(content, subscriber.getBody().toCompletableFuture().join());
        assertFalse(subscription.cancelled);
        assertEquals(2, subscription.requested);
    }

    @Test void limitedSubscriberCancelsAsSoonAsAccumulatedStreamingBytesExceedTheLimit() {
        ScraplingClient.LimitedSubscriber subscriber = new ScraplingClient.LimitedSubscriber();
        TrackingSubscription subscription = new TrackingSubscription();
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[ScraplingClient.MAX_RESPONSE_BYTES - 2])));
        ByteBuffer overflow = ByteBuffer.wrap(new byte[3]);
        ByteBuffer ignoredTail = ByteBuffer.wrap(new byte[10]);

        subscriber.onNext(List.of(overflow, ignoredTail));

        assertTrue(subscription.cancelled);
        assertEquals(2, subscription.requested, "No further chunk should be requested after overflow");
        assertEquals(0, overflow.position(), "The oversized chunk must not be copied into memory");
        assertEquals(0, ignoredTail.position());
        CompletionException failure = assertThrows(CompletionException.class,
                () -> subscriber.getBody().toCompletableFuture().join());
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        subscriber.onComplete();
        assertTrue(subscriber.getBody().toCompletableFuture().isCompletedExceptionally());
    }

    private ObjectNode payload() {
        return json.createObjectNode().put("ok", true).put("url", SOURCE).put("status", 200)
                .put("contentType", "text/html").put("title", "合成页面").put("markdown", MARKDOWN)
                .put("truncated", false).put("fetchMode", "dynamic");
    }

    private ScraplingClient.Response response(ObjectNode payload) throws Exception {
        return new ScraplingClient.Response(200, json.writeValueAsBytes(payload));
    }

    private ScraplingClient client(ScraplingClient.Response response) {
        return new ScraplingClient(json, ENDPOINT, (request, budget) -> response);
    }

    private ScraplingClient failing(Exception failure) {
        return new ScraplingClient(json, ENDPOINT, (request, budget) -> { throw failure; });
    }

    private static void assertSanitized(IllegalStateException failure) {
        assertFalse(failure.toString().contains(SECRET));
        assertNull(failure.getCause(), "Transport failures must not expose their credential-bearing cause");
    }

    private static String requestBody(HttpRequest request) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CompletableFuture<String> body = new CompletableFuture<>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer item) {
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                bytes.writeBytes(chunk);
            }
            public void onError(Throwable failure) { body.completeExceptionally(failure); }
            public void onComplete() { body.complete(bytes.toString(StandardCharsets.UTF_8)); }
        });
        return body.get(1, TimeUnit.SECONDS);
    }

    private static final class TrackingSubscription implements Flow.Subscription {
        private long requested;
        private boolean cancelled;
        public void request(long n) { requested += n; }
        public void cancel() { cancelled = true; }
    }
}
