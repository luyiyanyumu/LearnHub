package org.dyh.learnhub.service;

import org.dyh.learnhub.ai.EmbeddingClient;
import org.dyh.learnhub.entity.KgNode;
import org.dyh.learnhub.mapper.KgNodeMapper;
import org.dyh.learnhub.mapper.KgRelationMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KgEntityRecognitionTest {
    private KgGraphService service(String... names) {
        KgNodeMapper mapper = mock(KgNodeMapper.class);
        when(mapper.selectList(null)).thenReturn(java.util.Arrays.stream(names).map(name -> {
            KgNode node = new KgNode(); node.setId(name); node.setName(name); return node;
        }).toList());
        return new KgGraphService(mapper, mock(KgRelationMapper.class), mock(EmbeddingClient.class));
    }
    private List<String> names(KgGraphService graph, String text) {
        return graph.recognize(text, 12).stream().map(KgNode::getName).toList();
    }
    @Test void planningDoesNotRecallAnnAndContainsSeparateAsciiTerms() {
        assertEquals(List.of("Planning", "ReAct"), names(service("ReAct", "Planning", "ANN"), "ReAct 和 Planning 有什么关系？"));
        assertEquals(List.of("Planning", "ANN"), names(service("Planning", "ANN"), "Planning 和 ANN 有什么区别？"));
        assertTrue(names(service("RAG", "int"), "fragile integer").isEmpty());
    }
    @Test void parenthesesAndChineseBoundariesDoNotEraseExplicitEntities() {
        assertEquals(List.of("Planning", "ReAct"), names(service("ReAct", "Planning"), "ReAct（Planning）"));
        assertEquals(List.of("ANN"), names(service("ANN"), "如何理解ＡＮＮ索引？"));
    }
    @Test void longerNamesConsumeOnlyTheirOwnSpan() {
        assertEquals(List.of("DeepSeek Harness"), names(service("Harness", "DeepSeek Harness"), "DeepSeek Harness怎么用"));
        assertEquals(List.of("JavaScript", "Java"), names(service("Java", "JavaScript"), "JavaScript 与 Java"));
        assertEquals(List.of("JavaScript"), names(service("Java", "JavaScript"), "JavaScript"));
        assertEquals(List.of("Spring Boot"), names(service("Spring Boot"), "Spring-Boot如何配置"));
    }
}
