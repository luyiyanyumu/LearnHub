package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.entity.ModelProfile;
import org.dyh.learnhub.mapper.ModelProfileMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 按「地址 + 密钥」自动获取可用模型列表（OpenAI 兼容的 {@code GET {baseUrl}/models}）。
 *
 * <h3>为什么要有它</h3>
 * 原来模型名全靠手敲：`qwen3.8-flash-next-coder-iq1_m` 这种名字敲错一个字符，
 * 要等到「测试」或真正调用时才报 404。让服务自己列出它有什么模型，用户只做选择。
 *
 * <h3>它顺手兜住的三类常见配置错误（都是实测踩过的）</h3>
 * <ol>
 *   <li><b>基址少了 /v1</b>：{@code /models} 返回 404 时自动再试 {@code /v1/models}，
 *       成功就把修正后的基址一并返回（{@code suggestedBaseUrl}），界面直接替用户改好。</li>
 *   <li><b>容器里写了 localhost</b>：后端跑在 Docker 里，{@code 127.0.0.1} 指的是容器自己。
 *       回环地址连不上时自动换成 {@code host.docker.internal} 再试一次（非容器环境下这个域名
 *       解析不了，只是多一次失败，无副作用）。</li>
 *   <li><b>把完整接口地址当基址填</b>：粘贴了 {@code .../v1/chat/completions} 或 {@code .../v1/models}
 *       时先剥掉尾巴。</li>
 * </ol>
 *
 * <h3>密钥约定（与 {@link ModelProfileService} 一致）</h3>
 * 界面编辑已有档案时拿不到明文密钥，只会回传空串或 {@code __KEEP__}；此时按 {@code profileId}
 * 取库里的那把。响应里**永远不回显密钥**。
 */
@Service
public class ModelDiscoveryService {

