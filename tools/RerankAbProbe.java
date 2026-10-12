import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.ai.DeepSeekClient;
import org.dyh.learnhub.ai.ModelRouting;
import org.dyh.learnhub.config.AiProperties;
import org.dyh.learnhub.service.SettingsService;
import org.dyh.learnhub.service.rerank.CrossEncoderReranker;
import org.dyh.learnhub.service.rerank.LlmListwiseReranker;
import org.dyh.learnhub.service.rerank.QueryAwareExcerpt;
import org.dyh.learnhub.service.rerank.Reranker;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Isolated JSON-lines adapter over the production rerankers. It starts neither Spring nor a
 * database, and never invokes RerankService (which could mix backends through fallback).
 *
 * First stdin line: {baseUrl,apiKey,model,snippet:120,crossUrl}. Subsequent lines:
 * {id,question,items:[{key,title,text}],arm:"llm"|"cross"|"excerpt"}.
 * Credentials belong on stdin only, not in command arguments or an input file. Stdout contains
 * one JSON result per request; model text, endpoints, exception messages and credentials are
 * never emitted. Compile against backend/target/classes and the Maven dependency classpath.
 *
 * A/B requests contain 3–20 unique candidates, keeping LLM evaluation within one real batch.
 * elapsedMs includes the production client request (including its engine-restart retry, if any).
 * diagnostics distinguishes a valid ranking, repaired output and the LLM's silent fallback.
 */
public final class RerankAbProbe {
    private static final int SNIPPET = 120;
    private static final RecordingMapper JSON = new RecordingMapper();
    private static final PrintWriter OUT = new PrintWriter(
            new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);

    private final Config config;
    private final RecordingClient client;
    private final LlmListwiseReranker llm;
    private final CrossEncoderReranker cross;

    private RerankAbProbe(Config config) {
        this.config = config;
        FixedSettings settings = new FixedSettings(config);
        FixedRouting routing = new FixedRouting(settings, config);
        client = new RecordingClient(settings, routing);
        llm = new LlmListwiseReranker(client, routing, JSON, settings);
        cross = new CrossEncoderReranker(settings, JSON);
    }

