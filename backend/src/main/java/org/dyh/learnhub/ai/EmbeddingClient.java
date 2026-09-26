package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dyh.learnhub.service.SettingsService;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 嵌入客户端：把文本变成向量（语义检索的地基）。
 *
 * <h3>为什么走 Ollama 的 {@code /api/embed}</h3>
 * 本机已经在跑 Ollama，拉一个 embedding 模型（bge-m3，1024 维）就等于有了免费、私有的嵌入服务 ——
 * 不引 Java 依赖、不把资料发给第三方、按次成本为零。
 *
 * <p><b>一个踩过的坑</b>：用**聊天模型**（如 qwen3:8b）调这个接口会得到
 * {@code 501 This server does not support embeddings. Start it with --embeddings} ——
 * 看起来像"服务端没开嵌入支持"，实际是"这个模型不是嵌入模型"。换成 bge-m3 直接可用，
 * 不需要重启 Ollama。以后遇到这条报错先检查模型名，别去折腾启动参数。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingClient {

    /** Ollama 原生 API 的基址（不是 OpenAI 兼容那套 /v1） */
    public static final String DEFAULT_BASE_URL = "http://localhost:11434";
    public static final String DEFAULT_MODEL = "bge-m3";

    /** 一次请求最多嵌入多少条：太大容易超时，太小则往返次数多 */
    private static final int BATCH = 16;
    /** 单条文本截断：bge-m3 上下文 8192 token，这里按字符保守截 */
    private static final int MAX_CHARS = 6000;

    private final SettingsService settingsService;
    private final ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public String baseUrl() {
        String v = settingsService.effective(SettingsService.KEY_EMBED_BASE_URL);
        return v == null || v.isBlank() ? DEFAULT_BASE_URL : v.trim();
    }

    public String model() {
        String v = settingsService.effective(SettingsService.KEY_EMBED_MODEL);
        return v == null || v.isBlank() ? DEFAULT_MODEL : v.trim();
    }

    /** 单条嵌入 */
    public float[] embed(String text) {
        List<float[]> r = embedAll(List.of(text));
        return r.isEmpty() ? null : r.get(0);
    }

    /**
     * 批量嵌入（自动分包）。
     *
     * @return 与入参一一对应的向量；失败抛异常（由调用方决定是否降级）
     */
    public List<float[]> embedAll(List<String> texts) {
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += BATCH) {
            List<String> batch = texts.subList(i, Math.min(texts.size(), i + BATCH));
            out.addAll(embedBatch(batch));
        }
        return out;
    }

    private List<float[]> embedBatch(List<String> batch) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model());
        ArrayNode input = body.putArray("input");
        for (String t : batch) {
            String s = t == null ? "" : t;
            input.add(s.length() > MAX_CHARS ? s.substring(0, MAX_CHARS) : s);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/embed"))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("嵌入失败(" + resp.statusCode() + ")："
                        + brief(resp.body()) + "（若报不支持嵌入，先确认 " + model() + " 是嵌入模型）");
            }
            JsonNode root = objectMapper.readTree(resp.body());
            JsonNode arr = root.path("embeddings");
            if (!arr.isArray() || arr.size() != batch.size()) {
                throw new IllegalStateException("嵌入返回数量不符：期望 " + batch.size() + " 实得 " + arr.size());
            }
            List<float[]> out = new ArrayList<>();
            for (JsonNode v : arr) {
                float[] vec = new float[v.size()];
                for (int i = 0; i < v.size(); i++) {
                    vec[i] = (float) v.get(i).asDouble();
                }
                out.add(vec);
            }
            return out;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("嵌入调用异常：" + e.getMessage());
        }
    }

    /** 探活：给界面一个"能不能用"的即时判断 */
    public String probe() {
        try {
            float[] v = embed("探活");
            return v == null ? "空向量" : "ok（" + v.length + " 维，模型 " + model() + "）";
        } catch (Exception e) {
            return "不可用：" + brief(e.getMessage());
        }
    }

    // ---------------- 向量与字节的互转（存 BLOB 用，小端 float32） ----------------

    public static byte[] toBytes(float[] vec) {
        if (vec == null) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.allocate(vec.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : vec) {
            buf.putFloat(f);
        }
        return buf.array();
    }

    public static float[] toVector(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vec = new float[bytes.length / 4];
        for (int i = 0; i < vec.length; i++) {
            vec[i] = buf.getFloat();
        }
        return vec;
    }

    /** 余弦相似度（两向量都已是嵌入结果，通常已归一化，这里仍按通用公式算） */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static String brief(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }
}
