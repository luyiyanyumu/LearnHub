package org.dyh.learnhub.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import jakarta.annotation.PreDestroy;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.util.HtmlUtils;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PrimitiveIterator;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/** Bounded page ingestion. Configured structured scans cover every page, independently of native text layers. */
@Service
public class DocumentIngestionService {
    public static final String PIPELINE_VERSION = "document-ingestion-v3-layout";
    public static final String LEGACY_STRUCTURED_PIPELINE_VERSION = "document-ingestion-v4-structure";
    public static final String LEGACY_HQ_PIPELINE_VERSION = "document-ingestion-v5-scan-hq";
    public static final String STRUCTURED_PIPELINE_VERSION = "document-ingestion-v5-scan-hq2";
    static final int MAX_PAGES = 300;
    static final int MAX_PDF_PAGES = 500;
    static final int MAX_OCR_PAGES = 20;
    static final double MIN_CONFIDENCE = 0.65;
    private static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    private static final long MAX_PDF_FILE_BYTES = 200L * 1024 * 1024;
    private static final long MAX_PAGE_PIXELS = 12_000_000;
    private static final long MAX_TOTAL_PIXELS = 80_000_000;
    private static final int MAX_OCR_SIDE = 10_000;
    private static final int MAX_OCR_IMAGE_BYTES = 12 * 1024 * 1024;
    private static final int MAX_WARNINGS = 200;
    private static final int MAX_BLOCKS = 20000;
    private static final int MAX_RAW_CHARS = 800000;
    private static final int PDF_DPI = 300;
    private static final int STRUCTURED_PDF_DPI = 300;
    private static final long STRUCTURED_TIMEOUT_MS = 600_000;
    private static final Set<String> IMAGES = Set.of("png", "jpg", "jpeg", "tif", "tiff", "bmp", "gif", "webp");
    private static final String BASIC_OCR_NOTICE = "OCR 仅支持基础段落和简单分栏；标题层级、复杂表格、图表及公式请核对原文";
    private static final String ADAPTIVE_DPI_NOTICE = "页 OCR 自适应渲染至 ";
    private static final Pattern PAGE_NOTICE = Pattern.compile("^第 (\\d{1,9}) 页.*");
    private static final Set<String> NON_INDEXED_QUALITIES = Set.of("non_text", "empty", "page_furniture", "superseded_by_formula");
    private final PdfLayoutExtractor layout;
    private final DocumentOcrClient ocr;
    private final DocumentStructureClient structure;
    private final long timeoutMillis;
    private final Semaphore slots = new Semaphore(2);
    private final ExecutorService workers = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "document-ingestion"); thread.setDaemon(true); return thread;
    });
    private final Semaphore structuredSlot = new Semaphore(1);
    private final ExecutorService structuredWorker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "document-structured-ingestion"); thread.setDaemon(true); return thread;
    });

    public static boolean isCurrentPipeline(String version) {
        return PIPELINE_VERSION.equals(version) || STRUCTURED_PIPELINE_VERSION.equals(version);
    }

    public static boolean isStructuredPipeline(String version) {
        return STRUCTURED_PIPELINE_VERSION.equals(version) || LEGACY_STRUCTURED_PIPELINE_VERSION.equals(version)
                || LEGACY_HQ_PIPELINE_VERSION.equals(version);
    }

    public boolean prefersStructured(String ext) {
        return supports(ext) && structure != null && structure.configured();
    }

    public static boolean compatiblePipeline(String version, boolean structured) {
        return (structured ? STRUCTURED_PIPELINE_VERSION : PIPELINE_VERSION).equals(version);
    }

    static long maxSourceBytes(String extension) {
        return "pdf".equalsIgnoreCase(extension) ? MAX_PDF_FILE_BYTES : MAX_FILE_BYTES;
    }

    static String sourceTooLargeMessage(long maxBytes) {
        return "源文件超过 " + (maxBytes / (1024 * 1024)) + " MiB 正文抽取上限，原件已保留，可拆分后抽取。";
    }

    @Autowired
    public DocumentIngestionService(PdfLayoutExtractor layout, DocumentOcrClient ocr, DocumentStructureClient structure) {
        this(layout, ocr, structure, 120000);
    }

    public DocumentIngestionService(PdfLayoutExtractor layout, DocumentOcrClient ocr) {
        this(layout, ocr, null);
    }

    DocumentIngestionService(PdfLayoutExtractor layout, DocumentOcrClient ocr, long timeoutMillis) {
        this(layout, ocr, null, timeoutMillis);
    }

    private DocumentIngestionService(PdfLayoutExtractor layout, DocumentOcrClient ocr,
                                     DocumentStructureClient structure, long timeoutMillis) {
        this.layout = layout;
        this.ocr = ocr;
        this.structure = structure;
        this.timeoutMillis = timeoutMillis;
    }

    @JsonPropertyOrder({"report", "completedPages", "limitReasons"})
    public record Extraction(String status, String text, int chars, String error, String sourceSha256,
                             String pipelineVersion, List<Page> pages, QualityReport report, String markdown, String format) {
        public Extraction(String status, String text, int chars, String error, String sourceSha256,
                          String pipelineVersion, List<Page> pages, QualityReport report) {
            this(status, text, chars, error, sourceSha256, pipelineVersion, pages, report, text == null ? "" : text, "plain");
        }

        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public int completedPages() { return DocumentIngestionService.completedPages(this); }

        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public List<DocumentExtractionLimits.Reason> limitReasons() { return DocumentExtractionLimits.fromWarnings(report.warnings()); }
    }
    public record Page(int page, double width, double height, String coordinateUnit, String method, List<Block> blocks, String label) {
        public Page(int page, double width, double height, String coordinateUnit, String method, List<Block> blocks) {
            this(page,width,height,coordinateUnit,method,blocks,null);
        }
    }
    /** Source presentation uses CSS pixels, independently of page geometry and image raster resolution. */
    public record Presentation(String groupId, String placement, Double widthPx, Double heightPx, String align, String sourceRef) { }
    /** start/end are UTF-16 half-open offsets into Extraction.text; unindexed blocks use -1/-1. */
    public record Block(String id, String type, String rawText, String cleanText, int page, double[] rect,
                        double width, double height, Double confidence, String quality, boolean indexed,
                        int start, int end, int level, String src, String latex, String latexStatus,
                        String latexMessage, String markdown, int markdownStart, int markdownEnd, Presentation presentation) {
        public Block(String id, String type, String rawText, String cleanText, int page, double[] rect,
                     double width, double height, Double confidence, String quality, boolean indexed,
                     int start, int end, int level, String src, String latex, String latexStatus,
                     String latexMessage, String markdown, int markdownStart, int markdownEnd) {
            this(id,type,rawText,cleanText,page,rect,width,height,confidence,quality,indexed,start,end,level,src,latex,
                    latexStatus,latexMessage,markdown,markdownStart,markdownEnd,null);
        }
        public Block(String id, String type, String rawText, String cleanText, int page, double[] rect,
                     double width, double height, Double confidence, String quality, boolean indexed,
                     int start, int end, int level, String src, String latex, String latexStatus, String latexMessage) {
            this(id, type, rawText, cleanText, page, rect, width, height, confidence, quality, indexed,
                    start, end, level, src, latex, latexStatus, latexMessage, null, -1, -1);
        }
        public Block(String id, String type, String rawText, String cleanText, int page, double[] rect,
                     double width, double height, Double confidence, String quality, boolean indexed, int start, int end) {
            this(id, type, rawText, cleanText, page, rect, width, height, confidence, quality, indexed,
                    start, end, 0, null, null, null, null);
        }
    }
    public record QualityReport(int totalPages, int processedPages, int ocrPages, int rejectedPages,
                                Double meanConfidence, List<String> warnings, boolean truncated, boolean partial) { }
    public record Progress(String stage, int completedPages, int totalPages, int currentPage) { }

    public static boolean isCompletedPage(Page page) {
        return !hasCharacterLimit(page) && ("blank".equals(page.method()) || page.blocks().stream().anyMatch(Block::indexed)
                || !page.blocks().isEmpty() && page.blocks().stream().allMatch(block -> "page_furniture".equals(block.quality())));
    }

    private static boolean hasCharacterLimit(Page page) {
        return page.blocks().stream().anyMatch(block -> "character_limit".equals(block.quality()));
    }

    static boolean canResumeCharacterLimit(Extraction extraction) {
        var limits=extraction.report().warnings().stream().filter(DocumentExtractionLimits::isDocumentCapacity).toList();
        return STRUCTURED_PIPELINE_VERSION.equals(extraction.pipelineVersion())
                && extraction.pages().stream().anyMatch(DocumentIngestionService::hasCharacterLimit)
                && !limits.isEmpty() && limits.stream().allMatch(DocumentExtractionLimits::olderTextCapacity);
    }

    public static int completedPages(Extraction extraction) {
        return (int) extraction.pages().stream()
                .filter(page -> page.page() > 0 && page.page() <= extraction.report().totalPages())
                .filter(DocumentIngestionService::isCompletedPage).mapToInt(Page::page).distinct().count();
    }

    public boolean supports(String ext) {
        String format = ext == null ? "" : ext.toLowerCase(Locale.ROOT);
        return format.equals("pdf") || IMAGES.contains(format);
    }

    public Extraction extract(Path path, String ext) {
        return extract(path, ext, prefersStructured(ext), null, null);
    }

    public Extraction extractStructured(Path path, String ext) {
        return extract(path, ext, true, null, null);
    }

    /** Resume only from verified results for this exact file. A run never exceeds its normal processing budgets. */
    public Extraction extract(Path path, String ext, boolean structured, Extraction previous, Consumer<Progress> onProgress) {
        long budget = structured ? STRUCTURED_TIMEOUT_MS : timeoutMillis;
        State state = new State(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budget), structured, onProgress);
        if (!supports(ext)) return state.snapshot("unsupported", "该类型不使用 OCR 导入管线");
        if (structured && (structure == null || !structure.configured())) {
            state.warn("结构化 OCR 服务未配置", false);
            return state.snapshot("failed", "结构化 OCR 服务未配置");
        }
        Semaphore permit = structured ? structuredSlot : slots;
        ExecutorService executor = structured ? structuredWorker : workers;
        String timeLimit = budget >= 1000 ? (budget / 1000) + " 秒" : budget + " 毫秒";
        String budgetNotice = "已达到 " + timeLimit + "任务预算；再次继续抽取可处理剩余页";
        if (!permit.tryAcquire()) {
            state.warn(structured ? "已有资料正在结构化抽取，请稍后重试" : "已有两个文档正在抽取，请稍后重抽正文", false);
            return state.snapshot("failed", "文档抽取服务繁忙");
        }
        Future<Extraction> future;
        try {
            future = executor.submit(() -> {
                try {
                    check(state);
                    state.progress("preparing", 0);
                    boolean pdf = "pdf".equalsIgnoreCase(ext);
                    if (!Files.isRegularFile(path)) throw new IllegalStateException("源文件缺失");
                    long maxBytes = maxSourceBytes(ext);
                    if (Files.size(path) > maxBytes)
                        throw new IllegalStateException(sourceTooLargeMessage(maxBytes));
                    state.sourceSha256 = sourceHash(path, state);
                    Extraction baseline = previous != null && state.sourceSha256.equals(previous.sourceSha256())
                            && compatiblePipeline(previous.pipelineVersion(), structured) ? previous : null;
                    if (pdf) pdf(path, state, baseline); else image(path, state, baseline);
                    return state.snapshot(null, null);
                } catch (BudgetExceeded exceeded) {
                    state.warn(budgetNotice, true);
                    return state.snapshot(null, "文档抽取时间预算已耗尽");
                } catch (Exception failed) {
                    state.warn("文档抽取失败：" + safeMessage(failed), false);
                    return state.snapshot(null, safeMessage(failed));
                } finally { permit.release(); }
            });
        } catch (RuntimeException unavailable) {
            permit.release(); state.warn("文档抽取服务已停止", false);
            return state.snapshot("failed", "文档抽取服务已停止");
        }
        try {
            return future.get(budget, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timedOut) {
            state.stopped = true; future.cancel(true);
            state.warn(budgetNotice, true);
            return state.snapshot(null, "文档抽取时间预算已耗尽");
        } catch (InterruptedException interrupted) {
            state.stopped = true; future.cancel(true); Thread.currentThread().interrupt();
            state.warn("文档抽取被中断，正文仅包含已完成的合格块", true);
            return state.snapshot(null, "文档抽取已中断");
        } catch (Exception failed) {
            state.stopped = true; future.cancel(true); state.warn("文档抽取执行失败", false);
            return state.snapshot(null, "文档抽取执行失败");
        }
    }

    private void pdf(Path path, State state, Extraction previous) throws Exception {
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            state.initialize(document.getNumberOfPages(), "pt", previous);
            String[] pageLabels=null;
            try {
                var labels=document.getDocumentCatalog().getPageLabels();
                if(labels!=null) pageLabels=labels.getLabelsByPageIndices();
            } catch(Exception invalidLabels) { state.warnOnce("PDF 页标签无法读取，保留文件物理页序号"); }
            PDFRenderer renderer = new PDFRenderer(document);
            var candidates = state.candidates();
            for (int attempted = 0; candidates.hasNext(); attempted++) {
                check(state);
                if (attempted >= MAX_PDF_PAGES) {
                    state.warn(state.originalPages.isEmpty()
                            ? "PDF 超过 " + MAX_PDF_PAGES + " 页，仅处理前 " + MAX_PDF_PAGES + " 页"
                            : "已达到单次 PDF 抽取 " + MAX_PDF_PAGES + " 页上限；再次继续抽取可处理剩余页", true);
                    return;
                }
                int number = candidates.nextInt();
                state.progress("extracting", number);
                PDPage source = document.getPage(number - 1);
                var box = source.getCropBox();
                boolean sideways = Math.floorMod(source.getRotation(), 180) != 0;
                double width = sideways ? box.getHeight() : box.getWidth();
                double height = sideways ? box.getWidth() : box.getHeight();
                List<Block> blocks = List.of();
                String method = "text";
                try {
                    boolean blank = !source.hasContents() && source.getAnnotations().isEmpty();
                    boolean needsOcr = state.structured;
                    if (!state.structured) {
                        PdfLayoutExtractor.PageLayout page = layout.extractPage(document, number);
                        check(state);
                        blocks = nativeBlocks(page, width, height);
                        blank = blank && blocks.isEmpty();
                        int nativeCharacters = page.blocks().stream().mapToInt(block -> meaningful(block.text())).sum();
                        needsOcr = nativeCharacters == 0 || (nativeCharacters < 120 && largeRaster(source));
                    }
                    if (blank) method = "blank";
                    else if (needsOcr) {
                        // A short header must never masquerade as the complete text of a scan.
                        blocks = requiredOcr(blocks);
                        if (state.ocrLimitReached()) return;
                        if (ocrAllowed(state)) {
                            int preferredDpi = state.structured ? STRUCTURED_PDF_DPI : PDF_DPI;
                            int dpi = pdfOcrDpi(width, height, preferredDpi);
                            long pixels = pdfOcrPixels(width, height, dpi);
                            if (pixels > MAX_PAGE_PIXELS) {
                                state.warn("PDF 自适应降采样后仍超过单页像素或边长上限；请调整 PDF 页面尺寸", true);
                                throw new IllegalStateException("PDF 页面过大，无法安全渲染");
                            }
                            if (!reservePixels(state, pixels)) return;
                            renderer.setSubsamplingAllowed(dpi < preferredDpi);
                            BufferedImage bitmap = renderer.renderImageWithDPI(number - 1, dpi, ImageType.RGB);
                            try {
                                check(state);
                                if (dpi < preferredDpi) state.warnOnce("第 " + number + " " + ADAPTIVE_DPI_NOTICE + dpi
                                        + " DPI，定位保留原页坐标");
                                method = "ocr";
                                blocks = recognize(bitmap, number, width, height, state);
                            } finally { bitmap.flush(); }
                        } else state.warn("第 " + number + " 页文本层不足或包含大面积扫描图，尚未完整识别", false);
                    }
                } catch (BudgetExceeded exhausted) { throw exhausted; }
                catch (Exception failed) {
                    state.warn("第 " + number + " 页未进入索引：" + safeMessage(failed), false);
                }
                String label=pageLabels!=null && number<=pageLabels.length?pageLabels[number-1]:null;
                if (!state.completeAttempt(new Page(number, width, height, "pt", method, blocks,label))) return;
            }
        }
    }

    /** Use the highest whole DPI within both sidecar limits, keeping the page's aspect ratio. */
    private static int pdfOcrDpi(double width, double height, int preferredDpi) {
        if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0)
            throw new IllegalStateException("PDF 页尺寸无效，无法进行 OCR");
        if (pdfOcrPixels(width, height, preferredDpi) <= MAX_PAGE_PIXELS) return preferredDpi;
        int low = 1, high = preferredDpi - 1;
        while (low < high) {
            int dpi = (low + high + 1) / 2;
            if (pdfOcrPixels(width, height, dpi) <= MAX_PAGE_PIXELS) low = dpi;
            else high = dpi - 1;
        }
        return low;
    }

    private static long pdfOcrPixels(double width, double height, int dpi) {
        float scale = dpi / 72f;
        // PDFBox multiplies the float crop dimensions by this scale and rounds down.
        // Ceiling reserves at least as many pixels, including for rotated and tiny pages.
        double columns = Math.max(1, Math.ceil((float) width * scale));
        double rows = Math.max(1, Math.ceil((float) height * scale));
        if (columns > MAX_OCR_SIDE || rows > MAX_OCR_SIDE) return MAX_PAGE_PIXELS + 1;
        return (long) columns * (long) rows;
    }

    private void image(Path path, State state, Extraction previous) throws Exception {
        try (ImageInputStream input = ImageIO.createImageInputStream(path.toFile())) {
            if (input == null) throw new IllegalStateException("无法读取图像");
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalStateException("当前运行环境没有该图像格式的解码器");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, true);
                int total = reader.getNumImages(true);
                if (total <= 0) throw new IllegalStateException("图像没有可读取的页面");
                state.initialize(total, "px", previous);
                var candidates = state.candidates();
                for (int attempted = 0; candidates.hasNext(); attempted++) {
                    check(state);
                    if (attempted >= MAX_PAGES) {
                        state.warn("已达到单次图像抽取 " + MAX_PAGES + " 帧/页上限；再次继续抽取可处理剩余页", true);
                        return;
                    }
                    if (state.ocrLimitReached()) return;
                    int number = candidates.nextInt();
                    state.progress("extracting", number);
                    int width = reader.getWidth(number - 1), height = reader.getHeight(number - 1);
                    if (!ocrAllowed(state)) {
                        // Keep geometry for the existing image/vision fallback even without local OCR.
                        state.completeAttempt(new Page(number, width, height, "px", "text", List.of()));
                        return;
                    }
                    List<Block> blocks;
                    String method = "text";
                    try {
                        if (!reservePixels(state, (long) width * height)) return;
                        BufferedImage bitmap = reader.read(number - 1);
                        try {
                            check(state);
                            method = "ocr";
                            blocks = recognize(bitmap, number, width, height, state);
                        }
                        finally { bitmap.flush(); }
                    } catch (BudgetExceeded exhausted) { throw exhausted; }
                    catch (Exception failed) {
                        blocks = List.of(); state.warn("第 " + number + " 张图像未进入索引：" + safeMessage(failed), false);
                    }
                    if (!state.completeAttempt(new Page(number, width, height, "px", method, blocks))) return;
                }
            } finally { reader.dispose(); }
        }
    }

    private boolean ocrAllowed(State state) {
        if (!state.structured && !ocr.configured()) {
            state.warn("未配置本地 OCR 服务 LEARNHUB_DOCUMENT_OCR_URL；扫描页/图片不能完整检索", false);
            return false;
        }
        return true;
    }

    private List<Block> recognize(BufferedImage bitmap, int page, double width, double height, State state) throws Exception {
        check(state);
        state.ocrAttempts++;
        byte[] png = ocrPng(bitmap);
        check(state);
        if (state.structured) return recognizeStructure(png, bitmap, page, width, height, state);
        DocumentOcrClient.Result result = ocr.recognize(png,
                Duration.ofNanos(Math.max(1, state.deadline - System.nanoTime())));
        check(state);
        if (result.width() != bitmap.getWidth() || result.height() != bitmap.getHeight())
            throw new IllegalStateException("OCR 返回的图像尺寸与请求不一致");
        state.warnOnce(BASIC_OCR_NOTICE);
        for (String warning : result.warnings()) {
            String notice = "第 " + page + " 页 OCR：" + warning;
            if (warning.startsWith("ocr_text_only:")) state.warnOnce(notice);
            else state.warn(notice, false);
        }
        List<Block> blocks = new ArrayList<>();
        int index = 0;
        for (DocumentOcrClient.Block block : result.blocks()) {
            String cleaned = DocumentTextService.tidy(block.text());
            String quality = quality(cleaned, block.confidence());
            double[] rect = block.rect().clone();
            for (int i = 0; i < 4; i++) rect[i] *= i % 2 == 0 ? width / result.width() : height / result.height();
            blocks.add(new Block("p" + page + "-b" + (++index), block.type(), block.text(), cleaned, page, rect,
                    width, height, block.confidence(), quality, quality.equals("accepted"), -1, -1));
            if (!quality.equals("accepted")) state.warn("第 " + page + " 页部分 OCR 块因 " + quality + " 未进入索引", false);
        }
        return blocks;
    }

    private static byte[] ocrPng(BufferedImage bitmap) throws java.io.IOException {
        var bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(bitmap, "png", bytes)) throw new IllegalStateException("无法编码 OCR 图片");
        if (bytes.size() <= MAX_OCR_IMAGE_BYTES) return bytes.toByteArray();
        // A high-entropy RGB page can exceed the PNG transport limit even when its pixel count fits.
        var grayscale = new BufferedImage(bitmap.getWidth(), bitmap.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        try {
            var graphics = grayscale.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, bitmap.getWidth(), bitmap.getHeight());
                graphics.drawImage(bitmap, 0, 0, null);
            } finally { graphics.dispose(); }
            bytes.reset();
            if (!ImageIO.write(grayscale, "png", bytes)) throw new IllegalStateException("无法编码 OCR 图片");
            if (bytes.size() > MAX_OCR_IMAGE_BYTES)
                throw new IllegalStateException("OCR 图片超过 12 MiB 传输上限，请调整原件");
            return bytes.toByteArray();
        } finally { grayscale.flush(); }
    }

    private List<Block> recognizeStructure(byte[] png, BufferedImage bitmap, int page, double width,
                                           double height, State state) {
        var result = structure.recognize(png, Duration.ofNanos(Math.max(1, state.deadline - System.nanoTime())));
        check(state);
        if (result.width() != bitmap.getWidth() || result.height() != bitmap.getHeight())
            throw new IllegalStateException("结构化 OCR 返回的图像尺寸与请求不一致");
        for (String warning : result.warnings()) {
            switch (warning) {
                case "pp_ocr_lines_recovered" -> state.warnOnce("第 " + page + " 页已补回版面解析遗漏的 OCR 行");
                case "pp_formula_needs_review" -> state.warnOnce("结构化 OCR 公式转写需核对，核对前不纳入检索");
                case "pp_table_uncertain" -> state.warn("第 " + page + " 页表格结构待核对，已保留可用文字与坐标", false);
                default -> state.warn("第 " + page + " 页结构化 OCR 有待核对的区域", false);
            }
        }
        List<Block> blocks = new ArrayList<>();
        int index = 0;
        for (DocumentStructureClient.Block source : result.blocks()) {
            check(state);
            String type = source.type();
            String id = "p" + page + "-b" + (++index);
            double[] rect = source.rect().clone();
            for (int i = 0; i < rect.length; i++)
                rect[i] *= i % 2 == 0 ? width / result.width() : height / result.height();
            if ("formula".equals(type)) {
                String candidate = safeFormulaCandidate(source.latex());
                state.warnOnce("结构化 OCR 公式转写需核对，核对前不纳入检索");
                blocks.add(new Block(id, type, "", "", page, rect, width, height, null, "non_text", false,
                        -1, -1, 0, null, candidate, "needs_review", candidate == null
                        ? "公式转写不可用，请对照原图补充" : "结构化 OCR 转写待核对",
                        null, -1, -1, new Presentation(null, "block", null, null, null, "structure-formula")));
                continue;
            }
            if ("figure".equals(type)) {
                blocks.add(new Block(id, type, "", "", page, rect, width, height, null, "non_text", false, -1, -1));
                continue;
            }
            String raw = source.text();
            String clean = DocumentTextService.tidy(raw);
            if ("table".equals(type)) {
                raw = source.rows().stream().map(row -> String.join("\t", row))
                        .collect(java.util.stream.Collectors.joining("\n"));
                clean = tableHtml(source.rows());
            }
            String quality = quality(DocumentTextService.tidy(raw), source.confidence());
            blocks.add(new Block(id, type, raw, clean, page, rect, width, height, source.confidence(),
                    quality, "accepted".equals(quality), -1, -1, source.level(), null, null, null, null));
            if (!"accepted".equals(quality))
                state.warn("第 " + page + " 页有结构化 OCR 块未通过文字质量校验", false);
        }
        return blocks;
    }

    private static String safeFormulaCandidate(String latex) {
        if (latex == null || latex.isBlank()) return null;
        String candidate = latex.strip();
        try {
            FormulaRecognitionService.validateLatex(candidate);
            return candidate;
        } catch (IllegalStateException invalid) {
            return null;
        }
    }

    private static String tableHtml(List<List<String>> rows) {
        StringBuilder html = new StringBuilder("<table>");
        for (List<String> row : rows) {
            html.append("<tr>");
            for (String cell : row) html.append("<td>")
                    .append(HtmlUtils.htmlEscape(DocumentTextService.tidy(cell).replace('\n', ' ')))
                    .append("</td>");
            html.append("</tr>");
        }
        return html.append("</table>").toString();
    }

    private List<Block> nativeBlocks(PdfLayoutExtractor.PageLayout page, double width, double height) {
        List<Block> blocks = new ArrayList<>();
        int index = 0;
        for (PdfLayoutExtractor.Block block : page.blocks()) {
            String raw = block.text() == null ? "" : block.text();
            String clean = DocumentTextService.tidy(raw);
            if ("formula".equals(block.type()) && block.latex() != null && !block.latex().isBlank()
                    && Set.of("restored", "recognized").contains(String.valueOf(block.latexStatus())))
                clean = "$$\n" + block.latex().strip() + "\n$$";
            String quality = "figure".equals(block.type()) ? "non_text" : quality(clean, null);
            if ("formula".equals(block.type()) && "partial".equals(block.latexStatus())) quality = "uncertain_formula";
            blocks.add(new Block("p" + page.page() + "-b" + (++index), block.type(), raw, clean, page.page(),
                    boundedRect(block.rect(), width, height), width, height, null,
                    quality, quality.equals("accepted"), -1, -1, block.level(), block.src(), block.latex(),
                    block.latexStatus(), block.latexMessage(),block.markdown(),-1,-1,
                    "figure".equals(block.type())?new Presentation(null,"block",null,null,null,"pdf-figure:"+block.src()):null));
        }
        return blocks;
    }

    static String quality(String text, Double confidence) {
        if (text == null || text.isBlank()) return "empty";
        if (confidence != null && confidence < MIN_CONFIDENCE) return "low_confidence";
        long invalid = text.codePoints().filter(cp -> cp == 0xfffd || Character.getType(cp) == Character.PRIVATE_USE
                || (Character.isISOControl(cp) && !Character.isWhitespace(cp))).count();
        if (invalid > 0) return "garbled";
        if (meaningful(text) == 0) return "empty";
        return "accepted";
    }

    private static int meaningful(String text) {
        return text == null ? 0 : (int) text.codePoints().filter(Character::isLetterOrDigit).count();
    }

    private static double[] boundedRect(double[] rect, double width, double height) {
        if (rect == null || rect.length != 4) return null;
        double[] result = rect.clone();
        for (int i = 0; i < 4; i++) {
            if (!Double.isFinite(result[i])) return null;
            result[i] = Math.max(0, Math.min(i % 2 == 0 ? width : height, result[i]));
        }
        return result[2] > result[0] && result[3] > result[1] ? result : null;
    }

    private static boolean largeRaster(PDPage page) {
        var box = page.getCropBox();
        double width = box.getWidth(), height = box.getHeight();
        // Area is unchanged by page rotation; this scanner reports the unrotated crop coordinate system.
        for (double[] rect : new PdfFigureScanner(page).scan()) {
            double clippedWidth = Math.max(0, Math.min(width, rect[2]) - Math.max(0, rect[0]));
            double clippedHeight = Math.max(0, Math.min(height, rect[3]) - Math.max(0, rect[1]));
            if (clippedWidth * clippedHeight >= width * height * 0.40) return true;
        }
        return false;
    }

    private static List<Block> requiredOcr(List<Block> blocks) {
        return blocks.stream().map(block -> new Block(block.id(), block.type(), block.rawText(), block.cleanText(),
                block.page(), block.rect(), block.width(), block.height(), block.confidence(),
                "non_text".equals(block.quality()) ? "non_text" : "ocr_required", false, -1, -1,
                block.level(), block.src(), block.latex(), block.latexStatus(), block.latexMessage(),block.markdown(),-1,-1,block.presentation())).toList();
    }

    private static boolean reservePixels(State state, long pixels) {
        if (pixels <= 0 || pixels > MAX_PAGE_PIXELS) {
            state.warn("图像超过单页 1200 万像素上限，相关页未识别", true);
            throw new IllegalStateException("图像像素预算超限");
        }
        if (state.pixels + pixels > MAX_TOTAL_PIXELS) {
            state.warn("已达到单次 8000 万像素预算；再次继续抽取可处理剩余页", true);
            return false;
        }
        state.pixels += pixels;
        return true;
    }

    private static void check(State state) {
        if (state.stopped || Thread.currentThread().isInterrupted() || System.nanoTime() >= state.deadline) throw new BudgetExceeded();
    }

    private static String sourceHash(Path path, State state) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = new byte[65536]; int read;
            while ((read = input.read(bytes)) >= 0) { if (state != null) check(state); digest.update(bytes, 0, read); }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Wrap existing Office/plain-text extraction without changing its parser or requiring OCR. */
    public static Extraction fromLegacy(Path path, DocumentTextService.Extracted extracted) {
        String text = extracted.text() == null ? "" : extracted.text();
        String hash = "";
        List<String> warnings = new ArrayList<>();
        try { hash = sourceHash(path, null); } catch (Exception failed) { warnings.add("源文件哈希读取失败"); }
        if (extracted.error() != null && !extracted.error().isBlank()) warnings.add(extracted.error());
        boolean accepted = "ok".equals(extracted.status()) && !text.isEmpty();
        String quality = accepted ? "legacy" : "empty";
        boolean truncated = extracted.chars() > text.length();
        if (truncated) warnings.add("正文达到存储上限，源文件仅有部分内容进入索引");
        Block block = new Block("p1-b1", "paragraph", text, text, 1, null, 0, 0, null, quality, accepted,
                accepted ? 0 : -1, accepted ? text.length() : -1);
        String finalText = text;
        String status = extracted.status();
        return new Extraction(status, finalText, finalText.length(), accepted ? null : extracted.error(), hash,
                PIPELINE_VERSION, List.of(new Page(1, 0, 0, "none", "text", List.of(block))),
                new QualityReport(1, 1, 0, accepted ? 0 : 1, null, List.copyOf(warnings), truncated,
                        truncated || !warnings.isEmpty()));
    }

    private static String safeMessage(Exception failed) {
        if (failed instanceof IllegalArgumentException || failed instanceof IllegalStateException)
            return failed.getMessage() == null ? "处理失败" : failed.getMessage().replaceAll("[\r\n]", " ").substring(0, Math.min(250, failed.getMessage().length()));
        return "文件格式异常或解析器无法处理";
    }

    @PreDestroy public void close() {
        workers.shutdownNow();
        structuredWorker.shutdownNow();
    }
    private static final class BudgetExceeded extends RuntimeException { }

    private static final class State {
        final long deadline;
        final boolean structured;
        final Consumer<Progress> onProgress;
        volatile boolean stopped;
        volatile String sourceSha256 = "";
        int totalPages, completedPages, ocrAttempts;
        long pixels;
        boolean partial, truncated;
        boolean unlocatedCapacity;
        long rawChars, indexedChars;
        int blockCount, previousOcrPages;
        final TreeMap<Integer, Page> originalPages = new TreeMap<>();
        final Map<Integer, Page> pages = new TreeMap<>();
        final List<String> warnings = new ArrayList<>();
        final Set<String> limitWarnings = new LinkedHashSet<>();
        List<String> previousWarnings = List.of();
        State(long deadline, boolean structured, Consumer<Progress> onProgress) {
            this.deadline = deadline;
            this.structured = structured;
            this.onProgress = onProgress;
        }
        void initialize(int total, String unit, Extraction previous) {
            synchronized (this) {
                check(this);
                totalPages = total;
                if (previous != null && previous.report().totalPages() == total) {
                    previousWarnings = previous.report().warnings();
                    previousOcrPages = previous.report().ocrPages();
                    boolean capacity=previousWarnings.stream().anyMatch(DocumentExtractionLimits::isDocumentCapacity);
                    unlocatedCapacity=capacity && (previous.pages().stream().noneMatch(DocumentIngestionService::hasCharacterLimit)
                            || previousWarnings.stream().anyMatch(warning -> DocumentExtractionLimits.isDocumentCapacity(warning)
                                    && !DocumentExtractionLimits.isTextCapacity(warning)));
                    for (Page page : previous.pages()) {
                        if (page.page() < 1 || page.page() > total || !unit.equals(page.coordinateUnit())
                                || originalPages.putIfAbsent(page.page(), page) != null) continue;
                        replacePage(page);
                    }
                }
            }
            progress("extracting", 0);
        }
        PrimitiveIterator.OfInt candidates() {
            // A marked boundary page has an omitted tail; repair it before advancing to unprocessed pages.
            // Raw/block truncation has no reliable page boundary, so it cannot be guessed from the last page.
            if (unlocatedCapacity) return IntStream.empty().iterator();
            return IntStream.concat(originalPages.values().stream().filter(DocumentIngestionService::hasCharacterLimit).mapToInt(Page::page),IntStream.concat(
                    IntStream.rangeClosed(1, totalPages).filter(number -> !originalPages.containsKey(number)),
                    originalPages.values().stream().filter(page -> !hasCharacterLimit(page) && !isCompletedPage(page)).mapToInt(Page::page))).iterator();
        }
        void progress(String stage, int currentPage) {
            if (onProgress == null || stopped) return;
            Progress update;
            synchronized (this) { update = new Progress(stage, completedPages, totalPages, currentPage); }
            onProgress.accept(update);
        }
        boolean ocrLimitReached() {
            if (ocrAttempts < MAX_OCR_PAGES) return false;
            warn("已达到单次 " + MAX_OCR_PAGES + " 页 OCR 上限；再次继续抽取可处理剩余页", true);
            return true;
        }
        boolean completeAttempt(Page page) {
            check(this);
            boolean withinBudget = addPage(page);
            progress("extracting", page.page());
            return withinBudget;
        }
        synchronized void warn(String warning, boolean truncated) {
            if (truncated) limitWarnings.add(warning);
            else warnOnce(warning);
            partial = true; this.truncated |= truncated;
        }
        synchronized void warnOnce(String warning) { if (warnings.size() < MAX_WARNINGS && !warnings.contains(warning)) warnings.add(warning); }
        private synchronized boolean addPage(Page page) {
            if (stopped) return false;
            Page original = pages.get(page.page());
            if (original != null && isCompletedPage(original)) return true;
            int remainingBlocks = MAX_BLOCKS - blockCount + (original == null ? 0 : original.blocks().size());
            long remainingRaw = MAX_RAW_CHARS - rawChars + rawSize(original);
            // Each indexed block reserves a separator. The first separator is omitted when the text is built.
            long remainingIndex = DocumentTextService.MAX_TEXT_CHARS + 2L - indexedChars + indexSize(original);
            List<Block> adjusted = new ArrayList<>();
            boolean withinBudget = true;
            for (Block block : page.blocks()) {
                long storedChars = rawSize(block);
                if (remainingBlocks-- <= 0 || storedChars > remainingRaw) {
                    warn("累计抽取结构达到 20000 块/80 万原始字符上限；请拆分原件以抽取剩余内容", true);
                    withinBudget = false;
                    break;
                }
                remainingRaw -= storedChars;
                boolean index = block.indexed(); String quality = block.quality();
                if (index && indexSize(block) > remainingIndex) {
                    warn("累计正文达到 80 万字符上限；请拆分原件以抽取剩余内容", true);
                    index = false; quality = "character_limit"; withinBudget = false;
                }
                if (index) remainingIndex -= indexSize(block);
                else if (!NON_INDEXED_QUALITIES.contains(quality)) warn("第 " + page.page() + " 页存在未通过质量门槛的块：" + quality, false);
                adjusted.add(new Block(block.id(), block.type(), block.rawText(), block.cleanText(), block.page(),
                        block.rect(), block.width(), block.height(), block.confidence(), quality, index, -1, -1,
                        block.level(), block.src(), block.latex(), block.latexStatus(), block.latexMessage(),block.markdown(),-1,-1,block.presentation()));
                if (!withinBudget) break;
            }
            Page candidate = new Page(page.page(), page.width(), page.height(), page.coordinateUnit(), page.method(), List.copyOf(adjusted), page.label());
            if (original == null || isCompletedPage(candidate) || original.blocks().isEmpty() && !adjusted.isEmpty()) replacePage(candidate);
            if (!isCompletedPage(candidate)) warn("第 " + page.page() + " 页没有通过质量校验的正文", false);
            return withinBudget;
        }
        private void replacePage(Page page) {
            Page original = pages.put(page.page(), page);
            if (original != null) {
                blockCount -= original.blocks().size();
                rawChars -= rawSize(original);
                indexedChars -= indexSize(original);
                if (isCompletedPage(original)) completedPages--;
            }
            blockCount += page.blocks().size();
            rawChars += rawSize(page);
            indexedChars += indexSize(page);
            if (isCompletedPage(page)) completedPages++;
        }
        private static long rawSize(Page page) {
            return page == null ? 0 : page.blocks().stream().mapToLong(State::rawSize).sum();
        }
        private static long rawSize(Block block) {
            return size(block.rawText()) + size(block.latex());
        }
        private static long indexSize(Page page) {
            return page == null ? 0 : page.blocks().stream().filter(Block::indexed).mapToLong(State::indexSize).sum();
        }
        private static long indexSize(Block block) {
            return Math.max(size(block.cleanText()), (long) block.end() - block.start()) + 2L;
        }
        private static long size(String text) { return text == null ? 0 : text.length(); }

        private boolean retainedPage(int number) {
            return originalPages.containsKey(number) && originalPages.get(number) == pages.get(number);
        }

        private DocumentPageCleanup.Result cleanPages() {
            var cleaned = DocumentPageCleanup.clean(List.copyOf(pages.values()));
            if (originalPages.isEmpty()) return cleaned;
            StringBuilder text = new StringBuilder();
            List<Page> merged = new ArrayList<>();
            int removed = 0;
            for (Page candidate : cleaned.pages()) {
                Page page = retainedPage(candidate.page()) ? originalPages.get(candidate.page()) : candidate;
                List<Block> blocks = new ArrayList<>();
                for (Block block : page.blocks()) {
                    int start = -1, end = -1;
                    if (block.indexed()) {
                        if (!text.isEmpty()) text.append("\n\n");
                        start = text.length(); text.append(block.cleanText()); end = text.length();
                    }
                    if ("page_furniture".equals(block.quality())) removed++;
                    blocks.add(new Block(block.id(), block.type(), block.rawText(), block.cleanText(), block.page(),
                            block.rect(), block.width(), block.height(), block.confidence(), block.quality(), block.indexed(), start, end,
                            block.level(), block.src(), block.latex(), block.latexStatus(), block.latexMessage(), block.markdown(),
                            -1, -1, block.presentation()));
                }
                merged.add(new Page(page.page(), page.width(), page.height(), page.coordinateUnit(), page.method(), List.copyOf(blocks), page.label()));
            }
            return new DocumentPageCleanup.Result(text.toString(), List.copyOf(merged), removed);
        }

        synchronized Extraction snapshot(String override, String error) {
            var cleaned = cleanPages();
            String acceptedText = cleaned.text();
            List<Block> all = cleaned.pages().stream().flatMap(page -> page.blocks().stream()).toList();
            Double mean = all.stream().filter(block -> block.confidence() != null).mapToDouble(Block::confidence).average().stream().boxed().findFirst().orElse(null);
            int rejected = (int) cleaned.pages().stream().filter(page -> !isCompletedPage(page)).count();
            int ocrPages = Math.max(previousOcrPages, (int) pages.values().stream().filter(page -> "ocr".equals(page.method())).count());
            boolean retainedIssues = pages.values().stream().filter(page -> retainedPage(page.page()))
                    .flatMap(page -> page.blocks().stream()).anyMatch(block -> !block.indexed()
                            && block.quality() != null && !NON_INDEXED_QUALITIES.contains(block.quality()));
            boolean retainedStorageLimit = all.stream().anyMatch(block -> "character_limit".equals(block.quality()));
            var notices = new LinkedHashSet<>(limitWarnings);
            var retainedNotices = new ArrayList<String>();
            for (String warning : previousWarnings) {
                var pageNotice = PAGE_NOTICE.matcher(warning);
                boolean retainedNotice = pageNotice.matches() && retainedPage(Integer.parseInt(pageNotice.group(1)));
                boolean storageNotice = DocumentExtractionLimits.isDocumentCapacity(warning)
                        && (unlocatedCapacity || all.stream().anyMatch(block -> "character_limit".equals(block.quality())));
                if (!retainedNotice && !storageNotice && !(BASIC_OCR_NOTICE.equals(warning) && previousOcrPages > 0)) continue;
                if (retainedNotice && !warning.contains("ocr_text_only:") && !warning.contains("已补回版面解析遗漏")
                        && !warning.contains(ADAPTIVE_DPI_NOTICE)) retainedIssues = true;
                retainedStorageLimit |= storageNotice;
                if (storageNotice) notices.add(warning);
                else retainedNotices.add(warning);
            }
            boolean incomplete = partial || retainedIssues || retainedStorageLimit || pages.size() < totalPages || rejected > 0;
            String status = override != null ? override : !acceptedText.isEmpty() ? "ok" : error != null ? "failed" : "empty";
            notices.addAll(warnings);
            notices.addAll(retainedNotices);
            String resultError = acceptedText.isEmpty() ? error != null ? error : notices.isEmpty() ? "没有可提取的正文" : notices.getFirst() : null;
            if (cleaned.removed() > 0) notices.add("已排除 " + cleaned.removed() + " 个跨页重复的页眉/页脚候选，原始块保留在报告中");
            return new Extraction(status, acceptedText, acceptedText.length(), resultError, sourceSha256,
                    structured ? STRUCTURED_PIPELINE_VERSION : PIPELINE_VERSION,
                    cleaned.pages(), new QualityReport(totalPages, pages.size(), ocrPages, rejected, mean,
                            notices.stream().limit(MAX_WARNINGS).toList(), truncated || retainedStorageLimit, incomplete));
        }
    }
}
