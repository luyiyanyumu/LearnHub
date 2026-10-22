package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Anonymous bounded calls to the operator-configured renderer; page content never configures this endpoint. */
@Service
public class ScraplingClient {
    static final int MAX_RESPONSE_BYTES = 1_000_000;
    static final int MAX_SOURCE_CHARS = 100_000;
    private final ObjectMapper json;
    private final String serviceUrl;
    private final Transport transport;

    public record Result(String url, int status, String contentType, String title, String markdown,
                         boolean truncated, String fetchMode) {}
    record Response(int status, byte[] body) {}
    @FunctionalInterface interface Transport { Response send(HttpRequest request, long timeoutMs) throws Exception; }
    private static final class ServiceFailure extends IllegalStateException {
        ServiceFailure(String message) { super(message); }
    }
    private static final class BodyLimitException extends IllegalStateException {
        BodyLimitException() { super("response limit"); }
    }

    @Autowired
    public ScraplingClient(ObjectMapper json, @Value("${learnhub.scrapling-url:}") String serviceUrl) {
        this(json, serviceUrl, defaultTransport());
    }

    ScraplingClient(ObjectMapper json, String serviceUrl, Transport transport) {
        this.json = json;
        this.serviceUrl = serviceUrl == null ? "" : serviceUrl.trim();
        this.transport = transport;
    }

    public boolean configured() { return !serviceUrl.isBlank(); }

    public Result fetch(String url, long timeoutMs) {
        if (!configured()) throw new ServiceFailure("动态网页服务未配置");
        if (timeoutMs <= 0) throw new ServiceFailure("网页抓取时间预算已用尽");
        long budget = Math.min(timeoutMs, 60_000);
        try {
            URI base = URI.create(serviceUrl);
            if (!List.of("http", "https").contains(base.getScheme()) || base.getHost() == null
                    || base.getUserInfo() != null || base.getRawQuery() != null || base.getFragment() != null)
                throw new ServiceFailure("动态网页服务配置无效");
            String endpoint = serviceUrl.replaceAll("/+$", "") + "/fetch";
            String body = json.createObjectNode().put("url", url).put("timeoutMs", budget)
                    .put("maxChars", MAX_SOURCE_CHARS).toString();
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofMillis(budget))
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            Response response = transport.send(request, budget);
            if (response.body() == null || response.body().length > MAX_RESPONSE_BYTES)
                throw new ServiceFailure("动态网页服务响应超过安全上限");
            if (response.status() == 504) throw new ServiceFailure("动态网页服务抓取超时");
            if (response.status() == 429) throw new ServiceFailure("动态网页服务繁忙，请稍后重试");
            if (response.status() != 200)
                throw new ServiceFailure("动态网页服务请求失败（HTTP " + response.status() + "）");
            JsonNode payload = json.readTree(response.body());
            if (payload == null || !payload.path("ok").asBoolean(false))
                throw new ServiceFailure("动态网页服务未能完成抓取");
            if (!payload.path("markdown").isTextual() || !payload.path("url").isTextual()
                    || !payload.path("status").canConvertToInt() || !payload.path("contentType").isTextual()
                    || !"dynamic".equals(payload.path("fetchMode").asText()))
                throw new ServiceFailure("动态网页服务响应格式无效");
            int status = payload.path("status").asInt();
            if (status < 100 || status > 599) throw new ServiceFailure("动态网页服务响应格式无效");
            String markdown = payload.path("markdown").asText();
            if (markdown.isBlank()) throw new ServiceFailure("动态网页服务未返回可读正文");
            boolean truncated = payload.path("truncated").asBoolean(false) || markdown.length() > MAX_SOURCE_CHARS;
            if (markdown.length() > MAX_SOURCE_CHARS) markdown = markdown.substring(0, safeEnd(markdown, MAX_SOURCE_CHARS));
            return new Result(payload.path("url").asText(), status, payload.path("contentType").asText(),
                    payload.path("title").asText(""), markdown, truncated, "dynamic");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("动态网页抓取已中断");
        } catch (HttpTimeoutException | TimeoutException timeout) {
            throw new IllegalStateException("动态网页服务抓取超时");
        } catch (ServiceFailure safeFailure) {
            throw safeFailure;
        } catch (Exception unavailable) {
            throw new IllegalStateException("动态网页服务暂时不可用");
        }
    }

    private static int safeEnd(String text, int end) {
        return end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end)) ? end - 1 : end;
    }

    private static Transport defaultTransport() {
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10)).build();
        return (request, timeoutMs) -> {
            CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(request, ignored -> new LimitedSubscriber());
            try {
                HttpResponse<byte[]> response = pending.get(timeoutMs, TimeUnit.MILLISECONDS);
                return new Response(response.statusCode(), response.body());
            } catch (java.util.concurrent.ExecutionException failure) {
                pending.cancel(true);
                Throwable cause = failure.getCause();
                while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null)
                    cause = cause.getCause();
                if (cause instanceof HttpTimeoutException timeout) throw timeout;
                if (cause instanceof BodyLimitException)
                    throw new ServiceFailure("动态网页服务响应超过安全上限");
                throw failure;
            } catch (Exception failure) {
                pending.cancel(true);
                throw failure;
            }
        };
    }

    /** Bounds bytes while streaming rather than after an unbounded body has entered memory. */
    static final class LimitedSubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return body; }
        public void onSubscribe(Flow.Subscription next) { subscription = next; next.request(1); }
        public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk : chunks) {
                if (chunk.remaining() > MAX_RESPONSE_BYTES - bytes.size()) {
                    subscription.cancel();
                    body.completeExceptionally(new BodyLimitException());
                    return;
                }
                byte[] block = new byte[chunk.remaining()];
                chunk.get(block);
                bytes.writeBytes(block);
            }
            subscription.request(1);
        }
        public void onError(Throwable failure) { body.completeExceptionally(failure); }
        public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