    /** 嵌入模型的名字特征：这些不能用来对话，界面上单独标出来，免得被选成对话模型 */
    private static final List<String> EMBEDDING_MARKERS =
            List.of("embed", "bge-", "bge:", "m3e", "gte-", "e5-", "nomic-embed");

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "0.0.0.0", "::1", "[::1]");

    private final ModelProfileMapper mapper;
    private final ObjectMapper json;
    private final HttpClient http;

    /**
     * 生产用构造器。
     *
     * <p>⚠️ 必须显式 {@code @Autowired}：下面还有一个给测试注入 HttpClient 的构造器 ——
     * 有**两个**构造器时 Spring 不再自动挑，会直接抛
     * "Failed to instantiate ...: No default constructor found"（启动即挂，实测踩过；
     * 单元测试手工 new 所以完全没暴露）。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ModelDiscoveryService(ModelProfileMapper mapper, ObjectMapper json) {
        this(mapper, json, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(6))
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    /** 测试用：可注入自定义 HttpClient（Spring 不用这个，见上面的 @Autowired 说明） */
    ModelDiscoveryService(ModelProfileMapper mapper, ObjectMapper json, HttpClient http) {
        this.mapper = mapper;
        this.json = json;
        this.http = http;
    }

    /**
     * @param profileId 编辑中的档案 id（可空）；密钥留空/{@code __KEEP__} 时用它取库里的密钥，地址留空时用它的地址
     * @param baseUrl   界面上当前填的地址（可能还没保存）
     * @param apiKey    界面上当前填的密钥（可空）
     * @return {@code ok, models[{id, ownedBy, embedding}], count, ms, baseUrl(实际成功的), suggestedBaseUrl?, message, hint?}
     */
    public Map<String, Object> discover(String profileId, String baseUrl, String apiKey) {
        ModelProfile stored = StringUtils.hasText(profileId) ? mapper.selectById(profileId) : null;
        String base = StringUtils.hasText(baseUrl) ? baseUrl : (stored == null ? null : stored.getBaseUrl());
        String key = resolveKey(apiKey, stored);

        Map<String, Object> o = new LinkedHashMap<>();
        if (!StringUtils.hasText(base)) {
            o.put("ok", false);
            o.put("message", "请先填写 Base URL");
            return o;
        }
        String cleaned = cleanBase(base);
        long t0 = System.currentTimeMillis();
        Attempt last = null;
        for (String candidate : candidates(cleaned)) {
            last = fetch(candidate, key);
            if (last.ok) {
                o.put("ok", true);
                o.put("models", last.models);
                o.put("count", last.models.size());
                o.put("baseUrl", candidate);
                if (!candidate.equals(base.trim())) {
                    o.put("suggestedBaseUrl", candidate);
                    o.put("hint", explainRewrite(cleaned, candidate));
                }
                o.put("message", last.models.isEmpty()
                        ? "连上了，但服务没有列出任何模型（部分服务不支持列模型，请手动填写模型名）"
                        : "获取到 " + last.models.size() + " 个模型");
                o.put("ms", System.currentTimeMillis() - t0);
                return o;
            }
            // 鉴权失败不是"地址不对"：换地址也没用，直接报
            if (last.status == 401 || last.status == 403) {
                break;
            }
        }
        o.put("ok", false);
        o.put("ms", System.currentTimeMillis() - t0);
        o.put("baseUrl", cleaned);
        o.put("message", last == null ? "未知错误" : last.message);
        if (last != null && last.hint != null) {
            o.put("hint", last.hint);
        }
        return o;
    }

    private static String resolveKey(String apiKey, ModelProfile stored) {
        String k = apiKey == null ? "" : apiKey.trim();
        if (k.isEmpty() || "__KEEP__".equals(k)) {
            return stored == null ? null : stored.getApiKey();
        }
        return k;
    }

    // ------------------------------------------------------------------ 地址处理（纯函数，可单测）

    /** 剥掉用户误贴的接口尾巴与结尾斜杠：.../v1/chat/completions、.../v1/models → .../v1 */
    static String cleanBase(String raw) {
        String s = raw == null ? "" : raw.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        for (String tail : List.of("/chat/completions", "/completions", "/models", "/embeddings")) {
            if (s.toLowerCase(Locale.ROOT).endsWith(tail)) {
                s = s.substring(0, s.length() - tail.length());
                break;
            }
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /**
     * 依次尝试的基址：原样 → 补 /v1 →（回环地址）换 host.docker.internal 再各试一遍。
     * 顺序很重要：用户填的原样永远第一个试，只有失败才"替他改"。
     */
    static List<String> candidates(String base) {
        Set<String> out = new LinkedHashSet<>();
        out.add(base);
        if (!base.toLowerCase(Locale.ROOT).endsWith("/v1")) {
            out.add(base + "/v1");
        }
        String swapped = swapLoopback(base);
        if (swapped != null) {
            out.add(swapped);
            if (!swapped.toLowerCase(Locale.ROOT).endsWith("/v1")) {
                out.add(swapped + "/v1");
            }
        }
        return new ArrayList<>(out);
    }

    /** 回环主机换成 host.docker.internal；不是回环地址返回 null */
    static String swapLoopback(String base) {
        try {
            URI u = URI.create(base);
            String host = u.getHost();
            if (host == null || !LOOPBACK_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
                return null;
            }
            return new URI(u.getScheme(), u.getUserInfo(), "host.docker.internal", u.getPort(),
                    u.getPath(), u.getQuery(), u.getFragment()).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String explainRewrite(String original, String used) {
        boolean hostChanged = used.contains("host.docker.internal") && !original.contains("host.docker.internal");
        boolean v1Added = used.endsWith("/v1") && !original.endsWith("/v1");
        if (hostChanged && v1Added) {
            return "已自动改成 " + used + "：后端跑在容器里，localhost 指的是容器自己；且该服务的接口在 /v1 下";
        }
        if (hostChanged) {
            return "已自动改成 " + used + "：后端跑在容器里，localhost / 127.0.0.1 指的是容器自己，要用 host.docker.internal 访问宿主机";
        }
        if (v1Added) {
            return "已自动补上 /v1：该服务的 OpenAI 兼容接口在 /v1 下";
        }
        return "已自动修正地址为 " + used;
    }

    // ------------------------------------------------------------------ 请求与解析

    private Attempt fetch(String base, String key) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/models"))
                    .timeout(Duration.ofSeconds(12))
                    .header("Accept", "application/json")
                    .GET();
            if (StringUtils.hasText(key)) {
                b.header("Authorization", "Bearer " + key.trim());
            }
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            int code = r.statusCode();
            if (code >= 200 && code < 300) {
                return Attempt.ok(parseModels(json, r.body()));
            }
            String upstream = upstreamMessage(json, r.body());
            if (code == 401 || code == 403) {
                return Attempt.fail(code, "密钥无效或无权限（HTTP " + code + "）" + suffix(upstream),
                        StringUtils.hasText(key)
                                ? "检查密钥是否复制完整、是否属于这个服务商；中转服务的密钥与官方不通用"
                                : "这个服务需要密钥，请填写 API Key");
            }
            if (code == 404) {
                return Attempt.fail(code, "接口不存在（HTTP 404）" + suffix(upstream),
                        "基址可能不对；OpenAI 兼容服务的基址一般以 /v1 结尾，火山方舟是 /api/v3");
            }
            return Attempt.fail(code, "服务返回 HTTP " + code + suffix(upstream), null);
        } catch (HttpConnectTimeoutException e) {
            return Attempt.fail(0, "连接超时：" + base, "确认地址与端口；内网/代理环境下检查网络是否通");
        } catch (HttpTimeoutException e) {
            return Attempt.fail(0, "请求超时：" + base, "服务响应太慢或卡住了");
        } catch (ConnectException e) {
            return Attempt.fail(0, "连不上：" + base,
                    "确认服务在运行、端口正确；后端在容器里时，本机服务要用 host.docker.internal 访问");
        } catch (IllegalArgumentException e) {
            return Attempt.fail(0, "地址格式不对：" + base, "形如 https://api.deepseek.com 或 http://host.docker.internal:11434/v1");
        } catch (java.io.IOException e) {
            String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return Attempt.fail(0, "网络错误：" + m, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Attempt.fail(0, "请求被中断", null);
        }
    }

    /**
     * 兼容三种返回形状：
     * OpenAI {@code {"data":[{"id":..}]}}、Ollama 原生 {@code {"models":[{"name":..}]}}、裸数组。
     */
    static List<Map<String, Object>> parseModels(ObjectMapper json, String body) {
        List<Map<String, Object>> out = new ArrayList<>();
        JsonNode root;
        try {
            root = json.readTree(body == null ? "" : body);
        } catch (Exception e) {
            return out;
        }
        if (root == null) {
            return out;
        }
        JsonNode arr = root.isArray() ? root
                : root.has("data") && root.get("data").isArray() ? root.get("data")
                : root.has("models") && root.get("models").isArray() ? root.get("models")
                : null;
        if (arr == null) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode n : arr) {
            String id = n.isTextual() ? n.asText()
                    : n.hasNonNull("id") ? n.get("id").asText()
                    : n.hasNonNull("name") ? n.get("name").asText()
                    : n.hasNonNull("model") ? n.get("model").asText() : null;
            if (!StringUtils.hasText(id) || !seen.add(id)) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            if (n.hasNonNull("owned_by")) {
                m.put("ownedBy", n.get("owned_by").asText());
            }
            m.put("embedding", isEmbedding(id));
            m.put("purpose", isEmbedding(id) ? ModelProfileService.PURPOSE_EMBEDDING : ModelProfileService.PURPOSE_CHAT);
            out.add(m);
        }
        // 对话模型在前、嵌入模型在后；各自按名字排
        out.sort(Comparator.<Map<String, Object>, Boolean>comparing(m -> (Boolean) m.get("embedding"))
                .thenComparing(m -> String.valueOf(m.get("id")).toLowerCase(Locale.ROOT)));
        return out;
    }

    static boolean isEmbedding(String id) {
        String s = id.toLowerCase(Locale.ROOT);
        if (s.contains("rerank")) return false;
        return EMBEDDING_MARKERS.stream().anyMatch(s::contains);
    }

    private static String upstreamMessage(ObjectMapper json, String body) {
        if (!StringUtils.hasText(body)) {
            return null;
        }
        try {
            JsonNode r = json.readTree(body);
            JsonNode err = r.get("error");
            if (err != null) {
                return err.isTextual() ? err.asText() : err.path("message").asText(null);
            }
            if (r.hasNonNull("message")) {
                return r.get("message").asText();
            }
        } catch (Exception ignored) {
            // 非 JSON：截一小段原文
        }
        return body.length() > 120 ? body.substring(0, 120) : body;
    }

    private static String suffix(String upstream) {
        return StringUtils.hasText(upstream) ? "：" + upstream : "";
    }

    private record Attempt(boolean ok, int status, List<Map<String, Object>> models, String message, String hint) {
        static Attempt ok(List<Map<String, Object>> models) {
            return new Attempt(true, 200, models, null, null);
        }

        static Attempt fail(int status, String message, String hint) {
            return new Attempt(false, status, List.of(), message, hint);
        }
    }
}
