package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.dyh.learnhub.entity.ModelProfile;
import org.dyh.learnhub.service.ModelProfileService;
import org.dyh.learnhub.service.SettingsService;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Embeddings are optional and have a profile/route separate from chat. */
@Component
@RequiredArgsConstructor
public class EmbeddingClient {
    public static final String DEFAULT_BASE_URL = "http://localhost:11434";
    public static final String DEFAULT_MODEL = "bge-m3";
    private static final int BATCH = 16;
    private static final int MAX_CHARS = 6000;
    // kb_chunk.vec is a MySQL BLOB containing float32 values.
    private static final int MAX_DIMENSIONS = 65535 / Float.BYTES;
    private final SettingsService settingsService;
    private final ObjectMapper objectMapper;
    private final ModelProfileService profiles;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** Immutable request configuration. The key is private and never included in toString. */
    public static final class Snapshot {
        private final boolean configured;
        private final String model, baseUrl, provider, protocol, endpoint, apiKey, spaceFingerprint, reason;

        private Snapshot(boolean configured, String model, String baseUrl, String provider,
                         String protocol, String endpoint, String apiKey, String fingerprint, String reason) {
            this.configured = configured;
            this.model = model;
            this.baseUrl = baseUrl;
            this.provider = provider;
            this.protocol = protocol;
            this.endpoint = endpoint;
            this.apiKey = apiKey;
            this.spaceFingerprint = fingerprint;
            this.reason = reason;
        }

        public static Snapshot disabled(String reason) {
            return new Snapshot(false, "", "", "", "", "", null, "", reason);
        }

        public static Snapshot of(String model, String baseUrl, String provider, String apiKey) {
            if (model == null || model.isBlank()) throw new IllegalArgumentException("嵌入模型名不能为空");
            String kind = provider == null ? "custom" : provider.trim().toLowerCase(Locale.ROOT);
            String protocol = "ollama".equals(kind) ? "ollama" : "openai";
            String endpoint = normalizeEndpoint(baseUrl, protocol);
            String suffix = "ollama".equals(protocol) ? "/api/embed" : "/embeddings";
            String normalizedBase = endpoint.substring(0, endpoint.length() - suffix.length());
            String normalizedModel = model.trim();
            if (apiKey != null && apiKey.chars().anyMatch(c -> c < 0x20 || c > 0x7e))
                throw new IllegalArgumentException("嵌入认证信息含有无效字符");
            String fingerprint = sha256(protocol + "\n" + endpoint + "\n" + normalizedModel);
            return new Snapshot(true, normalizedModel, normalizedBase, kind, protocol, endpoint,
                    apiKey == null || apiKey.isBlank() ? null : apiKey.trim(), fingerprint, "");
        }

        public boolean configured() { return configured; }
        public String model() { return model; }
        public String baseUrl() { return baseUrl; }
        public String provider() { return provider; }
        public String endpoint() { return endpoint; }
        public String spaceFingerprint() { return spaceFingerprint; }
        public String reason() { return reason; }

        @Override
        public String toString() {
            return "EmbeddingSnapshot[configured=" + configured + ", model=" + model
                    + ", provider=" + provider + ", spaceFingerprint=" + spaceFingerprint + "]";
        }
    }

    public Snapshot snapshot() {
        String route = settingsService.effective(SettingsService.modelForTaskKey(ModelRouting.TASK_EMBED));
        if (route == null || route.isBlank() || ModelRouting.EMBED_DISABLED.equals(route.trim()))
            return Snapshot.disabled("未配置向量嵌入模型，使用关键词检索");
        if (ModelRouting.EMBED_LEGACY.equals(route.trim())) {
            try {
                return Snapshot.of(legacyValue(SettingsService.KEY_EMBED_MODEL, DEFAULT_MODEL),
                        legacyValue(SettingsService.KEY_EMBED_BASE_URL, DEFAULT_BASE_URL), "ollama", null);
            } catch (IllegalArgumentException e) {
                return Snapshot.disabled(e.getMessage());
            }
        }
        try {
            return snapshotForProfile(route.trim());
        } catch (IllegalArgumentException e) {
            return Snapshot.disabled(e.getMessage());
        }
    }

