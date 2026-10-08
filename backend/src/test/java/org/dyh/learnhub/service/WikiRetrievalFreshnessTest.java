package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.ai.DeepSeekClient;
import org.dyh.learnhub.ai.ModelRouting;
import org.dyh.learnhub.common.PageResult;
import org.dyh.learnhub.entity.WikiPage;
import org.dyh.learnhub.mapper.*;
import org.dyh.learnhub.vo.NoteVO;
import org.dyh.learnhub.vo.QuickRefVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WikiRetrievalFreshnessTest {
    private static final LocalDateTime SOURCE_TIME = LocalDateTime.of(2026, 10, 6, 12, 0);
    private static final LocalDateTime GENERATED_TIME = SOURCE_TIME.plusHours(1);
    private NoteService notes;
    private QuickRefService refs;
    private FileStorageService files;
    private WikiPageMapper pages;
    private KbChunkMapper chunks;
    private ModelRouting routing;
    private DeepSeekClient client;
    private WikiDependencyService dependencies;
    private WikiService service;

    @BeforeEach
    void setup() {
        notes = mock(NoteService.class); refs = mock(QuickRefService.class); files = mock(FileStorageService.class);
        pages = mock(WikiPageMapper.class); chunks = mock(KbChunkMapper.class);
        routing = mock(ModelRouting.class); client = mock(DeepSeekClient.class);
        dependencies = mock(WikiDependencyService.class);
        when(dependencies.freshness(anyList())).thenAnswer(invocation -> {
            Map<Long, WikiDependencyService.Freshness> result = new LinkedHashMap<>();
            for (WikiPage page : invocation.<List<WikiPage>>getArgument(0)) {
                result.put(page.getId(), new WikiDependencyService.Freshness(WikiDependencyService.Status.UNKNOWN,
                        List.of("legacy_no_dependencies"), false));
            }
            return result;
        });
        service = new WikiService(notes, refs, mock(CategoryService.class), mock(TagService.class), files, pages,
                mock(AppSettingMapper.class), mock(KgMapper.class), routing, mock(SkillService.class), chunks,
                mock(EntityCompileService.class), dependencies, client, new ObjectMapper());
        when(pages.retrievalFileScope(201)).thenReturn(List.of());
    }

    @AfterEach
    void cleanup() {
        service.shutdown();
        verifyNoInteractions(client, routing, files);
    }

    @Test
    void exactDependenciesSupersedeLegacyTimestampsAndCitationHashes() {
        WikiPage page = entity("entity-tracked", "full-sha256", "有来源依赖的生成页，无需旧 src1 标记。");
        tracked(page, WikiDependencyService.Status.CURRENT, List.of(), true);
        assertEquals(true, service.retrievalFreshness(List.of(page)).get(page.getTopicKey()));
        assertEquals(false, ReflectionTestUtils.invokeMethod(service, "compiledStale", "entity", page));
        verifyNoInteractions(chunks, notes, refs, pages);
    }

    @Test
    void trackedStaleUnknownAndMissingRecordsNeverFallBackToLegacyFreshness() {
        WikiPage page = entity("entity-tracked", "src1|n1", "依据 [笔记#1]。");
        for (var state : List.of(
                new WikiDependencyService.Freshness(WikiDependencyService.Status.STALE, List.of("source_content_changed"), true),
                new WikiDependencyService.Freshness(WikiDependencyService.Status.UNKNOWN, List.of("dependency_check_failed"), false),
                new WikiDependencyService.Freshness(WikiDependencyService.Status.UNKNOWN, List.of("dependency_record_missing"), false))) {
            when(dependencies.freshness(anyList())).thenReturn(Map.of(page.getId(), state));
            assertEquals(false, service.retrievalFreshness(List.of(page)).get(page.getTopicKey()));
            assertEquals(true, ReflectionTestUtils.invokeMethod(service, "compiledStale", "entity", page));
        }
        verifyNoInteractions(chunks, notes, refs, pages);
    }

    @Test
    void absentBatchStatusRemainsUnknownEvenIfLegacyProvenanceLooksCurrent() {
        WikiPage page = entity("entity-tracked", "src1|n1", "依据 [笔记#1]。");
        when(dependencies.freshness(anyList())).thenReturn(Map.of());
        assertEquals(false, service.retrievalFreshness(List.of(page)).get(page.getTopicKey()));
        verifyNoInteractions(chunks);
    }

    @Test
    void trackedCategoryStillInvalidatesWhenItsScopeMembershipChanges() {
        WikiPage page = category("cat-3", 3L, fingerprint("note1@" + SOURCE_TIME + ";"), "依据 [笔记#1]。");
        tracked(page, WikiDependencyService.Status.CURRENT, List.of(), true);
        categoryScope(3, List.of(note(1, SOURCE_TIME)), List.of(), List.of());
        assertEquals(true, service.retrievalFreshness(List.of(page)).get(page.getTopicKey()));
        categoryScope(3, List.of(note(1, SOURCE_TIME), note(2, SOURCE_TIME)), List.of(), List.of());
        assertEquals(false, service.retrievalFreshness(List.of(page)).get(page.getTopicKey()));
        verifyNoInteractions(chunks);
    }

    private void tracked(WikiPage page, WikiDependencyService.Status status, List<String> reasons, boolean hasRecords) {
        when(dependencies.freshness(anyList())).thenReturn(Map.of(page.getId(),
                new WikiDependencyService.Freshness(status, reasons, hasRecords)));
    }

    @Test
    void currentEntityRequiresKnownProvenanceGenerationTimeAndAllSourcesToExist() {
        when(chunks.allSourceTimes()).thenReturn(List.of(time("note", 1, SOURCE_TIME), time("file", 2, SOURCE_TIME)));
        WikiPage current = entity("entity-current", "src1|n1,f2", "Overview [笔记#1] [资料#2]");
        WikiPage unknown = entity("entity-unknown", "legacy-hash", "No references.");
        WikiPage noTime = entity("entity-no-time", "src1|n1", "[笔记#1]"); noTime.setGeneratedAt(null);
        WikiPage deleted = entity("entity-deleted", "src1|n8", "[笔记#8]");
        WikiPage empty = entity("entity-empty", "src1|n1", " ");
        WikiPage wrongType = entity("entity-wrong-type", "src1|n1", "[笔记#1]"); wrongType.setTopicType("index");
        Map<String, Boolean> fresh = service.retrievalFreshness(List.of(current, unknown, noTime, deleted, empty, wrongType));
        assertEquals(true, fresh.get(current.getTopicKey()));
        for (WikiPage page : List.of(unknown, noTime, deleted, empty, wrongType)) assertEquals(false, fresh.get(page.getTopicKey()));
        verify(chunks).allSourceTimes();
        verifyNoInteractions(notes, refs, pages);
    }

    @Test
    void sourceHashAndBodyCitationsAreUnionedSoCompactHashCannotHideAChangedDependency() {
        when(chunks.allSourceTimes()).thenReturn(List.of(time("note", 1, SOURCE_TIME), time("quick_ref", 2, GENERATED_TIME.plusSeconds(1))));
        WikiPage page = entity("entity-react", "src1|n1", "ReAct uses a loop [笔记#1] and [速查卡#2].");
        assertEquals(false, service.retrievalFreshness(List.of(page)).get(page.getTopicKey()));
        when(chunks.allSourceTimes()).thenReturn(List.of(time("note", 1, SOURCE_TIME), time("quick_ref", 2, GENERATED_TIME)));
        assertEquals(true, service.retrievalFreshness(List.of(page)).get(page.getTopicKey()), "equal timestamps are still current");
    }

    @Test
    void codeAndMetadataCitationExamplesDoNotInvalidateCurrentProseOrLegacyProvenance() {
        String content = """
                <!-- entity-aliases: Example [资料#997] -->
                # ReAct
                真实结论依据 [笔记#1]。
                行内示例 `[资料#999]` 和分段标记 [资料#9`example`99] 不属于引用。

                ````markdown
                [资料#999]
                ```
                [速查卡#998]
                ````

                    [资料#996]
                """;
        WikiPage current = entity("entity-current", "src1|n1", content);
        WikiPage legacy = entity("entity-legacy", "legacy-hash", content);
        when(chunks.allSourceTimes()).thenReturn(List.of(time("note", 1, SOURCE_TIME)));

        Map<String, Boolean> fresh = service.retrievalFreshness(List.of(current, legacy));

        assertEquals(true, fresh.get(current.getTopicKey()));
        assertEquals(true, fresh.get(legacy.getTopicKey()));
        assertEquals(false, ReflectionTestUtils.invokeMethod(service, "compiledStale", "entity", legacy,
                null, Map.of("note-1", SOURCE_TIME)), "the page UI must use the same non-code citation interpretation");
    }

    @Test
    void codeOnlyLegacyPageHasNoKnownProvenanceAndExplicitStoredDependenciesAreStillChecked() {
        String examples = "行内引用格式 `[笔记#1]`。\n\n```markdown\n[资料#999]\n```\n";
        WikiPage legacy = entity("entity-code-only", "legacy-hash", examples);
        WikiPage explicit = entity("entity-stored-dependency", "src1|n1,f999", examples);
        when(chunks.allSourceTimes()).thenReturn(List.of(time("note", 1, SOURCE_TIME)));

        Map<String, Boolean> fresh = service.retrievalFreshness(List.of(legacy, explicit));

        assertEquals(false, fresh.get(legacy.getTopicKey()), "code examples cannot supply missing legacy provenance");
        assertEquals(false, fresh.get(explicit.getTopicKey()), "a dependency recorded in src1 must remain authoritative");
    }

    @Test
    void visibleMarkdownCitationLinkKeepsItsDependencyButLinkUrlCannotForgeAnother() {
        WikiPage page = entity("entity-linked-citation", "legacy-hash", "依据 [笔记#1](https://example.invalid/[资料#999])。");
        when(chunks.allSourceTimes()).thenReturn(List.of(time("note", 1, SOURCE_TIME)));

        assertEquals(true, service.retrievalFreshness(List.of(page)).get(page.getTopicKey()));
        when(chunks.allSourceTimes()).thenReturn(List.of(time("note", 1, GENERATED_TIME.plusSeconds(1))));
        assertEquals(false, service.retrievalFreshness(List.of(page)).get(page.getTopicKey()));
    }

    @Test
    void legacyEntityCanUseExplicitCitationsButNeverAnUnknownSource() {
        WikiPage page = entity("entity-legacy", "legacy-hash", "Overview [笔记#1].");
        when(chunks.allSourceTimes()).thenReturn(List.of(time("note", 1, SOURCE_TIME)));
        assertEquals(true, service.retrievalFreshness(List.of(page)).get(page.getTopicKey()));
        when(chunks.allSourceTimes()).thenReturn(List.of(Map.of("t", "note", "id", 1)));
        assertEquals(false, service.retrievalFreshness(List.of(page)).get(page.getTopicKey()));
    }

    @Test
    void databaseFailureIsUnknownAndNeverTriggersGeneration() {
        WikiPage page = entity("entity-react", "src1|n1", "Overview [笔记#1].");
        when(chunks.allSourceTimes()).thenThrow(new IllegalStateException("offline"));
        assertEquals(false, service.retrievalFreshness(List.of(page)).get(page.getTopicKey()));
        assertTrue(service.retrievalFreshness(null).isEmpty());
        assertTrue(service.retrievalFreshness(List.of()).isEmpty());
    }

    @Test
    void categoryFingerprintIncludesCurrentNotesReferencesAndFilesWithoutReadingTheirBodies() {
        WikiPage page = category("cat-3", 3L, fingerprint("file2@null;note1@" + SOURCE_TIME + ";ref4@" + SOURCE_TIME + ";"), "Overview [筆记#1].");
        categoryScope(3, List.of(note(1, SOURCE_TIME)), List.of(ref(4, SOURCE_TIME)), List.of(file(2, 3)));
        when(chunks.allSourceTimes()).thenReturn(List.of(time("note", 1, SOURCE_TIME), time("quick_ref", 4, SOURCE_TIME), time("file", 2, SOURCE_TIME)));
        assertEquals(true, service.retrievalFreshness(List.of(page)).get(page.getTopicKey()));
        verify(pages).retrievalFileScope(201);
        verify(notes).page(3L, null, null, 1, 500);
        verify(refs).list(3L, null);
    }

    @Test
    void reExtractedFileInvalidatesCategoryEvenWhenItsLegacyFingerprintIsUnchanged() {
        WikiPage page = category("cat-3", 3L, fingerprint("file2@null;"), "Overview [资料#2].");
        categoryScope(3, List.of(), List.of(), List.of(file(2, 3)));
        when(chunks.allSourceTimes()).thenReturn(List.of(time("file", 2, GENERATED_TIME.plusMinutes(1))));
        assertEquals(false, service.retrievalFreshness(List.of(page)).get(page.getTopicKey()));
        when(chunks.allSourceTimes()).thenReturn(List.of(time("file", 2, SOURCE_TIME)));
        assertEquals(true, service.retrievalFreshness(List.of(page)).get(page.getTopicKey()));
    }

    @Test
    void addingDeletingOrEditingScopeChangesFingerprintEvenIfPageCitationsWereNotUpdated() {
        WikiPage page = category("cat-3", 3L, fingerprint("note1@" + SOURCE_TIME + ";"), "Overview [笔记#1].");
        categoryScope(3, List.of(note(1, SOURCE_TIME), note(2, SOURCE_TIME)), List.of(), List.of());
        when(chunks.allSourceTimes()).thenReturn(List.of(time("note", 1, SOURCE_TIME), time("note", 2, SOURCE_TIME)));
        assertEquals(false, service.retrievalFreshness(List.of(page)).get(page.getTopicKey()));
        categoryScope(3, List.of(), List.of(), List.of());
        assertEquals(false, service.retrievalFreshness(List.of(page)).get(page.getTopicKey()));
        categoryScope(3, List.of(note(1, SOURCE_TIME.plusMinutes(1))), List.of(), List.of());
        assertEquals(false, service.retrievalFreshness(List.of(page)).get(page.getTopicKey()));
    }

    @Test
    void topicScopeAndExplicitCitationUnionRejectsCrossCategoryDeletedCitation() {
        WikiPage page = category("cat-3", 3L, fingerprint("note1@" + SOURCE_TIME + ";"), "Overview [笔记#1] and [资料#999].");
        categoryScope(3, List.of(note(1, SOURCE_TIME)), List.of(), List.of());
        when(chunks.allSourceTimes()).thenReturn(List.of(time("note", 1, SOURCE_TIME)));
        assertEquals(false, service.retrievalFreshness(List.of(page)).get(page.getTopicKey()));
    }

    @Test
    void tagFingerprintUsesOnlyNotesAndAvoidsFileAndQuickReferenceScans() {
        WikiPage page = category("tag-4", 4L, fingerprint("note1@" + SOURCE_TIME + ";"), "Overview [笔记#1].");
        page.setTopicType("tag");
        when(notes.page(null, 4L, null, 1, 500)).thenReturn(PageResult.of(1, List.of(note(1, SOURCE_TIME))));
        when(chunks.allSourceTimes()).thenReturn(List.of(time("note", 1, SOURCE_TIME)));
        assertEquals(true, service.retrievalFreshness(List.of(page)).get(page.getTopicKey()));
        verifyNoInteractions(refs, pages);
    }

    @Test
    void incompleteNotesAndFileScopesRemainUnknownRatherThanClaimingFresh() {
        WikiPage page = category("cat-3", 3L, fingerprint("note1@" + SOURCE_TIME + ";"), "Overview [笔记#1].");
        categoryScope(3, List.of(note(1, SOURCE_TIME)), List.of(), List.of());
        when(chunks.allSourceTimes()).thenReturn(List.of(time("note", 1, SOURCE_TIME)));
        when(notes.page(3L, null, null, 1, 500)).thenReturn(PageResult.of(501, List.of(note(1, SOURCE_TIME))));
        assertEquals(false, service.retrievalFreshness(List.of(page)).get(page.getTopicKey()));
        when(notes.page(3L, null, null, 1, 500)).thenReturn(PageResult.of(2, List.of(note(1, SOURCE_TIME))));
        assertEquals(false, service.retrievalFreshness(List.of(page)).get(page.getTopicKey()));
        when(notes.page(3L, null, null, 1, 500)).thenReturn(PageResult.of(1, List.of(note(1, SOURCE_TIME))));
        List<Map<String, Object>> largeScope = new ArrayList<>();
        for (long id = 1; id <= 201; id++) largeScope.add(file(id, 99));
        when(pages.retrievalFileScope(201)).thenReturn(largeScope);
        assertEquals(false, service.retrievalFreshness(List.of(page)).get(page.getTopicKey()));
    }

    @Test
    void exactlyTwoHundredFilesAreACompleteScopeAndDoNotRejectAnUnrelatedCategory() {
        WikiPage page = category("cat-3", 3L, fingerprint("note1@" + SOURCE_TIME + ";"), "Overview [笔记#1].");
        categoryScope(3, List.of(note(1, SOURCE_TIME)), List.of(), List.of());
        when(chunks.allSourceTimes()).thenReturn(List.of(time("note", 1, SOURCE_TIME)));
        List<Map<String, Object>> scope = new ArrayList<>();
        for (long id = 1; id <= 200; id++) scope.add(file(id, 99));
        when(pages.retrievalFileScope(201)).thenReturn(scope);
        assertEquals(true, service.retrievalFreshness(List.of(page)).get(page.getTopicKey()));
    }

    @Test
    void multiplePagesShareOneSourceTimeScanAndOneFileMetadataScan() {
        WikiPage a = category("cat-3", 3L, fingerprint("note1@" + SOURCE_TIME + ";"), "Overview [笔记#1].");
        WikiPage b = category("cat-4", 4L, fingerprint("note2@" + SOURCE_TIME + ";"), "Overview [笔记#2].");
        categoryScope(3, List.of(note(1, SOURCE_TIME)), List.of(), List.of());
        categoryScope(4, List.of(note(2, SOURCE_TIME)), List.of(), List.of());
        when(chunks.allSourceTimes()).thenReturn(List.of(time("note", 1, SOURCE_TIME), time("note", 2, SOURCE_TIME)));
        Map<String, Boolean> result = service.retrievalFreshness(List.of(a, b));
        assertEquals(Map.of("cat-3", true, "cat-4", true), result);
        verify(chunks, times(1)).allSourceTimes();
        verify(pages, times(1)).retrievalFileScope(201);
    }

    private void categoryScope(long category, List<NoteVO> currentNotes, List<QuickRefVO> currentRefs, List<Map<String, Object>> currentFiles) {
        when(notes.page(category, null, null, 1, 500)).thenReturn(PageResult.of(currentNotes.size(), currentNotes));
        when(refs.list(category, null)).thenReturn(currentRefs);
        when(pages.retrievalFileScope(201)).thenReturn(currentFiles);
    }

    private static WikiPage entity(String key, String hash, String body) {
        WikiPage page = new WikiPage();
        page.setId(Integer.toUnsignedLong(key.hashCode()));
        page.setTopicKey(key); page.setTopicType("entity"); page.setSourceHash(hash);
        page.setGeneratedAt(GENERATED_TIME); page.setContentMd(body);
        return page;
    }

    private static WikiPage category(String key, Long id, String hash, String body) {
        WikiPage page = entity(key, hash, body); page.setTopicType("category"); page.setTopicId(id); return page;
    }
    private static NoteVO note(long id, LocalDateTime updated) {
        NoteVO note = new NoteVO(); note.setId(id); note.setUpdatedAt(updated); return note;
    }
    private static QuickRefVO ref(long id, LocalDateTime updated) {
        QuickRefVO ref = new QuickRefVO(); ref.setId(id); ref.setUpdatedAt(updated); return ref;
    }
    private static Map<String, Object> file(long id, long category) { return Map.of("id", id, "categoryId", category); }
    private static Map<String, Object> time(String type, long id, LocalDateTime updated) { return Map.of("t", type, "id", id, "ts", updated); }
    private static String fingerprint(String value) { return KgService.sha256(value); }
}
