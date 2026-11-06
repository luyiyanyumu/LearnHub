package org.dyh.learnhub.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class DocumentPageCleanupTest {
    private DocumentIngestionService.Block block(String id, String text, int page, double top) {
        return new DocumentIngestionService.Block(id,"para",text,text,page,new double[]{20,top,400,top+20},
                600,800,null,"accepted",true,0,text.length());
    }

    @Test void repeatedMarginsAreExcludedButIdenticalBodyDefinitionsAndCoordinatesSurvive() {
        String repeatedBody = "A synthetic definition legitimately repeated in the body.";
        var pages = IntStream.rangeClosed(1,3).mapToObj(page -> new DocumentIngestionService.Page(page,600,800,"pt","text",List.of(
                block("p"+page+"-header","Synthetic report page "+page,page,20),
                block("p"+page+"-body",repeatedBody,page,300),
                block("p"+page+"-footer",String.valueOf(page),page,760)))).toList();
        var result = DocumentPageCleanup.clean(pages);
        assertEquals(6,result.removed());
        assertFalse(result.text().contains("Synthetic report"));
        assertEquals(3,result.pages().size());
        for (var page : result.pages()) {
            assertFalse(page.blocks().getFirst().indexed());
            assertEquals("page_furniture",page.blocks().getFirst().quality());
            var body = page.blocks().get(1);
            assertTrue(body.indexed());
            assertEquals(repeatedBody,result.text().substring(body.start(),body.end()));
            assertArrayEquals(new double[]{20,300,400,320},body.rect());
        }
    }

    @Test void twoPagesAndMissingGeometryNeverJustifyContentDeletion() {
        var two = List.of(new DocumentIngestionService.Page(1,600,800,"pt","ocr",List.of(block("a","Repeated title",1,20))),
                new DocumentIngestionService.Page(2,600,800,"pt","ocr",List.of(block("b","Repeated title",2,20))));
        assertEquals(0,DocumentPageCleanup.clean(two).removed());
        assertEquals(2,DocumentPageCleanup.clean(two).pages().stream().filter(p -> p.blocks().getFirst().indexed()).count());
    }

    @Test void cleanupPreservesMarkdownPresentationAndPrintedPageLabels() {
        var presentation = new DocumentIngestionService.Presentation("synthetic-line", "inline", 120.0, 40.0, "left", "pdf-figure:1#0");
        var body = new DocumentIngestionService.Block("body", "para", "Synthetic raw", "Synthetic body", 1,
                new double[]{20, 300, 400, 320}, 600, 800, null, "accepted", true, 0, 14,
                0, null, null, null, null, "[Synthetic body](#citation-1)", 35, 65, presentation);
        var result = DocumentPageCleanup.clean(List.of(new DocumentIngestionService.Page(1, 600, 800, "pt", "text", List.of(body), "iv")));
        var retained = result.pages().getFirst().blocks().getFirst();
        assertEquals("iv", result.pages().getFirst().label());
        assertSame(presentation, retained.presentation());
        assertEquals(body.markdown(), retained.markdown());
        assertEquals(body.markdownStart(), retained.markdownStart());
        assertEquals(body.markdownEnd(), retained.markdownEnd());
        assertEquals("Synthetic body", result.text().substring(retained.start(), retained.end()));
    }
}
