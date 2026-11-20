package org.dyh.learnhub.controller;

import lombok.RequiredArgsConstructor;
import org.dyh.learnhub.common.Result;
import org.dyh.learnhub.service.DocumentOcrClient;
import org.dyh.learnhub.service.DocumentStructureClient;
import org.dyh.learnhub.service.FileStorageService;
import org.dyh.learnhub.service.DocumentVisionClient;
import org.dyh.learnhub.service.DocumentProcessingJobs;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class DocumentExtractionController {
    private final FileStorageService files;
    private final DocumentOcrClient ocr;
    private final DocumentVisionClient vision;
    private final DocumentProcessingJobs jobs;
    private final DocumentStructureClient structure;

    @GetMapping("/document-extraction/capabilities")
    public Result<Map<String, Object>> capabilities() {
        Map<String,Object> result=new java.util.LinkedHashMap<>(ocr.capabilities());
        result.put("vision",vision.capabilities());
        var scan=structure.capabilities();
        result.put("structure",scan);
        result.put("defaultEngine",structure.configured()?"pp-structurev3":"tesseract");
        result.put("defaultMode",structure.configured()?"full-page-scan":"text-first");
        return Result.ok(result);
    }

    @GetMapping("/{id}/extraction")
    public Result<Object> extraction(@PathVariable Long id) { return Result.ok(files.extractionOf(id)); }

    @GetMapping("/{id}/markdown")
    public Result<Map<String,Object>> markdown(@PathVariable Long id, @RequestParam(defaultValue="latest") String version,
                                              @RequestParam(required=false) String sourceRevision,
                                              @RequestParam(required=false) String artifactRevision) {
        return Result.ok(files.markdownOf(id,version,sourceRevision,artifactRevision));
    }

    @GetMapping("/{id}/markdown/download")
    public ResponseEntity<byte[]> markdownDownload(@PathVariable Long id, @RequestParam(defaultValue="latest") String version,
                                                    @RequestParam(required=false) String sourceRevision,
                                                    @RequestParam(required=false) String artifactRevision) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .header("Content-Disposition","attachment; filename=\"document.md\"")
                .header("Cache-Control","no-store").body(files.markdownBytes(id,version,sourceRevision,artifactRevision));
    }

    @GetMapping("/{id}/markdown/bundle")
    public ResponseEntity<byte[]> bundle(@PathVariable Long id, @RequestParam(defaultValue="latest") String version,
                                         @RequestParam(required=false) String sourceRevision,
                                         @RequestParam(required=false) String artifactRevision) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header("Content-Disposition","attachment; filename=\"document-markdown.zip\"")
                .header("Cache-Control","no-store").body(files.markdownBundle(id,version,sourceRevision,artifactRevision));
    }

    @GetMapping("/{id}/extracted-assets/{hash}.png")
    public ResponseEntity<byte[]> asset(@PathVariable Long id,@PathVariable String hash,@RequestParam String sourceRevision) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).header("X-Content-Type-Options","nosniff")
                .header("Cache-Control","private, no-store").body(files.markdownAsset(id,hash,sourceRevision));
    }

    public record MediaRequest(boolean force,boolean localScan) {
        public MediaRequest(boolean force) { this(force,false); }
    }
    @PostMapping("/{id}/process-media")
    public Result<Map<String,Object>> processMedia(@PathVariable Long id,@RequestBody(required=false) MediaRequest request) {
        return Result.ok(request!=null && request.localScan() ? jobs.startMedia(id,request.force(),true)
                : jobs.startMedia(id,request!=null && request.force()));
    }
    @GetMapping("/media-jobs/{job}")
    public Result<Map<String,Object>> mediaStatus(@PathVariable String job) { return Result.ok(jobs.status(job,"media")); }

    public record ExtractionRequest(boolean resume,boolean force) {
        public ExtractionRequest(boolean resume) { this(resume,false); }
    }

    @PostMapping("/{id}/extraction-jobs")
    public Result<Map<String,Object>> startExtraction(@PathVariable Long id,@RequestBody(required=false) ExtractionRequest request) {
        return Result.ok(request!=null && request.force() ? jobs.startExtraction(id,request.resume(),true)
                : jobs.startExtraction(id,request!=null && request.resume()));
    }

    @GetMapping("/extraction-jobs/{job}")
    public Result<Map<String,Object>> extractionStatus(@PathVariable String job) { return Result.ok(jobs.status(job,"extraction")); }

    @PostMapping("/{id}/process-structure")
    public Result<Map<String,Object>> processStructure(@PathVariable Long id,@RequestBody(required=false) ExtractionRequest request) {
        var capability=structure.capabilities();
        if (!Boolean.TRUE.equals(capability.get("available")))
            throw new IllegalArgumentException(String.valueOf(capability.getOrDefault("message","结构化识别服务不可用")));
        return Result.ok(jobs.startStructure(id,request!=null && request.resume()));
    }

    @GetMapping("/structure-jobs/{job}")
    public Result<Map<String,Object>> structureStatus(@PathVariable String job) { return Result.ok(jobs.status(job,"structure")); }

    public record FormulaReview(String blockId,String sourceRevision,String expectedLatex,String latex,Boolean pendingScan,String artifactRevision) {
        public FormulaReview(String blockId,String sourceRevision,String expectedLatex,String latex,Boolean pendingScan) {
            this(blockId,sourceRevision,expectedLatex,latex,pendingScan,null);
        }
    }
    @PostMapping("/{id}/confirm-structure-formula")
    public Result<Map<String,Object>> confirmStructureFormula(@PathVariable Long id,@RequestBody FormulaReview review) {
        return Result.ok(files.confirmStructuredFormula(id,review.blockId(),review.sourceRevision(),review.expectedLatex(),review.latex(),
                Boolean.TRUE.equals(review.pendingScan()),review.artifactRevision()));
    }

    public record ScanPublication(String sourceRevision,String artifactRevision) { }
    @PostMapping("/{id}/publish-scan")
    public Result<Map<String,Object>> publishScan(@PathVariable Long id,@RequestBody ScanPublication review) {
        return Result.ok(files.publishScan(id,review.sourceRevision(),review.artifactRevision()));
    }

    public record MigrationRequest(boolean force,Boolean processMedia) { }
    @PostMapping("/markdown-migrations")
    public Result<Map<String,Object>> migrate(@RequestBody(required=false) MigrationRequest request) {
        return Result.ok(jobs.startMigration(request!=null && request.force(),request==null || !Boolean.FALSE.equals(request.processMedia())));
    }
    @GetMapping("/markdown-migrations/{job}")
    public Result<Map<String,Object>> migrationStatus(@PathVariable String job) { return Result.ok(jobs.status(job,"migration")); }

    @GetMapping("/{id}/extraction-page-image")
    public ResponseEntity<byte[]> sourcePage(@PathVariable Long id, @RequestParam(defaultValue = "1") int page,
                                           @RequestParam(required = false) String sourceRevision) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                .header("Cache-Control", "no-store").header("X-Content-Type-Options", "nosniff")
                .body(files.extractionPageImage(id, page, sourceRevision));
    }

    public record LocateRequest(String text) { }

    @PostMapping("/{id}/source-locations")
    public Result<?> locate(@PathVariable Long id, @RequestBody LocateRequest input) {
        if (input.text() == null || input.text().isBlank() || input.text().length() > 32_000)
            throw new IllegalArgumentException("定位正文须为 1～32000 个字符");
        return Result.ok(files.sourceLocations(id, input.text()));
    }
}
