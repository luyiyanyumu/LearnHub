package org.dyh.learnhub.controller;

import org.dyh.learnhub.service.WikiRetrievalService;
import org.dyh.learnhub.service.RagAnswerEvalService;
import org.dyh.learnhub.service.RetrievalContextService;
import org.dyh.learnhub.service.SettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WikiRetrievalControllerTest {
    @Mock WikiRetrievalService wiki;
    @Mock RetrievalContextService contexts;
    @Mock SettingsService settings;
    @Mock RagAnswerEvalService answerEval;
    @InjectMocks WikiController wikiController;
    @InjectMocks VectorController vectorController;

    @Test void wikiInspectionEndpointsReturnTheServicesGeneratedNavigationMetadata() {
        Map<String, Object> view = Map.of("generatedGuide", true, "factVerified", false,
                "items", List.of(Map.of("topicKey", "entity-react", "sectionKey", "section-1")));
        when(wiki.searchView("ReAct", 6)).thenReturn(view);
        when(wiki.readPage("entity-react", "section-1", 4000)).thenReturn(view);
        assertSame(view, wikiController.search("ReAct", 6).getData());
        assertSame(view, wikiController.read("entity-react", "section-1", 4000).getData());
    }

    @Test void explicitAblationFlagsDoNotReadOrMutateGlobalSettings() {
        when(contexts.build("q", 5, "vector", true, false)).thenReturn(
                new RetrievalContextService.Context(List.of("【Wiki 生成导览】模型整理内容"), List.of(),
                        List.of(Map.of("type", "wiki", "id", 9L, "generatedGuide", true))));
        var data = vectorController.context("q", 5, "vector", true, false).getData();
        assertTrue(String.valueOf(data.get("text")).contains("模型整理内容"));
        assertEquals("", data.get("groundingText"));
        assertEquals(List.of(), data.get("refs"));
        verifyNoInteractions(settings);
    }

    @Test void answerEvaluationForwardsExplicitFlagsAndSelectedQuestions() {
        when(answerEval.run("wiki arm", 5, 2, "fused", List.of(12L, 14L), true, false))
                .thenReturn(Map.of("wikiInject", true, "kgInject", false));
        var data = vectorController.answerEvalRun("wiki arm", 5, 2, "fused", "12,14", true, false).getData();
        assertEquals(true, data.get("wikiInject"));
        assertEquals(false, data.get("kgInject"));
        verifyNoInteractions(settings);
    }
}
