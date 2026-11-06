package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** Talks only to the operator-configured local OCR service; document content never selects an endpoint. */
@Service
public class DocumentOcrClient {
    static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    static final int MAX_BLOCKS = 5000;
    private static final long MAX_PIXELS = 16_000_000;
    private final URI base;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public DocumentOcrClient(@Value("${learnhub.document-ocr.url:${LEARNHUB_DOCUMENT_OCR_URL:}}") String url) {
        this(url, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    DocumentOcrClient(String url, HttpClient http) {
        this.base = configuredBase(url);
        this.http = http;
    }

    public record Block(String id, String type, String text, double[] rect, double confidence, int words) { }
    public record Result(String engine, String version, int width, int height, List<Block> blocks,
                         List<String> warnings) { }
    public record Health(boolean available, String engine, String version, String reason) { }

    public boolean configured() { return base != null; }

    public Health health() {
        if (!configured()) return new Health(false, "", "", "未配置本地 OCR 服务 LEARNHUB_DOCUMENT_OCR_URL");
        try {
            JsonNode body = request("health", null, Duration.ofSeconds(2));
            boolean ready = body.path("ok").asBoolean(body.path("ready").asBoolean(false));
            return new Health(ready, boundedText(body, "engine", 100, false),
                    boundedText(body, "version", 100, false), ready ? "" : "本地 OCR 服务尚未就绪");
        } catch (Exception failed) {
            return new Health(false, "", "", "本地 OCR 服务不可用");
        }
    }

    public Map<String, Object> capabilities() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", configured());
        result.put("available", false); result.put("engine", ""); result.put("languages", List.of());
        result.put("message", "未配置本地 OCR 服务 LEARNHUB_DOCUMENT_OCR_URL");
        if (!configured()) return result;
        try {
            JsonNode body = request("health", null, Duration.ofSeconds(2));
            boolean ready = body.path("ok").asBoolean(body.path("ready").asBoolean(false));
            result.put("available", ready);
            result.put("engine", boundedText(body, "engine", 100, false));
            result.put("version", boundedText(body, "version", 100, false));
            JsonNode languages = body.get("languages");
            if (languages != null && languages.isArray() && languages.size() <= 100) {
                List<String> values = new ArrayList<>();
                for (JsonNode language : languages) if (language.isTextual() && language.textValue().length() <= 100)
                    values.add(language.textValue());
                result.put("languages", List.copyOf(values));
            }
            result.put("message", ready ? "本地 OCR 服务可用" : "本地 OCR 服务尚未就绪");
        } catch (Exception unavailable) { result.put("message", "本地 OCR 服务不可用，请检查容器健康状态"); }
        return result;
    }

    public Result recognize(byte[] png, Duration remaining) {
        if (!configured()) throw new IllegalStateException("未配置本地 OCR 服务 LEARNHUB_DOCUMENT_OCR_URL");
        if (png == null || png.length == 0 || png.length > 12 * 1024 * 1024)
            throw new IllegalArgumentException("OCR 图片为空或超过传输上限");
        JsonNode body = request("ocr", png, remaining.compareTo(Duration.ofSeconds(65)) > 0
                ? Duration.ofSeconds(65) : remaining);
        String engine = boundedText(body, "engine", 100, true);
        String version = boundedText(body, "version", 100, true);
        int width = positiveInt(body.get("width"), "width", 100000);
        int height = positiveInt(body.get("height"), "height", 100000);
        if ((long) width * height > MAX_PIXELS) throw invalid();
        JsonNode entries = body.get("blocks");
        if (entries == null || !entries.isArray() || entries.size() > MAX_BLOCKS) throw invalid();
        List<Block> blocks = new ArrayList<>();
        for (JsonNode entry : entries) {
            String id = boundedText(entry, "id", 100, true);
            String type = boundedText(entry, "type", 40, true);
            String text = boundedText(entry, "text", 100000, false);
            JsonNode box = entry.get("rect");
            if (box == null || !box.isArray() || box.size() != 4) throw invalid();
            double[] rect = new double[4];
            for (int i = 0; i < 4; i++) {
                JsonNode coordinate = box.get(i);
                if (!coordinate.isNumber()) throw invalid();
                rect[i] = coordinate.doubleValue();
                if (!Double.isFinite(rect[i]) || rect[i] < 0
                        || rect[i] > (i % 2 == 0 ? width : height)) throw invalid();
            }
            if (rect[2] <= rect[0] || rect[3] <= rect[1]) throw invalid();
            JsonNode score = entry.get("confidence");
            if (score == null || !score.isNumber() || !Double.isFinite(score.doubleValue())
                    || score.doubleValue() < 0 || score.doubleValue() > 1) throw invalid();
            JsonNode words = entry.get("words");
            if (words != null && (!words.isIntegralNumber() || !words.canConvertToInt()
                    || words.intValue() < 0 || words.intValue() > 100000)) throw invalid();
            blocks.add(new Block(id, type, text, rect, score.doubleValue(), words == null ? 0 : words.intValue()));
        }
        List<String> warnings = new ArrayList<>();
        JsonNode notices = body.get("warnings");
        if (notices != null) {
            if (!notices.isArray() || notices.size() > 100) throw invalid();
            for (JsonNode notice : notices) {
                if (!notice.isTextual() || notice.textValue().length() > 1000) throw invalid();
                warnings.add(notice.textValue());
            }
        }
        return new Result(engine, version, width, height, List.copyOf(blocks), List.copyOf(warnings));
    }

    private JsonNode request(String endpoint, byte[] png, Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) throw new IllegalStateException("OCR 时间预算已耗尽");
        var builder = HttpRequest.newBuilder(base.resolve(endpoint)).timeout(timeout).header("Accept", "application/json");
        if (png == null) builder.GET();
        else builder.header("Content-Type", "image/png").POST(HttpRequest.BodyPublishers.ofByteArray(png));
        CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(builder.build(), response -> new LimitedBody());
        try {
            HttpResponse<byte[]> response = pending.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IllegalStateException("本地 OCR 服务返回 HTTP " + response.statusCode());
            JsonNode parsed = json.readTree(response.body());
            if (parsed == null || !parsed.isObject()) throw invalid();
            return parsed;
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("本地 OCR 请求已中断");
        } catch (IllegalStateException invalid) {
            throw invalid;
        } catch (Exception failed) {
            pending.cancel(true);
            // Never echo service response bodies or document text into an exception.
            throw new IllegalStateException("本地 OCR 请求失败、超时或响应超过安全上限");
        }
    }

