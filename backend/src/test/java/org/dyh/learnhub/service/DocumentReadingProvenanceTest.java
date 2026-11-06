package org.dyh.learnhub.service;

import org.dyh.learnhub.entity.FileInfo;
import org.dyh.learnhub.mapper.FileInfoMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import javax.imageio.IIOImage;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentReadingProvenanceTest {
    @TempDir Path work;
    String originalDirectory;
    FileInfo info;
    FileStorageService files;
    Path source;

    @BeforeEach void setup() throws Exception {
        originalDirectory = System.getProperty("user.dir"); System.setProperty("user.dir",work.toString());
        Files.createDirectories(work.resolve("uploads"));
        info = new FileInfo(); info.setId(61L); info.setTextStatus("ok"); info.setTextContent("Accepted synthetic evidence.");
        var mapper = mock(FileInfoMapper.class); when(mapper.selectById(61L)).thenReturn(info);
        files = new FileStorageService(mapper,null,null,new PdfLayoutExtractor(),null,null,null,null,null,mock(FolderService.class));
    }
    @AfterEach void restore() { System.setProperty("user.dir",originalDirectory); }

    private String save(List<DocumentIngestionService.Page> pages) throws Exception {
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
        new DocumentExtractionStore().save(61L,source,new DocumentIngestionService.Extraction("ok",info.getTextContent(),
                info.getTextContent().length(),null,sha,"document-ingestion-v1",pages,
                new DocumentIngestionService.QualityReport(pages.size(),pages.size(),0,0,null,List.of(),false,false)));
        return sha;
    }

    @Test void confirmedFormulaCacheStillUpdatesReaderWithoutChangingIndexedText() throws Exception {
        info.setExt("pdf"); info.setStoreName("synthetic.pdf"); source = work.resolve("uploads/synthetic.pdf");
        Files.writeString(source,"synthetic-immutable-pdf-fixture");
        double[] rect = {20,30,180,80};
        var block = new DocumentIngestionService.Block("p1-b1","formula","fraction candidate","fraction candidate",1,
                rect,600,800,null,"uncertain_formula",false,-1,-1,0,null,"a / b","partial","请核对原图");
        save(List.of(new DocumentIngestionService.Page(1,600,800,"pt","text",List.of(block))));
        Path cached = files.formulaRecognitionCachePath(61L,files.formulaRecognitionKey(61L,1,rect));
        Files.createDirectories(cached.getParent());
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(cached.toFile(),Map.of("latex","\\frac{a}{b}",
                "status","recognized","profile","synthetic-vision","model","fixture"));
        var page = (Map<?,?>)((List<?>)files.layoutOf(61L).get("pages")).getFirst();
        var shown = (Map<?,?>)((List<?>)page.get("blocks")).getFirst();
        assertEquals("recognized",shown.get("latexStatus")); assertEquals("\\frac{a}{b}",shown.get("latex"));
        assertEquals("Accepted synthetic evidence.",info.getTextContent(),"reader confirmation must not silently rewrite the index");
    }

    @Test void multipageRasterPreviewUsesTheRequestedFrameAndRejectsStaleRevision() throws Exception {
        info.setExt("tiff"); info.setStoreName("synthetic.tiff"); source = work.resolve("uploads/synthetic.tiff");
        var writer = ImageIO.getImageWritersByFormatName("TIFF").next();
        try (var output = ImageIO.createImageOutputStream(source.toFile())) {
            writer.setOutput(output); writer.prepareWriteSequence(null);
            for (Color color : List.of(Color.RED,Color.BLUE)) {
                var image = new BufferedImage(60,80,BufferedImage.TYPE_INT_RGB);
                var graphics = image.createGraphics(); graphics.setColor(color); graphics.fillRect(0,0,60,80); graphics.dispose();
                writer.writeToSequence(new IIOImage(image,null,null),null); image.flush();
            }
            writer.endWriteSequence();
        } finally { writer.dispose(); }
        var block = new DocumentIngestionService.Block("p2-b1","para",info.getTextContent(),info.getTextContent(),2,
                new double[]{1,1,50,60},60,80,.99,"accepted",true,0,info.getTextContent().length());
        String revision = save(List.of(new DocumentIngestionService.Page(2,60,80,"px","ocr",List.of(block))));
        var bytes = files.extractionPageImage(61L,2,revision);
        var rendered = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        assertEquals(Color.BLUE.getRGB(),rendered.getRGB(20,20));
        assertThrows(IllegalArgumentException.class,() -> files.extractionPageImage(61L,3,revision));
        assertThrows(IllegalArgumentException.class,() -> files.extractionPageImage(61L,2,"a".repeat(64)));
    }
}
