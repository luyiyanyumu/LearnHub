package org.dyh.learnhub.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DocumentExtractionStoreTest {
    @TempDir Path temp;
    private final DocumentExtractionStore store = new DocumentExtractionStore();
    private static final String ACCEPTED = "Synthetic Cedar evidence is indexed with an exact page position.";
    private static final String REJECTED = "Low confidence synthetic Maple candidate must not become evidence.";

    DocumentIngestionService.Extraction extraction(Path path) throws Exception {
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        var good = new DocumentIngestionService.Block("p2-b1", "para", ACCEPTED, ACCEPTED, 2,
                new double[]{20, 30, 220, 70}, 600, 800, .94, "accepted", true, 0, ACCEPTED.length());
        var bad = new DocumentIngestionService.Block("p2-b2", "para", REJECTED, REJECTED, 2,
                new double[]{20, 90, 220, 130}, 600, 800, .2, "rejected", false, -1, -1);
        return new DocumentIngestionService.Extraction("ok", ACCEPTED, ACCEPTED.length(), "待核对一个识别区域", sha,
                "document-ingestion-v1", List.of(new DocumentIngestionService.Page(2, 600, 800, "pt", "ocr", List.of(good, bad))),
                new DocumentIngestionService.QualityReport(2, 2, 1, 0, .57, List.of("低置信度候选未进入检索"), false, true));
    }

    @Test void sourceAndIndexedTextMustBothMatchTheSavedProvenance() throws Exception {
        Path source = temp.resolve("synthetic.png"); Files.writeString(source, "synthetic-source-A");
        var extraction = extraction(source);
        store.save(9L, source, extraction);
        assertTrue(store.read(9L, source, ACCEPTED).isPresent());
        assertTrue(store.read(9L, source, ACCEPTED + " changed").isEmpty());
        Files.writeString(source, "synthetic-source-B"); // Same length: size alone must not validate a revision.
        assertTrue(store.read(9L, source, ACCEPTED).isEmpty());
        assertThrows(java.io.IOException.class, () -> store.save(9L, source, extraction));
    }

    @Test void positionsFollowAcceptedRangesAndRejectedCandidatesNeverLocate() throws Exception {
        Path source = temp.resolve("synthetic.png"); Files.writeString(source, "synthetic-source");
        var extraction = extraction(source);
        var hits = DocumentExtractionStore.locate(extraction, "Cedar evidence is indexed with an exact page position.");
        assertEquals(1, hits.size());
        assertEquals(2, hits.getFirst().page());
        assertEquals("p2-b1", hits.getFirst().blockId());
        assertArrayEquals(new double[]{20, 30, 220, 70}, hits.getFirst().rect());
        assertEquals(extraction.sourceSha256(), hits.getFirst().sourceRevision());
        assertEquals(extraction.pipelineVersion(),hits.getFirst().pipelineVersion());
        assertTrue(DocumentExtractionStore.locate(extraction, REJECTED).isEmpty());
        assertTrue(DocumentExtractionStore.locate(extraction, "unrelated synthetic query").isEmpty());
    }

    @Test void damagedReportDegradesToNoLocationWithoutBreakingOriginalAccess() throws Exception {
        Path source = temp.resolve("synthetic.pdf"); Files.writeString(source, "synthetic-original");
        store.save(4L, source, extraction(source));
        Files.writeString(DocumentExtractionStore.reportPath(source,4L,ACCEPTED), "{damaged");
        assertTrue(store.read(4L, source, ACCEPTED).isEmpty());
        assertEquals("synthetic-original", Files.readString(source));
    }

    @Test void stagedConversionDoesNotHideThePriorDatabaseVersionBeforeCommit() throws Exception {
        Path source=temp.resolve("synthetic.pdf"); Files.writeString(source,"synthetic-original");
        var old=extraction(source); store.save(4L,source,old);
        String md="# New synthetic Markdown\n\nCedar evidence.";
        var block=new DocumentIngestionService.Block("new","markdown",md,md,1,null,0,0,null,"accepted",true,0,md.length());
        var next=new DocumentIngestionService.Extraction("ok",md,md.length(),null,
                old.sourceSha256(),DocumentIngestionService.PIPELINE_VERSION,
                List.of(new DocumentIngestionService.Page(1,0,0,"none","text",List.of(block))),old.report(),md,"markdown");
        store.save(4L,source,next); // No database update yet (or it fails).
        assertEquals(ACCEPTED,store.read(4L,source,ACCEPTED).orElseThrow().text());
        assertEquals(next.markdown(),store.read(4L,source,next.text()).orElseThrow().markdown());
        assertEquals("synthetic-original",Files.readString(source));
    }

    @Test void assetsBeyond512RemainReadableAndExportRejectsMissingOrCorruptImages() throws Exception {
        Path source=temp.resolve("synthetic.pdf"); Files.writeString(source,"synthetic-original");
        var old=extraction(source); StringBuilder markdown=new StringBuilder(); String last=null; byte[] lastBytes=null;
        Path assets=temp.resolve(".derived/4/assets"); Files.createDirectories(assets);
        for(int i=0;i<513;i++) {
            byte[] bytes=("synthetic image bytes "+i).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            Files.write(assets.resolve(hash+".png"),bytes); markdown.append("![原图](assets/").append(hash).append(".png)\n");
            last=hash; lastBytes=bytes;
        }
        var report=new DocumentIngestionService.Extraction(old.status(),old.text(),old.chars(),null,old.sourceSha256(),old.pipelineVersion(),
                old.pages(),old.report(),markdown.toString(),"markdown");
        assertEquals(513,store.assets(4L,source,report).size());
        assertArrayEquals(lastBytes,store.asset(4L,source,report,last));
        assertEquals(513,store.exportAssets(4L,source,report).size());
        Files.writeString(assets.resolve(last+".png"),"corrupt synthetic asset");
        assertThrows(java.io.IOException.class,()->store.exportAssets(4L,source,report));
        Files.delete(assets.resolve(last+".png"));
        assertThrows(java.io.IOException.class,()->store.exportAssets(4L,source,report));
    }

    @Test void literalAssetPathsInsideCodeOrOrdinaryTextAreNotRequiredExportResources() throws Exception {
        Path source=temp.resolve("synthetic.md"); Files.writeString(source,"synthetic-original");
        var old=extraction(source);
        String path="assets/"+"a".repeat(64)+".png";
        String markdown="```js\nconst source = '"+path+"';\n```\n\n`"+path+"`\n\nExample path: "+path;
        var report=new DocumentIngestionService.Extraction(old.status(),old.text(),old.chars(),null,old.sourceSha256(),old.pipelineVersion(),
                old.pages(),old.report(),markdown,"markdown");
        assertTrue(store.exportAssets(4L,source,report).isEmpty());
    }

    @Test void controlledHtmlImagesAreIncludedInExportsButLiteralExamplesAndCommentsAreNot() throws Exception {
        Path source=temp.resolve("synthetic.md"); Files.writeString(source,"synthetic-original");
        var old=extraction(source); String path="assets/"+"a".repeat(64)+".png";
        String tag="<img src=\""+path+"\" width=\"24\" height=\"12\" alt=\"synthetic\">";
        var visible=new DocumentIngestionService.Extraction(old.status(),old.text(),old.chars(),null,old.sourceSha256(),old.pipelineVersion(),
                old.pages(),old.report(),tag,"markdown");
        assertThrows(java.io.IOException.class,()->store.exportAssets(4L,source,visible));
        var literal=new DocumentIngestionService.Extraction(old.status(),old.text(),old.chars(),null,old.sourceSha256(),old.pipelineVersion(),
                old.pages(),old.report(),"```html\n"+tag+"\n```\n\n<!-- "+tag+" -->","markdown");
        assertTrue(store.exportAssets(4L,source,literal).isEmpty());
    }

    @Test void visibleMarkdownProjectionLocatesEscapedLinksAndTableEntities() throws Exception {
        Path source=temp.resolve("synthetic.pdf"); Files.writeString(source,"synthetic-original");
        var old=extraction(source);
        String md="<table><tr><td>Synthetic Cedar &amp; Maple evidence</td></tr></table>";
        var block=new DocumentIngestionService.Block("p3-html","table",md,md,3,new double[]{10,20,300,80},600,800,
                null,"accepted",true,0,md.length());
        var extraction=new DocumentIngestionService.Extraction("ok",md,md.length(),null,old.sourceSha256(),
                DocumentIngestionService.PIPELINE_VERSION,List.of(new DocumentIngestionService.Page(3,600,800,"pt","text",List.of(block))),old.report(),md,"markdown");
        var located=DocumentExtractionStore.locate(extraction,"Synthetic Cedar & Maple evidence");
        assertEquals(1,located.size()); assertEquals(3,located.getFirst().page());
        assertEquals("Synthetic Cedar evidence",DocumentExtractionStore.visibleText("**Synthetic** [Cedar](https://example.invalid) evidence"));
    }
}