    /** Strict profile lookup for the settings test button, independent of the current route. */
    public Snapshot snapshotForProfile(String profileId) {
        ModelProfile profile = profiles.embeddingProfile(profileId);
        try {
            return Snapshot.of(profile.getModel(), profile.getBaseUrl(), profile.getProvider(), profile.getApiKey());
        } catch (IllegalArgumentException e) {
            return Snapshot.disabled(e.getMessage());
        }
    }

    private String legacyValue(String key, String fallback) {
        String value = settingsService.effective(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public String baseUrl() { return snapshot().baseUrl(); }
    public String model() { return snapshot().model(); }
    public float[] embed(String text) { return embed(snapshot(), text); }
    public float[] embed(Snapshot snapshot, String text) {
        List<float[]> result = embedAll(snapshot, java.util.Collections.singletonList(text));
        return result.isEmpty() ? null : result.get(0);
    }
    public List<float[]> embedAll(List<String> texts) { return embedAll(snapshot(), texts); }

    /** Every batch uses the same snapshot even if settings change during the call. */
    public List<float[]> embedAll(Snapshot snapshot, List<String> texts) {
        return embedAll(snapshot, texts, Duration.ofMinutes(5));
    }

    private List<float[]> embedAll(Snapshot snapshot, List<String> texts, Duration timeout) {
        if (snapshot == null || !snapshot.configured())
            throw new IllegalStateException(snapshot == null ? "未配置向量嵌入模型" : snapshot.reason());
        if (texts == null) throw new IllegalArgumentException("嵌入文本不能为空");
        List<float[]> out = new ArrayList<>();
        int dimensions = 0;
        for (int i = 0; i < texts.size(); i += BATCH) {
            List<float[]> vectors = embedBatch(snapshot, texts.subList(i, Math.min(texts.size(), i + BATCH)), timeout);
            for (float[] vector : vectors) {
                if (dimensions != 0 && vector.length != dimensions)
                    throw new IllegalStateException("嵌入服务返回的向量维数不一致");
                dimensions = vector.length;
                out.add(vector);
            }
        }
        return out;
    }

    private List<float[]> embedBatch(Snapshot snapshot, List<String> batch, Duration timeout) {
        var body = objectMapper.createObjectNode();
        body.put("model", snapshot.model());
        var input = body.putArray("input");
        for (String text : batch) {
            if (text == null || text.isBlank()) throw new IllegalArgumentException("嵌入文本不能为空");
            int end = Math.min(text.length(), MAX_CHARS);
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            input.add(text.substring(0, end));
        }
        if ("openai".equals(snapshot.protocol)) body.put("encoding_format", "float");
        var builder = HttpRequest.newBuilder(URI.create(snapshot.endpoint()))
                .timeout(timeout).header("Content-Type", "application/json; charset=utf-8");
        if (snapshot.apiKey != null) builder.header("Authorization", "Bearer " + snapshot.apiKey);
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200)
                // Bodies may echo credentials or user text. Never include them in errors.
                throw new IllegalStateException("嵌入请求失败（HTTP " + response.statusCode()
                        + "），请检查嵌入模型、服务地址和认证配置");
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode entries = root.path("ollama".equals(snapshot.protocol) ? "embeddings" : "data");
            if (!entries.isArray() || entries.size() != batch.size())
                throw new IllegalStateException("嵌入返回数量与输入数量不一致");
            float[][] ordered = new float[batch.size()][];
            for (int position = 0; position < entries.size(); position++) {
                JsonNode entry = entries.get(position);
                int index = position;
                JsonNode values = entry;
                if ("openai".equals(snapshot.protocol)) {
                    JsonNode suppliedIndex = entry.get("index");
                    if (suppliedIndex == null || !suppliedIndex.isIntegralNumber() || !suppliedIndex.canConvertToInt())
                        throw new IllegalStateException("嵌入服务返回的索引无效");
                    index = suppliedIndex.intValue();
                    values = entry.path("embedding");
                }
                if (index < 0 || index >= ordered.length || ordered[index] != null)
                    throw new IllegalStateException("嵌入服务返回的索引重复或越界");
                ordered[index] = validVector(values);
            }
            return List.of(ordered);
        } catch (IllegalStateException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("嵌入请求已中断");
        } catch (Exception e) {
            throw new IllegalStateException("嵌入服务调用失败，请检查服务地址、网络和响应格式");
        }
    }

