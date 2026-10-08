package org.dyh.learnhub.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dyh.learnhub.entity.WikiPage;
import org.dyh.learnhub.mapper.GraphEvidenceSourceMapper;
import org.dyh.learnhub.mapper.WikiPageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WikiRetrievalServiceTest {
    private WikiPageMapper pages;
    private WikiService wiki;
    private GraphEvidenceSourceMapper sources;
    private WikiRetrievalService service;

    @BeforeEach
    void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), WikiPage.class);
        pages = mock(WikiPageMapper.class);
        wiki = mock(WikiService.class);
        sources = mock(GraphEvidenceSourceMapper.class);
        service = new WikiRetrievalService(pages, wiki, sources);
        when(wiki.retrievalFreshness(anyList())).thenAnswer(call -> {
            Map<String, Boolean> fresh = new HashMap<>();
            for (WikiPage page : call.<List<WikiPage>>getArgument(0)) fresh.put(page.getTopicKey(), true);
            return fresh;
        });
    }

    @Test
    void emptyQueryAndNonpositiveLimitsDoNotReadDatabase() {
        assertTrue(service.search("", 4).isEmpty());
        assertTrue(service.search(null, 4).isEmpty());
        assertTrue(service.search("ReAct", 0).isEmpty());
        assertFalse((Boolean) service.readPage(" ", null, 4000).get("ok"));
        assertFalse((Boolean) service.readPage("entity-react", null, -1).get("ok"));
        verifyNoInteractions(pages, sources);
    }

    @Test
    void filtersEmptyQualityUnknownTypeAndIndexPagesBeforeFreshnessGate() {
        WikiPage valid = page(1, "entity", "entity-react", "ReAct", "## ReAct\nReAct uses tools.");
        WikiPage warn = page(2, "entity", "entity-warn", "ReAct", "warn body"); warn.setQuality("warn");
        WikiPage index = page(3, "index", "wiki-index", "ReAct", "index body");
        WikiPage lint = page(4, "lint", "wiki-lint", "ReAct", "lint body");
        WikiPage empty = page(5, "entity", "entity-empty", "ReAct", " ");
        WikiPage unknown = page(6, null, "entity-unknown", "ReAct", "unknown body");
        when(pages.selectList(any())).thenReturn(Arrays.asList(valid, warn, index, lint, empty, unknown, null));
        assertEquals(List.of(1L), service.search("ReAct", 10).stream().map(WikiRetrievalService.Section::pageId).toList());
        verify(wiki).retrievalFreshness(List.of(valid));
    }

    @Test
    void staleMissingAndFailedFreshnessAreNotRetrievedOrRead() {
        WikiPage page = page(1, "entity", "entity-react", "ReAct", "## ReAct\nReAct body.");
        when(pages.selectList(any())).thenReturn(List.of(page));
        when(pages.selectOne(any())).thenReturn(page);
        when(wiki.retrievalFreshness(anyList())).thenReturn(Map.of(page.getTopicKey(), false), Map.of());
        assertTrue(service.search("ReAct", 4).isEmpty());
        assertFalse((Boolean) service.readPage(page.getTopicKey(), "section-1", 4000).get("ok"));
        when(wiki.retrievalFreshness(anyList())).thenThrow(new IllegalStateException("database unavailable"));
        assertTrue(service.search("ReAct", 4).isEmpty());
        when(pages.selectList(any())).thenThrow(new IllegalStateException("database unavailable"));
        assertTrue(service.search("ReAct", 4).isEmpty());
    }

    @Test
    void realMarkdownHeadingsProduceStableKeysAndIgnoreFencedFakeHeadingsAndMetadata() {
        WikiPage page = page(7, "entity", "entity-react", "Agent", """
                <!-- entity-aliases: ReAct, Reasoning and Acting -->
                Intro [笔记#1].

                # Agent
                ReAct overview [速查、卡#2] [[Planning|计划]].

                ````markdown
                # Fake heading
                [资料#999] [[Fake]]
                ```
                <!-- entity-aliases: HiddenAlias -->
                ````

                ### `ToolAPI`
                ReAct details [资料#3].

                Comparison
                ----------
                ReAct comparisons.
                """);
        when(pages.selectOne(any())).thenReturn(page);
        Map<String, Object> result = service.readPage(page.getTopicKey(), null, 4000);
        List<Map<String, Object>> sections = rows(result, "sections");
        assertEquals(List.of("section-0", "section-1", "section-2", "section-3"),
                sections.stream().map(s -> s.get("sectionKey")).toList());
        assertEquals("Agent > ToolAPI", sections.get(2).get("heading"));
        assertEquals("Agent > Comparison", sections.get(3).get("heading"));
        assertEquals(List.of("quick_ref:2"), sections.get(1).get("sourceRefs"));
        assertEquals(List.of("Planning"), sections.get(1).get("links"));
        assertTrue(((String) sections.get(1).get("text")).contains("# Fake heading"));
        assertEquals(List.of("note:1", "quick_ref:2", "file:3"), sections.get(3).get("sourceRefs"));
        assertTrue(sections.stream().allMatch(s -> !((List<?>) s.get("sourceRefs")).contains("file:999")));
        assertEquals("wiki:7:section-2", sections.get(2).get("key"));
        when(pages.selectList(any())).thenReturn(List.of(page));
        assertFalse(service.search("Reasoning", 4).isEmpty(), "stored aliases locate a page without that literal body word");
        assertTrue(service.search("HiddenAlias", 4).stream().allMatch(s -> s.score() < 1000),
                "a literal inside code may match its body but must not become a stored alias");
    }

    @Test
    void inlineCodeCanBeSearchedButCannotForgeSourceRefsOrWikiLinks() {
        WikiPage page = page(1, "entity", "entity-mapping", "Spring", "## `GetMapping`\nUse `GetMapping` with `[笔记#999] [[Fake]]`. [笔记#2]");
        when(pages.selectList(any())).thenReturn(List.of(page));
        WikiRetrievalService.Section hit = service.search("GetMapping", 1).getFirst();
        assertEquals("GetMapping", hit.heading());
        assertEquals(List.of("note:2"), hit.sourceRefs());
        assertEquals(List.of(), hit.links());
    }

    @Test
    void explicitTitleOrAliasWinsOverGenericRelationshipPageAndHeadingsAloneAreNotHits() {
        WikiPage subject = page(1, "entity", "entity-react", "Agent", "<!-- entity-aliases: ReAct -->\n# Agent\nReAct reason and act. [笔记#1]\n## Empty\n## Detail\nReAct details.");
        WikiPage noisy = page(2, "category", "cat-2", "知识库关系", "# 知识库关系\n关系和知识库：" + "关系 区别 知识库 相关 主要 内容 ".repeat(100));
        when(pages.selectList(any())).thenReturn(List.of(noisy, subject));
        List<WikiRetrievalService.Section> hits = service.search("ReAct 在知识库中的关系和区别是什么", 4);
        assertEquals(1L, hits.getFirst().pageId());
        assertTrue(hits.stream().noneMatch(s -> s.text().equals("## Empty")));
    }

    @Test
    void rejectedMqttPageDoesNotFallBackToUnrelatedDefinitionPagesOrQuestionWords() {
        WikiPage stale = page(1, "entity", "entity-mqtt", "MQTT", "# MQTT\nMQTT is a messaging protocol.");
        WikiPage warn = page(2, "entity", "entity-mqtt-warn", "MQTT", "# MQTT\nMQTT body."); warn.setQuality("warn");
        WikiPage profile = page(3, "entity", "entity-profile", "profile", "# profile 关系\nprofile 是什么？配置的关系以及使用方式是什么，how to use a profile.");
        WikiPage spring = page(4, "entity", "entity-spring", "Java Spring Boot 是什么", "# Java Spring Boot 是什么\nSpring Boot 是什么以及如何使用？It is what you use to build an application.");
        when(pages.selectList(any())).thenReturn(List.of(stale, warn, profile, spring));
        when(wiki.retrievalFreshness(anyList())).thenReturn(Map.of("entity-mqtt", false, "entity-profile", true, "entity-spring", true));
        for (String query : List.of("MQTT 是什么？", "What is MQTT?", "Please explain MQTT", "How to use MQTT in an application?")) {
            assertTrue(service.search(query, 4).isEmpty(), query + " must require MQTT, not its definition/how-to framing");
        }
    }

    @Test
    void definitionQuestionCannotUseAnIncidentalMqttBodyMentionButRelationshipAndKeywordQueriesStillCan() {
        WikiPage stale = page(1, "entity", "entity-mqtt", "MQTT", "# MQTT\nMQTT 是轻量消息协议。");
        WikiPage profile = page(2, "entity", "entity-profile", "profile", "# 关系\n配置文件需包含依赖项定义，如Java环境或MQTT协议 [[MQTT协议]] 连接参数。[笔记#1]");
        when(pages.selectList(any())).thenReturn(List.of(stale, profile));
        when(wiki.retrievalFreshness(anyList())).thenReturn(Map.of("entity-mqtt", false, "entity-profile", true));
        for (String query : List.of("MQTT 是什么？", "什么是 MQTT？", "MQTT 指什么？", "MQTT 是啥？", "MQTT 什么意思？", "What is MQTT?", "What are MQTT?", "Define MQTT", "Please explain MQTT")) {
            assertTrue(service.search(query, 4).isEmpty(), query + " must not use an incidental profile connection-parameter mention as a definition guide");
        }
        for (String query : List.of("MQTT", "MQTT 和 profile 的关系是什么？", "Explain the relationship between MQTT and profile", "Compare MQTT and profile")) {
            assertEquals(List.of(2L), service.search(query, 4).stream().map(WikiRetrievalService.Section::pageId).toList(), query);
        }
    }

    @Test
    void definitionCanUseARelevantCategorySectionHeadingOrAnEntityAlias() {
        WikiPage category = page(1, "category", "cat-1", "通讯协议", "# MQTT\nMQTT 是面向消息的发布订阅协议。[笔记#1]\n# profile连接参数\n配置文件包含 MQTT 连接参数。[笔记#1]");
        WikiPage alias = page(2, "entity", "entity-messaging", "消息协议", "<!-- entity-aliases: MQTT -->\n# 定义\n这是一种发布订阅通信协议。[笔记#1]");
        when(pages.selectList(any())).thenReturn(List.of(category, alias));
        List<WikiRetrievalService.Section> hits = service.search("MQTT 是什么？", 4);
        assertEquals(Set.of(1L, 2L), new HashSet<>(hits.stream().map(WikiRetrievalService.Section::pageId).toList()));
        assertEquals(List.of("section-1"), hits.stream().filter(s -> s.pageId() == 1).map(WikiRetrievalService.Section::sectionKey).toList());
        assertTrue(hits.stream().noneMatch(s -> s.heading().contains("profile")));
    }

    @Test
    void framingRemovalKeepsRealChineseSubjectsIncludingKnowledgeBaseAndTwoCharacterTopics() {
        WikiPage queue = page(1, "entity", "entity-queue", "消息队列", "# 消息队列\n消息队列通过消息解耦应用，支持异步通信。");
        WikiPage reflection = page(2, "entity", "entity-reflection", "反射", "# 反射\n反射可以在运行时查看类型并访问成员。");
        WikiPage knowledge = page(3, "entity", "entity-knowledge", "知识库", "# 知识库\n知识库按主题组织资料，并保留来源。");
        WikiPage unrelated = page(4, "entity", "entity-spring", "Spring Boot 是什么", "# Spring Boot 是什么\nSpring Boot 如何工作以及是什么。");
        when(pages.selectList(any())).thenReturn(List.of(unrelated, queue, reflection, knowledge));
        for (String query : List.of("消息队列是什么", "什么是消息队列", "消息队列的工作原理是什么")) {
            assertEquals(List.of(1L), service.search(query, 4).stream().map(WikiRetrievalService.Section::pageId).toList(), query);
        }
        assertEquals(List.of(2L), service.search("反射如何工作", 4).stream().map(WikiRetrievalService.Section::pageId).toList());
        assertEquals(List.of(3L), service.search("知识库是什么", 4).stream().map(WikiRetrievalService.Section::pageId).toList());
        assertTrue(service.search("是什么？如何？Please explain", 4).isEmpty(), "framing without a subject has no candidates");
    }

    @Test
    void factualAndRelationshipQueriesExcludeGapSectionsAndTheirDescendantsButExplicitGapQueriesAllowThem() {
        WikiPage rewind = page(1, "entity", "entity-agentrewind", "AgentRewind", """
                # 概览
                AgentRewind 恢复检查点并回滚状态。[资料#5]
                # 待补充
                AgentRewind 的回滚状态细节尚待补充。[资料#5]
                ## 调用步骤
                AgentRewind 的调用步骤没有完整覆盖。[资料#5]
                # 知识缺口
                AgentRewind 和 ReAct 的关系还缺核验资料。[资料#5]
                # 待完善
                AgentRewind 的实现需要更多来源。[资料#5]
                """);
        when(pages.selectList(any())).thenReturn(List.of(rewind));
        for (String query : List.of("AgentRewind 如何回滚状态？", "AgentRewind 和 ReAct 有什么关系？")) {
            List<WikiRetrievalService.Section> hits = service.search(query, 4);
            assertEquals(List.of("section-1"), hits.stream().map(WikiRetrievalService.Section::sectionKey).toList(), query);
        }
        assertTrue(service.search("AgentRewind 有哪些知识缺口需要补充", 4).stream()
                .anyMatch(s -> s.heading().contains("知识缺口") || s.heading().contains("待补充") || s.heading().contains("待完善")));
        assertTrue(service.search("AgentRewind knowledge gaps", 4).stream()
                .anyMatch(s -> s.heading().contains("待补充") || s.heading().contains("知识缺口") || s.heading().contains("待完善")));
    }

    @Test
    void explicitEnglishSubjectMustMatchAWholeIdentifierRatherThanAnUnrelatedSubstring() {
        WikiPage irrelevant = page(1, "entity", "entity-cargo", "Cargo", "# Cargo\nCargo is ongoing package management.");
        WikiPage go = page(2, "entity", "entity-go", "Go", "# Go\nGo uses goroutines.");
        when(pages.selectList(any())).thenReturn(List.of(irrelevant, go));
        assertEquals(List.of(2L), service.search("What is Go?", 4).stream().map(WikiRetrievalService.Section::pageId).toList());
    }

    @Test
    void searchKeepsTwoSectionsPerPageAndRoomForOtherPages() {
        WikiPage a = page(1, "category", "cat-1", "ReAct", "# First\nReAct one.\n# Second\nReAct two.\n# Third\nReAct three.");
        WikiPage b = page(2, "tag", "tag-2", "ReAct", "# Other\nReAct context.");
        when(pages.selectList(any())).thenReturn(List.of(a, b));
        List<WikiRetrievalService.Section> hits = service.search("ReAct", 10);
        assertEquals(3, hits.size());
        assertEquals(Set.of(1L, 2L), new HashSet<>(hits.subList(0, 2).stream().map(WikiRetrievalService.Section::pageId).toList()));
        assertEquals(2, hits.stream().filter(s -> s.pageId() == 1).count());
    }

    @Test
    void noHeadingPageUsesSectionZeroAndExactReadPreservesCompleteCitation() {
        String body = "ReAct overview and complete reference [笔记#12].";
        WikiPage page = page(12, "entity", "entity-react", "ReAct", body);
        when(pages.selectOne(any())).thenReturn(page);
        Map<String, Object> atBoundary = service.readPage(page.getTopicKey(), "section-0", body.length());
        assertEquals(true, atBoundary.get("ok"));
        assertEquals(body, rows(atBoundary, "sections").getFirst().get("text"));
        assertEquals(body.length(), atBoundary.get("chars"));
        Map<String, Object> tooSmall = service.readPage(page.getTopicKey(), "section-0", body.length() - 1);
        assertEquals(false, tooSmall.get("ok"));
        assertTrue(rows(tooSmall, "sections").isEmpty());
        assertEquals(List.of("note:12"), rows(tooSmall, "omittedSections").getFirst().get("sourceRefs"));
        assertEquals("", rows(tooSmall, "omittedSections").getFirst().get("text"));
        assertEquals(0, tooSmall.get("chars"));
    }

    @Test
    void readBudgetClampsLargeInputsSkipsWholeSectionsAndBoundsMetadata() {
        StringBuilder body = new StringBuilder("# Huge\n").append("ReAct ".repeat(800)).append("[笔记#1]\n");
        for (int i = 0; i < 70; i++) body.append("# Small ").append(i).append("\nReAct [笔记#2].\n");
        WikiPage page = page(1, "entity", "entity-react", "ReAct", body.toString());
        when(pages.selectOne(any())).thenReturn(page);
        Map<String, Object> result = service.readPage(page.getTopicKey(), null, Integer.MAX_VALUE);
        assertEquals(4000, result.get("budget"));
        assertTrue((Integer) result.get("chars") <= 4000);
        assertEquals(24, rows(result, "sections").size());
        assertEquals(24, rows(result, "availableSections").size());
        assertTrue(rows(result, "omittedSections").size() <= 24);
        assertEquals(true, result.get("metadataTruncated"));
        assertEquals(false, result.get("complete"));
        assertEquals("", rows(result, "omittedSections").getFirst().get("text"));
        assertEquals(List.of("note:1"), rows(result, "omittedSections").getFirst().get("sourceRefs"));
        assertEquals("complete_section_text_chars_including_separators", result.get("budgetScope"));
    }

    @Test
    void generatedGuideFlagsAndOversizeSearchTextAreExplicit() {
        WikiPage page = page(1, "entity", "entity-react", "ReAct", "# ReAct\n" + "ReAct body ".repeat(150) + "[笔记#2]");
        when(pages.selectList(any())).thenReturn(List.of(page));
        Map<String, Object> result = service.searchView("ReAct", 4);
        assertEquals(true, result.get("ok"));
        assertEquals(true, result.get("generatedGuide"));
        assertEquals(false, result.get("factVerified"));
        assertEquals(true, result.get("requires_source_check"));
        assertEquals("unverified_generated_guide", result.get("verificationStatus"));
        Map<String, Object> item = rows(result, "items").getFirst();
        assertEquals("", item.get("text"));
        assertEquals(true, item.get("textOmitted"));
        assertEquals(List.of("note:2"), item.get("sourceRefs"));
        assertEquals("section-1", item.get("sectionKey"));
    }

    @Test
    void readRejectsUnknownSectionWarnMissingPageAndReadFailure() {
        WikiPage page = page(1, "entity", "entity-react", "ReAct", "# ReAct\nbody");
        when(pages.selectOne(any())).thenReturn(page);
        assertEquals(false, service.readPage(page.getTopicKey(), "section-999", 2000).get("ok"));
        page.setQuality("warn");
        assertEquals(false, service.readPage(page.getTopicKey(), null, 2000).get("ok"));
        when(pages.selectOne(any())).thenReturn(null);
        assertEquals(false, service.readPage(page.getTopicKey(), null, 2000).get("ok"));
        when(pages.selectOne(any())).thenThrow(new IllegalStateException("offline"));
        assertEquals(false, service.readPage(page.getTopicKey(), null, 2000).get("ok"));
    }

    @Test
    void sourcePassagesResolveCurrentRawBlocksAndKeepMultiSourceContextBeforeExtraChunks() {
        WikiRetrievalService.Section section = section("ReAct", List.of("note:1", "quick_ref:2", "file:3"));
        String note = "# Unrelated\nSQL database.\n# Loop\nReAct loops with tools.\n# Planning\nReAct plans actions.\n# Observation\nReAct observes output.";
        when(sources.currentSources("note", List.of(1L))).thenReturn(List.of(raw(1, "Source", note)));
        when(sources.currentSources("quick_ref", List.of(2L))).thenReturn(List.of(raw(2, "Source", "ReAct quick reference.")));
        when(sources.currentSources("file", List.of(3L))).thenReturn(List.of(raw(3, "Source", "ReAct paper explains the loop.")));
        List<RetrievalHit> hits = service.sourcePassages("ReAct", List.of(section), 10);
        assertEquals(4, hits.size());
        assertEquals(Set.of("note:1", "quick_ref:2", "file:3"), new HashSet<>(hits.subList(0, 3).stream().map(RetrievalHit::sourceRef).toList()));
        assertEquals(2, hits.stream().filter(h -> h.sourceRef().equals("note:1")).count());
        List<TextChunker.Chunk> chunks = TextChunker.splitWithHeadings(note);
        for (RetrievalHit hit : hits) {
            assertEquals(List.of("wiki"), hit.channels());
            assertTrue(hit.graphRelations().isEmpty());
            assertFalse(hit.text().contains("GENERATED"));
            if (hit.sourceType().equals("note")) {
                assertNotEquals(0, hit.seq());
                assertEquals(chunks.get(hit.seq()).text(), hit.text());
                assertEquals("note:1:" + hit.seq(), hit.key());
            }
        }
    }

    @Test
    void wikiAliasQueryCanLocateCanonicalPageTitleInCurrentSourceWithoutAHeadingMatch() {
        WikiRetrievalService.Section section = new WikiRetrievalService.Section(1L, "entity-react", "ReAct", "section-1",
                "定义", "Reasoning and Acting 的模型生成导览", List.of("note:1"), List.of(), 1);
        when(sources.currentSources("note", List.of(1L))).thenReturn(List.of(raw(1, "原文", "ReAct 通过推理、行动和观察推进任务。")));
        List<RetrievalHit> hits = service.sourcePassages("Reasoning and Acting", List.of(section), 4);
        assertEquals(1, hits.size());
        assertEquals("ReAct 通过推理、行动和观察推进任务。", hits.getFirst().text());
        assertEquals(List.of("wiki"), hits.getFirst().channels());
    }

    @Test
    void genericWikiHeadingAloneCannotAdmitAnUnrelatedCurrentSourcePassage() {
        WikiRetrievalService.Section section = new WikiRetrievalService.Section(1L, "entity-mqtt", "MQTT", "section-1",
                "定义", "MQTT 的模型生成导览", List.of("note:1"), List.of(), 1);
        when(sources.currentSources("note", List.of(1L))).thenReturn(List.of(raw(1, "Java", "# 定义\nJava 是一种编程语言。")));
        assertTrue(service.sourcePassages("MQTT 是什么", List.of(section), 4).isEmpty());
    }

    @Test
    void deletedEmptyInvalidAndUnrequestedSourcesDoNotBecomeEvidenceAndOtherTypesSurviveFailure() {
        WikiRetrievalService.Section section = section("ReAct", List.of("note:1", "note:2", "file:3", "note:0", "bad:9"));
        when(sources.currentSources("note", List.of(1L, 2L))).thenReturn(Arrays.asList(raw(1, "Source", ""), raw(99, "Source", "ReAct unrelated returned row"), null));
        when(sources.currentSources("file", List.of(3L))).thenReturn(List.of(raw(3, "Source", "ReAct current raw paper.")));
        assertEquals(List.of("file:3"), service.sourcePassages("ReAct", List.of(section), 10).stream().map(RetrievalHit::sourceRef).toList());
        when(sources.currentSources("note", List.of(1L, 2L))).thenThrow(new IllegalStateException("offline"));
        assertEquals(List.of("file:3"), service.sourcePassages("ReAct", List.of(section), 10).stream().map(RetrievalHit::sourceRef).toList());
    }

    @Test
    void sourceResolutionDoesNotQueryWithoutUsableRefsAndCapsDistinctDependencies() {
        assertTrue(service.sourcePassages("ReAct", List.of(section("ReAct", List.of("note:0", "file:bogus"))), 10).isEmpty());
        assertTrue(service.sourcePassages("", List.of(section("ReAct", List.of("note:1"))), 10).isEmpty());
        verifyNoInteractions(sources);
        List<String> refs = new ArrayList<>();
        for (long id = 1; id <= 40; id++) refs.add("note:" + id);
        service.sourcePassages("ReAct", List.of(section("ReAct", refs)), 40);
        verify(sources).currentSources(eq("note"), argThat(ids -> ids.size() == 24 && ids.getLast() == 24L));
    }

    private static WikiPage page(long id, String type, String key, String title, String body) {
        WikiPage page = new WikiPage();
        page.setId(id); page.setTopicType(type); page.setTopicKey(key); page.setTitle(title);
        page.setQuality("ok"); page.setContentMd(body);
        return page;
    }

    private static WikiRetrievalService.Section section(String heading, List<String> refs) {
        return new WikiRetrievalService.Section(1L, "entity-react", "ReAct", "section-1", heading,
                "GENERATED guide statements are not raw evidence", refs, List.of(), 1);
    }

    private static Map<String, Object> raw(long id, String title, String content) {
        return Map.of("id", id, "title", title, "content", content, "category", "current category");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> result, String key) {
        return (List<Map<String, Object>>) result.get(key);
    }
}
