package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** JSON-only client for the isolated runner. Never executes generated code on the backend host. */
@Service
public class CodeExecutionService {
    public static final int MAX_SOURCE_CHARS = 32_000;
    public static final int MAX_JSON_CHARS = 200_000;
    public static final int EXECUTION_TIMEOUT_MS = 10_000;
    private static final int TRANSPORT_TIMEOUT_MS = 12_000;
    private static final int MAX_RESPONSE_BYTES = 1_000_000;
    private static final int MAX_LOG_CHARS = 8_000;
    private static final long HEALTH_CACHE_NANOS = TimeUnit.SECONDS.toNanos(2);
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client;
    private final URI endpoint;
    private final boolean configured;
    private volatile long healthCheckedAt;
    private volatile boolean healthy;

    @Autowired
    public CodeExecutionService(@Value("${learnhub.code-runner-url:}") String runnerUrl) {
        this(runnerUrl, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    CodeExecutionService(String runnerUrl, HttpClient client) {
        this.configured = runnerUrl != null && !runnerUrl.isBlank();
        this.endpoint = parseEndpoint(runnerUrl);
        this.client = client;
    }

    /** Cached readiness, suitable for excluding code tools from a request's advertised capabilities. */
    public boolean available() {
        if (endpoint == null) return false;
        long checked = healthCheckedAt;
        if (checked != 0 && System.nanoTime() - checked < HEALTH_CACHE_NANOS) return healthy;
        synchronized (this) {
            if (healthCheckedAt != 0 && System.nanoTime() - healthCheckedAt < HEALTH_CACHE_NANOS) return healthy;
            try {
                JsonNode result = request("health", null, 2_000);
                healthy = result.path("ok").asBoolean(false) && result.path("ready").asBoolean(false);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                healthy = false;
            } catch (Exception ignored) {
                healthy = false;
            }
            healthCheckedAt = System.nanoTime();
            return healthy;
        }
    }

    public JsonNode status() {
        boolean ready = available();
        return json.createObjectNode().put("configured", configured).put("enabled", endpoint != null)
                .put("available", ready).put("language", "python").put("isolation", "separate_runner")
                .put("maxSourceChars", MAX_SOURCE_CHARS).put("maxJsonChars", MAX_JSON_CHARS)
                .put("timeoutMs", EXECUTION_TIMEOUT_MS)
                .put("message", ready ? "隔离代码执行环境可用" : endpoint == null
                        ? (configured ? "代码执行环境地址配置无效" : "尚未配置代码执行环境")
                        : "代码执行环境暂不可用");
    }

    public JsonNode analyze(String source) {
        ObjectNode invalid = validateSource(source);
        if (invalid != null) return invalid;
        return invoke("analyze", json.createObjectNode().put("source", source));
    }

    public JsonNode execute(String source, JsonNode input) {
        ObjectNode invalid = validateSource(source);
        if (invalid != null) return invalid;
        if (input == null) return failure("code_invalid_request", "代码输入必须为 JSON（空值使用 null）");
        if (input.toString().length() > MAX_JSON_CHARS) return failure("code_limit_exceeded", "代码输入超过字符限制");
        ObjectNode body = json.createObjectNode().put("source", source).put("timeoutMs", EXECUTION_TIMEOUT_MS);
        body.set("input", input.deepCopy());
        return invoke("execute", body);
    }

    private ObjectNode validateSource(String source) {
        if (source == null || source.isBlank()) return failure("code_invalid_request", "代码源码不能为空");
        if (source.length() > MAX_SOURCE_CHARS) return failure("code_limit_exceeded", "代码源码超过字符限制");
        return null;
    }

    private JsonNode invoke(String path, ObjectNode body) {
        if (endpoint == null) return failure("code_runner_unavailable", "代码执行环境未配置或地址无效");
        try {
            JsonNode result = request(path, body, TRANSPORT_TIMEOUT_MS);
            if (!result.isObject() || !result.path("ok").isBoolean()
                    || (result.path("ok").asBoolean() && !result.has("output"))) {
                return unavailable();
            }
            if (result.has("output") && result.get("output").toString().length() > MAX_JSON_CHARS) {
                return failure("code_limit_exceeded", "代码输出超过字符限制");
            }
            // Only the bounded public runner contract crosses back into the agent runtime.
            ObjectNode safe = json.createObjectNode().put("ok", result.path("ok").asBoolean());
            if (result.has("output")) safe.set("output", result.get("output"));
            for (String field : List.of("stdout", "stderr", "error", "code", "sourceFingerprint")) {
                if (result.path(field).isTextual()) {
                    String text = result.get(field).asText();
                    int limit = "sourceFingerprint".equals(field) ? 128 : "code".equals(field) ? 128 : MAX_LOG_CHARS;
                    safe.put(field, "code".equals(field) ? normalizeCode(text)
                            : text.substring(0, Math.min(text.length(), limit)));
                }
            }
            if (result.path("elapsedMs").isNumber()) safe.put("elapsedMs", Math.max(0, result.path("elapsedMs").asLong()));
            if (!safe.path("ok").asBoolean() && !safe.has("error")) safe.put("error", "代码执行未成功");
            return safe;
        } catch (TimeoutException e) {
            markUnavailable();
            return failure("code_runner_timeout", "代码执行环境响应超时");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof HttpTimeoutException) {
                markUnavailable();
                return failure("code_runner_timeout", "代码执行环境响应超时");
            }
            return unavailable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            markUnavailable();
            return failure("code_runner_unavailable", "代码执行请求已中断");
        } catch (Exception e) {
            return unavailable();
        }
    }

    private JsonNode request(String path, JsonNode body, int timeoutMs)
            throws InterruptedException, ExecutionException, TimeoutException, IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint.resolve(path))
                .timeout(Duration.ofMillis(timeoutMs)).header("Accept", "application/json");
        if (body == null) builder.GET();
        else builder.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(builder.build(), ignored -> new LimitedBody(MAX_RESPONSE_BYTES));
        try {
            HttpResponse<byte[]> response = pending.get(timeoutMs, TimeUnit.MILLISECONDS);
            int status = response.statusCode();
            if (status == 429 && body != null) {
                return failure("code_runner_busy", "代码执行环境繁忙，请稍后重试");
            }
            if (status != 200 && !(body != null && (status == 400 || status == 408 || status == 413 || status == 422))) {
                throw new IOException("runner HTTP failure");
            }
            JsonNode result = json.readTree(response.body());
            if (result == null) throw new IOException("runner empty response");
            if (status != 200 && (!result.path("ok").isBoolean() || result.path("ok").asBoolean())) {
                throw new IOException("runner invalid failure response");
            }
            return result;
        } finally {
            if (!pending.isDone()) pending.cancel(true);
        }
    }

    private ObjectNode unavailable() {
        markUnavailable();
        return failure("code_runner_unavailable", "代码执行环境不可用或返回无效响应");
    }

    private void markUnavailable() {
        healthy = false;
        healthCheckedAt = System.nanoTime();
    }

    private ObjectNode failure(String code, String error) {
        return json.createObjectNode().put("ok", false).put("code", code).put("error", error);
    }

    private static String normalizeCode(String raw) {
        String code = raw.substring(0, Math.min(raw.length(), 128)).toLowerCase(Locale.ROOT);
        return switch (code) {
            case "runner_unavailable", "cleanup_failed" -> "code_runner_unavailable";
            case "runner_busy" -> "code_runner_busy";
            case "request_timeout" -> "code_runner_timeout";
            default -> code.startsWith("code_") ? code : "code_" + code;
        };
    }

    private static URI parseEndpoint(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            URI uri = URI.create(raw.trim());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) return null;
            return URI.create(raw.trim().replaceAll("/+$", "") + "/");
        } catch (IllegalArgumentException e) { return null; }
    }

    /** Bounds bytes while they arrive, rather than allocating an unbounded response before checking. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        private LimitedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) return;
            for (ByteBuffer item : items) {
                if (item.remaining() > limit - body.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("runner response exceeds byte limit"));
                    return;
                }
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                body.writeBytes(bytes);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(body.toByteArray()); }
    }
}
