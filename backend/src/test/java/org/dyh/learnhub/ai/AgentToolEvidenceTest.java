package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.service.RetrievalContextService;
import org.dyh.learnhub.service.RetrievalHit;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentToolEvidenceTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void automaticallyInjectedWikiGuideNeverEntersSourceVerification() {
        String original = "【原文】ReAct 的实际来源内容。";
        String guide = "【Wiki 生成导览】生成但未核验的解释。";
        var evidence = new AgentToolEvidence(new RetrievalContextService.Context(List.of(guide, original),
                List.of(), List.of(Map.of("type", "wiki", "id", 3L, "generatedGuide", true))), json);

        assertEquals(original, evidence.text());
        assertFalse(evidence.text().contains("未核验"));
        // UI attribution is retained independently of the text used to verify the answer.
        assertEquals(true, evidence.references().getFirst().get("generatedGuide"));
    }

    @Test void wikiOnlyInitialContextHasNoGroundingText() {
        var evidence = new AgentToolEvidence(new RetrievalContextService.Context(
                List.of("【Wiki 生成导览】生成解释与引用线索。"), List.of(),
                List.of(Map.of("type", "wiki", "id", 3L, "generatedGuide", true))), json);

        assertEquals("", evidence.text());
        assertEquals(1, evidence.references().size());
    }

    @Test void generatedWikiToolTextAndListedSourceIdsAreNotActualSourceEvidence() throws Exception {
        var evidence = new AgentToolEvidence(RetrievalContextService.Context.empty(), json);
        String result = json.writeValueAsString(Map.of("ok", true, "text", "错误的生成解释", "sourceRefs",
                List.of(Map.of("type", "note", "id", 7)), "verified", true));

        evidence.capture("search_wiki", result);
        evidence.capture("read_wiki", result);

        assertEquals("", evidence.text());
        assertTrue(evidence.references().isEmpty());
        assertTrue(evidence.skipReason().contains("Wiki"));
        evidence.capture("get_note", json.writeValueAsString(Map.of("ok", true, "note_id", 7,
                "title", "原文", "content", "实际读取的原文")));
        assertTrue(evidence.text().contains("实际读取的原文"));
        assertFalse(evidence.text().contains("错误的生成解释"));
        assertEquals(1, evidence.references().size());
        assertEquals("note", evidence.references().getFirst().get("type"));
        // Phase 1 conservatively skips a complete check after unverified guide material was consumed.
        assertNotNull(evidence.skipReason());
    }

    @Test void originalPassagesRecalledViaWikiRetainTheirChannelAndCanBeChecked() throws Exception {
        var evidence = new AgentToolEvidence(RetrievalContextService.Context.empty(), json);
        evidence.capture("search_knowledge", json.writeValueAsString(Map.of("ok", true, "items", List.of(Map.of(
                "type", "file", "id", 9, "title", "原文", "snippet", "实际原文片段", "channels", List.of("wiki"))))));

        assertTrue(evidence.text().contains("实际原文片段"));
        assertEquals(List.of("wiki", "tool"), evidence.references().getFirst().get("channels"));
        assertNull(evidence.skipReason());
    }

    @Test void wikiToolMetadataAppearsInTheUiButNeverTurnsItsTextOrSourceRefsIntoGroundingEvidence() throws Exception {
        var evidence = new AgentToolEvidence(RetrievalContextService.Context.empty(), json);
        Map<String, Object> section = Map.of("pageId", 3, "pageTitle", "ReAct", "topicKey", "entity:react",
                "sectionKey", "s0", "heading", "循环机制", "text", "生成的解释", "sourceRefs", List.of("note:7"),
                "links", List.of("Planning"), "key", "note:7:0", "factVerified", true);
        evidence.capture("search_wiki", json.writeValueAsString(Map.of("ok", true, "items", List.of(section))));
        evidence.capture("read_wiki", json.writeValueAsString(Map.of("ok", true, "sections", List.of(section),
                "omittedSections", List.of(Map.of("pageId", 3, "sectionKey", "s1", "text", "未实际返回的正文")))));

        assertEquals("", evidence.text());
        assertEquals(1, evidence.references().size());
        Map<String, Object> guide = evidence.references().getFirst();
        assertEquals("wiki", guide.get("type"));
        assertEquals("wiki:3:s0", guide.get("passageKey"));
        assertEquals("entity:react", guide.get("topicKey"));
        assertEquals("循环机制", guide.get("heading"));
        assertEquals(List.of("note:7"), guide.get("sourceRefs"));
        assertEquals(List.of("wiki", "tool"), guide.get("channels"));
        assertEquals(true, guide.get("generatedGuide"));
        assertEquals(false, guide.get("factVerified"));
        assertTrue(evidence.skipReason().contains("Wiki"));
    }

    @Test void repeatedPassageReadMergesAttributionWithoutSpendingTheBudgetTwice() throws Exception {
        String body = "ReAct 的观察反馈用于 Planning。";
        String block = "原文：" + body;
        var hit = new RetrievalHit("note", 1L, "原文", "", body, .1, 2,
                List.of("graph"), List.of("ReAct —用于→ Planning"));
        var ref = Map.<String, Object>of("type", "note", "id", 1L, "title", "原文",
                "passageKey", hit.key(), "seq", 2, "channels", hit.channels(), "graphRelations", hit.graphRelations());
        var evidence = new AgentToolEvidence(new RetrievalContextService.Context(List.of(block), List.of(hit), List.of(ref)), json);
        String response = json.writeValueAsString(Map.of("ok", true, "items", List.of(Map.of(
                "type", "note", "id", 1L, "title", "原文", "snippet", body,
                "seq", 2, "passageKey", hit.key(), "channels", List.of("vector")))));

        evidence.capture("search_knowledge", response);
        evidence.capture("search_knowledge", response);

        assertEquals(block, evidence.text());
        assertNull(evidence.skipReason());
        assertEquals(1, evidence.references().size());
        assertEquals(List.of("graph", "vector", "tool"), evidence.references().getFirst().get("channels"));
        assertEquals(hit.graphRelations(), evidence.references().getFirst().get("graphRelations"));
    }

    @Test void changedTextAtTheSamePassageKeepsBothVersionsTheModelActuallySaw() throws Exception {
        String before = "旧材料：参数为 3。";
        String after = "新材料：参数为 5。";
        var hit = new RetrievalHit("note", 2L, "参数", "", before, .1, 0, List.of("keyword"), List.of());
        var evidence = new AgentToolEvidence(new RetrievalContextService.Context(List.of(before), List.of(hit),
                List.of(Map.of("type", "note", "id", 2L, "passageKey", hit.key()))), json);

        evidence.capture("search_knowledge", json.writeValueAsString(Map.of("ok", true, "items", List.of(Map.of(
                "type", "note", "id", 2L, "title", "参数", "snippet", after, "seq", 0, "passageKey", hit.key())))));

        assertTrue(evidence.text().contains(before));
        assertTrue(evidence.text().contains(after));
        assertEquals(1, evidence.references().size());
    }

    @Test void separateFileWindowsKeepTheirRealTextOffsetsAndSourceIdentity() throws Exception {
        var evidence = new AgentToolEvidence(RetrievalContextService.Context.empty(), json);
        evidence.capture("get_file", json.writeValueAsString(Map.of("ok", true, "file_id", 3,
                "name", "测试.pdf", "excerpts", List.of(
                        Map.of("offset", 120, "excerpt", "第一段实际原文"),
                        Map.of("offset", 980, "excerpt", "第二段实际原文")))));

        assertTrue(evidence.text().contains("第一段实际原文"));
        assertTrue(evidence.text().contains("第二段实际原文"));
        assertEquals(2, evidence.references().size());
        assertEquals(List.of(120L, 980L), evidence.references().stream().map(r -> r.get("offset")).toList());
        assertNotEquals(evidence.references().get(0).get("passageKey"), evidence.references().get(1).get("passageKey"));
        assertNull(evidence.skipReason());
    }

    @Test void unsavedEditorEvidenceHasNoPersistedSourceAndDoesNotLeakToAnotherRequest() {
        var first = new AgentToolEvidence(RetrievalContextService.Context.empty(), json);
        var second = new AgentToolEvidence(RetrievalContextService.Context.empty(), json);
        String excerpt = "【用户提供的当前编辑器内容】\n尚未保存的正文";
        first.addEditorExcerpt(excerpt);

        assertEquals(excerpt, first.text());
        assertTrue(first.references().isEmpty());
        assertEquals("", second.text());
        assertTrue(second.references().isEmpty());
    }

    @Test void unreadableToolDataDoesNotPermitCheckingOnlyEarlierEvidence() {
        var evidence = new AgentToolEvidence(new RetrievalContextService.Context(List.of("之前的原文"), List.of(), List.of()), json);

        evidence.capture("get_file", "{unreadable");

        assertEquals("之前的原文", evidence.text());
        assertNotNull(evidence.skipReason());
        assertTrue(evidence.references().isEmpty());
    }

    @Test void legacyNullMetadataAndReturnedMapEditsCannotBreakOrModifyTheRecord() {
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("type", "note");
        original.put("id", 4L);
        original.put("title", null);
        var evidence = new AgentToolEvidence(new RetrievalContextService.Context(List.of(), List.of(), List.of(original)), json);

        original.put("id", 99L);
        evidence.references().getFirst().put("id", 88L);

        assertEquals(4L, evidence.references().getFirst().get("id"));
        assertNull(evidence.references().getFirst().get("title"));
    }
}