    private static URI configuredBase(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI uri = URI.create(url.trim());
            if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null)
                throw new IllegalArgumentException();
            return URI.create(uri.toString().replaceAll("/+$", "") + "/");
        } catch (Exception invalid) {
            throw new IllegalArgumentException("本地 OCR 服务地址必须是受信任的 HTTP(S) 基础地址，不含凭据、查询或片段");
        }
    }

    private static int positiveInt(JsonNode value, String name, int maximum) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < 1 || value.intValue() > maximum) throw invalid();
        return value.intValue();
    }

    private static String boundedText(JsonNode body, String key, int maximum, boolean required) {
        JsonNode value = body.get(key);
        if (value == null && !required) return "";
        if (value == null || !value.isTextual() || value.textValue().length() > maximum
                || (required && value.textValue().isBlank())) throw invalid();
        return value.textValue();
    }

    private static IllegalStateException invalid() { return new IllegalStateException("本地 OCR 响应结构、坐标或置信度无效"); }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return body; }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        public void onNext(List<ByteBuffer> items) {
            for (ByteBuffer item : items) {
                if ((long) bytes.size() + item.remaining() > MAX_RESPONSE_BYTES) {
                    subscription.cancel(); body.completeExceptionally(invalid()); return;
                }
                byte[] next = new byte[item.remaining()]; item.get(next); bytes.writeBytes(next);
            }
            subscription.request(1);
        }
        public void onError(Throwable failed) { body.completeExceptionally(failed); }
        public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