    public static void main(String[] args) throws Exception {
        // Production failure logging may contain an upstream response body. Disable it before
        // creating any production classes; diagnostics below uses fixed codes, never messages.
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(Level.OFF);
        }
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        Config config;
        try {
            config = Config.read(JSON.readTree(input.readLine()));
        } catch (Exception error) {
            emit(errorResult(null, null, "configuration_error", error), "");
            return;
        }
        RerankAbProbe probe = new RerankAbProbe(config);
        emit(Map.of("status", "ready"), config.apiKey());
        for (String line; (line = input.readLine()) != null;) {
            if (line.isBlank()) continue;
            Map<String, Object> result;
            try {
                result = probe.run(JSON.readTree(line));
            } catch (Exception error) {
                result = errorResult(null, null, "invalid_request", error);
            }
            emit(result, config.apiKey());
        }
    }

    private Map<String, Object> run(JsonNode request) throws Exception {
        String id = text(request, "id");
        String arm = text(request, "arm");
        String question = text(request, "question");
        if (!Set.of("llm", "cross", "excerpt").contains(arm)) throw new IllegalArgumentException();
        JsonNode rawItems = request.path("items");
        if (!rawItems.isArray() || rawItems.isEmpty() || rawItems.size() > LlmListwiseReranker.BATCH
                || (!"excerpt".equals(arm) && rawItems.size() <= 2)) throw new IllegalArgumentException();
        List<Reranker.Item> items = new ArrayList<>();
        List<Map<String, String>> excerpts = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (JsonNode raw : rawItems) {
            String key = text(raw, "key");
            String title = text(raw, "title");
            String source = text(raw, "text");
            if (key.isBlank() || !keys.add(key)) throw new IllegalArgumentException();
            items.add(new Reranker.Item(key, title, source));
            String excerpt = QueryAwareExcerpt.rerankExcerpt(question, source, SNIPPET);
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("key", key);
            entry.put("title", title);
            entry.put("excerpt", excerpt);
            entry.put("document", title + (excerpt.isEmpty() ? "" : "｜" + excerpt));
            excerpts.add(entry);
        }
        String excerptsHash = sha256(JSON.writeValueAsBytes(excerpts));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("arm", arm);
        result.put("excerptsHash", excerptsHash);
        client.reset(items.size());
        JSON.arm = arm;
        JSON.crossResponse = null;
        List<String> order = List.of();
        Map<String, Object> diagnostics;
        long started = System.nanoTime();
        try {
            if ("excerpt".equals(arm)) {
                order = items.stream().map(Reranker.Item::key).toList();
                result.put("excerpts", excerpts);
                diagnostics = diagnostic("excerpt", false, false);
            } else if ("llm".equals(arm)) {
                order = llm.rerank(question, items);
                diagnostics = client.diagnostics();
            } else {
                // Call this backend directly, even if the application's active backend is LLM.
                // No availability()/status() or facade call can introduce a fallback to A.
                order = cross.rerank(question, items);
                diagnostics = analyzeCross(JSON.crossResponse, items.size());
            }
        } catch (Exception error) {
            diagnostics = diagnostic("request_error", false, false);
            diagnostics.put("errorType", error.getClass().getSimpleName());
            // A failed B arm returns no ranking, never an original-order or LLM substitute.
        } finally {
            JSON.arm = "";
        }
        result.put("order", order);
        result.put("elapsedMs", (System.nanoTime() - started) / 1_000_000.0);
        diagnostics.put("candidateCount", items.size());
        diagnostics.put("snippet", SNIPPET);
        result.put("diagnostics", diagnostics);
        return result;
    }

    private static Map<String, Object> analyzeCross(JsonNode response, int count) {
        JsonNode values = response == null ? null : response.has("results")
                ? response.path("results") : response.path("data");
        Map<String, Object> diagnostics = analyzeIndices(values, count, 0, true);
        boolean valid = Boolean.TRUE.equals(diagnostics.get("strictPermutation"));
        diagnostics.put("status", valid ? "ok" : "repaired");
        diagnostics.put("fallback", false);
        diagnostics.put("qualityEligible", valid);
        return diagnostics;
    }

    private static Map<String, Object> analyzeIndices(JsonNode values, int count, int offset, boolean cross) {
        List<Integer> rawOrder = new ArrayList<>();
        List<Integer> invalidIds = new ArrayList<>();
        List<Integer> duplicateIds = new ArrayList<>();
        List<Integer> missingIds = new ArrayList<>();
        Set<Integer> accepted = new LinkedHashSet<>();
        int nonIntegerCount = 0;
        int invalidScoreCount = 0;
        // Iterate the same shape as production; strictPermutation additionally requires an array
        // of actual integers rather than Jackson's permissive string/decimal coercion.
        if (values != null) for (JsonNode value : values) {
            JsonNode index = cross ? value.path("index") : value;
            int number = index.asInt(-1);
            if (index.isIntegralNumber() && index.canConvertToInt()) rawOrder.add(number);
            else nonIntegerCount++;
            if (number - offset < 0 || number - offset >= count) invalidIds.add(number);
            else if (!accepted.add(number)) duplicateIds.add(number);
            if (cross && (!value.path("relevance_score").isNumber()
                    || !Double.isFinite(value.path("relevance_score").asDouble()))) invalidScoreCount++;
        }
        for (int index = offset; index < count + offset; index++) {
            if (!accepted.contains(index)) missingIds.add(index);
        }
        boolean strict = values != null && values.isArray() && values.size() == count
                && invalidIds.isEmpty() && duplicateIds.isEmpty() && missingIds.isEmpty()
                && nonIntegerCount == 0 && invalidScoreCount == 0;
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("rawOrder", rawOrder);
        diagnostics.put("invalidIds", invalidIds);
        diagnostics.put("missingIds", missingIds);
        diagnostics.put("duplicateIds", duplicateIds);
        diagnostics.put("nonIntegerCount", nonIntegerCount);
        if (cross) diagnostics.put("invalidScoreCount", invalidScoreCount);
        diagnostics.put("acceptedCount", accepted.size());
        diagnostics.put("strictPermutation", strict);
        return diagnostics;
    }

    private static Map<String, Object> diagnostic(String status, boolean fallback, boolean eligible) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("status", status);
        diagnostics.put("fallback", fallback);
        diagnostics.put("qualityEligible", eligible);
        return diagnostics;
    }

    private static Map<String, Object> errorResult(String id, String arm, String status, Exception error) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("arm", arm);
        result.put("order", List.of());
        result.put("elapsedMs", 0);
        result.put("excerptsHash", null);
        Map<String, Object> diagnostics = diagnostic(status, false, false);
        diagnostics.put("errorType", error.getClass().getSimpleName());
        result.put("diagnostics", diagnostics);
        return result;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.path(field).isTextual()) throw new IllegalArgumentException();
        return node.path(field).asText();
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static void emit(Map<String, Object> result, String secret) throws Exception {
        JsonNode node = JSON.valueToTree(result);
        redact(node, secret);
        OUT.println(JSON.writeValueAsString(node));
    }

    private static void redact(JsonNode node, String secret) {
        if (secret == null || secret.isEmpty()) return;
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                if (entry.getValue().isTextual()) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) node).put(entry.getKey(),
                            entry.getValue().asText().replace(secret, "[redacted]"));
                } else redact(entry.getValue(), secret);
            });
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                if (node.path(i).isTextual()) {
                    ((com.fasterxml.jackson.databind.node.ArrayNode) node).set(i,
                            JSON.getNodeFactory().textNode(node.path(i).asText().replace(secret, "[redacted]")));
                } else redact(node.path(i), secret);
            }
        }
    }

    private record Config(String baseUrl, String apiKey, String model, String crossUrl) {
        static Config read(JsonNode node) {
            if (node == null || !node.path("snippet").isIntegralNumber()
                    || node.path("snippet").asInt() != SNIPPET) throw new IllegalArgumentException();
            String base = text(node, "baseUrl");
            String cross = text(node, "crossUrl");
            checkUrl(base);
            checkUrl(cross);
            String model = text(node, "model");
            if (model.isBlank()) throw new IllegalArgumentException();
            return new Config(base, text(node, "apiKey"), model, cross);
        }

        private static void checkUrl(String value) {
            URI uri = URI.create(value);
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null) throw new IllegalArgumentException();
        }
    }

    private static final class FixedSettings extends SettingsService {
        private final Config config;

        FixedSettings(Config config) {
            super(null, null);
            this.config = config;
        }

        @Override public String effective(String key) {
            if (Reranker.KEY_SNIPPET.equals(key)) return Integer.toString(SNIPPET);
            if (CrossEncoderReranker.KEY_URL.equals(key)) return config.crossUrl();
            // No live settings, environment or mapper reads are permitted in this probe.
            return null;
        }
    }

    private static final class FixedRouting extends ModelRouting {
        private final ModelTarget target;

        FixedRouting(SettingsService settings, Config config) {
            super(settings, null);
            target = new ModelTarget("ab-fixed", "A/B fixed target", config.baseUrl(), config.apiKey(),
                    config.model(), false);
        }

        @Override public ModelTarget forTask(String task) {
            if (!TASK_RERANK.equals(task)) throw new IllegalArgumentException();
            return target;
        }
    }

    private static final class RecordingMapper extends ObjectMapper {
        private String arm = "";
        private JsonNode crossResponse;

        @Override public JsonNode readTree(String content) throws JsonProcessingException {
            JsonNode parsed = super.readTree(content);
            if ("cross".equals(arm)) crossResponse = parsed;
            return parsed;
        }
    }

    private static final class RecordingClient extends DeepSeekClient {
        private int count;
        private Map<String, Object> last;

        RecordingClient(SettingsService settings, ModelRouting routing) {
            super(new AiProperties(), settings, JSON, null, routing);
        }

        void reset(int count) {
            this.count = count;
            last = diagnostic("not_called", false, false);
        }

        Map<String, Object> diagnostics() {
            return last;
        }

        @Override public JsonNode chat(List<?> messages, List<?> tools,
                                      String baseUrl, String apiKey, String model,
                                      int maxTokens, double temperature, String thinking,
                                      String reasoningEffort, Duration timeout) throws Exception {
            ChatResult reply;
            try {
                reply = super.chatFull(messages, tools, baseUrl, apiKey, model, maxTokens, temperature,
                        thinking, reasoningEffort, timeout);
            } catch (Exception error) {
                last = diagnostic("request_error", true, false);
                last.put("errorType", error.getClass().getSimpleName());
                // Replace the provider's exception text before the production rerankBatch sees it.
                throw new IllegalStateException("probe_request_failed");
            }
            String content = reply.message().path("content").asText("");
            if (content.isBlank()) last = diagnostic("empty_content", true, false);
            else {
                try {
                    JsonNode values = JSON.readTree(stripFence(content)).path("order");
                    last = analyzeIndices(values, count, 1, false);
                    boolean valid = Boolean.TRUE.equals(last.get("strictPermutation"));
                    boolean fallback = ((Integer) last.get("acceptedCount")) == 0;
                    last.put("status", fallback ? "silent_fallback" : valid ? "ok" : "repaired");
                    last.put("fallback", fallback);
                    last.put("qualityEligible", valid && !reply.truncated());
                } catch (Exception error) {
                    last = diagnostic("parse_error", true, false);
                    last.put("errorType", error.getClass().getSimpleName());
                }
            }
            last.put("finishReason", Set.of("stop", "length", "tool_calls", "content_filter")
                    .contains(reply.finishReason()) ? reply.finishReason() : "other");
            last.put("truncated", reply.truncated());
            last.put("promptTokens", reply.promptTokens());
            last.put("completionTokens", reply.completionTokens());
            last.put("reasoningTokens", reply.reasoningTokens());
            last.put("requestTimeoutMs", timeout.toMillis());
            last.put("maxTokens", maxTokens);
            return reply.message();
        }

        private static String stripFence(String source) {
            // Match the production parser exactly without replacing its eventual parsing result.
            String value = source.trim();
            if (value.startsWith("```")) {
                int newline = value.indexOf('\n');
                if (newline > 0) value = value.substring(newline + 1);
                int end = value.lastIndexOf("```");
                if (end >= 0) value = value.substring(0, end);
            }
            return value.trim();
        }
    }
}
