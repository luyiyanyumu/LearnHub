package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.dyh.learnhub.entity.ModelProfile;
import org.dyh.learnhub.mapper.ModelProfileMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「按地址 + 密钥自动获取模型」。
 * 纯函数部分直接测；联网部分起一个本机假服务，覆盖成功、补 /v1、401、密钥回退四条路径。
 */
class ModelDiscoveryServiceTest {

    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------------ 纯函数

    @Test
    @DisplayName("基址清洗：误贴的完整接口地址与结尾斜杠都剥掉")
    void cleanBase() {
        assertEquals("https://api.openai.com/v1", ModelDiscoveryService.cleanBase("https://api.openai.com/v1/chat/completions"));
        assertEquals("http://x:1234/v1", ModelDiscoveryService.cleanBase("http://x:1234/v1/models/"));
        assertEquals("https://api.deepseek.com", ModelDiscoveryService.cleanBase(" https://api.deepseek.com/// "));
        assertEquals("http://h:8000/v1", ModelDiscoveryService.cleanBase("http://h:8000/v1/embeddings"));
    }

    @Test
    @DisplayName("候选顺序：原样永远第一个；缺 /v1 时补；回环地址再换 host.docker.internal")
    void candidates() {
        assertEquals(List.of("https://api.deepseek.com", "https://api.deepseek.com/v1"),
                ModelDiscoveryService.candidates("https://api.deepseek.com"));
        assertEquals(List.of("http://127.0.0.1:8080/v1", "http://host.docker.internal:8080/v1"),
                ModelDiscoveryService.candidates("http://127.0.0.1:8080/v1"));
        assertEquals(List.of("http://localhost:11434", "http://localhost:11434/v1",
                        "http://host.docker.internal:11434", "http://host.docker.internal:11434/v1"),
                ModelDiscoveryService.candidates("http://localhost:11434"));
    }

    @Test
    @DisplayName("回环替换只动主机名，端口与路径保留；非回环返回 null")
    void swapLoopback() {
        assertEquals("http://host.docker.internal:8080/v1", ModelDiscoveryService.swapLoopback("http://127.0.0.1:8080/v1"));
        assertEquals("http://host.docker.internal:1234/v1", ModelDiscoveryService.swapLoopback("http://localhost:1234/v1"));
        assertNull(ModelDiscoveryService.swapLoopback("https://api.deepseek.com"));
        assertNull(ModelDiscoveryService.swapLoopback("http://host.docker.internal:11434/v1"));
    }

    @Test
    @DisplayName("解析：OpenAI / Ollama 原生 / 裸数组三种形状；去重；嵌入模型排在后面并标记")
    void parse() {
        String openai = "{\"object\":\"list\",\"data\":[{\"id\":\"qwen3:8b\",\"owned_by\":\"library\"},"
                + "{\"id\":\"bge-m3:latest\"},{\"id\":\"aura-7b:latest\"},{\"id\":\"qwen3:8b\"}]}";
        List<Map<String, Object>> m = ModelDiscoveryService.parseModels(json, openai);
        assertEquals(List.of("aura-7b:latest", "qwen3:8b", "bge-m3:latest"), m.stream().map(x -> x.get("id")).toList());
        assertEquals(true, m.get(2).get("embedding"));
        assertEquals("embedding", m.get(2).get("purpose"));
        assertEquals("chat", m.get(1).get("purpose"));
        assertEquals("library", m.get(1).get("ownedBy"));

        String ollama = "{\"models\":[{\"name\":\"qwen3:8b\"},{\"name\":\"nomic-embed-text\"}]}";
        assertEquals(List.of("qwen3:8b", "nomic-embed-text"),
                ModelDiscoveryService.parseModels(json, ollama).stream().map(x -> x.get("id")).toList());

        assertEquals(1, ModelDiscoveryService.parseModels(json, "[\"m1\"]").size());
        assertTrue(ModelDiscoveryService.parseModels(json, "not json").isEmpty());
        assertFalse(ModelDiscoveryService.isEmbedding("bge-reranker-v2-m3"));
    }

    // ------------------------------------------------------------------ 联网（本机假服务）

    private String startServer(String path, int status, String body, AtomicReference<String> authSeen) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            if (authSeen != null) {
                authSeen.set(ex.getRequestHeaders().getFirst("Authorization"));
            }
            boolean hit = ex.getRequestURI().getPath().equals(path);
            byte[] out = (hit ? body : "{\"error\":{\"message\":\"not found\"}}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(hit ? status : 404, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    @DisplayName("基址缺 /v1：/models 404 后自动试 /v1/models，并把修正后的地址回给界面")
    void autoAppendV1() throws Exception {
        String base = startServer("/v1/models", 200, "{\"data\":[{\"id\":\"m-a\"},{\"id\":\"m-b\"}]}", null);
        ModelDiscoveryService s = new ModelDiscoveryService(mock(ModelProfileMapper.class), json);

        Map<String, Object> r = s.discover(null, base, "sk-test");

        assertEquals(true, r.get("ok"));
        assertEquals(2, r.get("count"));
        assertEquals(base + "/v1", r.get("suggestedBaseUrl"));
    }

    @Test
    @DisplayName("401：明确报「密钥无效」，且不再换地址重试（换地址解决不了鉴权）")
    void unauthorized() throws Exception {
        String base = startServer("/v1/models", 401, "{\"error\":{\"message\":\"Invalid API key\"}}", null);
        ModelDiscoveryService s = new ModelDiscoveryService(mock(ModelProfileMapper.class), json);

        Map<String, Object> r = s.discover(null, base + "/v1", "dyh");

        assertEquals(false, r.get("ok"));
        assertTrue(String.valueOf(r.get("message")).contains("密钥无效"), String.valueOf(r.get("message")));
        assertTrue(String.valueOf(r.get("message")).contains("Invalid API key"));
    }

    @Test
    @DisplayName("编辑已有档案：密钥框留空/__KEEP__ 时用库里那把，且响应里不回显密钥")
    void fallsBackToStoredKey() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        String base = startServer("/v1/models", 200, "{\"data\":[{\"id\":\"m\"}]}", auth);
        ModelProfile p = new ModelProfile();
        p.setId("p1");
        p.setBaseUrl(base + "/v1");
        p.setApiKey("sk-stored-secret");
        ModelProfileMapper mapper = mock(ModelProfileMapper.class);
        when(mapper.selectById("p1")).thenReturn(p);
        ModelDiscoveryService s = new ModelDiscoveryService(mapper, json);

        Map<String, Object> r = s.discover("p1", "", "__KEEP__");

        assertEquals(true, r.get("ok"));
        assertEquals("Bearer sk-stored-secret", auth.get());
        assertFalse(r.toString().contains("sk-stored-secret"), "响应里不能出现明文密钥");
    }

    @Test
    @DisplayName("没填地址：直接提示，不发请求")
    void missingBase() {
        Map<String, Object> r = new ModelDiscoveryService(mock(ModelProfileMapper.class), json).discover(null, " ", null);
        assertEquals(false, r.get("ok"));
        assertEquals("请先填写 Base URL", r.get("message"));
    }

    @Test
    @DisplayName("探测报错拍平：null message 的异常不再只剩类名")
    void flattenCause() {
        Exception e = new RuntimeException(new java.net.ConnectException());
        String s = ModelProfileService.flatten(e);
        assertTrue(s.contains("ConnectException"), s);
    }
}
