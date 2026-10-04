package org.dyh.learnhub.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.dyh.learnhub.entity.FileInfo;
import org.dyh.learnhub.mapper.FileInfoMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ResourceLock("java.lang.System.properties")
class PdfFormulaCropTest {
    @TempDir Path temp;
    private String originalDir;
    private FileStorageService service;

    @BeforeEach
    void setup() throws Exception {
        originalDir = System.getProperty("user.dir");
        System.setProperty("user.dir", temp.toString());
        Path uploads = Files.createDirectories(temp.resolve("uploads"));
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.LETTER));
            doc.save(uploads.resolve("source.pdf").toFile());
        }
        FileInfo info = new FileInfo();
        info.setId(1L);
        info.setExt("pdf");
        info.setStoreName("source.pdf");
        FileInfoMapper mapper = mock(FileInfoMapper.class);
        when(mapper.selectById(1L)).thenReturn(info);
        // 最后一个参数是 KbChunkMapper（改名时同步知识块标题用），本测试不涉及 → null
        service = new FileStorageService(mapper, null, null, new PdfLayoutExtractor(), null, null, null, null, null);
    }

    @AfterEach
    void restore() {
        System.setProperty("user.dir", originalDir);
    }

    @Test
    void subPointCoordinatesDoNotReturnTheWrongCachedCrop() throws Exception {
        byte[] first = service.pageImageRect(1L, 1, 180.2, 280.2, 220.2, 312.2);
        byte[] second = service.pageImageRect(1L, 1, 180.49, 280.2, 220.2, 312.2);
        assertEquals(84, ImageIO.read(new ByteArrayInputStream(first)).getWidth());
        assertEquals(83, ImageIO.read(new ByteArrayInputStream(second)).getWidth());
        assertArrayEquals(second, service.pageImageRect(1L, 1, 180.49, 280.2, 220.2, 312.2));
    }

    @Test
    void clippingAtPageEdgeUsesTheIntersectionRatherThanShiftingTheRegion() throws Exception {
        byte[] png = service.pageImageRect(1L, 1, -5, 0, 20, 20);
        assertEquals(42, ImageIO.read(new ByteArrayInputStream(png)).getWidth());
        assertThrows(IllegalArgumentException.class, () -> service.pageImageRect(1L, 1, 800, 800, 820, 820));
        assertThrows(IllegalArgumentException.class, () -> service.pageImageRect(1L, 2, 0, 0, 20, 20));
        assertThrows(IllegalArgumentException.class, () -> service.pageImageRect(1L, 1, Double.NaN, 0, 20, 20));
    }
}
