package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.dyh.learnhub.controller.CliCommandController.Argument;
import org.dyh.learnhub.controller.CliCommandController.Body;
import org.dyh.learnhub.controller.CliCommandController.Command;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentAppCommandsTest {
    private static final Body OBJECT = new Body(true, Map.of("type", "object"));
    private static final Argument ID = new Argument("id", "path", true, "integer", null);
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;
    private ExecutorService executor;
    private AgentAppCommands gateway;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", this::respond);
        server.start();
        List<Command> commands = List.of(
                new Command("note.detail", "GET", "/api/notes/{id}", List.of(ID,
                        new Argument("kw", "query", false, "string", null)), null, "json"),
                new Command("note.archive", "GET", "/api/notes/archive", List.of(), null, "json"),
                new Command("wiki.topic", "GET", "/api/wiki/{key}",
                        List.of(new Argument("key", "path", true, "string", null)), null, "json"),
                new Command("wiki.topics", "GET", "/api/wiki/topics", List.of(), null, "json"),
                new Command("note.save", "POST", "/api/notes", List.of(), OBJECT, "json"),
                new Command("note.preview-edit", "POST", "/api/notes/{id}/edit/preview", List.of(ID), OBJECT, "json"),
                new Command("note.edit-content", "POST", "/api/notes/{id}/edit", List.of(ID), OBJECT, "json"),
                new Command("settings.update", "PUT", "/api/settings", List.of(), OBJECT, "json"),
                new Command("ai.chat", "POST", "/api/ai/chat", List.of(), OBJECT, "json"),
                new Command("ai.approve-action", "POST", "/api/ai/actions/{id}/approve", List.of(ID), null, "json"),
                new Command("ai.polish-stream", "POST", "/api/ai/polish-stream", List.of(), OBJECT, "sse"),
                new Command("file.download", "GET", "/api/files/{id}/download", List.of(ID), null, "binary"),
                new Command("file.upload", "POST", "/api/files/upload", List.of(
                        new Argument("file", "file", true, "string", null),
                        new Argument("folderId", "form", false, "integer", null)), null, "json"),
                new Command("model.discover", "POST", "/api/model/discover", List.of(), OBJECT, "json"),
                new Command("fixture.large", "GET", "/api/fixture/large", List.of(), null, "json"),
                new Command("fixture.redirect", "GET", "/api/fixture/redirect", List.of(), null, "json"),
                new Command("fixture.slow", "GET", "/api/fixture/slow", List.of(), null, "json")
        );
        gateway = new AgentAppCommands(json, () -> commands, () -> server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void resolvesTheCatalogWithSafePathsAndNoRedirectOrRecursiveActions() throws Exception {
        assertThat(gateway.list("note.", "", 2).path("truncated").asBoolean()).isTrue();
        assertThat(gateway.describe("ai.chat").path("available").asBoolean()).isFalse();
        assertThatThrownBy(() -> gateway.prepare("unknown", json.createObjectNode())).hasMessageContaining("未找到");
        assertThatThrownBy(() -> gateway.prepare("ai.approve-action", json.readTree("{\"path\":{\"id\":1}}")))
                .hasMessageContaining("审批由用户");
        assertThatThrownBy(() -> gateway.prepare("note.detail", json.readTree("{\"path\":{\"id\":\"archive\"}}")))
                .hasMessageContaining("类型不符");
        assertThatThrownBy(() -> gateway.prepare("wiki.topic", json.readTree("{\"path\":{\"key\":\"topics\"}}")))
                .hasMessageContaining("另一条");
        assertThatThrownBy(() -> gateway.prepare("wiki.topic", json.readTree("{\"path\":{\"key\":\"../notes\"}}")))
                .hasMessageContaining("有效片段");
        assertThatThrownBy(() -> gateway.prepare("note.detail", json.readTree("{\"path\":{\"id\":1},\"query\":{\"url\":\"x\"}}")))
                .hasMessageContaining("未声明");
        assertThat(requests).hasValue(0);
        JsonNode result = run("note.detail", "{\"path\":{\"id\":\"9007199254740993\"},\"query\":{\"kw\":[\"合成 &\",\"+?\"]}}");
        assertThat(result.at("/result/path").asText()).isEqualTo("/api/notes/9007199254740993");
        assertThat(result.at("/result/query").asText()).isEqualTo("kw=%E5%90%88%E6%88%90+%26&kw=%2B%3F");
        assertThat(result.path("result").has("apiKey")).isFalse();
        assertThat(run("fixture.redirect", "{}").path("code").asText()).isEqualTo("http_302");
        assertThat(requests).hasValue(2);
    }

    @Test
    void writesHaveAnExactFrozenRequestAndReviewableBoundedPreview() throws Exception {
        ObjectNode input = json.createObjectNode();
        input.putObject("body").put("title", "合成笔记").put("content", "合成".repeat(1_000));
        AgentAppCommands.Prepared prepared = gateway.prepare("note.save", input);
        assertThat(prepared.requiresApproval()).isTrue();
        assertThat(prepared.summary()).doesNotContain("合成笔记");
        assertThat(prepared.preview().at("/input/body/title").asText()).isEqualTo("合成笔记");
        assertThat(prepared.preview().path("truncated").asBoolean()).isTrue();
        ((ObjectNode) input.path("body")).put("title", "改动源对象");
        ((ObjectNode) prepared.input().path("body")).put("title", "改动返回副本");
        assertThat(requests).hasValue(0);
        assertThat(gateway.run(prepared, deadline()).at("/result/body/title").asText()).isEqualTo("合成笔记");

        assertThat(gateway.prepare("note.preview-edit", json.readTree("{\"path\":{\"id\":1},\"body\":{}}"))
                .requiresApproval()).isFalse();
        assertThat(gateway.prepare("note.edit-content", json.readTree("{\"path\":{\"id\":1},\"body\":{}}"))
                .requiresApproval()).isTrue();
        assertThat(gateway.prepare("settings.update", json.readTree("{\"body\":{\"temperature\":\"0.2\"}}"))
                .requiresApproval()).isTrue();
        assertThatThrownBy(() -> gateway.prepare("settings.update", json.readTree("{\"body\":{\"wikiApiKey\":\"synthetic-secret\"}}")))
                .hasMessageContaining("凭据").hasMessageNotContaining("synthetic-secret");
        assertThatThrownBy(() -> gateway.prepare("settings.update", json.readTree("{\"body\":{\"baseUrl\":\"https://u:p@fixture.invalid\"}}")))
                .hasMessageContaining("凭据");
        assertThatThrownBy(() -> gateway.prepare("model.discover", json.readTree("{\"body\":{\"baseUrl\":\"https://fixture.invalid\"}}")))
                .hasMessageContaining("已保存档案");
        assertThatThrownBy(() -> gateway.prepare("note.save", json.readTree("{\"body\":[1]}")))
                .hasMessageContaining("类型");
    }

    @Test
    void uploadContainsOnlyGeneratedBytesAndBinaryReadProducesDeferredLink() throws Exception {
        assertThatThrownBy(() -> gateway.prepare("file.upload", json.readTree("{\"files\":{\"file\":\"C:/synthetic.txt\"}}")))
                .hasMessageContaining("生成的文件");
        assertThatThrownBy(() -> gateway.prepare("file.upload", json.readTree("{\"files\":{\"file\":{\"name\":\"../synthetic.txt\",\"text\":\"合成\"}}}")))
                .hasMessageContaining("单个文件名");
        JsonNode request = json.readTree("{\"files\":{\"file\":{\"name\":\"synthetic.txt\",\"base64\":\"aGVsbG8=\"}},\"form\":{\"folderId\":5}}");
        var prepared = gateway.prepare("file.upload", request);
        assertThat(prepared.preview().at("/input/files/file/0/bytes").asInt()).isEqualTo(5);
        assertThat(prepared.preview().toString()).doesNotContain("aGVsbG8=");
        assertThat(gateway.run(prepared, deadline()).at("/result/body").asText())
                .contains("name=\"file\"; filename=\"synthetic.txt\"", "\r\nhello\r\n", "name=\"folderId\"");
        assertThat(run("file.download", "{\"path\":{\"id\":3}}").at("/result/href").asText()).isEqualTo("/api/files/3/download");
        assertThat(requests).hasValue(1);
    }

    @Test
    void boundsTheFullResponseAndReadsTerminalSseData() throws Exception {
        assertThat(run("fixture.large", "{}").path("code").asText()).isEqualTo("output_limit");
        long start = System.currentTimeMillis();
        assertThat(gateway.run(gateway.prepare("fixture.slow", json.createObjectNode()), start + 150)
                .path("code").asText()).isEqualTo("timeout");
        assertThat(System.currentTimeMillis() - start).isLessThan(2_000);
        assertThat(run("ai.polish-stream", "{\"body\":{}}").at("/result/content").asText()).isEqualTo("合成结果");
    }

    private JsonNode run(String id, String input) throws IOException {
        return gateway.run(gateway.prepare(id, json.readTree(input)), deadline());
    }

    private static long deadline() { return System.currentTimeMillis() + 5_000; }

    private void respond(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        try (exchange) {
            String path = exchange.getRequestURI().getRawPath();
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (path.equals("/api/fixture/redirect")) {
                exchange.getResponseHeaders().set("Location", "/api/notes/2");
                exchange.sendResponseHeaders(302, -1);
                return;
            }
            if (path.equals("/api/fixture/large")) {
                write(exchange, "application/json", "x".repeat(129 * 1024));
                return;
            }
            if (path.equals("/api/fixture/slow")) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                try { Thread.sleep(3_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return;
            }
            if (path.equals("/api/ai/polish-stream")) {
                write(exchange, "text/event-stream", "event: progress\r\ndata: 1\r\n\r\nevent: done\r\ndata: {\r\ndata: \"content\":\"合成结果\"}\r\n\r\n");
                return;
            }
            ObjectNode data = json.createObjectNode().put("path", path).put("query", exchange.getRequestURI().getRawQuery())
                    .put("apiKey", "synthetic-secret");
            if (body.length > 0) {
                if (exchange.getRequestHeaders().getFirst("Content-Type").equals("application/json")) data.set("body", json.readTree(body));
                else data.put("body", new String(body, StandardCharsets.UTF_8));
            }
            write(exchange, "application/json", json.createObjectNode().put("code", 200).put("msg", "ok").set("data", data).toString());
        }
    }

    private static void write(HttpExchange exchange, String media, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", media);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
