package org.dyh.learnhub.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dyh.learnhub.ai.DeepSeekClient;
import org.dyh.learnhub.ai.ModelRouting;
import org.dyh.learnhub.entity.WikiPage;
import org.dyh.learnhub.mapper.KbChunkMapper;
import org.dyh.learnhub.mapper.WikiPageMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic source text and mocked model/mapper calls only. */
class EntityAutomaticCompileTest {
    private final KbChunkMapper sources = mock(KbChunkMapper.class);
    private final WikiPageMapper pages = mock(WikiPageMapper.class);
    private final DeepSeekClient client = mock(DeepSeekClient.class);
    private final ModelRouting routing = mock(ModelRouting.class);
    private final WikiDependencyService dependencies = mock(WikiDependencyService.class);
    private final ObjectMapper json = new ObjectMapper();
    private final WikiPage existingTool = page("entity-synthetic-git", "Git", "已有合成工具页");
    private final WikiPage existingCommand = page("entity-synthetic-git-commit", "git commit", "已有合成命令页，须保留");
    private EntityCompileService service;

    @BeforeEach void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "entity-automatic-test"), WikiPage.class);
        service = new EntityCompileService(sources, pages, client, routing, json, mock(SkillService.class), dependencies);
        when(routing.targetIdOf(ModelRouting.TASK_ENTITY)).thenReturn("synthetic-entity");
        when(routing.forProfile("synthetic-entity")).thenReturn(new ModelRouting.ModelTarget(
                "synthetic-entity", "synthetic", "https://model.example.invalid/v1", "", "synthetic-model", true));
        when(sources.allNotes()).thenReturn(List.of(Map.of("id", 91001L, "title", "Git 合成教材",
                "content", "Git 通过提交记录版本，git commit 将暂存内容记录为一个提交，用于审查和恢复。".repeat(12))));
        when(sources.allRefs()).thenReturn(List.of());
        when(sources.allFiles()).thenReturn(List.of());
        when(pages.selectList(any())).thenReturn(List.of(existingTool, existingCommand));
    }

    @AfterEach void shutdown() { service.shutdown(); }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-json", "{}", "{\"entities\":{}}",
            "{\"entities\":[]} {\"entities\":[]}", "{\"entities\":[]} trailing"})
    void automaticExtractionRejectsMalformedOrTrailingJsonWithoutChangingExistingPages(String body) throws Exception {
        respond(body, "stop");
        Map<String, Object> result = await(service.startAutomatically());
        assertEquals("failed", result.get("status"));
        assertEquals(true, result.get("automatic"));
        assertEquals(0, result.get("pages"));
        assertTrue(result.get("error").toString().contains("实体抽取失败"));
        verifyNoPageMutation();
        verifyNoInteractions(dependencies);
        verifyFullCall();
    }

    @Test void aLengthLimitedExtractionCannotTurnEvenValidJsonIntoASuccessfulAutomaticJob() throws Exception {
        respond("{\"entities\":[]}", "length");
        Map<String, Object> result = await(service.startAutomatically());
        assertEquals("failed", result.get("status"));
        assertEquals(0, result.get("pages"));
        verifyNoPageMutation();
        verifyNoInteractions(dependencies);
        verifyFullCall();
    }

    @Test void trustworthyEmptyAutomaticExtractionRetainsLegacySubcommandPagesAndListsThemInTheIndex() throws Exception {
        respond("{\"entities\":[]}", "stop");
        Map<String, Object> result = await(service.startAutomatically());
        assertEquals("done", result.get("status"));
        assertEquals(true, result.get("automatic"));
        verify(pages, never()).delete(any());
        verify(pages, never()).deleteById(any(java.io.Serializable.class));
        verify(pages, never()).updateById(any(WikiPage.class));
        ArgumentCaptor<WikiPage> written = ArgumentCaptor.forClass(WikiPage.class);
        verify(pages).insert(written.capture());
        assertEquals("index", written.getValue().getTopicKey());
        assertTrue(written.getValue().getContentMd().contains("git commit"));
        assertTrue(written.getValue().getContentMd().contains(existingCommand.getTopicKey()));
        assertEquals("已有合成命令页，须保留", existingCommand.getContentMd());
        verifyNoInteractions(dependencies);
        verifyFullCall();
    }

    @Test void automaticPageWritingUsesConfiguredBudgetAndKeepsMathWithoutMainModelThinking() throws Exception {
        when(routing.forProfile("synthetic-entity")).thenReturn(new ModelRouting.ModelTarget(
                "synthetic-entity", "synthetic", "https://model.example.invalid/v1", "", "synthetic-model", false));
        when(client.maxTokensOf("synthetic-entity")).thenReturn(6144);
        when(client.thinkingOf("synthetic-entity")).thenReturn("enabled");
        when(client.reasoningEffortOf("synthetic-entity")).thenReturn("high");
        String formula = "$$\np = \\frac{k}{n}\n$$";
        String markdown = "## 概览\nGit 通过提交记录版本。[笔记#91001]\n## 合成示例\n"
                + "该合成教材用版本记录练习审查、恢复和结果核对。".repeat(8)
                + "[笔记#91001]\n" + formula;
        when(client.chatFull(anyList(), isNull(), anyString(), nullable(String.class), anyString(),
                anyInt(), anyDouble(), nullable(String.class), nullable(String.class), any(Duration.class)))
                .thenReturn(new DeepSeekClient.ChatResult(json.createObjectNode().put("content",
                        "{\"entities\":[{\"name\":\"Git\",\"kind\":\"tool\",\"aliases\":[]}]}"), "stop", 100, 100, 0))
                .thenReturn(new DeepSeekClient.ChatResult(json.createObjectNode().put("content", markdown), "stop", 100, 200, 0));

        Map<String, Object> done = await(service.startAutomatically());

        assertEquals("done", done.get("status"));
        assertEquals(1, done.get("pages"));
        verify(client).chatFull(anyList(), isNull(), anyString(), nullable(String.class), anyString(),
                eq(3000), eq(0.2), eq("disabled"), isNull(), any(Duration.class));
        verify(client).chatFull(anyList(), isNull(), anyString(), nullable(String.class), anyString(),
                eq(6144), eq(0.3), eq("disabled"), isNull(), any(Duration.class));
        verify(client, never()).thinkingOf(anyString());
        verify(client, never()).reasoningEffortOf(anyString());
        verify(client, never()).chat(anyList(), isNull(), anyString(), nullable(String.class), anyString(),
                anyInt(), anyDouble(), nullable(String.class), nullable(String.class), any(Duration.class));
        ArgumentCaptor<WikiPage> written = ArgumentCaptor.forClass(WikiPage.class);
        verify(dependencies).saveGenerated(written.capture(), any(WikiDependencyService.Snapshot.class));
        assertEquals(EntityCompileService.entityKey("Git"), written.getValue().getTopicKey());
        assertTrue(written.getValue().getContentMd().contains(formula));
        verify(pages, never()).delete(any());
    }

    private void respond(String body, String finishReason) throws Exception {
        when(client.chatFull(anyList(), isNull(), anyString(), nullable(String.class), anyString(),
                anyInt(), anyDouble(), nullable(String.class), nullable(String.class), any(Duration.class)))
                .thenReturn(new DeepSeekClient.ChatResult(json.createObjectNode().put("content", body), finishReason, 12, 20, 0));
    }

    private void verifyFullCall() throws Exception {
        verify(client).chatFull(anyList(), isNull(), anyString(), nullable(String.class), anyString(),
                anyInt(), anyDouble(), eq("disabled"), isNull(), any(Duration.class));
        verify(client, never()).chat(anyList(), nullable(List.class), anyString(), nullable(String.class), anyString(),
                anyInt(), anyDouble(), nullable(String.class), nullable(String.class), any(Duration.class));
    }

    private void verifyNoPageMutation() {
        verify(pages, never()).delete(any());
        verify(pages, never()).deleteById(any(java.io.Serializable.class));
        verify(pages, never()).insert(any(WikiPage.class));
        verify(pages, never()).updateById(any(WikiPage.class));
        assertEquals("已有合成工具页", existingTool.getContentMd());
        assertEquals("已有合成命令页，须保留", existingCommand.getContentMd());
    }

    private Map<String, Object> await(Map<String, Object> started) {
        String id = started.get("jobId").toString();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Map<String, Object> status = service.view(id);
            if (!"running".equals(status.get("status")) &&
                    (!"failed".equals(status.get("status")) || status.get("error") != null)) return status;
            Thread.yield();
        }
        fail("合成后台任务未结束");
        return Map.of();
    }

    private static WikiPage page(String key, String title, String body) {
        WikiPage page = new WikiPage();
        page.setId("Git".equals(title) ? 91001L : 91002L);
        page.setTopicKey(key); page.setTopicType("entity"); page.setTitle(title); page.setContentMd(body);
        return page;
    }
}
