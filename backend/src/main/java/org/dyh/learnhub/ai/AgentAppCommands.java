package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.dyh.learnhub.controller.CliCommandController;
import org.dyh.learnhub.controller.CliCommandController.Argument;
import org.dyh.learnhub.controller.CliCommandController.Command;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Service;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runs catalogued application commands through the same handlers used by the UI. */
@Service
public class AgentAppCommands {
    private static final int MAX_RESULT = 128 * 1024;
    private static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_INPUT = 6 * 1024 * 1024;
    private static final long MAX_CALL_MS = 330_000;
    private static final Pattern SLOT = Pattern.compile("\\{([A-Za-z_][\\w-]*)}");
    private static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE");
    private static final Map<String, String> SECTIONS = Map.of(
            "path", "path", "query", "query", "header", "header", "form", "form", "file", "files");
    private static final Set<String> READ_POSTS = Set.of("/api/notes/{id}/edit/preview", "/api/files/{id}/source-locations");
    private static final Set<String> SECRET_KEYS = Set.of("apikey", "password", "token", "accesstoken", "refreshtoken",
            "secret", "clientsecret", "secretkey", "privatekey", "authorization", "cookie", "credentials");
    private static final Set<String> FORBIDDEN_HEADERS = Set.of("host", "content-length", "content-type", "connection",
            "transfer-encoding", "expect", "proxy-authorization", "forwarded", "authorization", "cookie");

