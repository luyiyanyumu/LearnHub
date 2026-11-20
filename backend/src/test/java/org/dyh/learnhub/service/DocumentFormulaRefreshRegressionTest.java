package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.dyh.learnhub.entity.FileInfo;
import org.dyh.learnhub.mapper.FileInfoMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.dyh.learnhub.service.DocumentIngestionService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentFormulaRefreshRegressionTest {
    private static final long FILE_ID = 84L;
    private static final String PRIMARY = "p1-b1";
    private static final String COMPANION = PRIMARY + "-vision-formula-1";
    private static final double[] REGION = {40, 45, 180, 80};

    @TempDir Path directory;
    private String originalDirectory;
    private FileInfo info;
    private FileStorageService files;
    private DocumentMediaProcessor media;
    private DocumentVisionClient vision;

    @BeforeEach void setup() throws Exception {
        originalDirectory = System.getProperty("user.dir");
        System.setProperty("user.dir", directory.toString());
        Path source = Files.createDirectories(directory.resolve("uploads")).resolve("synthetic.pdf");
        try (var pdf = new PDDocument()) {
            pdf.addPage(new PDPage(PDRectangle.LETTER));
            pdf.save(source.toFile());
        }
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
        var blocks = List.of(formula(PRIMARY, "x=2", "block"), formula(COMPANION, "y=3", "annotation"),
                new Block("p1-b2", "para", "Uncertain synthetic OCR", "Uncertain synthetic OCR", 1,
                        new double[]{40, 100, 180, 120}, 612, 792, .2, "low_confidence", false, -1, -1));
        var report = new QualityReport(1, 1, 0, 0, null, List.of(), false, true);
        var initial = DocumentMarkdownWriter.render(new Extraction("ok", "", 0, null, sha, PIPELINE_VERSION,
                List.of(new Page(1, 612, 792, "pt", "text", blocks)), report));
        new DocumentExtractionStore().save(FILE_ID, source, initial);

        info = new FileInfo();
        info.setId(FILE_ID);
        info.setExt("pdf");
        info.setStoreName("synthetic.pdf");
        info.setSize(Files.size(source));
        info.setTextStatus("ok");
        info.setTextContent(initial.text());
        var mapper = mock(FileInfoMapper.class);
        when(mapper.selectById(FILE_ID)).thenReturn(info);
        vision = mock(DocumentVisionClient.class);
        var layout = new PdfLayoutExtractor();
        media = new DocumentMediaProcessor(layout, mock(DocumentOcrClient.class), vision);
        files = new FileStorageService(mapper, null, new DocumentTextService(), layout, null, null,
                mock(ApplicationEventPublisher.class), null, null, mock(FolderService.class));
        files.setMediaProcessor(media);
    }

    @AfterEach void cleanup() {
        if (media != null) media.close();
        System.setProperty("user.dir", originalDirectory);
    }

    @Test void savedFormulasSharingACropKeepDistinctValuesInTheReaderAndAfterMediaRetry() {
        assertEquals(List.of("x=2", "y=3"), readerFormulas());
        files.processMedia(FILE_ID, false);
        assertSavedFormulas("x=2", "y=3");
        verifyNoInteractions(vision);
    }

    @Test void confirmedReaderCacheOnlyUpdatesThePrimaryFormulaWithoutAnotherRecognitionCall() throws Exception {
        Path cache = files.formulaRecognitionCachePath(FILE_ID, files.formulaRecognitionKey(FILE_ID, 1, REGION));
        Files.createDirectories(cache.getParent());
        new ObjectMapper().writeValue(cache.toFile(), Map.of("latex", "x=5", "status", "recognized",
                "profile", "synthetic", "model", "fixture"));

        assertEquals(List.of("x=5", "y=3"), readerFormulas());
        assertTrue(info.getTextContent().contains("x=2"), "opening the reader does not change indexed text");
        files.processMedia(FILE_ID, false);
        assertSavedFormulas("x=5", "y=3");
        assertFalse(info.getTextContent().contains("x=2"));
        verifyNoInteractions(vision);
    }

    private List<?> readerFormulas() {
        var page = (Map<?, ?>) ((List<?>) files.layoutOf(FILE_ID).get("pages")).getFirst();
        return ((List<?>) page.get("blocks")).stream().map(Map.class::cast)
                .filter(block -> "formula".equals(block.get("type"))).map(block -> block.get("latex")).toList();
    }

    private void assertSavedFormulas(String primary, String companion) {
        var saved = (Extraction) files.extractionOf(FILE_ID);
        var formulas = saved.pages().getFirst().blocks().stream().filter(block -> "formula".equals(block.type())).toList();
        assertEquals(List.of(PRIMARY, COMPANION), formulas.stream().map(Block::id).toList());
        assertEquals(List.of(primary, companion), formulas.stream().map(Block::latex).toList());
        assertTrue(formulas.stream().allMatch(Block::indexed));
        assertTrue(saved.text().contains(primary));
        assertTrue(saved.text().contains(companion));
        DocumentMarkdownWriterTest.assertOffsets(saved);
    }

    private static Block formula(String id, String latex, String placement) {
        return new Block(id, "formula", "", "$$\n" + latex + "\n$$", 1, REGION.clone(), 612, 792,
                null, "recognized", true, -1, -1, 0, null, latex, "recognized", "Synthetic accepted formula",
                null, -1, -1, new Presentation(null, placement, null, null, null, null));
    }
}
