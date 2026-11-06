package org.dyh.learnhub.service;

import jakarta.annotation.PreDestroy;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded text-first ingestion. Raw files and OCR output are retained separately from accepted index text. */
@Service
public class DocumentIngestionService {
    public static final String PIPELINE_VERSION = "document-ingestion-v3-layout";
    static final int MAX_PAGES = 300;
    static final int MAX_PDF_PAGES = 500;
    static final int MAX_OCR_PAGES = 20;
    static final double MIN_CONFIDENCE = 0.65;
    private static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    private static final long MAX_PAGE_PIXELS = 12_000_000;
    private static final long MAX_TOTAL_PIXELS = 80_000_000;
    private static final int MAX_BLOCKS = 20000;
    private static final int MAX_RAW_CHARS = 800000;
    private static final float PDF_DPI = 300;
    private static final Set<String> IMAGES = Set.of("png", "jpg", "jpeg", "tif", "tiff", "bmp", "gif", "webp");
    private final PdfLayoutExtractor layout;
    private final DocumentOcrClient ocr;
    private final long timeoutMillis;
    private final Semaphore slots = new Semaphore(2);
    private final ExecutorService workers = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "document-ingestion"); thread.setDaemon(true); return thread;
    });

    @Autowired
    public DocumentIngestionService(PdfLayoutExtractor layout, DocumentOcrClient ocr) {
        this(layout, ocr, 120000);
    }

    DocumentIngestionService(PdfLayoutExtractor layout, DocumentOcrClient ocr, long timeoutMillis) {
        this.layout = layout; this.ocr = ocr; this.timeoutMillis = timeoutMillis;
    }

    public record Extraction(String status, String text, int chars, String error, String sourceSha256,
                             String pipelineVersion, List<Page> pages, QualityReport report, String markdown, String format) {
        public Extraction(String status, String text, int chars, String error, String sourceSha256,
                          String pipelineVersion, List<Page> pages, QualityReport report) {
            this(status, text, chars, error, sourceSha256, pipelineVersion, pages, report, text == null ? "" : text, "plain");
        }
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

    public boolean supports(String ext) {
        String format = ext == null ? "" : ext.toLowerCase(Locale.ROOT);
        return format.equals("pdf") || IMAGES.contains(format);
    }

    public Extraction extract(Path path, String ext) {
        State state = new State(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis));
        if (!supports(ext)) return state.snapshot("unsupported", "该类型不使用 OCR 导入管线");
        if (!slots.tryAcquire()) {
            state.warn("已有两个文档正在抽取，请稍后重抽正文", false);
            return state.snapshot("failed", "文档抽取服务繁忙");
        }
        Future<Extraction> future;
        try {
            future = workers.submit(() -> {
                try {
                    check(state);
                    if (!Files.isRegularFile(path) || Files.size(path) > MAX_FILE_BYTES)
                        throw new IllegalStateException("源文件缺失或超过 64 MiB 导入上限");
                    state.sourceSha256 = sourceHash(path, state);
                    if ("pdf".equalsIgnoreCase(ext)) pdf(path, state); else image(path, state);
                    return state.snapshot(null, null);
                } catch (BudgetExceeded exceeded) {
                    state.warn("已达到 120 秒任务预算；未处理内容没有进入索引，请拆分资料后重试", true);
                    return state.snapshot(null, "文档抽取时间预算已耗尽");
                } catch (Exception failed) {
                    state.warn("文档抽取失败：" + safeMessage(failed), false);
                    return state.snapshot(null, safeMessage(failed));
                } finally { slots.release(); }
            });
        } catch (RuntimeException unavailable) {
            slots.release(); state.warn("文档抽取服务已停止", false);
            return state.snapshot("failed", "文档抽取服务已停止");
        }
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timedOut) {
            state.stopped = true; future.cancel(true);
            state.warn("已达到 120 秒任务预算；未处理内容没有进入索引，请拆分资料后重试", true);
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

    private void pdf(Path path, State state) throws Exception {
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            state.totalPages = document.getNumberOfPages();
            String[] pageLabels=null;
            try {
                var labels=document.getDocumentCatalog().getPageLabels();
                if(labels!=null) pageLabels=labels.getLabelsByPageIndices();
            } catch(Exception invalidLabels) { state.warnOnce("PDF 页标签无法读取，保留文件物理页序号"); }
            if (state.totalPages > MAX_PDF_PAGES) state.warn("PDF 超过 " + MAX_PDF_PAGES + " 页，仅处理前 " + MAX_PDF_PAGES + " 页", true);
            PDFRenderer renderer = new PDFRenderer(document);
            for (int number = 1; number <= Math.min(state.totalPages, MAX_PDF_PAGES); number++) {
                check(state);
                PDPage source = document.getPage(number - 1);
                var box = source.getCropBox();
                boolean sideways = Math.floorMod(source.getRotation(), 180) != 0;
                double width = sideways ? box.getHeight() : box.getWidth();
                double height = sideways ? box.getWidth() : box.getHeight();
                List<Block> blocks = List.of();
                String method = "text";
                try {
                    PdfLayoutExtractor.PageLayout page = layout.extractPage(document, number);
                    check(state);
                    blocks = nativeBlocks(page, width, height);
                    int nativeCharacters = page.blocks().stream().mapToInt(block -> meaningful(block.text())).sum();
                    boolean needsOcr = nativeCharacters == 0
                            || (nativeCharacters < 120 && largeRaster(source));
                    if (needsOcr) {
                        // A short header must never masquerade as the complete text of a scan.
                        blocks = requiredOcr(blocks);
                        if (ocrAllowed(state, number)) {
                            long pixels = (long) Math.ceil(width * PDF_DPI / 72) * (long) Math.ceil(height * PDF_DPI / 72);
                            reservePixels(state, pixels);
                            BufferedImage bitmap = renderer.renderImageWithDPI(number - 1, PDF_DPI, ImageType.RGB);
                            check(state);
                            try {
                                blocks = recognize(bitmap, number, width, height, state);
                                method = "ocr";
                            } finally { bitmap.flush(); }
                        } else state.warn("第 " + number + " 页文本层不足或包含大面积扫描图，尚未完整识别", false);
                    }
                } catch (BudgetExceeded exhausted) { throw exhausted; }
                catch (Exception failed) {
                    state.warn("第 " + number + " 页未进入索引：" + safeMessage(failed), false);
                }
                String label=pageLabels!=null && number<=pageLabels.length?pageLabels[number-1]:null;
                if (!state.addPage(new Page(number, width, height, "pt", method, blocks,label))) break;
            }
        }
    }

    private void image(Path path, State state) throws Exception {
        try (ImageInputStream input = ImageIO.createImageInputStream(path.toFile())) {
            if (input == null) throw new IllegalStateException("无法读取图像");
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalStateException("当前运行环境没有该图像格式的解码器");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, true);
                state.totalPages = reader.getNumImages(true);
                if (state.totalPages <= 0) throw new IllegalStateException("图像没有可读取的页面");
                if (state.totalPages > MAX_PAGES) state.warn("图像超过 300 帧/页，仅处理前 300 页", true);
                for (int number = 1; number <= Math.min(state.totalPages, MAX_PAGES); number++) {
                    check(state);
                    int width = reader.getWidth(number - 1), height = reader.getHeight(number - 1);
                    if (!ocrAllowed(state, number)) {
                        state.addPage(new Page(number, width, height, "px", "ocr", List.of()));
                        break;
                    }
                    List<Block> blocks;
                    try {
                        reservePixels(state, (long) width * height);
                        BufferedImage bitmap = reader.read(number - 1);
                        check(state);
                        try { blocks = recognize(bitmap, number, width, height, state); }
                        finally { bitmap.flush(); }
                    } catch (BudgetExceeded exhausted) { throw exhausted; }
                    catch (Exception failed) {
                        blocks = List.of(); state.warn("第 " + number + " 张图像未进入索引：" + safeMessage(failed), false);
                    }
                    if (!state.addPage(new Page(number, width, height, "px", "ocr", blocks))) break;
                }
            } finally { reader.dispose(); }
        }
    }

    private boolean ocrAllowed(State state, int page) {
        if (!ocr.configured()) {
            state.warn("未配置本地 OCR 服务 LEARNHUB_DOCUMENT_OCR_URL；扫描页/图片不能完整检索", false);
            return false;
        }
        if (state.ocrPages >= MAX_OCR_PAGES) {
            state.warn("已达到每份资料 20 页 OCR 上限；剩余扫描页/图片未识别", true); return false;
        }
        return true;
    }

    private List<Block> recognize(BufferedImage bitmap, int page, double width, double height, State state) throws Exception {
        check(state);
        state.ocrPages++;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(bitmap, "png", bytes)) throw new IllegalStateException("无法编码 OCR 图片");
        check(state);
        DocumentOcrClient.Result result = ocr.recognize(bytes.toByteArray(),
                Duration.ofNanos(Math.max(1, state.deadline - System.nanoTime())));
        check(state);
        if (result.width() != bitmap.getWidth() || result.height() != bitmap.getHeight())
            throw new IllegalStateException("OCR 返回的图像尺寸与请求不一致");
        state.warnOnce("OCR 仅支持基础段落和简单分栏；标题层级、复杂表格、图表及公式请核对原文");
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

    private static void reservePixels(State state, long pixels) {
        if (pixels <= 0 || pixels > MAX_PAGE_PIXELS || state.pixels + pixels > MAX_TOTAL_PIXELS) {
            state.warn("图像超过单页 1200 万/全任务 8000 万像素预算，相关内容未识别", true);
            throw new IllegalStateException("图像像素预算超限");
        }
        state.pixels += pixels;
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

    @PreDestroy public void close() { workers.shutdownNow(); }
    private static final class BudgetExceeded extends RuntimeException { }

    private static final class State {
        final long deadline;
        volatile boolean stopped;
        volatile String sourceSha256 = "";
        volatile int totalPages, ocrPages;
        long pixels;
        boolean partial, truncated;
        int rawChars, blockCount;
        final List<Page> pages = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final StringBuilder text = new StringBuilder();
        State(long deadline) { this.deadline = deadline; }
        synchronized void warn(String warning, boolean truncated) {
            warnOnce(warning); partial = true; this.truncated |= truncated;
        }
        synchronized void warnOnce(String warning) { if (warnings.size() < 200 && !warnings.contains(warning)) warnings.add(warning); }
        synchronized boolean addPage(Page page) {
            if (stopped) return false;
            List<Block> adjusted = new ArrayList<>();
            boolean withinBudget = true;
            for (Block block : page.blocks()) {
                if (++blockCount > MAX_BLOCKS || rawChars + block.rawText().length() > MAX_RAW_CHARS) {
                    warn("抽取结构达到 20000 块/80 万原始字符上限，剩余内容未保存或索引", true); withinBudget = false; break;
                }
                rawChars += block.rawText().length();
                boolean index = block.indexed(); String quality = block.quality();
                int start = -1, end = -1;
                int separator = text.isEmpty() ? 0 : 2;
                if (index && text.length() + separator + block.cleanText().length() > DocumentTextService.MAX_TEXT_CHARS) {
                    warn("正文达到 40 万字符上限，剩余块未进入索引；公式和段落未截断", true);
                    index = false; quality = "character_limit"; withinBudget = false;
                }
                if (index) {
                    if (separator > 0) text.append("\n\n");
                    start = text.length(); text.append(block.cleanText()); end = text.length();
                } else if (!Set.of("non_text", "empty").contains(quality)) warn("第 " + page.page() + " 页存在未通过质量门槛的块：" + quality, false);
                adjusted.add(new Block(block.id(), block.type(), block.rawText(), block.cleanText(), block.page(),
                        block.rect(), block.width(), block.height(), block.confidence(), quality, index, start, end,
                        block.level(), block.src(), block.latex(), block.latexStatus(), block.latexMessage(),block.markdown(),-1,-1,block.presentation()));
                if (!withinBudget) break;
            }
            pages.add(new Page(page.page(), page.width(), page.height(), page.coordinateUnit(), page.method(), List.copyOf(adjusted),page.label()));
            if (adjusted.stream().noneMatch(Block::indexed)) warn("第 " + page.page() + " 页没有通过质量校验的正文", false);
            return withinBudget;
        }
        synchronized Extraction snapshot(String override, String error) {
            var cleaned = DocumentPageCleanup.clean(pages);
            String acceptedText = cleaned.text();
            List<Block> all = cleaned.pages().stream().flatMap(page -> page.blocks().stream()).toList();
            Double mean = all.stream().filter(block -> block.confidence() != null).mapToDouble(Block::confidence).average().stream().boxed().findFirst().orElse(null);
            int rejected = (int) cleaned.pages().stream().filter(page -> page.blocks().stream().noneMatch(Block::indexed)).count();
            boolean incomplete = partial || pages.size() < totalPages || rejected > 0;
            String status = override != null ? override : !acceptedText.isEmpty() ? "ok" : error != null ? "failed" : "empty";
            String resultError = acceptedText.isEmpty() ? error != null ? error : warnings.isEmpty() ? "没有可提取的正文" : warnings.getFirst() : null;
            List<String> notices = new ArrayList<>(warnings);
            if (cleaned.removed() > 0) notices.add("已排除 " + cleaned.removed() + " 个跨页重复的页眉/页脚候选，原始块保留在报告中");
            return new Extraction(status, acceptedText, acceptedText.length(), resultError, sourceSha256, PIPELINE_VERSION,
                    cleaned.pages(), new QualityReport(totalPages, pages.size(), ocrPages, rejected, mean,
                    List.copyOf(notices), truncated, incomplete));
        }
    }
}
