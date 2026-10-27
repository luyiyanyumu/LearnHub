package org.dyh.learnhub.service;

import org.dyh.learnhub.ai.EmbeddingClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeChunkingServiceTest {
    private final EmbeddingClient embedder = mock(EmbeddingClient.class);
    private final KnowledgeChunkingService service = new KnowledgeChunkingService(embedder);
    private final EmbeddingClient.Snapshot target = EmbeddingClient.Snapshot.of("synthetic", "http://example.test", "ollama", null);
    private final KnowledgeBaseSettings.Config semantic = KnowledgeBaseSettings.validatePatch(KnowledgeBaseSettings.defaults(),
            Map.of("chunkStrategy", "semantic_long", "semanticMinChars", 2000));
    private final String text = "合成缓存策略和过期处理，说明数据库一致性与并发查询的实现。".repeat(120);

    @Test void defaultShortDocumentsNotesAndCardsNeverCallBoundaryModel() {
        service.split("file", text, target, KnowledgeBaseSettings.defaults());
        service.split("file", "短资料", target, semantic);
        service.split("note", text, target, semantic);
        service.split("quick_ref", text, target, semantic);
        verifyNoInteractions(embedder);
    }

    @Test void longFilesCalculateBoundariesOnlyFromProseAndKeepProtectedBlock() {
        String code = "```java\n" + "// 保留空行和代码\n\n".repeat(100) + "```";
        when(embedder.embedAll(eq(target), anyList(), eq(16), eq(6000))).thenAnswer(inv ->
                ((List<?>) inv.getArgument(1)).stream().map(value -> new float[]{1, 0}).toList());
        var result = service.split("file", text + "\n\n" + code, target, semantic);
        assertTrue(result.stream().anyMatch(chunk -> chunk.text().equals(code)));
        verify(embedder).embedAll(eq(target), argThat(values -> values.stream().noneMatch(value -> value.contains("```"))), eq(16), eq(6000));
    }

    @Test void boundaryFailureAbortsPreparation() {
        when(embedder.embedAll(eq(target), anyList(), anyInt(), anyInt())).thenThrow(new IllegalStateException("合成断连"));
        assertThrows(IllegalStateException.class, () -> service.split("file", text, target, semantic));
    }
}
