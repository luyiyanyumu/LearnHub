package org.dyh.learnhub.service;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class DocumentTextServiceTest {
    private static final int PADDING_MIB = 21;
    private static final long OLD_SIZE_LIMIT = 20L * 1024 * 1024;
    @TempDir Path temp;
    private final DocumentTextService service = new DocumentTextService();

    @Test void pdfLargerThanFormerSizeLimitStillExtractsItsText() throws Exception {
        Path path = temp.resolve("large-synthetic.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(40, 700);
                content.showText("Synthetic PDF text above the former size limit.");
                content.endText();
            }
            // A referenced, uncompressed stream makes the fixture genuinely larger than 20 MiB.
            var padding = document.getDocument().createCOSStream();
            try (var output = padding.createOutputStream()) {
                byte[] chunk = new byte[1024 * 1024];
                for (int i = 0; i < PADDING_MIB; i++) output.write(chunk);
            }
            document.getDocumentCatalog().getCOSObject().setItem(COSName.getPDFName("SyntheticPadding"), padding);
            document.save(path.toFile());
        }
        assertTrue(Files.size(path) > OLD_SIZE_LIMIT);

        var extracted = service.extract(path, "pdf", Files.size(path));

        assertEquals(DocumentTextService.STATUS_OK, extracted.status(), extracted.error());
        assertTrue(extracted.text().contains("Synthetic PDF text above the former size limit."));
        assertNull(extracted.error());
    }

    @Test void wordLargerThanFormerSizeLimitAlsoPassesItsFormatSpecificExtractor() throws Exception {
        Path path = temp.resolve("large-synthetic.docx");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.setLevel(Deflater.NO_COMPRESSION);
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(("<w:document xmlns:w=\"" + OmmlLatexConverter.W + "\"><w:body>"
                    + "<w:p><w:r><w:t>Synthetic Word text above the former size limit.</w:t></w:r></w:p>"
                    + "</w:body></w:document>").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/media/synthetic-padding.bin"));
            byte[] chunk = new byte[1024 * 1024];
            for (int i = 0; i < PADDING_MIB; i++) zip.write(chunk);
            zip.closeEntry();
        }
        assertTrue(Files.size(path) > OLD_SIZE_LIMIT);

        var extracted = service.extract(path, "docx", Files.size(path));

        assertEquals(DocumentTextService.STATUS_OK, extracted.status(), extracted.error());
        assertEquals("Synthetic Word text above the former size limit.", extracted.text());
    }

    @Test void invalidLargePdfReportsExtractionFailureInsteadOfBeingSkipped() throws Exception {
        Path path = temp.resolve("invalid-synthetic.pdf");
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap("This is not a PDF.".getBytes(StandardCharsets.UTF_8)));
            channel.position(OLD_SIZE_LIMIT);
            channel.write(ByteBuffer.wrap(new byte[]{1}));
        }

        var extracted = service.extract(path, "pdf", Files.size(path));

        assertEquals(DocumentTextService.STATUS_FAILED, extracted.status());
        assertNull(extracted.text());
        assertNotNull(extracted.error());
    }
}
