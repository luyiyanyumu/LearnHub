package org.dyh.learnhub.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Version-bound provenance is stored with the uploads, so existing backup/delete lifecycles cover it. */
@Service
public class DocumentExtractionStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_REPORT_BYTES = 16L * 1024 * 1024;
    private record Saved(String sourceSha256, String textSha256, DocumentIngestionService.Extraction extraction, String markdownFile) { }
    private record SavedScanProgress(long sourceSize, long sourceModified, Map<String,Object> summary) { }
    public record Coverage(int totalPages, int processedPages, boolean partial, boolean truncated, Integer completedPages,
                           List<DocumentExtractionLimits.Reason> limitReasons) { }
    private record CachedCoverage(BasicFileAttributes attributes, String textDigest, Coverage coverage) {
        boolean matches(BasicFileAttributes current, String expectedDigest) {
            return textDigest.equals(expectedDigest) && attributes.size() == current.size()
                    && attributes.lastModifiedTime().equals(current.lastModifiedTime())
                    && Objects.equals(attributes.fileKey(), current.fileKey());
        }
    }
    private final Map<Path, CachedCoverage> coverageCache = Collections.synchronizedMap(new LinkedHashMap<>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Path, CachedCoverage> eldest) { return size() > 512; }
    });
    public record Asset(String name, String sha256, long bytes, String mediaType) { }
    private static final java.util.regex.Pattern ASSET = java.util.regex.Pattern.compile("assets/([a-f0-9]{64})\\.png");

    public record SourceLocation(int page, double[] rect, double width, double height, String coordinateUnit,
                                 String blockId, Double confidence, String method, String sourceRevision, String pipelineVersion) {
        public SourceLocation(int page,double[] rect,double width,double height,String coordinateUnit,String blockId,
                              Double confidence,String method,String sourceRevision) {
            this(page,rect,width,height,coordinateUnit,blockId,confidence,method,sourceRevision,null);
        }
    }

    private static Path target(Path source, Long id) {
        if (id == null || id <= 0) throw new IllegalArgumentException("资料 ID 无效");
        return source.toAbsolutePath().getParent().resolve(".derived").resolve(id.toString()).resolve("ingestion.json");
    }

    public void save(Long id, Path source, DocumentIngestionService.Extraction extraction) throws IOException {
        if (!sha256(source).equals(extraction.sourceSha256())) throw new IOException("原文件已变化，请重新抽取");
        String markdown = extraction.markdown() == null ? extraction.text() : extraction.markdown();
        byte[] markdownBytes=(markdown == null ? "" : markdown).getBytes(StandardCharsets.UTF_8);
        if (markdownBytes.length>8*1024*1024) throw new IOException("Markdown 超出 8 MiB 存储上限");
        String markdownFile = "document-" + digest(markdown) + ".md";
        Path directory = target(source, id).getParent();
        atomicWrite(directory.resolve(markdownFile), markdownBytes);
        byte[] bytes = JSON.writeValueAsBytes(new Saved(extraction.sourceSha256(), digest(extraction.text()), extraction, markdownFile));
        if (bytes.length > MAX_REPORT_BYTES) throw new IOException("抽取报告超出存储上限");
        // Content-addressed reports allow the DB transaction to keep reading the old version until it commits.
        Path destination = reportPath(source, id, extraction.text());
        if (Files.isRegularFile(destination) && Files.size(destination)<=MAX_REPORT_BYTES) {
            byte[] previous=Files.readAllBytes(destination);
            if (!java.util.Arrays.equals(previous,bytes)) {
                Path history=destination.resolveSibling("history-"+sha256(destination)+".json");
                if (!Files.exists(history)) atomicWrite(history,previous);
            }
        }
        atomicWrite(destination, bytes);
        coverageCache.remove(destination);
    }

    static Path reportPath(Path source, Long id, String text) {
        return target(source, id).resolveSibling("ingestion-" + digest(text) + ".json");
    }

    /** A new scan generation is resumable without replacing the published text or its navigation report. */
    public void savePendingScan(Long id, Path source, DocumentIngestionService.Extraction extraction) throws IOException {
        if (!DocumentIngestionService.STRUCTURED_PIPELINE_VERSION.equals(extraction.pipelineVersion())
                || !sha256(source).equals(extraction.sourceSha256())) throw new IOException("扫描候选不属于当前原件或模型版本");
        byte[] bytes = JSON.writeValueAsBytes(extraction);
        if (bytes.length > MAX_REPORT_BYTES) throw new IOException("扫描候选报告超出存储上限");
        atomicWrite(target(source, id).resolveSibling("pending-scan.json"), bytes);
        var attributes = Files.readAttributes(source, BasicFileAttributes.class);
        atomicWrite(target(source, id).resolveSibling("pending-scan-progress.json"), JSON.writeValueAsBytes(
                new SavedScanProgress(attributes.size(), attributes.lastModifiedTime().toMillis(), summary(extraction))));
    }

    public Optional<DocumentIngestionService.Extraction> readPendingScan(Long id, Path source) {
        Path path = target(source, id).resolveSibling("pending-scan.json");
        try {
            if (!Files.isRegularFile(source) || !Files.isRegularFile(path) || Files.size(path) > MAX_REPORT_BYTES)
                return Optional.empty();
            var extraction = JSON.readValue(Files.readAllBytes(path), DocumentIngestionService.Extraction.class);
            if (!DocumentIngestionService.STRUCTURED_PIPELINE_VERSION.equals(extraction.pipelineVersion())
                    || !sha256(source).equals(extraction.sourceSha256())) return Optional.empty();
            int length = extraction.text() == null ? 0 : extraction.text().length();
            if (extraction.pages().stream().flatMap(page -> page.blocks().stream())
                    .anyMatch(block -> block.indexed() && (block.start() < 0 || block.end() <= block.start() || block.end() > length)))
                return Optional.empty();
            return Optional.of(extraction);
        } catch (Exception invalid) { return Optional.empty(); }
    }

    public void clearPendingScan(Long id, Path source) throws IOException {
        Files.deleteIfExists(target(source, id).resolveSibling("pending-scan.json"));
        Files.deleteIfExists(target(source, id).resolveSibling("pending-scan-progress.json"));
    }

    /** Lists only inspect a bounded progress record; full source verification still precedes every resume and review. */
    public Optional<Map<String,Object>> readPendingScanSummary(Long id, Path source) {
        Path path = target(source, id).resolveSibling("pending-scan-progress.json");
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > 128_000
                    || !Files.isRegularFile(path.resolveSibling("pending-scan.json"))) return Optional.empty();
            var saved = JSON.readValue(Files.readAllBytes(path), SavedScanProgress.class);
            var attributes = Files.readAttributes(source, BasicFileAttributes.class);
            if (attributes.size() != saved.sourceSize() || attributes.lastModifiedTime().toMillis() != saved.sourceModified()
                    || !DocumentIngestionService.STRUCTURED_PIPELINE_VERSION.equals(saved.summary().get("pipelineVersion")))
                return Optional.empty();
            return Optional.of(saved.summary());
        } catch (Exception invalid) { return Optional.empty(); }
    }

    private static void atomicWrite(Path destination, byte[] bytes) throws IOException {
        Files.createDirectories(destination.getParent());
        Path staged = Files.createTempFile(destination.getParent(), "ingestion-", ".tmp");
        try {
            Files.write(staged, bytes);
            try { Files.move(staged, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(staged, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(staged); }
    }

    /** A report from another source revision or another indexed text is never used for navigation. */
    public Optional<DocumentIngestionService.Extraction> read(Long id, Path source, String indexedText) {
        Path path = reportPath(source, id, indexedText);
        try {
            if (!Files.isRegularFile(path)) path = target(source, id); // Adopt the first OCR pipeline's report without rewriting it on GET.
            if (!Files.isRegularFile(source) || !Files.isRegularFile(path) || Files.size(path) > MAX_REPORT_BYTES) return Optional.empty();
            Saved saved = JSON.readValue(Files.readAllBytes(path), Saved.class);
            if (saved.extraction() == null || !digest(indexedText).equals(saved.textSha256())
                    || !sha256(source).equals(saved.sourceSha256())
                    || !saved.sourceSha256().equals(saved.extraction().sourceSha256())) return Optional.empty();
            int length=saved.extraction().text() == null?0:saved.extraction().text().length();
            if (saved.extraction().pages().stream().flatMap(page -> page.blocks().stream())
                    .anyMatch(block -> block.indexed() && (block.start()<0 || block.end()<=block.start() || block.end()>length)))
                return Optional.empty();
            if (saved.markdownFile() != null) {
                if (!saved.markdownFile().matches("document-[a-f0-9]{64}\\.md")) return Optional.empty();
                Path md = path.resolveSibling(saved.markdownFile());
                if (!Files.isRegularFile(md) || Files.size(md) > 8L * 1024 * 1024
                        || !digest(Files.readString(md, StandardCharsets.UTF_8)).equals(digest(saved.extraction().markdown())))
                    return Optional.empty();
            }
            return Optional.of(saved.extraction());
        } catch (Exception invalid) { return Optional.empty(); }
    }

    /** List metadata only. Original bytes and Markdown are verified by the full reader, not during pagination. */
    Optional<Coverage> readCoverage(Long id, Path source, long expectedSize, String indexedText) {
        String expectedDigest = digest(indexedText);
        Path legacy = target(source, id);
        Path report = legacy.resolveSibling("ingestion-" + expectedDigest + ".json");
        if (!Files.isRegularFile(report)) report = legacy;
        try {
            var attrs = Files.readAttributes(report, BasicFileAttributes.class);
            if (!attrs.isRegularFile() || attrs.size() > MAX_REPORT_BYTES) return Optional.empty();
            var original = Files.readAttributes(source, BasicFileAttributes.class);
            if (!original.isRegularFile() || (expectedSize > 0 && expectedSize != original.size())
                    || original.lastModifiedTime().compareTo(attrs.lastModifiedTime()) > 0) return Optional.empty();
            var cached = coverageCache.get(report);
            if (cached != null && cached.matches(attrs, expectedDigest)) return Optional.ofNullable(cached.coverage());
            Coverage coverage = parseCoverage(report, expectedDigest);
            coverageCache.put(report, new CachedCoverage(attrs, expectedDigest, coverage));
            return Optional.ofNullable(coverage);
        } catch (IOException | RuntimeException invalid) { return Optional.empty(); }
    }

    private static Coverage parseCoverage(Path report, String expectedDigest) throws IOException {
        try (JsonParser parser = JSON.getFactory().createParser(report.toFile())) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return null;
            boolean matchingText = false;
            Coverage coverage = null;
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                parser.nextToken();
                if ("textSha256".equals(field)) {
                    if (!expectedDigest.equals(parser.getValueAsString())) return null;
                    matchingText = true;
                } else if ("extraction".equals(field) && parser.currentToken() == JsonToken.START_OBJECT) {
                    coverage = parseExtractionCoverage(parser, matchingText);
                } else parser.skipChildren();
                if (matchingText && coverage != null) return coverage;
            }
            return null;
        }
    }

    private static Coverage parseExtractionCoverage(JsonParser parser, boolean textAlreadyMatched) throws IOException {
        Coverage coverage = null;
        Integer completed = null;
        Set<Integer> completedLegacyPages = null;
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String field = parser.currentName();
            parser.nextToken();
            if ("report".equals(field) && parser.currentToken() == JsonToken.START_OBJECT) {
                JsonNode report = JSON.readTree(parser);
                int total = report.path("totalPages").asInt(-1), processed = report.path("processedPages").asInt(-1);
                if (total >= 0 && processed >= 0 && processed <= total
                        && report.path("partial").isBoolean() && report.path("truncated").isBoolean()) {
                    List<String> warnings = new ArrayList<>();
                    report.path("warnings").forEach(warning -> {
                        if (warning.isTextual()) warnings.add(warning.textValue());
                    });
                    coverage = new Coverage(total, processed, report.path("partial").asBoolean(), report.path("truncated").asBoolean(),
                            null, DocumentExtractionLimits.fromWarnings(warnings));
                }
            } else if ("completedPages".equals(field) && parser.currentToken() == JsonToken.VALUE_NUMBER_INT) {
                completed = parser.getIntValue();
            } else if ("pages".equals(field) && parser.currentToken() == JsonToken.START_ARRAY) {
                completedLegacyPages = parseCompletedPages(parser);
            } else parser.skipChildren();
            if (textAlreadyMatched && coverage != null && completed != null && completed >= 0 && completed <= coverage.totalPages()) break;
        }
        if (coverage == null) return null;
        int total = coverage.totalPages();
        if (completed == null || completed < 0 || completed > total) {
            completed = completedLegacyPages == null ? null
                    : (int) completedLegacyPages.stream().filter(page -> page <= total).count();
        }
        return new Coverage(total, coverage.processedPages(), coverage.partial(), coverage.truncated(), completed, coverage.limitReasons());
    }

    /** Older reports only counted attempted pages. Stream the fields used by isCompletedPage without loading page text. */
    private static Set<Integer> parseCompletedPages(JsonParser parser) throws IOException {
        Set<Integer> completed = new HashSet<>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.currentToken() != JsonToken.START_OBJECT) throw new IOException("抽取报告页结构无效");
            int page = 0;
            boolean blank = false;
            boolean usableBlocks = false;
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                parser.nextToken();
                if ("page".equals(field)) page = parser.getValueAsInt();
                else if ("method".equals(field)) blank = "blank".equals(parser.getValueAsString());
                else if ("blocks".equals(field) && parser.currentToken() == JsonToken.START_ARRAY)
                    usableBlocks = hasUsableBlocks(parser);
                parser.skipChildren();
            }
            if (parser.currentToken() != JsonToken.END_OBJECT) throw new IOException("抽取报告页结构无效");
            if (page > 0 && (blank || usableBlocks)) completed.add(page);
        }
        return completed;
    }

    private static boolean hasUsableBlocks(JsonParser parser) throws IOException {
        boolean indexed = false;
        int count = 0;
        boolean onlyFurniture = true;
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.currentToken() != JsonToken.START_OBJECT) throw new IOException("抽取报告块结构无效");
            if (indexed) {
                parser.skipChildren();
                continue;
            }
            count++;
            boolean furniture = false;
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                parser.nextToken();
                if ("indexed".equals(field)) indexed = parser.currentToken() == JsonToken.VALUE_TRUE;
                else if ("quality".equals(field)) furniture = "page_furniture".equals(parser.getValueAsString());
                parser.skipChildren();
            }
            if (parser.currentToken() != JsonToken.END_OBJECT) throw new IOException("抽取报告块结构无效");
            onlyFurniture &= furniture;
        }
        return indexed || (count > 0 && onlyFurniture);
    }

    public static Map<String, Object> summary(DocumentIngestionService.Extraction extraction) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", extraction.status());
        result.put("sourceSha256", extraction.sourceSha256());
        result.put("pipelineVersion", extraction.pipelineVersion());
        result.put("format", extraction.format() == null ? "plain" : extraction.format());
        result.put("report", extraction.report());
        result.put("completedPages", extraction.completedPages());
        result.put("limitReasons", DocumentExtractionLimits.fromWarnings(extraction.report().warnings()));
        result.put("canResumeCharacterLimit", DocumentIngestionService.canResumeCharacterLimit(extraction));
        var localScan=DocumentMediaProcessor.localScanProgress(extraction);
        if (localScan.requested()) result.put("localScanProgress",localScan);
        return result;
    }

    public static Map<String, Object> notProcessed() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "not_processed");
        result.put("error", "尚无与当前原件和检索正文一致的抽取报告，可重新抽取生成");
        result.put("pages", List.of());
        result.put("report", null);
        return result;
    }

    public List<Asset> assets(Long id, Path source, DocumentIngestionService.Extraction extraction) {
        List<Asset> result = new ArrayList<>();
        for (String hash : assetHashes(extraction)) {
            try { result.add(checkedAsset(id,source,hash)); }
            catch (IOException missing) { /* Unavailable images are excluded from the preview manifest. */ }
        }
        return List.copyOf(result);
    }

    private static java.util.Set<String> assetHashes(DocumentIngestionService.Extraction extraction) {
        if (extraction == null) return java.util.Set.of();
        String document = extraction.markdown() == null ? "" : extraction.markdown();
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        org.commonmark.parser.Parser.builder().build().parse(document).accept(new org.commonmark.node.AbstractVisitor() {
            @Override public void visit(org.commonmark.node.Image image) {
                var matcher=ASSET.matcher(image.getDestination());
                if (matcher.matches()) names.add(matcher.group(1));
            }
            @Override public void visit(org.commonmark.node.HtmlInline html) { collectHtmlImages(html.getLiteral(),names); }
            @Override public void visit(org.commonmark.node.HtmlBlock html) { collectHtmlImages(html.getLiteral(),names); }
        });
        for (var page : extraction.pages()) for (var block : page.blocks()) {
            var src = ASSET.matcher(block.src() == null ? "" : block.src());
            if (src.matches()) names.add(src.group(1));
        }
        return names;
    }

    private static void collectHtmlImages(String html,java.util.Set<String> names) {
        String visible=html.replaceAll("(?s)<!--.*?-->","");
        var tags=java.util.regex.Pattern.compile("(?is)<img\\b(?:\"[^\"]*\"|'[^']*'|[^'\">])*>").matcher(visible);
        var source=java.util.regex.Pattern.compile("(?i)\\bsrc\\s*=\\s*(?:\"(assets/([a-f0-9]{64})\\.png)\"|'(assets/([a-f0-9]{64})\\.png)')");
        while(tags.find()) {
            var attr=source.matcher(tags.group());
            if(attr.find()) names.add((attr.group(2)!=null?attr.group(2):attr.group(4)).toLowerCase(java.util.Locale.ROOT));
        }
    }

    /** An export must contain every referenced local image, rather than silently produce a broken bundle. */
    public List<Asset> exportAssets(Long id, Path source, DocumentIngestionService.Extraction extraction) throws IOException {
        List<Asset> result = new ArrayList<>();
        for (String hash : assetHashes(extraction)) result.add(checkedAsset(id,source,hash));
        return List.copyOf(result);
    }

    private Asset checkedAsset(Long id,Path source,String hash) throws IOException {
        Path directory=target(source,id).getParent().resolve("assets"), asset=directory.resolve(hash+".png");
        if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(asset) || !Files.isRegularFile(asset)
                || Files.size(asset)>8L*1024*1024 || !sha256(asset).equals(hash))
            throw new IOException("原图资产缺失或损坏");
        return new Asset("assets/"+hash+".png",hash,Files.size(asset),"image/png");
    }

    public byte[] asset(Long id, Path source, DocumentIngestionService.Extraction extraction, String hash) throws IOException {
        if (hash == null || !hash.matches("[a-f0-9]{64}") || !assetHashes(extraction).contains(hash))
            throw new IllegalArgumentException("图片不属于当前资料版本，请重新加载抽取报告");
        checkedAsset(id,source,hash);
        return Files.readAllBytes(target(source, id).getParent().resolve("assets").resolve(hash + ".png"));
    }

    /** Match returned evidence against accepted text ranges, never against rejected OCR candidates. */
    public static List<SourceLocation> locate(DocumentIngestionService.Extraction extraction, String evidence) {
        return locate(extraction, evidence, "");
    }

    public static List<SourceLocation> locate(DocumentIngestionService.Extraction extraction, String evidence, String question) {
        if (extraction == null || evidence == null || evidence.isBlank() || extraction.text() == null) return List.of();
        String body = extraction.text();
        String query = evidence.strip();
        if (query.length() > 32_000) query = query.substring(0, 32_000);
        List<int[]> ranges = new ArrayList<>();
        addRanges(body, query, ranges);
        if (ranges.isEmpty()) {
            // Chunking removes heading markup and excerpts may contain an omission marker.
            // Use literal content anchors, preserving mathematical symbols and source spelling.
            String[] anchors = query.split("(?:\\r?\\n|…|\\.\\.\\.)+");
            for (String value : anchors) {
                String anchor = value.strip().replaceFirst("^#{1,6}\\s+", "");
                if (anchor.codePoints().filter(Character::isLetterOrDigit).count() < 8) continue;
                addRanges(body, anchor, ranges);
                if (ranges.size() >= 24) break;
            }
        }
        record Candidate(SourceLocation location, double relevance) { }
        List<Candidate> result = new ArrayList<>();
        String visibleEvidence = visibleText(query);
        List<String> visibleAnchors = java.util.Arrays.stream(visibleEvidence.split("(?:\\r?\\n|…|\\.\\.\\.)+"))
                .map(String::strip).filter(value -> value.codePoints().filter(Character::isLetterOrDigit).count() >= 8).limit(24).toList();
        for (var page : extraction.pages()) for (var block : page.blocks()) {
            if (!block.indexed() || block.start() < 0 || block.end() <= block.start()) continue;
            boolean overlaps = ranges.stream().anyMatch(range -> block.start() < range[1] && block.end() > range[0]);
            if (!overlaps && block.end() <= body.length()) {
                // A deterministic Markdown projection handles escaped punctuation, links and table entities.
                // Coordinates still come from the original indexed block, never from a semantic/fuzzy guess.
                String visibleBlock = visibleText(body.substring(block.start(), block.end()));
                long letters = visibleBlock.codePoints().filter(Character::isLetterOrDigit).count();
                overlaps = letters >= 2 && visibleBlock.equals(visibleEvidence)
                        || letters >= 8 && visibleEvidence.contains(visibleBlock)
                        || visibleAnchors.stream().anyMatch(anchor -> visibleBlock.contains(anchor));
            }
            if (overlaps) {
                var location = new SourceLocation(page.page(), block.rect(), page.width(), page.height(), page.coordinateUnit(),
                        block.id(), block.confidence(), "recognized".equals(block.quality()) ? "vision" : page.method(), extraction.sourceSha256(),extraction.pipelineVersion());
                double relevance = question == null || question.isBlank() ? 0
                        : org.dyh.learnhub.service.rerank.QueryAwareExcerpt.relevance(question, block.cleanText(), "").total();
                result.add(new Candidate(location, relevance));
            }
        }
        return result.stream().sorted(java.util.Comparator.comparingDouble(Candidate::relevance).reversed())
                .limit(12).map(Candidate::location).toList();
    }

    static String visibleText(String markdown) {
        StringBuilder result=new StringBuilder();
        org.commonmark.node.Node parsed=org.commonmark.parser.Parser.builder().build().parse(markdown == null ? "" : markdown);
        parsed.accept(new org.commonmark.node.AbstractVisitor() {
            @Override public void visit(org.commonmark.node.Text node) { result.append(node.getLiteral()); }
            @Override public void visit(org.commonmark.node.Code node) { result.append(node.getLiteral()); }
            @Override public void visit(org.commonmark.node.FencedCodeBlock node) { result.append(node.getLiteral()).append('\n'); }
            @Override public void visit(org.commonmark.node.IndentedCodeBlock node) { result.append(node.getLiteral()).append('\n'); }
            @Override public void visit(org.commonmark.node.SoftLineBreak node) { result.append('\n'); }
            @Override public void visit(org.commonmark.node.HardLineBreak node) { result.append('\n'); }
            @Override public void visit(org.commonmark.node.Paragraph node) { visitChildren(node); result.append('\n'); }
            @Override public void visit(org.commonmark.node.Heading node) { visitChildren(node); result.append('\n'); }
            @Override public void visit(org.commonmark.node.HtmlBlock node) { html(node.getLiteral()); }
            @Override public void visit(org.commonmark.node.HtmlInline node) { html(node.getLiteral()); }
            private void html(String literal) {
                result.append(literal.replaceAll("<[^>]*>"," ").replace("&amp;","&").replace("&lt;","<")
                        .replace("&gt;",">").replace("&quot;","\"").replace("&#39;","'").replace("&nbsp;"," "));
            }
        });
        return result.toString().replaceAll("[ \\t]+"," ").replaceAll(" *\\n *","\n").strip();
    }

    private static void addRanges(String body, String anchor, List<int[]> ranges) {
        int from = 0;
        while (ranges.size() < 24) {
            int start = body.indexOf(anchor, from);
            if (start < 0) return;
            ranges.add(new int[]{start, start + anchor.length()});
            from = start + Math.max(1, anchor.length());
        }
    }

    private static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static String sha256(Path source) throws IOException {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(source)) {
                byte[] buffer = new byte[65536];
                for (int length; (length = input.read(buffer)) >= 0;) if (length > 0) hash.update(buffer, 0, length);
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
