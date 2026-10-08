package org.dyh.learnhub.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dyh.learnhub.ai.DeepSeekClient;
import org.dyh.learnhub.ai.ModelRouting;
import org.dyh.learnhub.common.PageResult;
import org.dyh.learnhub.entity.Category;
import org.dyh.learnhub.entity.WikiPage;
import org.dyh.learnhub.mapper.*;
import org.dyh.learnhub.vo.NoteVO;
import org.dyh.learnhub.vo.QuickRefVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Integration checks for the model-material boundary and atomic Wiki/dependency persistence. */
class WikiGenerationDependencyTest {
    private NoteService notes;
    private QuickRefService refs;
    private FileStorageService files;
    private WikiPageMapper pages;
    private WikiDependencyService dependencies;
    private DeepSeekClient client;
    private WikiService service;
    private final Map<WikiDependencyService.SourceRef, WikiDependencyService.SourceInput> fullSources = new LinkedHashMap<>();

    @BeforeEach
    void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "wiki-generation-test"), WikiPage.class);
        notes = mock(NoteService.class); refs = mock(QuickRefService.class); files = mock(FileStorageService.class);
        pages = mock(WikiPageMapper.class); dependencies = mock(WikiDependencyService.class); client = mock(DeepSeekClient.class);
        CategoryService categories = mock(CategoryService.class); Category category = new Category(); category.setId(3L); category.setName("Agent 学习");
        when(categories.getById(3L)).thenReturn(category);
        ModelRouting routing = mock(ModelRouting.class);
        when(routing.targets()).thenReturn(List.of(new ModelRouting.ModelTarget("local", "本地测试", "http://localhost:11434/v1", "", "test-model", true)));
        when(routing.targetIdOf(ModelRouting.TASK_WIKI)).thenReturn("local");
        service = new WikiService(notes, refs, categories, mock(TagService.class), files, pages, mock(AppSettingMapper.class),
                mock(KgMapper.class), routing, mock(SkillService.class), mock(KbChunkMapper.class), mock(EntityCompileService.class),
                dependencies, client, new ObjectMapper());
        when(notes.page(3L, null, null, 1, 500)).thenReturn(PageResult.of(0, List.of()));
        when(refs.list(3L, null)).thenReturn(List.of()); when(files.retrievalScan(200)).thenReturn(List.of());
        when(dependencies.captureSnapshot(anyCollection())).thenAnswer(call -> {
            Collection<WikiDependencyService.SourceRef> requested = call.getArgument(0);
            List<WikiDependencyService.SourceInput> read = requested.stream().map(fullSources::get).filter(Objects::nonNull).toList();
            var captured = WikiDependencyService.fromSources(read);
            return new WikiDependencyService.Snapshot(captured.capturedAt(), captured.sources(),
                    requested.stream().filter(ref -> !fullSources.containsKey(ref)).toList(), captured.hash());
        });
        when(dependencies.saveGenerated(any(WikiPage.class), any(WikiDependencyService.Snapshot.class))).thenAnswer(call -> {
            WikiPage page = call.getArgument(0); page.setId(88L); return page;
        });
    }

    @AfterEach
    void cleanup() {
        service.shutdown();
        ExecutorService runner = (ExecutorService) ReflectionTestUtils.getField(service, "jobRunner");
        if (runner != null) runner.shutdownNow();
    }

    @Test
    void generationMaterialCapturesFullTextAndSelectsOnlyWholeOriginalChunks() {
        String full = "# Authors\nAlice Example\n\n# State mechanism\n" + "The controller records a recoverable state. ".repeat(14)
                + "\n\n# Rollback\n" + "It restores the saved state and resumes execution. ".repeat(14)
                + "\n\n# Limitations\nThe unchanged environment is required.";
        noteScope(note(1, "This list summary deliberately omits the mechanism."));
        put("note", 1, full);
        Object material = generationMaterial();
        WikiDependencyService.Snapshot snapshot = read(material, "snapshot");
        assertEquals(WikiDependencyService.sha256(full), snapshot.sources().get(0).fullContentHash());
        assertEquals(full, snapshot.sources().get(0).fullContent());
        List<TextChunker.Chunk> originals = TextChunker.splitWithHeadings(full);
        for (var chunk : snapshot.sources().get(0).chunks()) {
            assertEquals(originals.get(chunk.seq()).text(), chunk.text());
            assertEquals(originals.get(chunk.seq()).heading(), chunk.heading());
            assertEquals(WikiDependencyService.sha256(chunk.text()), chunk.hash());
        }
        assertTrue(snapshot.sources().get(0).chunks().stream().noneMatch(chunk -> chunk.heading().equals("Authors")));
        String prompt = read(material, "prompt");
        assertTrue(prompt.contains("recoverable state")); assertTrue(prompt.contains("restores the saved state"));
        assertFalse(prompt.contains("This list summary deliberately"));
        assertTrue(prompt.length() <= 12000);
        verifyNoInteractions(client); verify(pages, never()).insert(any(WikiPage.class));
    }

    @Test
    void anUnreadableOrMetadataOnlySourceCannotOverwriteAnExistingWikiPage() {
        WikiPage old = new WikiPage(); old.setId(9L); old.setTopicKey("cat-3"); old.setContentMd("Existing useful knowledge page");
        when(pages.selectOne(any())).thenReturn(old);
        noteScope(note(1, "Available summary without readable full text"));
        IllegalStateException missing = assertThrows(IllegalStateException.class, () -> service.startGenerate("cat-3", null));
        assertTrue(missing.getMessage().contains("未覆盖已有知识页"));
        put("note", 1, "# Authors\nAlice Example\nBob Example");
        assertThrows(IllegalStateException.class, () -> service.startGenerate("cat-3", null));
        assertEquals("Existing useful knowledge page", old.getContentMd());
        verify(dependencies, never()).saveGenerated(any(), any()); verifyNoInteractions(client);
        verify(pages, never()).insert(any(WikiPage.class)); verify(pages, never()).updateById(any(WikiPage.class));
    }

    @Test
    void topicFileCaptureIsBoundedToFortyCandidateSourcesAndTwelveThousandPromptCharacters() {
        List<Map<String, Object>> available = new ArrayList<>();
        for (long id = 1; id <= 60; id++) {
            available.add(file(id)); put("file", id, "A complete body explaining the source's state mechanism " + id + ".");
        }
        when(files.retrievalScan(200)).thenReturn(available);
        Object material = generationMaterial();
        WikiDependencyService.Snapshot snapshot = read(material, "snapshot");
        assertEquals(60, (Integer) read(material, "total"));
        assertEquals(40, snapshot.sourceRefs().size());
        assertTrue(snapshot.sourceRefs().stream().allMatch(ref -> ref.id() <= 40));
        ArgumentCaptor<Collection<WikiDependencyService.SourceRef>> captured = ArgumentCaptor.forClass(Collection.class);
        verify(dependencies).captureSnapshot(captured.capture());
        assertEquals(40, captured.getValue().size());
        assertTrue(((String) read(material, "prompt")).length() <= 12000);
    }

    @Test
    void generationPipelineKeepsTheBeforeModelSnapshotWhenSourcesChangeDuringStreaming() throws Exception {
        String original = "# State\nAn original source states that recoverable checkpoints are saved before rollback.";
        noteScope(note(1, "List summary")); put("note", 1, original);
        AtomicReference<String> sentPrompt = new AtomicReference<>();
        model(call -> {
            List<Map<String, String>> messages = call.getArgument(0);
            sentPrompt.set(messages.get(1).get("content"));
            assertTrue(sentPrompt.get().contains("recoverable checkpoints"));
            put("note", 1, "# Edited while generating\nThe current source changed after the model input was captured.");
            call.<IntConsumer>getArgument(9).accept(300);
            return validAnswer("[笔记#1]");
        });
        CountDownLatch saved = saveSignal();
        Map<String, Object> job = service.startGenerate("cat-3", null);
        awaitCompletion(job, saved);
        ArgumentCaptor<WikiDependencyService.Snapshot> snapshot = ArgumentCaptor.forClass(WikiDependencyService.Snapshot.class);
        ArgumentCaptor<WikiPage> page = ArgumentCaptor.forClass(WikiPage.class);
        verify(dependencies).saveGenerated(page.capture(), snapshot.capture());
        assertEquals(WikiDependencyService.sha256(original), snapshot.getValue().sources().get(0).fullContentHash());
        assertNotEquals(WikiDependencyService.sha256(fullSources.get(new WikiDependencyService.SourceRef("note", 1L)).fullContent()),
                snapshot.getValue().sources().get(0).fullContentHash());
        assertTrue(snapshot.getValue().sources().get(0).chunks().get(0).text().contains("recoverable checkpoints"));
        assertEquals("cat-3", page.getValue().getTopicKey());
        assertEquals("ok", page.getValue().getQuality());
        verify(dependencies, times(1)).captureSnapshot(anyCollection());
        verify(pages, never()).insert(any(WikiPage.class)); verify(pages, never()).updateById(any(WikiPage.class));
    }

    @Test
    void structuralCitationValidationAcceptsOnlySourcesActuallySentToTheModel() throws Exception {
        noteScope(note(1, "Actual useful source"), note(2, "Only a list summary for metadata"), note(3, "Unreadable source summary"));
        put("note", 1, "# State mechanism\nThe execution records a state snapshot.");
        put("note", 2, "# Authors\nAlice Example\nBob Example");
        model(call -> validAnswer("[笔记#1] [笔记#2] [笔记#3]"));
        CountDownLatch saved = saveSignal(); Map<String, Object> job = service.startGenerate("cat-3", null); awaitCompletion(job, saved);
        ArgumentCaptor<WikiPage> page = ArgumentCaptor.forClass(WikiPage.class);
        ArgumentCaptor<WikiDependencyService.Snapshot> snapshot = ArgumentCaptor.forClass(WikiDependencyService.Snapshot.class);
        verify(dependencies).saveGenerated(page.capture(), snapshot.capture());
        assertEquals(List.of(new WikiDependencyService.SourceRef("note", 1L)), snapshot.getValue().sourceRefs());
        assertEquals("warn", page.getValue().getQuality());
        assertTrue(page.getValue().getQualityNote().contains("笔记#2")); assertTrue(page.getValue().getQualityNote().contains("笔记#3"));
        assertTrue(page.getValue().getContentMd().contains("[笔记#1]"));
        assertFalse(page.getValue().getContentMd().contains("[笔记#2]")); assertFalse(page.getValue().getContentMd().contains("[笔记#3]"));
        ArgumentCaptor<List<?>> messages = ArgumentCaptor.forClass(List.class);
        verify(client, times(2)).chatStream(messages.capture(), anyString(), anyString(), anyString(), anyInt(), anyDouble(),
                anyString(), nullable(String.class), any(Duration.class), any(IntConsumer.class));
        String prompt = ((Map<?, ?>) messages.getAllValues().get(0).get(1)).get("content").toString();
        assertTrue(prompt.contains("【笔记#1】")); assertFalse(prompt.contains("【笔记#2】")); assertFalse(prompt.contains("【笔记#3】"));
    }

    @Test
    void quickReferenceSourceUsesCanonicalDependencyTypeAndValidCitationInTheSavedPage() throws Exception {
        QuickRefVO card = new QuickRefVO(); card.setId(7L); card.setTitle("Rollback card"); card.setContent("A preview is not the complete body");
        card.setCategoryId(3L); card.setUpdatedAt(LocalDateTime.of(2026, 10, 7, 10, 0));
        when(refs.list(3L, null)).thenReturn(List.of(card));
        put("quick_ref", 7, "# State rollback\nThe complete card describes a recoverable checkpoint.");
        model(call -> validAnswer("[速查卡#7]"));
        CountDownLatch saved = saveSignal(); Map<String, Object> job = service.startGenerate("cat-3", null); awaitCompletion(job, saved);
        ArgumentCaptor<WikiPage> page = ArgumentCaptor.forClass(WikiPage.class);
        ArgumentCaptor<WikiDependencyService.Snapshot> snapshot = ArgumentCaptor.forClass(WikiDependencyService.Snapshot.class);
        verify(dependencies).saveGenerated(page.capture(), snapshot.capture());
        assertEquals(List.of(new WikiDependencyService.SourceRef("quick_ref", 7L)), snapshot.getValue().sourceRefs());
        assertEquals("ok", page.getValue().getQuality()); assertTrue(page.getValue().getContentMd().contains("[速查卡#7]"));
    }

    private Object generationMaterial() {
        Object topic = ReflectionTestUtils.invokeMethod(service, "resolve", "cat-3");
        return ReflectionTestUtils.invokeMethod(service, "generationMaterial", topic);
    }

    private void model(Answer<String> answer) throws Exception {
        doAnswer(answer).when(client).chatStream(anyList(), anyString(), anyString(), anyString(), anyInt(), anyDouble(),
                anyString(), nullable(String.class), any(Duration.class), any(IntConsumer.class));
    }

    private CountDownLatch saveSignal() {
        CountDownLatch saved = new CountDownLatch(1);
        doAnswer(call -> {
            WikiPage page = call.getArgument(0); page.setId(88L); saved.countDown(); return page;
        }).when(dependencies).saveGenerated(any(WikiPage.class), any(WikiDependencyService.Snapshot.class));
        return saved;
    }

    private void awaitCompletion(Map<String, Object> start, CountDownLatch saved) throws InterruptedException {
        assertTrue(saved.await(5, TimeUnit.SECONDS), "generation did not reach atomic save: " + service.jobView(start.get("jobId").toString()));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        Map<String, Object> view = service.jobView(start.get("jobId").toString());
        while ("running".equals(view.get("status")) && System.nanoTime() < deadline) {
            Thread.sleep(5); view = service.jobView(start.get("jobId").toString());
        }
        assertEquals("done", view.get("status"), () -> "job failed: " + service.jobView(start.get("jobId").toString()));
    }

    private void noteScope(NoteVO... entries) { when(notes.page(3L, null, null, 1, 500)).thenReturn(PageResult.of(entries.length, List.of(entries))); }
    private void put(String type, long id, String full) {
        var source = new WikiDependencyService.SourceInput(type, id, "Source " + id, full); fullSources.put(source.ref(), source);
    }
    private static <T> T read(Object record, String component) { return ReflectionTestUtils.invokeMethod(record, component); }
    private static NoteVO note(long id, String summary) {
        NoteVO note = new NoteVO(); note.setId(id); note.setTitle("Source " + id); note.setSummary(summary); note.setCategoryId(3L);
        note.setUpdatedAt(LocalDateTime.of(2026, 10, 7, 10, 0).minusMinutes(id)); return note;
    }
    private static Map<String, Object> file(long id) {
        return Map.of("id", id, "categoryId", 3L, "originName", "Source " + id, "summary", "", "text", "List preview", "textChars", 50);
    }
    private static String validAnswer(String citation) {
        return "这份知识页整理当前原文里已经出现的状态机制，并保留片段来源以便继续阅读。\n\n## 状态机制\n"
                + ("原文描述了保存状态与继续执行的过程；具体结论仍应对照当前来源确认。" + citation + "\n").repeat(8);
    }
}