    private final ObjectMapper json;
    private final Supplier<List<Command>> catalog;
    private final IntSupplier port;
    private final HttpClient http = HttpClient.newBuilder().proxy(ProxySelector.of(null))
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(3)).build();

    @Autowired
    public AgentAppCommands(ObjectMapper json, CliCommandController catalog, ApplicationContext context) {
        this(json, () -> catalog.commands().getData().commands(), () -> {
            if (context instanceof WebServerApplicationContext web && web.getWebServer() != null) {
                return web.getWebServer().getPort();
            }
            return 0;
        });
    }

    AgentAppCommands(ObjectMapper json, Supplier<List<Command>> catalog, IntSupplier port) {
        this.json = json;
        this.catalog = catalog;
        this.port = port;
    }

    public JsonNode list(String prefix, String query, int limit) {
        String from = prefix == null ? "" : prefix.strip().toLowerCase(Locale.ROOT);
        String keyword = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        int cap = Math.max(1, Math.min(50, limit));
        ObjectNode out = json.createObjectNode().put("ok", true);
        ArrayNode items = out.putArray("commands");
        int matches = 0;
        for (Command command : catalog.get()) {
            if (!command.id().startsWith(from) || !(command.id() + " " + command.path() + " " + command.method())
                    .toLowerCase(Locale.ROOT).contains(keyword)) continue;
            if (++matches <= cap) items.add(descriptor(command, false));
        }
        return out.put("total", matches).put("truncated", matches > cap);
    }

    public JsonNode describe(String id) {
        return descriptor(find(id, catalog.get()), true).put("ok", true);
    }

    private ObjectNode descriptor(Command command, boolean detailed) {
        ObjectNode out = detailed ? json.valueToTree(command) : json.createObjectNode()
                .put("id", command.id()).put("method", command.method()).put("path", command.path())
                .put("response", command.response());
        String reason = unavailable(command);
        out.put("available", reason == null).put("requiresApproval", requiresApproval(command));
        if (reason != null) out.put("reason", reason);
        if (detailed && command.parameters().stream().anyMatch(arg -> arg.in().equals("file"))) {
            out.put("filesHint", "只接受生成的文件对象 {name,text} 或 {name,base64}，总量不超过 4 MiB。");
        }
        if (detailed && command.id().equals("model.discover")) out.put("bodyHint", "仅接受已保存档案的 profileId。");
        return out;
    }

    private static Command find(String id, List<Command> commands) {
        return commands.stream().filter(command -> command.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到该应用命令，请先查看命令目录。"));
    }

    private static String unavailable(Command command) {
        String path = command.path();
        String sample = SLOT.matcher(path).replaceAll("value");
        if (!METHODS.contains(command.method()) || !path.startsWith("/api/") || path.startsWith("/api/cli/")
                || !sample.matches("[A-Za-z0-9_./-]+") || sample.contains("//")
                || !URI.create(sample).normalize().getPath().equals(sample)) return "此路由不在应用命令执行范围内。";
        if (path.equals("/api/ai/chat") || path.equals("/api/ai/chat-stream")) return "内置智能体不能递归开启自己的对话。";
        if (path.startsWith("/api/ai/actions/") && !command.method().equals("GET")) return "审批由用户在应用内完成。";
        return null;
    }

    private static boolean requiresApproval(Command command) {
        return !Set.of("GET", "HEAD").contains(command.method())
                && !(command.method().equals("POST") && READ_POSTS.contains(command.path()));
    }

    public Prepared prepare(String id, JsonNode input) {
        List<Command> commands = catalog.get();
        Command command = find(id, commands);
        String reason = unavailable(command);
        if (reason != null) throw new IllegalArgumentException(reason);
        if (input == null || input.isNull() || input.isMissingNode()) input = json.createObjectNode();
        if (!input.isObject()) throw new IllegalArgumentException("命令 input 必须是 JSON 对象。");
        ObjectNode canonical = ((ObjectNode) input).deepCopy();
        validate(command, canonical);
        String path = fillPath(command.path(), canonical.path("path"));
        checkRoute(command, commands, path);
        String target = appendQuery(path, canonical.path("query"));
        Payload payload = payload(command, canonical);
        ObjectNode preview = json.createObjectNode().put("command", id).put("method", command.method())
                .put("route", command.path()).put("requestBytes", payload.bytes().length);
        ObjectNode reviewable = canonical.deepCopy();
        if (reviewable.has("files")) reviewable.set("files", reviewFiles(reviewable.get("files")));
        PreviewBudget budget = new PreviewBudget();
        preview.set("input", reviewValue(reviewable, budget, 0));
        preview.put("truncated", budget.truncated);
        String summary = (command.method().equals("DELETE") ? "删除 · " : "应用操作 · ") + id;
        String targetId = canonical.path("path").path("id").asText();
        if (targetId.matches("[0-9]{1,20}")) summary += " #" + targetId;
        return new Prepared(command, canonical, requiresApproval(command), summary, preview, target, payload);
    }

    private ObjectNode reviewFiles(JsonNode files) {
        ObjectNode review = json.createObjectNode();
        files.fields().forEachRemaining(entry -> {
            ArrayNode rows = review.putArray(entry.getKey());
            for (JsonNode item : values(entry.getValue(), false, entry.getKey())) {
                ObjectNode row = rows.addObject().put("name", item.path("name").asText())
                        .put("bytes", generatedFile(item).length);
                if (item.has("text")) row.set("text", item.get("text"));
            }
        });
        return review;
    }

    private JsonNode reviewValue(JsonNode value, PreviewBudget budget, int depth) {
        if (depth > 8 || --budget.nodes < 0) {
            budget.truncated = true;
            return json.getNodeFactory().textNode("…");
        }
        if (value.isObject()) {
            ObjectNode copy = json.createObjectNode();
            for (Iterator<Map.Entry<String, JsonNode>> it = value.fields(); it.hasNext();) {
                if (budget.nodes <= 0) { budget.truncated = true; break; }
                Map.Entry<String, JsonNode> entry = it.next();
                if (secretKey(entry.getKey())) copy.put(entry.getKey(), "[已隐藏]");
                else if (entry.getKey().length() <= 128) copy.set(entry.getKey(), reviewValue(entry.getValue(), budget, depth + 1));
                else { budget.truncated = true; budget.nodes--; }
            }
            return copy;
        }
        if (value.isArray()) {
            ArrayNode copy = json.createArrayNode();
            for (JsonNode item : value) {
                if (budget.nodes <= 0) { budget.truncated = true; break; }
                copy.add(reviewValue(item, budget, depth + 1));
            }
            return copy;
        }
        if (value.isTextual()) {
            String text = value.asText();
            int length = Math.min(text.length(), Math.min(1_200, budget.chars));
            budget.chars -= length;
            if (length < text.length()) {
                budget.truncated = true;
                return json.getNodeFactory().textNode(text.substring(0, length) + "…");
            }
        }
        return value;
    }

    private static void validate(Command command, ObjectNode input) {
        for (Iterator<String> it = input.fieldNames(); it.hasNext();) {
            String name = it.next();
            if (name.equals("body")) continue;
            if (!SECTIONS.containsValue(name) || !input.path(name).isObject()) {
                throw new IllegalArgumentException("input 只能使用 path、query、header、form、files 或 body。");
            }
            input.path(name).fieldNames().forEachRemaining(field -> {
                if (command.parameters().stream().noneMatch(arg -> name.equals(SECTIONS.get(arg.in())) && field.equals(arg.name()))) {
                    throw new IllegalArgumentException("input." + name + " 含有未声明的参数。");
                }
            });
        }
        for (Argument argument : command.parameters()) {
            String section = SECTIONS.get(argument.in());
            String label = section + "." + argument.name();
            JsonNode value = input.path(section).path(argument.name());
            if (value.isMissingNode()) {
                if (argument.required() || section.equals("path")) throw new IllegalArgumentException("缺少参数 " + label + "。");
                continue;
            }
            if (section.equals("header") && unsafeHeader(argument.name())) throw new IllegalArgumentException("不能设置此请求头。");
            if (section.equals("files")) continue;
            List<JsonNode> items = values(value, section.equals("path"), label);
            for (JsonNode item : items) scalar(item, argument.type(), label);
        }
        JsonNode body = input.path("body");
        if (command.body() == null && !body.isMissingNode()) throw new IllegalArgumentException("此命令不接收 JSON 正文。");
        if (command.body() != null && command.body().required() && (body.isMissingNode() || body.isNull())) {
            throw new IllegalArgumentException("缺少参数 body。");
        }
        if (command.body() != null && !body.isMissingNode() && !body.isNull()) {
            String kind = String.valueOf(command.body().schema().get("type"));
            boolean valid = switch (kind) {
                case "object" -> body.isObject();
                case "array" -> body.isArray();
                case "string" -> body.isTextual();
                case "integer" -> body.isIntegralNumber();
                case "number" -> body.isNumber();
                case "boolean" -> body.isBoolean();
                default -> true;
            };
            if (!valid) throw new IllegalArgumentException("body 类型与命令定义不符。");
        }
        if (containsSecret(input)) throw new IllegalArgumentException("请在应用设置中填写或清除凭据；智能体命令参数不接受凭据字段。");
        if (command.id().equals("model.discover") && (!body.isObject() || body.size() != 1
                || !body.path("profileId").isTextual() || body.path("profileId").asText().isBlank())) {
            throw new IllegalArgumentException("模型发现仅接受已保存档案的 profileId。");
        }
        if (command.id().equals("ai.test") && !body.path("baseUrl").asText().isBlank()) {
            throw new IllegalArgumentException("连接自检使用应用已配置的服务地址。");
        }
    }

    private static boolean secretKey(String name) {
        String key = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return SECRET_KEYS.contains(key) || key.endsWith("apikey") || key.endsWith("password") || key.endsWith("secretkey");
    }

    private static boolean containsUrlCredential(JsonNode value) {
        if (!value.isTextual() || !value.asText().matches("(?is)^https?://.*")) return false;
        try {
            URI url = URI.create(value.asText());
            if (url.getRawUserInfo() != null) return true;
            if (url.getQuery() == null) return false;
            for (String pair : url.getQuery().split("&")) if (secretKey(pair.split("=", 2)[0])) return true;
        } catch (IllegalArgumentException invalid) { return true; }
        return false;
    }

    private static boolean containsSecret(JsonNode node) {
        if (node.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext();) {
                Map.Entry<String, JsonNode> entry = it.next();
                if (secretKey(entry.getKey()) || (entry.getKey().toLowerCase(Locale.ROOT).endsWith("url")
                        && containsUrlCredential(entry.getValue())) || containsSecret(entry.getValue())) return true;
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) if (containsSecret(item)) return true;
        }
        return false;
    }

    private static boolean unsafeHeader(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return FORBIDDEN_HEADERS.contains(lower) || lower.startsWith("x-forwarded-") || secretKey(name);
    }

    private static List<JsonNode> values(JsonNode value, boolean single, String label) {
        if (!value.isArray()) return List.of(value);
        if (single || value.isEmpty()) throw new IllegalArgumentException(label + " 的值数量无效。");
        List<JsonNode> values = new ArrayList<>(value.size());
        value.forEach(values::add);
        return values;
    }

    private static String scalar(JsonNode value, String type, String label) {
        if (!(value.isTextual() || value.isNumber() || value.isBoolean())) {
            throw new IllegalArgumentException(label + " 必须是文本、数字或布尔值。");
        }
        String raw = value.asText();
        boolean valid = switch (type == null ? "" : type) {
            case "integer" -> raw.matches("[+-]?\\d+");
            case "number" -> raw.matches("[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?");
            case "boolean" -> raw.matches("(?i:true|false|on|off|yes|no|1|0)");
            default -> true;
        };
        if (!valid) throw new IllegalArgumentException(label + " 的参数类型不符。");
        return raw;
    }

    private static String fillPath(String path, JsonNode arguments) {
        Matcher matcher = SLOT.matcher(path);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String raw = scalar(arguments.path(matcher.group(1)), null, "path." + matcher.group(1));
            if (raw.isEmpty() || raw.equals(".") || raw.equals("..") || raw.matches("(?s).*[\\\\/\\p{Cntrl}].*")) {
                throw new IllegalArgumentException("路径参数必须是一个有效片段。");
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(encode(raw).replace("+", "%20")));
        }
        return matcher.appendTail(rendered).toString();
    }

    private static void checkRoute(Command chosen, List<Command> commands, String path) {
        PathPatternParser parser = PathPatternParser.defaultInstance;
        PathContainer actual = PathContainer.parsePath(path);
        PathPattern selected = parser.parse(chosen.path());
        if (!selected.matches(actual)) throw new IllegalArgumentException("解析后的路径不匹配命令。");
        for (Command other : commands) {
            if (other.id().equals(chosen.id()) || !other.method().equals(chosen.method())) continue;
            PathPattern candidate = parser.parse(other.path());
            if (candidate.matches(actual) && PathPattern.SPECIFICITY_COMPARATOR.compare(candidate, selected) <= 0) {
                throw new IllegalArgumentException("路径参数会匹配另一条应用命令。");
            }
        }
    }

    private static String appendQuery(String path, JsonNode query) {
        List<String> pairs = new ArrayList<>();
        query.fields().forEachRemaining(entry -> {
            for (JsonNode value : values(entry.getValue(), false, "query." + entry.getKey())) {
                pairs.add(encode(entry.getKey()) + "=" + encode(scalar(value, null, "query." + entry.getKey())));
            }
        });
        if (pairs.isEmpty()) return path;
        String result = path + "?" + String.join("&", pairs);
        if (result.length() > 8_192) throw new IllegalArgumentException("命令路径和查询参数过长。");
        return result;
    }

    private static String encode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8); }

    private Payload payload(Command command, ObjectNode input) {
        boolean multipart = command.parameters().stream().anyMatch(arg -> Set.of("file", "form").contains(arg.in()));
        if (!multipart) {
            if (!input.has("body")) return new Payload(new byte[0], null);
            byte[] bytes = input.get("body").toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_INPUT) throw new IllegalArgumentException("命令正文过大。");
            return new Payload(bytes, "application/json");
        }
        if (input.has("body")) throw new IllegalArgumentException("上传表单不能同时包含 JSON 正文。");
        String boundary = "learnhub-" + UUID.randomUUID();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Argument argument : command.parameters()) {
            if (!Set.of("file", "form").contains(argument.in())) continue;
            JsonNode value = input.path(SECTIONS.get(argument.in())).path(argument.name());
            if (value.isMissingNode()) continue;
            if (!argument.name().matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("上传参数定义无效。");
            for (JsonNode item : values(value, false, argument.name())) {
                write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + argument.name() + "\"");
                if (argument.in().equals("file")) {
                    String name = item.path("name").asText();
                    if (!item.isObject() || item.size() != 2 || !item.path("name").isTextual() || name.isBlank()
                            || name.length() > 180 || name.equals(".") || name.equals("..") || name.matches("(?s).*[\\\\/\"\\p{Cntrl}].*")) {
                        throw new IllegalArgumentException("上传只接受生成的文件对象和单个文件名。");
                    }
                    write(out, "; filename=\"" + name + "\"\r\nContent-Type: application/octet-stream\r\n\r\n");
                    byte[] content = generatedFile(item);
                    if (out.size() + content.length > MAX_FILE_BYTES) throw new IllegalArgumentException("生成的上传数据超过 4 MiB。");
                    out.writeBytes(content);
                } else {
                    write(out, "\r\n\r\n" + scalar(item, argument.type(), argument.name()));
                }
                write(out, "\r\n");
                if (out.size() > MAX_FILE_BYTES) throw new IllegalArgumentException("生成的上传数据超过 4 MiB。");
            }
        }
        write(out, "--" + boundary + "--\r\n");
        return new Payload(out.toByteArray(), "multipart/form-data; boundary=" + boundary);
    }

    private static byte[] generatedFile(JsonNode file) {
        if (file.path("text").isTextual() && file.path("text").asText().length() <= MAX_FILE_BYTES) {
            return file.path("text").asText().getBytes(StandardCharsets.UTF_8);
        }
        if (file.path("base64").isTextual() && file.path("base64").asText().length() <= MAX_INPUT) {
            try { return Base64.getDecoder().decode(file.path("base64").asText()); }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("生成文件的 base64 无效。"); }
        }
        throw new IllegalArgumentException("生成文件需包含文本或 base64，大小不能超过 4 MiB。");
    }

    private static void write(ByteArrayOutputStream out, String value) {
        out.writeBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    public JsonNode run(Prepared prepared, long deadline) {
        Command command = prepared.command();
        long remaining = Math.min(MAX_CALL_MS, deadline - System.currentTimeMillis());
        if (remaining <= 0) return failure(command, "deadline", "本轮时间预算不足，命令尚未执行。");
        if (command.response().equals("binary")) {
            ObjectNode link = json.createObjectNode().put("href", prepared.target)
                    .put("deferred", true).put("message", "下载地址已生成，尚未读取文件内容。");
            return success(command, link);
        }
        int actualPort = port.getAsInt();
        if (actualPort < 1 || actualPort > 65_535) return failure(command, "unavailable", "应用 HTTP 服务暂不可用。");
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + actualPort + prepared.target))
                    .timeout(Duration.ofMillis(remaining)).header("Accept", switch (command.response()) {
                        case "sse" -> "text/event-stream";
                        case "text" -> "text/*, application/json";
                        default -> "application/json";
                    });
            prepared.input.path("header").fields().forEachRemaining(entry -> {
                for (JsonNode value : values(entry.getValue(), false, entry.getKey())) builder.header(entry.getKey(), value.asText());
            });
            if (prepared.payload.contentType() != null) builder.header("Content-Type", prepared.payload.contentType());
            HttpRequest request = builder.method(command.method(), prepared.payload.contentType() == null
                    ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(prepared.payload.bytes())).build();
            var pending = http.sendAsync(request, info -> new LimitedBody());
            try {
                return response(command, pending.get(remaining, TimeUnit.MILLISECONDS));
            } catch (TimeoutException timeout) {
                pending.cancel(true);
                return failure(command, "timeout", "命令未在预算内返回，请查询应用状态后再决定是否重试。");
            } catch (InterruptedException interrupted) {
                pending.cancel(true);
                Thread.currentThread().interrupt();
                return failure(command, "interrupted", "等待命令结果已中断，请查询应用状态。");
            }
        } catch (ExecutionException error) {
            Throwable cause = error;
            while (cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof ResultLimit) return failure(command, "output_limit", "结果超过 128 KiB，请缩小查询范围，写入状态请在应用中核对。");
            if (cause instanceof java.net.http.HttpTimeoutException) return failure(command, "timeout", "命令等待超时，请查询应用状态。");
            return failure(command, "transport", "应用请求未得到完整结果，请查询应用状态。");
        } catch (IOException | IllegalArgumentException error) {
            return failure(command, "response", "应用命令的请求或响应格式无效。");
        }
    }

    private JsonNode response(Command command, HttpResponse<byte[]> response) throws IOException {
        int status = response.statusCode();
        if (status < 200 || status >= 300) return failure(command, "http_" + status, "应用请求失败（HTTP " + status + "）。");
        if (status == 204 || command.method().equals("HEAD")) return success(command, json.nullNode());
        String media = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        if (media.equals("application/json") || media.endsWith("+json")) {
            JsonNode value = json.readTree(response.body());
            if (value == null) return failure(command, "response", "应用没有返回有效的 JSON。");
            if (value.has("code") && value.path("code").isInt() && value.path("msg").isTextual()) {
                int code = value.path("code").intValue();
                if (code != 200) return failure(command, "app_" + code, "应用未接受该操作（业务码 " + code + "），请检查参数及当前状态。");
                value = value.has("data") ? value.get("data") : json.nullNode();
            }
            if (value.has("ok") && value.path("ok").isBoolean() && !value.path("ok").asBoolean()) {
                return failure(command, "app_rejected", "应用未完成该操作，请检查参数及当前状态。");
            }
            return success(command, redact(value));
        }
        if (command.response().equals("sse") && media.equals("text/event-stream")) return events(command, response.body());
        if (command.response().equals("text") && media.startsWith("text/") && !media.equals("text/html")) {
            return success(command, json.getNodeFactory().textNode(new String(response.body(), StandardCharsets.UTF_8)));
        }
        return failure(command, "response", "应用响应格式与命令定义不符。");
    }

    private JsonNode events(Command command, byte[] bytes) throws IOException {
        String event = "";
        List<String> data = new ArrayList<>();
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\\r\\n|\\n|\\r", -1);
        for (int i = 0; i < lines.length - 1; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                if (!data.isEmpty() && Set.of("result", "done").contains(event)) {
                    JsonNode value = json.readTree(String.join("\n", data));
                    if (value == null || value.isMissingNode()) return failure(command, "response", "应用事件流结果为空。");
                    return success(command, redact(value));
                }
                if (Set.of("error", "failed").contains(event)) return failure(command, "stream_failed", "应用事件流报告失败。");
                event = "";
                data.clear();
            } else if (line.startsWith("event:")) event = line.substring(6).stripLeading();
            else if (line.startsWith("data:")) data.add(line.substring(5).replaceFirst("^ ", ""));
        }
        return failure(command, "stream_incomplete", "应用事件流在返回最终结果前中断。");
    }

    private JsonNode redact(JsonNode value) {
        if (value.isObject()) {
            ObjectNode out = json.createObjectNode();
            value.fields().forEachRemaining(entry -> {
                if (!secretKey(entry.getKey())) out.set(entry.getKey(), redact(entry.getValue()));
            });
            return out;
        }
        if (value.isArray()) {
            ArrayNode out = json.createArrayNode();
            value.forEach(item -> out.add(redact(item)));
            return out;
        }
        return value;
    }

    private ObjectNode success(Command command, JsonNode value) {
        return json.createObjectNode().put("ok", true).put("command", command.id()).set("result", value);
    }

    private ObjectNode failure(Command command, String code, String reason) {
        return json.createObjectNode().put("ok", false).put("command", command.id()).put("code", code).put("error", reason);
    }

    private record Payload(byte[] bytes, String contentType) { }

    private static final class PreviewBudget {
        private int nodes = 80;
        private int chars = 5_000;
        private boolean truncated;
    }

    public static final class Prepared {
        private final Command command;
        private final ObjectNode input;
        private final boolean requiresApproval;
        private final String summary;
        private final ObjectNode preview;
        private final String target;
        private final Payload payload;

        private Prepared(Command command, ObjectNode input, boolean requiresApproval, String summary, ObjectNode preview,
                         String target, Payload payload) {
            this.command = command;
            this.input = input;
            this.requiresApproval = requiresApproval;
            this.summary = summary;
            this.preview = preview;
            this.target = target;
            this.payload = payload;
        }

        public Command command() { return command; }
        public JsonNode input() { return input.deepCopy(); }
        public boolean requiresApproval() { return requiresApproval; }
        public String summary() { return summary; }
        public JsonNode preview() { return preview.deepCopy(); }
    }

    private static final class ResultLimit extends IOException { }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long count;
        private boolean stopped;

        @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (stopped) return;
            count += buffers.stream().mapToLong(ByteBuffer::remaining).sum();
            if (count > MAX_RESULT) {
                stopped = true;
                subscription.cancel();
                delegate.onError(new ResultLimit());
            } else delegate.onNext(buffers);
        }
        @Override public void onError(Throwable error) { if (!stopped) delegate.onError(error); }
        @Override public void onComplete() { if (!stopped) delegate.onComplete(); }
    }
}
