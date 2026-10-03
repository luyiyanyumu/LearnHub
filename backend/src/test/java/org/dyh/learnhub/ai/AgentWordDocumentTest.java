package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.dyh.learnhub.entity.FileInfo;
import org.dyh.learnhub.service.FileStorageService;
import org.dyh.learnhub.service.WordDocService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「智能体生成 Word 并给下载链接」这条链路的契约测试。
 *
 * <p>钉住四件事：① 产物是**真 docx**（能被 POI 读回、内容不丢）；
 * ② 返回值里有**可用的下载链接**（`/api/files/{id}/download`）—— 用户点得开才算交付完成；
 * ③ 空正文要**明确报错**而不是生成一份空文档；④ 生成物进资料库时带上说明（可被检索）。
 *
 * <p>用 Mockito 而不是起 Spring 上下文：本仓库的现有测试都是纯单测（没有 {@code @SpringBootTest} 先例），
 * 而这条链路真正要验的是"生成与交付的约定"，不依赖数据库。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentWordDocumentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private FileStorageService fileStorageService;

    @InjectMocks
    private AgentService agentService;

    /**
     * 显式装配测试真正需要的那几个依赖。
     *
     * <p>{@code @InjectMocks} 只注入 {@code @Mock} 字段，`wordDocService`（真实实现）与
     * `objectMapper` 会留成 null —— 所以这里手写进去。用真实 {@link WordDocService} 而不是 mock：
     * 这条链路要验的正是"生成的确实是能读回的 docx"。
     */
    @BeforeEach
    void wireDependencies() throws Exception {
        set(agentService, "wordDocService", new WordDocService());
        set(agentService, "objectMapper", MAPPER);
    }

    private static void set(Object target, String field, Object value) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private JsonNode args(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    @DisplayName("生成 Word：产物是真 docx、返回可下载链接、说明进检索")
    void generatesDocxAndReturnsDownloadLink() throws Exception {
        FileInfo stored = new FileInfo();
        stored.setId(42L);
        stored.setOriginName("Java 并发笔记.docx");
        stored.setExt("docx");
        stored.setSize(4096L);
        stored.setTextStatus("ok");
        when(fileStorageService.uploadBytes(anyString(), any(byte[].class), any())).thenReturn(stored);

        List<String> events = new ArrayList<>();
        String out = agentService.createWordDocument(args("""
                {"title":"Java 并发笔记",
                 "markdown":"# Java 并发笔记\\n\\n这是**重点**。\\n\\n## 线程池\\n\\n- 核心参数\\n- 拒绝策略",
                 "summary":"并发：线程池参数与拒绝策略"}
                """), events);

        JsonNode result = MAPPER.readTree(out);
        assertTrue(result.path("ok").asBoolean(), "应返回 ok=true，实际：" + out);
        assertEquals(42L, result.path("file_id").asLong());
        assertEquals("/api/files/42/download", result.path("download_url").asText(),
                "下载链接不对，用户点不开就等于没交付");
        assertEquals("[Java 并发笔记.docx](/api/files/42/download)", result.path("markdown_link").asText(),
                "markdown_link 是模型要照抄的那一行，必须现成可用");
        assertTrue(result.path("hint").asText().contains("最后一行"),
                "返回值要明确要求模型把链接放最后一行");

        // ① 上传的确实是 docx，且内容完整
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(fileStorageService).uploadBytes(eq("Java 并发笔记.docx"), bytes.capture(), any());
        byte[] uploaded = bytes.getValue();
        assertTrue(uploaded.length > 0, "上传内容为空");
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(uploaded));
             XWPFWordExtractor ex = new XWPFWordExtractor(doc)) {
            String text = ex.getText();
            assertTrue(text.contains("Java 并发笔记"), "标题丢了：" + text);
            assertTrue(text.contains("线程池"), "小节丢了：" + text);
            assertTrue(text.contains("拒绝策略"), "列表内容丢了：" + text);
            assertFalse(text.contains("**"), "行内标记没被解析：" + text);
        }

        // ④ 生成物带说明（进检索），并且有事件提示
        verify(fileStorageService).updateSummary(eq(42L), eq("并发：线程池参数与拒绝策略"));
        assertEquals(1, events.size(), "应有一条「已生成」事件");
        assertTrue(events.get(0).contains("Java 并发笔记.docx"), "事件里应带文件名：" + events.get(0));
    }

    @Test
    @DisplayName("没给 summary 时用兜底说明，仍然入库可检索")
    void fallsBackToDefaultSummary() throws Exception {
        FileInfo stored = new FileInfo();
        stored.setId(7L);
        stored.setOriginName("周报.docx");
        stored.setSize(100L);
        when(fileStorageService.uploadBytes(anyString(), any(byte[].class), any())).thenReturn(stored);

        String out = agentService.createWordDocument(args("""
                {"title":"周报","markdown":"# 周报\\n\\n本周做了三件事。"}
                """), new ArrayList<>());

        assertTrue(MAPPER.readTree(out).path("ok").asBoolean());
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(fileStorageService).updateSummary(eq(7L), summary.capture());
        assertTrue(summary.getValue().contains("周报"), "兜底说明里应含标题：" + summary.getValue());
    }

    @Test
    @DisplayName("正文为空：明确报错，不生成空文档、不入库")
    void rejectsEmptyMarkdown() throws Exception {
        String out = agentService.createWordDocument(args("""
                {"title":"空文档","markdown":"   "}
                """), new ArrayList<>());

        JsonNode result = MAPPER.readTree(out);
        assertFalse(result.path("ok").asBoolean(), "空正文应报错");
        assertTrue(result.path("error").asText().contains("markdown"), "错误信息要指出缺哪个字段：" + out);
        verify(fileStorageService, never()).uploadBytes(anyString(), any(byte[].class), any());
    }

    @Test
    @DisplayName("落盘失败：如实返回错误，不谎报成功")
    void reportsStorageFailure() throws Exception {
        when(fileStorageService.uploadBytes(anyString(), any(byte[].class), any()))
                .thenThrow(new IllegalStateException("磁盘写满"));
        when(fileStorageService.updateSummary(anyLong(), anyString())).thenReturn(null);

        String out = agentService.createWordDocument(args("""
                {"title":"x","markdown":"# x\\n正文"}
                """), new ArrayList<>());

        JsonNode result = MAPPER.readTree(out);
        assertFalse(result.path("ok").asBoolean(), "失败时必须 ok=false");
        assertNotNull(result.path("error").asText());
        assertTrue(result.path("error").asText().contains("磁盘写满"),
                "要把真实原因带回去（模型据此才能给用户可行动的答复）：" + out);
    }

    @Test
    @DisplayName("工具已注册到智能体的工具清单里（否则模型永远不会调用它）")
    void toolIsRegistered() throws Exception {
        java.lang.reflect.Method m = AgentService.class.getDeclaredMethod("toolDefinitions");
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Object> defs = (List<Object>) m.invoke(agentService);
        String all = MAPPER.writeValueAsString(defs);
        assertTrue(all.contains("create_word_document"), "工具清单里没有 create_word_document");
        assertTrue(all.contains("markdown_link") || all.contains("下载链接"),
                "工具描述里没提下载链接，模型不会知道要交付链接");
    }

    @Test
    @DisplayName("生成的是 .docx 文件（扩展名决定后端用 POI 抽正文）")
    void fileNameIsDocx() throws Exception {
        assertEquals("标题.docx", new WordDocService().render("标题", "正文").fileName());
        FileInfo stored = new FileInfo();
        stored.setId(1L);
        stored.setOriginName("标题.docx");
        stored.setSize(1L);
        when(fileStorageService.uploadBytes(anyString(), any(byte[].class), any())).thenReturn(stored);
        agentService.createWordDocument(args("""
                {"title":"标题","markdown":"正文"}
                """), new ArrayList<>());
        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(fileStorageService).uploadBytes(name.capture(), any(byte[].class), any());
        assertTrue(name.getValue().endsWith(".docx"), "文件名应以 .docx 结尾：" + name.getValue());
    }
}
