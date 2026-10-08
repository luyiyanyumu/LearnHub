package org.dyh.learnhub.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.ai.DeepSeekClient;
import org.dyh.learnhub.ai.ModelRouting;
import org.dyh.learnhub.entity.WikiPage;
import org.dyh.learnhub.mapper.KbChunkMapper;
import org.dyh.learnhub.mapper.WikiPageMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EntityRegenerateTest {
    private final KbChunkMapper chunks = mock(KbChunkMapper.class);
    private final WikiPageMapper pages = mock(WikiPageMapper.class);
    private final DeepSeekClient client = mock(DeepSeekClient.class);
    private final ModelRouting routing = mock(ModelRouting.class);
    private final SkillService skills = mock(SkillService.class);
    private final WikiDependencyService dependencies = mock(WikiDependencyService.class);
    private final ObjectMapper json = new ObjectMapper();
    private EntityCompileService service;

    @BeforeEach
    void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "entity-regenerate-test"), WikiPage.class);
        service = new EntityCompileService(chunks, pages, client, routing, json, skills, dependencies);
        when(routing.targetIdOf(ModelRouting.TASK_ENTITY)).thenReturn("selected");
        when(routing.forProfile("selected")).thenReturn(target("selected", "writer-model"));
        when(chunks.allNotes()).thenReturn(List.of());
        when(chunks.allRefs()).thenReturn(List.of());
        when(chunks.allFiles()).thenReturn(List.of());
        when(pages.selectList(any())).thenReturn(List.of());
    }

    @AfterEach
    void cleanup() {
        service.shutdown();
    }

    @Test
    void onePageRetainsExactKeyAliasesAndCapturesLateCurrentOriginalChunks() throws Exception {
        WikiPage old = page("entity-legacy-key", "可恢复智能体", "<!-- entity-aliases: AgentRewind, 回退智能体 -->\n旧页正文");
        when(pages.selectOne(any())).thenReturn(old);
        when(pages.selectList(any())).thenReturn(List.of(page("entity-react", "ReAct", "已有页")));
        String full = paper();
        when(chunks.allFiles()).thenReturn(List.of(row(6, "AgentRewind.pdf", full)));
        when(chunks.allNotes()).thenReturn(List.of(row(99, "Spring Boot", "Spring Boot 使用自动配置。")));
        AtomicReference<String> sent = new AtomicReference<>();
        stubChat(answer -> {
            sent.set(userMessage(answer.getArgument(0)));
            return response(validWiki("资料#6"));
        });
        Map<String, Object> started = service.startRegenerate(old.getTopicKey(), null);
        Map<String, Object> done = awaitJob(started);
        assertEquals("done", done.get("status"));
        assertEquals(old.getTopicKey(), done.get("topicKey"));
        assertEquals("selected", done.get("targetId"));
        assertEquals("writer-model", done.get("model"));
        assertEquals(1, done.get("pages"));
        assertEquals("ok", done.get("quality"));
        assertTrue(String.valueOf(done.get("detail")).contains("原文片段"));
        assertTrue(sent.get().contains("Restore state s_k"));
        assertTrue(sent.get().contains("- ReAct"));
        assertFalse(sent.get().contains("Spring Boot"));
        ArgumentCaptor<WikiPage> generated = ArgumentCaptor.forClass(WikiPage.class);
        ArgumentCaptor<WikiDependencyService.Snapshot> snapshot = ArgumentCaptor.forClass(WikiDependencyService.Snapshot.class);
        verify(dependencies).saveGenerated(generated.capture(), snapshot.capture());
        assertEquals(old.getTopicKey(), generated.getValue().getTopicKey());
        assertEquals(old.getId(), generated.getValue().getId());
        assertTrue(generated.getValue().getContentMd().startsWith("<!-- entity-aliases: AgentRewind, 回退智能体 -->"));
        assertEquals(snapshot.getValue().sourceHash(), generated.getValue().getSourceHash());
        assertEquals(64, generated.getValue().getSourceHash().length());
        assertEquals(1, generated.getValue().getItemCount());
        assertEquals(full, snapshot.getValue().sources().getFirst().fullContent());
        assertEquals(WikiMaterialSampler.sha256(full), snapshot.getValue().sources().getFirst().fullContentHash());
        assertTrue(snapshot.getValue().sources().getFirst().chunks().stream().anyMatch(c -> c.text().contains("Restore state s_k")));
        assertTrue(snapshot.getValue().chunkCount() <= 6);
        verify(pages, never()).insert(any(WikiPage.class));
        verify(pages, never()).updateById(any(WikiPage.class));
        verify(dependencies, never()).captureSnapshot(anyCollection());
        verifyChatCount(1);
    }

    @Test
    void sourceChangesDuringModelCallDoNotReplaceGenerationBaseline() throws Exception {
        WikiPage old = page("entity-rewind", "AgentRewind", "旧页面");
        when(pages.selectOne(any())).thenReturn(old);
        String original = "## State rollback\nAgentRewind restores the original checkpoint and recorded context.";
        Map<String, Object> source = new HashMap<>(row(6, "AgentRewind.pdf", original));
        when(chunks.allFiles()).thenReturn(List.of(source));
        stubChat(answer -> {
            source.put("content", "AgentRewind newly changed source after model request.");
            return response(validWiki("资料#6"));
        });
        assertEquals("done", awaitJob(service.startRegenerate(old.getTopicKey(), "selected")).get("status"));
        ArgumentCaptor<WikiDependencyService.Snapshot> snapshot = ArgumentCaptor.forClass(WikiDependencyService.Snapshot.class);
        verify(dependencies).saveGenerated(any(WikiPage.class), snapshot.capture());
        assertEquals(original, snapshot.getValue().sources().getFirst().fullContent());
        assertEquals(WikiMaterialSampler.sha256(original), snapshot.getValue().sources().getFirst().fullContentHash());
        verify(chunks, times(1)).allFiles();
        verify(dependencies, never()).captureSnapshot(anyCollection());
    }

    @Test
    void noSubstantiveMatchingMaterialFailsAndKeepsOldPage() throws Exception {
        WikiPage old = page("entity-mqtt", "MQTT", "原有内容");
        when(pages.selectOne(any())).thenReturn(old);
        when(chunks.allFiles()).thenReturn(List.of(row(6, "MQTT.pdf", "MQTT\nAuthors: Example Author")));
        when(chunks.allNotes()).thenReturn(List.of(row(1, "Spring", "Spring Boot 自动配置。")));
        Map<String, Object> done = awaitJob(service.startRegenerate(old.getTopicKey(), null));
        assertEquals("failed", done.get("status"));
        assertTrue(String.valueOf(done.get("error")).contains("原页面已保留"));
        assertEquals("原有内容", old.getContentMd());
        assertEquals(0, done.get("pages"));
        verifyNoInteractions(client, dependencies);
        verify(pages, never()).updateById(any(WikiPage.class));
    }

    @Test
    void fakeAliasInCodeDoesNotExpandRegenerationScope() {
        WikiPage old = page("entity-unmatched", "不存在的概念", "## Example\n```html\n<!-- entity-aliases: MQTT -->\n```");
        when(pages.selectOne(any())).thenReturn(old);
        when(chunks.allNotes()).thenReturn(List.of(row(1, "协议笔记", "MQTT 使用发布订阅。")));
        assertEquals("failed", awaitJob(service.startRegenerate(old.getTopicKey(), null)).get("status"));
        verifyNoInteractions(client, dependencies);
    }

    @Test
    void qualityChecksOnlyActualSelectedSourcesEvenWhenOtherIdExistsInLibrary() throws Exception {
        WikiPage old = page("entity-rewind", "AgentRewind", "Old");
        when(pages.selectOne(any())).thenReturn(old);
        when(chunks.allFiles()).thenReturn(List.of(row(6, "AgentRewind", "AgentRewind saves a state checkpoint and recovers from it.")));
        when(chunks.allNotes()).thenReturn(List.of(row(99, "Java", "Java 使用虚拟机执行程序。")));
        stubChat(answer -> response(validWiki("笔记#99")));
        Map<String, Object> done = awaitJob(service.startRegenerate(old.getTopicKey(), null));
        assertEquals("done", done.get("status"));
        assertEquals("warn", done.get("quality"));
        ArgumentCaptor<WikiPage> generated = ArgumentCaptor.forClass(WikiPage.class);
        ArgumentCaptor<WikiDependencyService.Snapshot> snapshot = ArgumentCaptor.forClass(WikiDependencyService.Snapshot.class);
        verify(dependencies).saveGenerated(generated.capture(), snapshot.capture());
        assertFalse(generated.getValue().getContentMd().contains("[笔记#99]"));
        assertTrue(generated.getValue().getQualityNote().contains("不存在"));
        assertEquals(List.of("file:6"), snapshot.getValue().sourceRefs().stream().map(WikiDependencyService.SourceRef::key).toList());
    }

    @Test
    void singlePageRoutingFailureBecomesFinishedFailedJob() {
        WikiPage old = page("entity-rewind", "AgentRewind", "Old");
        when(pages.selectOne(any())).thenReturn(old);
        when(routing.forProfile("missing")).thenThrow(new IllegalArgumentException("档案已删除"));
        Map<String, Object> done = awaitJob(service.startRegenerate(old.getTopicKey(), "missing"));
        assertEquals("failed", done.get("status"));
        assertEquals("档案已删除", done.get("error"));
        verifyNoInteractions(chunks, client, dependencies);
    }

    @Test
    void batchRoutingFailureAlsoBecomesFailedJob() {
        when(routing.forProfile("missing")).thenThrow(new IllegalArgumentException("档案已删除"));
        Map<String, Object> done = awaitJob(service.start("missing"));
        assertEquals("failed", done.get("status"));
        assertEquals("entities", done.get("topicKey"));
        verifyNoInteractions(chunks, client, dependencies);
    }

    @Test
    void modelFailureDoesNotSaveOldPageOrNewDependencies() throws Exception {
        WikiPage old = page("entity-rewind", "AgentRewind", "Original");
        when(pages.selectOne(any())).thenReturn(old);
        when(chunks.allFiles()).thenReturn(List.of(row(6, "AgentRewind", "AgentRewind restores an execution checkpoint.")));
        stubChat(answer -> { throw new IllegalStateException("model unavailable"); });
        assertEquals("failed", awaitJob(service.startRegenerate(old.getTopicKey(), null)).get("status"));
        assertEquals("Original", old.getContentMd());
        verifyNoInteractions(dependencies);
    }

    @Test
    void deletingPageDuringGenerationDoesNotResurrectIt() throws Exception {
        WikiPage old = page("entity-rewind", "AgentRewind", "Original");
        when(pages.selectOne(any())).thenReturn(old, old, null);
        when(chunks.allFiles()).thenReturn(List.of(row(6, "AgentRewind", "AgentRewind restores an execution checkpoint.")));
        stubChat(answer -> response(validWiki("资料#6")));
        Map<String, Object> done = awaitJob(service.startRegenerate(old.getTopicKey(), null));
        assertEquals("failed", done.get("status"));
        assertTrue(String.valueOf(done.get("error")).contains("已删除"));
        verifyNoInteractions(dependencies);
    }

    @Test
    void batchExtractionAndWriterSampleFullOriginalWithBoundedPrompts() throws Exception {
        when(pages.selectOne(any())).thenReturn(null);
        String full = paper();
        when(chunks.allFiles()).thenReturn(List.of(row(6, "AgentRewind.pdf", full)));
        List<String> prompts = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        stubChat(answer -> {
            String prompt = userMessage(answer.getArgument(0));
            prompts.add(prompt);
            return response(calls.getAndIncrement() == 0
                    ? "{\"entities\":[{\"name\":\"AgentRewind\",\"kind\":\"concept\",\"brief\":\"generated brief\",\"aliases\":[]}]}"
                    : validWiki("资料#6"));
        });
        Map<String, Object> done = awaitJob(service.start(null));
        assertEquals("done", done.get("status"));
        assertEquals(1, done.get("pages"));
        assertEquals(2, prompts.size());
        assertTrue(prompts.stream().allMatch(p -> p.length() <= WikiMaterialSampler.MAX_MATERIAL_CHARS + 1000));
        assertTrue(prompts.get(0).contains("Restore state s_k"));
        assertTrue(prompts.get(1).contains("Restore state s_k"));
        assertFalse(prompts.get(1).contains("generated brief"));
        ArgumentCaptor<WikiDependencyService.Snapshot> snapshot = ArgumentCaptor.forClass(WikiDependencyService.Snapshot.class);
        verify(dependencies).saveGenerated(any(WikiPage.class), snapshot.capture());
        assertEquals(full, snapshot.getValue().sources().getFirst().fullContent());
        assertTrue(snapshot.getValue().chunkCount() <= 6);
    }

    @Test
    void synchronousImpactCannotOverwriteBackgroundWriterTarget() throws Exception {
        WikiPage old = page("entity-rewind", "AgentRewind", "Original");
        when(pages.selectOne(any())).thenReturn(old);
        when(chunks.allFiles()).thenReturn(List.of(row(6, "AgentRewind", "AgentRewind restores a checkpoint.")));
        when(routing.forProfile("impact")).thenReturn(target("impact", "impact-model"));
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        stubChat(answer -> {
            String model = answer.getArgument(4);
            if (model.equals("writer-model")) {
                writing.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test synchronization timeout");
                return response(validWiki("资料#6"));
            }
            assertEquals("impact-model", model);
            return response("{\"targets\":[]}");
        });
        Map<String, Object> started = service.startRegenerate(old.getTopicKey(), "selected");
        assertTrue(writing.await(5, TimeUnit.SECONDS));
        try {
            assertEquals(List.of(), service.impact("new text", 2, "impact").get("targets"));
        } finally {
            release.countDown();
        }
        assertEquals("done", awaitJob(started).get("status"));
        ArgumentCaptor<WikiPage> generated = ArgumentCaptor.forClass(WikiPage.class);
        verify(dependencies).saveGenerated(generated.capture(), any(WikiDependencyService.Snapshot.class));
        assertEquals("writer-model", generated.getValue().getModel());
        assertEquals("selected", generated.getValue().getTargetId());
    }

    @Test
    void rejectsMissingOrNonEntityPagesBeforeEnqueuing() {
        assertThrows(IllegalArgumentException.class, () -> service.startRegenerate("cat-1", null));
        assertThrows(IllegalArgumentException.class, () -> service.startRegenerate("entity-missing", null));
        WikiPage wrong = page("entity-wrong", "Wrong", "Body");
        wrong.setTopicType("category");
        when(pages.selectOne(any())).thenReturn(wrong);
        assertThrows(IllegalArgumentException.class, () -> service.startRegenerate("entity-wrong", null));
        verifyNoInteractions(routing, chunks, client, dependencies);
    }

    private void stubChat(org.mockito.stubbing.Answer<JsonNode> answer) throws Exception {
        when(client.chat(anyList(), isNull(), anyString(), nullable(String.class), anyString(), anyInt(), anyDouble(),
                nullable(String.class), nullable(String.class), any(Duration.class))).thenAnswer(answer);
    }

    private void verifyChatCount(int count) throws Exception {
        verify(client, times(count)).chat(anyList(), isNull(), anyString(), nullable(String.class), anyString(), anyInt(),
                anyDouble(), nullable(String.class), nullable(String.class), any(Duration.class));
    }

    private Map<String, Object> awaitJob(Map<String, Object> started) {
        String id = String.valueOf(started.get("jobId"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            Map<String, Object> view = service.view(id);
            if (!"running".equals(view.get("status"))) return view;
            try { Thread.sleep(10); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        }
        fail("后台任务没有结束：" + service.view(id));
        return Map.of();
    }

    private JsonNode response(String content) {
        return json.createObjectNode().put("content", content);
    }

    private static ModelRouting.ModelTarget target(String id, String model) {
        return new ModelRouting.ModelTarget(id, id, "http://local.invalid/v1", "", model, true);
    }

    private static WikiPage page(String key, String title, String content) {
        WikiPage page = new WikiPage();
        page.setId(42L);
        page.setTopicKey(key);
        page.setTopicType("entity");
        page.setTitle(title);
        page.setContentMd(content);
        return page;
    }

    private static Map<String, Object> row(long id, String title, String full) {
        return Map.of("id", id, "title", title, "content", full);
    }

    private static String validWiki(String source) {
        return "## 概览\nAgentRewind 从检查点恢复执行状态。[" + source + "]\n## 工作机制\n"
                + "它保存执行环境与上下文，在回退后继续执行，并保留恢复记忆。".repeat(15)
                + "[" + source + "]";
    }

    private static String paper() {
        StringBuilder full = new StringBuilder("# AgentRewind\nAuthors: Example Author\nEmail: author@example.org\n");
        for (int i = 0; i < 35; i++) full.append("## Background ").append(i).append('\n')
                .append("General learning systems perform iterative evaluation. ".repeat(17)).append('\n');
        full.append("## State rollback mechanism\nRestore state s_k and inject memory M into context c_k.");
        return full.toString();
    }

    private static String userMessage(List<?> messages) {
        return String.valueOf(((Map<?, ?>) messages.getLast()).get("content"));
    }
}