    private static float[] validVector(JsonNode values) {
        if (!values.isArray() || values.isEmpty() || values.size() > MAX_DIMENSIONS)
            throw new IllegalStateException("嵌入服务返回的向量维数无效");
        float[] vector = new float[values.size()];
        boolean nonZero = false;
        for (int i = 0; i < vector.length; i++) {
            JsonNode value = values.get(i);
            float number = (float) value.asDouble();
            if (!value.isNumber() || !Float.isFinite(number))
                throw new IllegalStateException("嵌入服务返回的向量包含无效数值");
            vector[i] = number;
            nonZero |= number != 0;
        }
        if (!nonZero) throw new IllegalStateException("嵌入服务返回了零向量");
        return vector;
    }

    public Map<String, Object> probe(Snapshot snapshot) {
        long start = System.currentTimeMillis();
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("model", snapshot.model());
        result.put("baseUrl", snapshot.baseUrl());
        try {
            float[] vector = embedAll(snapshot, List.of("连接测试"), Duration.ofSeconds(20)).get(0);
            result.put("ok", true);
            result.put("dimension", vector.length);
            result.put("message", "嵌入服务连接正常");
        } catch (IllegalArgumentException | IllegalStateException e) {
            result.put("ok", false);
            result.put("message", e.getMessage());
        }
        result.put("ms", System.currentTimeMillis() - start);
        return result;
    }

    public String probe() {
        Map<String, Object> result = probe(snapshot());
        return Boolean.TRUE.equals(result.get("ok"))
                ? "ok（" + result.get("dimension") + " 维，模型 " + result.get("model") + "）"
                : "不可用：" + result.get("message");
    }

    private static String normalizeEndpoint(String rawBase, String protocol) {
        try {
            URI uri = URI.create(rawBase == null ? "" : rawBase.trim()).normalize();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((!"http".equals(scheme) && !"https".equals(scheme)) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
                throw new IllegalArgumentException();
            String path = uri.getPath() == null ? "" : uri.getPath();
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            if ("ollama".equals(protocol)) {
                if (path.endsWith("/api/embed")) path = path.substring(0, path.length() - 10);
                if (path.endsWith("/v1")) path = path.substring(0, path.length() - 3);
                path += "/api/embed";
            } else {
                for (String tail : List.of("/chat/completions", "/models", "/embeddings")) {
                    if (path.endsWith(tail)) {
                        path = path.substring(0, path.length() - tail.length());
                        break;
                    }
                }
                path += "/embeddings";
            }
            int port = uri.getPort();
            if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) port = -1;
            return new URI(scheme, null, uri.getHost().toLowerCase(Locale.ROOT), port, path, null, null).toASCIIString();
        } catch (Exception e) {
            throw new IllegalArgumentException("嵌入基址须为不含账号、查询参数或片段的 HTTP/HTTPS 地址");
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用");
        }
    }

    public static byte[] toBytes(float[] vector) {
        if (vector == null) return null;
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) buffer.putFloat(value);
        return buffer.array();
    }

    public static float[] toVector(byte[] bytes) {
        if (bytes == null || bytes.length < 4) return null;
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[bytes.length / 4];
        for (int i = 0; i < vector.length; i++) vector[i] = buffer.getFloat();
        return vector;
    }

    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * (double) b[i];
            normA += a[i] * (double) a[i];
            normB += b[i] * (double) b[i];
        }
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
