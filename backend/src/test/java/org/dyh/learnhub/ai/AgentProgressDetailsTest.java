package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentProgressDetailsTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test @SuppressWarnings("unchecked") void parametersAndUrlsAreSanitizedAndPreviousEventsAreDeeplyImmutable() throws Exception {
        List<Map<String, Object>> events = new ArrayList<>();
        var progress = new AgentChatProgress(new AgentService.ChatListener() {
            @Override public void onProgress(Map<String, Object> row) { events.add(row); }
        });
        String step = progress.tool("web_fetch", null, json.writeValueAsString(Map.of(
                "url", "https://synthetic-user:synthetic-password@example.com/guide?token=synthetic-url-secret&lang=zh",
                "options", Map.of("Authorization", "Bearer synthetic-secret", "api_key", "synthetic-api-secret"),
                "query", "合成关键词")));
        var first = events.getFirst();
        String running = json.writeValueAsString(first);
        assertFalse(running.contains("synthetic-password"));
        assertFalse(running.contains("synthetic-user"));
        assertFalse(running.contains("synthetic-url-secret"));
        assertFalse(running.contains("synthetic-api-secret"));
        assertFalse(running.contains("Bearer synthetic-secret"));
        assertTrue(running.contains("合成关键词"));
        List<Map<String, Object>> tools = (List<Map<String, Object>>) first.get("tools");
        Map<String, Object> arguments = (Map<String, Object>) tools.getFirst().get("arguments");
        assertThrows(UnsupportedOperationException.class, () -> arguments.put("query", "altered"));
        assertThrows(UnsupportedOperationException.class, () -> ((Map<String, Object>) arguments.get("options")).put("api_key", "altered"));

        progress.result(step, "web_fetch", json.valueToTree(Map.of("ok", false, "error", "合成站点连接超时")));
        progress.finish(step, "error", "本次调用未完成");
        assertFalse(first.containsKey("error"));
        assertEquals("running", first.get("status"));
        assertEquals("合成站点连接超时", progress.snapshot().getFirst().get("error"));
        assertEquals(progress.snapshot().getFirst(), events.getLast());
    }

    @Test @SuppressWarnings("unchecked") void recordsSearchLinksAndReadDocumentsFromTheActualStructuredResults() {
        var web = AgentProgressDetails.result("web_search", json.valueToTree(Map.of("ok", true,
                "sources", List.of(Map.of("url", "https://example.com/actual-result", "title", "合成站点", "snippet", "合成摘要"),
                        Map.of("url", "javascript:alert(1)", "title", "不安全链接")))));
        var sites = (List<Map<String, Object>>) web.get("websites");
        assertEquals(1, sites.size());
        assertEquals("https://example.com/actual-result", sites.getFirst().get("url"));
        assertEquals("合成站点", sites.getFirst().get("title"));

        var source = AgentProgressDetails.result("get_file", json.valueToTree(Map.of("ok", true,
                "file_id", 731, "name", "合成论文.pdf", "content", "实际读取的合成段落", "offset", 600)));
        var files = (List<Map<String, Object>>) source.get("sources");
        assertEquals(731L, files.getFirst().get("id"));
        assertEquals("file", files.getFirst().get("type"));
        assertEquals("合成论文.pdf", files.getFirst().get("title"));
        assertEquals(600L, files.getFirst().get("offset"));
        assertEquals("实际读取的合成段落", files.getFirst().get("snippet"));
    }

    @Test @SuppressWarnings("unchecked") void wikiNavigationRetainsSourceIdentifiersWithoutClaimingVerification() {
        var details = AgentProgressDetails.result("read_wiki", json.valueToTree(Map.of("ok", true,
                "pageId", 81, "pageTitle", "合成导览", "topicKey", "synthetic-topic",
                "sections", List.of(Map.of("sectionKey", "s1", "heading", "合成小节", "text", "合成生成内容",
                        "sourceRefs", List.of("note:751:0"), "factVerified", true)))));
        var source = ((List<Map<String, Object>>) details.get("sources")).getFirst();
        assertEquals(81L, source.get("id"));
        assertEquals("synthetic-topic", source.get("topicKey"));
        assertEquals("s1", source.get("sectionKey"));
        assertEquals(true, source.get("generatedGuide"));
        assertEquals(false, source.get("factVerified"));
        assertEquals(List.of("note:751:0"), source.get("sourceRefs"));
    }

    @Test @SuppressWarnings("unchecked") void historyUsesWhitelistBoundsAndPreservesRetrievalChannels() throws Exception {
        var entry = json.valueToTree(Map.of("rawReasoning", "hidden-synthetic-reasoning", "prompt", "hidden-synthetic-prompt",
                "tools", List.of(Map.of("name", "search_knowledge", "arguments", Map.of("query", "x".repeat(100_000)))),
                "sources", List.of(Map.of("type", "note", "id", 751, "title", "合成笔记", "snippet", "段落".repeat(3000),
                        "channels", List.of("keyword", "vector"), "graphRelations", List.of("合成关系"))),
                "result", "r".repeat(100_000), "error", "e".repeat(100_000)));
        var details = AgentProgressDetails.displayDetails(entry);
        assertFalse(details.containsKey("rawReasoning"));
        assertFalse(details.containsKey("prompt"));
        assertTrue(json.writeValueAsString(details).length() < 14_000);
        assertTrue(((String) details.get("result")).length() <= 1801);
        assertTrue(((String) details.get("error")).length() <= 1001);
        var source = ((List<Map<String, Object>>) details.get("sources")).getFirst();
        assertEquals(List.of("keyword", "vector"), source.get("channels"));
        assertEquals(List.of("合成关系"), source.get("graphRelations"));
    }
}
