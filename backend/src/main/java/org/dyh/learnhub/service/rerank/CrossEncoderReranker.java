package org.dyh.learnhub.service.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dyh.learnhub.service.SettingsService;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cross-Encoder 精排：逐对给 (query, 候选) 打分，按分数排序。
 *
 * <h3>为什么它比 LLM 列表重排合适</h3>
 * <ul>
 *   <li><b>逐对独立</b>：分数不受候选顺序影响，没有 listwise 的位置偏差；</li>
 *   <li><b>没有窗口</b>：60 条候选一次算完，不需要分批 + 轮转（也就不需要"漏编号补后面"那套兜底）；</li>
 *   <li><b>没有格式风险</b>：输出是数值数组，不存在"模型没按 JSON 回"的问题；</li>
 *   <li><b>快</b>：560M 的 bge-reranker 在 CPU 上给几十条打分是几百毫秒级，
 *       而本地 8B 列表重排每批要秒级到十几秒。</li>
 * </ul>
 *
 * <h3>服务形态</h3>
 * 用 {@code tools/rerank-server.py}（sentence-transformers + CrossEncoder，标准库 HTTP 服务）暴露
 * {@code POST /rerank}，请求/响应对齐 Jina / TEI / Cohere 的常见形状：
 * <pre>
 * 请求 {"query":"...","documents":["...","..."]}
 * 响应 {"results":[{"index":0,"relevance_score":0.98}, ...]}   ← 按分数降序
 * </pre>
 * 为什么不做成 JVM 内嵌：这台机器 **Maven Central 不通**（加不了新依赖），
 * 而 Python 侧已经有 torch / onnxruntime / tokenizers，起个 sidecar 是最短路径。
 *
 * <p>失败**抛异常**，由门面 {@code RerankService} 回退到 LLM 重排 ——
 * 检索不能因为精排服务挂了就没有结果。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrossEncoderReranker implements Reranker {

    /** 精排服务地址（Jina / TEI / 本仓库 tools/rerank-server.py 都兼容这个路径） */
    public static final String KEY_URL = "kb.rerank_url";
    /** 探活结果缓存时长：服务挂了不该让每轮对话都等一次连接超时 */
    private static final long HEALTH_TTL_MS = 30_000;
    /** 摘要片段长度的默认值（可用 {@link Reranker#KEY_SNIPPET} 覆盖；Cross-Encoder 给长一点通常更准） */
    private static final int DEFAULT_SNIPPET = 120;
    /** 上限：再长就是拿整篇当 prompt，CPU 上打分时间会线性涨 */
    private static final int MAX_SNIPPET = 2000;

    private final SettingsService settingsService;
    private final ObjectMapper json;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private volatile long lastHealthAt;
    private volatile boolean lastHealthy;
    private volatile String lastError = "";

    @Override
    public String name() {
        return "cross";
    }

    @Override
    public boolean available() {
        long now = System.currentTimeMillis();
        if (now - lastHealthAt < HEALTH_TTL_MS) {
            return lastHealthy;
        }
        String url = healthUrl();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode root = json.readTree(resp.body());
            lastHealthy = resp.statusCode() == 200 && "ok".equals(root.path("status").asText(""));
            lastError = lastHealthy ? "" : "服务返回 " + resp.statusCode() + " / " + root.path("status").asText("");
        } catch (Exception e) {
            lastHealthy = false;
            lastError = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        if (!lastHealthy) {
            log.warn("Cross-Encoder 精排服务不可用（本轮回退 LLM 重排）：{} → {}", url, lastError);
        }
        lastHealthAt = now;
        return lastHealthy;
    }

    @Override
    public List<String> rerank(String question, List<Item> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("query", question == null ? "" : question);
        ArrayNode docs = body.putArray("documents");
        for (Item it : items) {
            // Both backends preserve the section label and a question-centered source
            // excerpt within the same configured character budget.
            String snip = QueryAwareExcerpt.rerankExcerpt(question, it.snippet(), snippetLimit());
            docs.add((it.title() == null ? "" : it.title()) + (snip.isEmpty() ? "" : "｜" + snip));
        }
        JsonNode data;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(settingsService.effective(KEY_URL) == null
                            || settingsService.effective(KEY_URL).isBlank()
                            ? "http://127.0.0.1:8091/rerank" : settingsService.effective(KEY_URL).trim()))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                lastHealthy = false;
                lastHealthAt = System.currentTimeMillis();
                lastError = "HTTP " + resp.statusCode() + "：" + clip(resp.body());
                throw new IllegalStateException("精排服务 HTTP " + resp.statusCode() + "：" + clip(resp.body()));
            }
            data = json.readTree(resp.body());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            lastHealthy = false;
            lastHealthAt = System.currentTimeMillis();
            lastError = e.toString();
            throw new IllegalStateException("精排服务调用失败：" + e, e);
        }
        // 兼容两种字段名：results（Jina/TEI/本仓库脚本）/ data（部分 OpenAI 风格实现）
        JsonNode arr = data.has("results") ? data.path("results") : data.path("data");
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) {
            int idx = n.path("index").asInt(-1);
            if (idx >= 0 && idx < items.size()) {
                String k = items.get(idx).key();
                if (!out.contains(k)) {
                    out.add(k);
                }
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("精排服务没有返回任何可用 index：" + clip(data.toString()));
        }
        // 服务只回了一部分（被 top_n 截过）：剩下的按原顺序补在后面，绝不丢候选
        for (Item it : items) {
            if (!out.contains(it.key())) {
                out.add(it.key());
            }
        }
        return out;
    }

    @Override
    public Map<String, Object> status() {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("backend", name());
        o.put("url", url());
        o.put("healthy", available());
        if (!lastError.isBlank()) {
            o.put("lastError", lastError);
        }
        return o;
    }

    /** 最近一次错误（界面/日志用） */
    public String lastError() {
        return lastError;
    }

    /** 当前生效的候选文本长度（设置里没配就用默认 120，上限 {@link #MAX_SNIPPET}） */
    private int snippetLimit() {
        String v = settingsService.effective(Reranker.KEY_SNIPPET);
        if (v == null || v.isBlank()) {
            return DEFAULT_SNIPPET;
        }
        try {
            int n = Integer.parseInt(v.trim());
            return n <= 0 ? MAX_SNIPPET : Math.min(n, MAX_SNIPPET);
        } catch (NumberFormatException e) {
            return DEFAULT_SNIPPET;
        }
    }

    private String url() {
        String v = settingsService.effective(KEY_URL);
        return v == null || v.isBlank() ? "http://127.0.0.1:8091/rerank" : v.trim();
    }

    /** /rerank → /health（探针用同一个服务，不必额外开端口） */
    private String healthUrl() {
        String u = url();
        return u.endsWith("/rerank") ? u.substring(0, u.length() - "/rerank".length()) + "/health" : u + "/health";
    }

    private static String clip(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
