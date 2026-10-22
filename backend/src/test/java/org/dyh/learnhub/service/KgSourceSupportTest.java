package org.dyh.learnhub.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dyh.learnhub.ai.EmbeddingClient;
import org.dyh.learnhub.entity.KgNode;
import org.dyh.learnhub.entity.KgRelation;
import org.dyh.learnhub.mapper.KgNodeMapper;
import org.dyh.learnhub.mapper.KgRelationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KgSourceSupportTest {
    private final KgNodeMapper nodes = mock(KgNodeMapper.class);
    private final KgRelationMapper relations = mock(KgRelationMapper.class);
    private final KgGraphService graph = new KgGraphService(nodes, relations, mock(EmbeddingClient.class));
    private final List<Map<String, Object>> updates = new ArrayList<>();

    @BeforeEach void init() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "synthetic"), KgRelation.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "synthetic"), KgNode.class);
        when(relations.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(relations.update(isNull(), any(Wrapper.class))).thenAnswer(call -> {
            LambdaUpdateWrapper<KgRelation> wrapper = call.getArgument(1);
            wrapper.getSqlSegment(); wrapper.getSqlSet();
            updates.add(new HashMap<>(wrapper.getParamNameValuePairs())); return 1;
        });
    }
    private KgRelation row(String origin, String sources, String evidence) {
        KgRelation relation = new KgRelation(); relation.setId(1L); relation.setOrigin(origin);
        relation.setSources(sources); relation.setEvidence(evidence); relation.setWeight(0.9);
        relation.setHeadId(EntityLinker.id("Alpha")); relation.setRelation("part_of"); relation.setTailId(EntityLinker.id("Beta"));
        return relation;
    }

    @Test void removingOneDoesNotMatchTenAndPreservesManualAndDerivedFacts() {
        when(relations.selectList(any(Wrapper.class))).thenReturn(List.of(
                row("llm", "笔记#10", "合成原始证据。"), row("manual", "笔记#1", "手工合成事实。"),
                row("derived", "笔记#1", "规则推导的合成事实。")));
        assertTrue(graph.replaceSourceSupport("笔记#1", List.of(), Map.of("笔记#10", "合成原始证据。"), null).isEmpty());
        verify(relations, never()).delete(any(Wrapper.class));
        verify(relations, never()).update(isNull(), any(Wrapper.class));
        verifyNoInteractions(nodes);
    }

    @Test void lastAutomaticSupportIsDeletedWithoutDeletingAnyNodes() {
        when(relations.selectList(any(Wrapper.class))).thenReturn(List.of(row("llm", "笔记#1", "旧合成证据。")));
        graph.replaceSourceSupport("笔记#1", List.of(), Map.of(), null);
        verify(relations).delete(any(Wrapper.class)); verifyNoInteractions(nodes);
    }

    @Test void sharedQuoteIsClearedAndRemainingSourceQueuedIfOnlyRemovedSourceSaidIt() {
        when(relations.selectList(any(Wrapper.class))).thenReturn(List.of(row("llm", "笔记#1|笔记#10", "只有已删除来源说过的完整合成证据。")));
        Set<String> refresh = graph.replaceSourceSupport("笔记#1", List.of(),
                Map.of("笔记#10", "剩余来源有不同的合成表述。"), null);
        assertEquals(Set.of("笔记#10"), refresh);
        assertEquals(1, updates.size());
        assertTrue(updates.getFirst().containsValue(null), "旧证据必须显式 SET NULL");
        assertTrue(updates.getFirst().containsValue(0.4));
        assertTrue(updates.getFirst().containsValue("笔记#10"));
        verify(relations, never()).delete(any(Wrapper.class));
    }

    @Test void sharedQuoteIsRetainedWhenRemainingSourceStillContainsTheEntireQuote() {
        String evidence = "Alpha 是 Beta 的组成部分。";
        when(relations.selectList(any(Wrapper.class))).thenReturn(List.of(row("llm", "笔记#1|资料#2", evidence)));
        assertTrue(graph.replaceSourceSupport("笔记#1", List.of(), Map.of("资料#2", "合成前文。" + evidence), null).isEmpty());
        assertTrue(updates.getFirst().containsValue(evidence));
        assertTrue(updates.getFirst().containsValue(0.9));
    }

    @Test void aMatchingManualUniqueKeyIsNeverUpsertedByAutomaticExtraction() {
        KgRelation manual = row("manual", "人工", "人工维护的合成事实。");
        when(relations.selectOne(any(Wrapper.class))).thenReturn(manual);
        String evidence = "Alpha 是 Beta 的组成部分。";
        graph.replaceSourceSupport("笔记#1", List.of(new TripleExtractor.Triple("Alpha", "part_of", "Beta", evidence, "资料#999", 0.9)),
                Map.of("笔记#1", evidence), "synthetic-model");
        verify(relations, never()).insert(any(KgRelation.class));
        verify(relations, never()).update(isNull(), any(Wrapper.class));
        verifyNoInteractions(nodes);
        assertEquals("人工", manual.getSources());
    }

    @Test void newAutomaticFactUsesCanonicalSourceAndRejectsInventedEvidence() {
        String evidence = "Alpha 是 Beta 的组成部分。";
        graph.replaceSourceSupport("资料#2", List.of(
                new TripleExtractor.Triple("Alpha", "part_of", "Beta", evidence, "笔记#999", 0.9),
                new TripleExtractor.Triple("Gamma", "part_of", "Beta", "原文不存在的合成证据。", "资料#2", 0.9)),
                Map.of("资料#2", evidence), "synthetic-model");
        var inserted = org.mockito.ArgumentCaptor.forClass(KgRelation.class);
        verify(relations).insert(inserted.capture());
        assertEquals("资料#2", inserted.getValue().getSources());
        assertEquals("llm", inserted.getValue().getOrigin());
        assertEquals(evidence, inserted.getValue().getEvidence());
        verify(nodes, never()).delete(any(Wrapper.class));
    }
}
