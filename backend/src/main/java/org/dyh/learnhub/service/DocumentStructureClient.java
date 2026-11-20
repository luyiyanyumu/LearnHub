package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** Bounded client for the operator-configured PP-StructureV3 sidecar. */
@Service
public class DocumentStructureClient {
    private static final String ENGINE = "pp-structurev3";
    static final String PROFILE = "high-quality-v2";
    private static final String MISSING = "未配置结构化 OCR 服务 LEARNHUB_DOCUMENT_STRUCTURE_URL";
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_BLOCKS = 5000;
    private static final Duration PAGE_TIMEOUT = Duration.ofSeconds(250);
    private final URI base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json = new ObjectMapper();

    public DocumentStructureClient(
            @Value("${learnhub.document-structure.url:${LEARNHUB_DOCUMENT_STRUCTURE_URL:}}") String url) {
        base = configuredBase(url);
    }

    public record Block(String type, String text, double[] rect, Double confidence, int level,
                        List<List<String>> rows, String latex) { }
    public record Result(int width, int height, List<Block> blocks, List<String> warnings) { }

    public boolean configured() { return base != null; }

    public Map<String, Object> capabilities() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", configured());
        result.put("available", false);
        result.put("engine", ENGINE);
        result.put("profile", "");
        result.put("version", "");
        result.put("message", MISSING);
        if (!configured()) return result;
        try {
            JsonNode body = request("health", null, Duration.ofSeconds(2));
            if (!ENGINE.equals(text(body.get("engine"), 40, false))) throw invalid();
            String profile = body.path("profile").asText("");
            boolean compatible = PROFILE.equals(profile);
            boolean ready = body.path("ready").asBoolean(false) && compatible;
            result.put("profile", compatible ? profile : "unknown");
            result.put("version", text(body.get("version"), 100, false));
            result.put("available", ready);
            result.put("message", !compatible ? "扫描服务尚未升级到高精度模型" : ready ? "高精度扫描模型可用" : "高精度扫描模型正在加载");
        } catch (IllegalStateException unavailable) {
            result.put("message", "结构化 OCR 服务尚未就绪，请检查服务状态");
        }
        return result;
    }

    public Result recognize(byte[] png, Duration remaining) {
        if (!configured()) throw new IllegalStateException(MISSING);
        if (png == null || png.length == 0 || png.length > 12 * 1024 * 1024)
            throw new IllegalArgumentException("结构化 OCR 图片为空或超过传输上限");
        if (remaining == null || remaining.isNegative() || remaining.isZero())
            throw new IllegalStateException("结构化 OCR 时间预算已耗尽");
        JsonNode body = request("ocr", png, remaining.compareTo(PAGE_TIMEOUT) > 0 ? PAGE_TIMEOUT : remaining);
        if (!ENGINE.equals(text(body.get("engine"), 40, false))) throw invalid();
        text(body.get("version"), 100, false);
        if (!PROFILE.equals(body.path("profile").asText("")))
            throw new IllegalStateException("扫描服务尚未升级到高精度模型，请更新结构化扫描服务");
        int width = positiveInt(body.get("width"), 10000);
        int height = positiveInt(body.get("height"), 10000);
        if ((long) width * height > 12_000_000) throw invalid();
        JsonNode entries = body.get("blocks");
        if (entries == null || !entries.isArray() || entries.size() > MAX_BLOCKS) throw invalid();
        List<Block> blocks = new ArrayList<>();
        for (JsonNode entry : entries) blocks.add(block(entry, width, height));
        List<String> warnings = new ArrayList<>();
        JsonNode notices = body.get("warnings");
        if (notices != null) {
            if (!notices.isArray() || notices.size() > 100) throw invalid();
            for (JsonNode item : notices) {
                String warning = text(item, 100, false);
                if (!warning.matches("pp_[a-z0-9_]+")) throw invalid();
                warnings.add(warning);
            }
        }
        return new Result(width, height, List.copyOf(blocks), List.copyOf(warnings));
    }

    private static Block block(JsonNode entry, int width, int height) {
        String type = text(entry.get("type"), 40, false);
        if (!Set.of("para", "heading", "table", "formula", "figure").contains(type)) throw invalid();
        String content = text(entry.get("text"), 100000, true);
        JsonNode box = entry.get("rect");
        if (box == null || !box.isArray() || box.size() != 4) throw invalid();
        double[] rect = new double[4];
        for (int i = 0; i < rect.length; i++) {
            JsonNode coordinate = box.get(i);
            if (!coordinate.isNumber()) throw invalid();
            rect[i] = coordinate.doubleValue();
            if (!Double.isFinite(rect[i]) || rect[i] < 0 || rect[i] > (i % 2 == 0 ? width : height))
                throw invalid();
        }
        if (rect[2] <= rect[0] || rect[3] <= rect[1]) throw invalid();
        JsonNode score = entry.get("confidence");
        Double confidence = null;
        if (score != null && !score.isNull()) {
            if (!score.isNumber() || !Double.isFinite(score.doubleValue()) || score.doubleValue() < 0
                    || score.doubleValue() > 1) throw invalid();
            confidence = score.doubleValue();
        }
        if (confidence == null && Set.of("para", "heading").contains(type)) throw invalid();
        int level = "heading".equals(type) ? positiveInt(entry.get("level"), 6) : 0;
        List<List<String>> rows = "table".equals(type) ? rows(entry.get("rows")) : List.of();
        String latex = "formula".equals(type) ? text(entry.get("latex"), 4096, true) : "";
        return new Block(type, content, rect, confidence, level, rows, latex);
    }

    private static List<List<String>> rows(JsonNode rows) {
        if (rows == null || !rows.isArray() || rows.isEmpty() || rows.size() > 64) throw invalid();
        List<List<String>> result = new ArrayList<>();
        int columns = rows.get(0).size();
        if (columns < 1 || columns > 48) throw invalid();
        for (JsonNode row : rows) {
            if (!row.isArray() || row.size() != columns) throw invalid();
            List<String> cells = new ArrayList<>();
            for (JsonNode cell : row) cells.add(text(cell, 4096, true));
            result.add(List.copyOf(cells));
        }
        return List.copyOf(result);
    }

    private JsonNode request(String endpoint, byte[] png, Duration timeout) {
        var builder = HttpRequest.newBuilder(base.resolve(endpoint)).timeout(timeout).header("Accept", "application/json");
        if (png == null) builder.GET();
        else builder.header("Content-Type", "image/png").POST(HttpRequest.BodyPublishers.ofByteArray(png));
        var pending = http.sendAsync(builder.build(), response -> new LimitedBody());
        try {
            var response = pending.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200)
                throw new IllegalStateException("结构化 OCR 服务返回 HTTP " + response.statusCode());
            JsonNode body = json.readTree(response.body());
            if (body == null || !body.isObject()) throw invalid();
            return body;
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("结构化 OCR 请求已中断");
        } catch (IllegalStateException rejected) {
            throw rejected;
        } catch (Exception failed) {
            pending.cancel(true);
            throw new IllegalStateException("结构化 OCR 请求失败、超时或响应超限");
        }
    }

    private static URI configuredBase(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI uri = URI.create(url.trim());
            if (uri.getScheme() == null || !Set.of("http", "https").contains(uri.getScheme())
                    || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) throw new IllegalArgumentException();
            return URI.create(uri.toString().replaceAll("/+$", "") + "/");
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("结构化 OCR 服务必须配置可信 HTTP(S) 基础地址且不含凭据、查询和片段");
        }
    }

    private static int positiveInt(JsonNode value, int maximum) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < 1 || value.intValue() > maximum) throw invalid();
        return value.intValue();
    }

    private static String text(JsonNode value, int maximum, boolean allowEmpty) {
        if (allowEmpty && (value == null || value.isNull())) return "";
        if (value == null || !value.isTextual() || value.textValue().length() > maximum
                || (!allowEmpty && value.textValue().isBlank())) throw invalid();
        return value.textValue();
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("结构化 OCR 响应结构、坐标或置信度无效");
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> items) {
            for (ByteBuffer item : items) {
                if ((long) bytes.size() + item.remaining() > MAX_BYTES) {
                    subscription.cancel();
                    body.completeExceptionally(invalid());
                    return;
                }
                byte[] next = new byte[item.remaining()];
                item.get(next);
                bytes.writeBytes(next);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { body.completeExceptionally(failure); }
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
